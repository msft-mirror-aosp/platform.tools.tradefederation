/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.postprocessor;

import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.AggregatedAtomInfo;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.GaugeBucketInfo;
import com.android.os.StatsLog.GaugeMetricData;
import com.android.os.StatsLog.StatsLogReport;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.ProtoUtil;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A post processor that processes gauge metrics collected in a "before/after" approach, i.e. one
 * snapshot before a test/run and one after, pulling out metrics according to a supplied list of
 * metric formatters and reporting their deltas.
 *
 * <p>Metrics collected this way look like the following: (metrics reside in the atoms)
 *
 * <p>
 *
 * <pre>
 * reports {
 *   metrics {
 *     gauge_metrics {
 *       data {
 *         # Bucket for the "before" snapshot
 *         bucket_info {
 *           atom {...}
 *           atom {...}
 *           ...
 *         }
 *         # Bucket for the "after" snapshot
 *         bucket_info {
 *           atom {...}
 *           atom {...}
 *           ...
 *         }
 *       }
 *     }
 *     ...
 *   }
 *   ...
 * }
 * </pre>
 *
 * <p>As an example, if the supplied metric formatter is {@code on_device_power_measurement} for key
 * and {@code [subsystem_name]-[rail_name]=[energy_microwatt_secs]} for value, the metric for an
 * atom where {@code subsystem_name} is {@code display}, {@code rail_name} is {@code RAIL_NAME} and
 * {@code energy_microwatt_secs} is {@code 10} will look like {@code statsd-<config
 * name>-gauge-on_device_power_measurement-delta-display-RAIL_NAME=10}.
 *
 * <p>The before/after metrics are matched for delta calculation by their name, so it is assumed
 * that the formatters will ensure that each snapshot generates unique metric sets within them. The
 * processor will generate warnings in these scenarios:
 *
 * <p>
 *
 * <ul>
 *   <li>There are duplicate metric keys generated by the formatters within each snapshot
 *   <li>An atom or metric key is present in one snapshot but not the other
 * </ul>
 */
@OptionClass(alias = "statsd-before-after-gauge-metric-processor")
public class StatsdBeforeAfterGaugeMetricPostProcessor extends StatsdGenericPostProcessor {
    @Option(
            name = "metric-formatter",
            description =
                    "A formatter to format a statsd atom into a key-value pair for a metric. "
                            + "Format: Atom name (snake case) as key and a 'metric_key=value' "
                            + "formatter as value. For the formatter, enclose atom field "
                            + "references in square brackets, which will be substituted with field "
                            + "values in the atom. "
                            + "Example: key: on_device_power_measurement, "
                            + "value: [subsystem_name]-[rail_name]=[energy_microwatt_secs]."
                            + "References to repeated fields should be avoided unless the user is "
                            + "confident that it will always contain only one value in practice. "
                            + "Field definitions can be found in the atoms.proto file under "
                            + "frameworks/proto_logging/stats in the source tree. "
                            + "The metric key can be empty if only one metric is coming out of a "
                            + "particular atom and the atom name is descriptive enough.")
    private MultiMap<String, String> mMetricFormatters = new MultiMap<>();

    @Option(
            name = "also-report-before-after",
            description =
                    "Also report the before and after values for each metric. These will be "
                            + "prefixed with '[statsd report prefix]-gauge-[atom name]-before' and "
                            + "'[statsd report prefix]-gauge-[atom name]-after'.")
    private boolean mAlsoReportBeforeAfter = true;

    // Corresponds to a field reference, e.g., "[field1_name.field2_name.field3_name]".
    private static final Pattern FIELD_REF_PATTERN =
            Pattern.compile("\\[(?:[a-zA-Z_]+\\.)*(?:[a-zA-Z_]+)\\]");

    /**
     * Parse the gauge metrics from the {@link ConfigMetricsReportList} using the atom formatters.
     *
     * <p>Event metrics resulting in duplicate keys will be stored as comma separated values.
     */
    @Override
    protected Map<String, Metric.Builder> parseMetricsFromReportList(
            ConfigMetricsReportList reportList) {
        // Before and after metrics, keyed by atom names. Under each atom name, the metrics are
        // stored in a MultiMap keyed by metric keys parsed using the formatters.
        // The MultiMap is used in case a parsed metric key has multiple values. This should be
        // avoided via proper formatter configuration, but we still record the metrics so that we
        // can log them for debugging.
        Map<String, MultiMap<String, String>> beforeMetrics = new HashMap<>();
        Map<String, MultiMap<String, String>> afterMetrics = new HashMap<>();

        // Mappings of parsed metric keys to their formatters, keyed by atom names and then parsed
        // metric keys. These mappings are used to generate useful warnings when a formatter results
        // in a parsed metric key with multiple values, which hinders delta calculation.
        Map<String, Map<String, Set<String>>> beforekeyToFormatterOutput = new HashMap<>();
        Map<String, Map<String, Set<String>>> afterkeyToFormatterOutput = new HashMap<>();

        // Go through each report to parse metrics.
        for (ConfigMetricsReport report : reportList.getReportsList()) {
            for (StatsLogReport statsdMetric : report.getMetricsList()) {
                if (!statsdMetric.hasGaugeMetrics()) {
                    continue;
                }
                List<GaugeMetricData> dataItems = statsdMetric.getGaugeMetrics().getDataList();
                for (GaugeMetricData data : dataItems) {
                    // There should be two buckets, one for the "before" snapshot and one for the
                    // "after".
                    if (data.getBucketInfoList().size() != 2) {
                        logWarning(
                                "GaugeMetricData %s does not have two buckets and therefore does "
                                        + "not contain both before and after snapshots. Skipping.",
                                data);
                        continue;
                    }
                    parseMetricsByFormatters(
                            data.getBucketInfo(0), beforeMetrics, beforekeyToFormatterOutput);
                    parseMetricsByFormatters(
                            data.getBucketInfo(1), afterMetrics, afterkeyToFormatterOutput);
                }
            }
        }

        // Warn about atoms that are present in one snapshot but not the other.
        Set<String> atomsInBeforeOnly =
                Sets.difference(beforeMetrics.keySet(), afterMetrics.keySet());
        if (atomsInBeforeOnly.size() > 0) {
            logWarning(
                    "The following atom(s) have a \"before\" snapshot but not an \"after\" "
                            + "snapshot: %s. Metrics:\n%s.",
                    atomsInBeforeOnly,
                    formatMetricsForLoggingByAtoms(beforeMetrics, atomsInBeforeOnly));
        }
        Set<String> atomsInAfterOnly =
                Sets.difference(afterMetrics.keySet(), beforeMetrics.keySet());
        if (atomsInAfterOnly.size() > 0) {
            logWarning(
                    "The following atom(s) have an \"after\" snapshot but not a \"before\" "
                            + "snapshot: %s. Metrics:\n%s",
                    atomsInAfterOnly,
                    formatMetricsForLoggingByAtoms(afterMetrics, atomsInAfterOnly));
        }

        // Obtain the delta metrics, and warn of any metrics that don't match up for each atom.
        // Delta metrics are keyed by atom names, and the metrics are stored as multimaps of parsed
        // to metric keys to delta value. We only expect one value per parsed metric key but use a
        // multimap here to re-use some utility methods.
        Map<String, MultiMap<String, String>> deltaMetrics = new HashMap<>();
        for (String atomName : Sets.intersection(beforeMetrics.keySet(), afterMetrics.keySet())) {
            deltaMetrics.put(atomName, new MultiMap<String, String>());
            MultiMap<String, String> atomBeforeMetrics = beforeMetrics.get(atomName);
            MultiMap<String, String> atomAfterMetrics = afterMetrics.get(atomName);

            // Warn of any non-paired parsed metric keys.
            Set<String> metricsKeysInBeforeOnly =
                    Sets.difference(atomBeforeMetrics.keySet(), atomAfterMetrics.keySet());
            if (metricsKeysInBeforeOnly.size() > 0) {
                logWarning(
                        "For atom %s, the following metric(s) have a \"before\" value but not an "
                                + "\"after\" value:\n%s",
                        atomName,
                        formatAtomMetricsForLoggingByMetricKeys(
                                atomBeforeMetrics, metricsKeysInBeforeOnly, 1));
            }
            Set<String> metricsKeysInAfterOnly =
                    Sets.difference(atomAfterMetrics.keySet(), atomBeforeMetrics.keySet());
            if (metricsKeysInAfterOnly.size() > 0) {
                logWarning(
                        "For atom %s, the following metric(s) have an \"after\" value but not a "
                                + "\"before\" value:\n%s",
                        atomName,
                        formatAtomMetricsForLoggingByMetricKeys(
                                atomAfterMetrics, metricsKeysInAfterOnly, 1));
            }

            // For all paired metric keys, calculate delta.
            for (String metricKey :
                    Sets.intersection(atomBeforeMetrics.keySet(), atomAfterMetrics.keySet())) {
                List<String> beforeValues = atomBeforeMetrics.get(metricKey);
                List<String> afterValues = atomAfterMetrics.get(metricKey);

                if (beforeValues.size() > 1) {
                    logWarning(
                            "Metric %s (from formatter(s) %s) of atom %s has multiple values %s in "
                                    + "the \"before\" snapshot, which will result in meaningless "
                                    + "delta values. Delta calculation for this metric will be "
                                    + "skipped. Please double check your metric formatters if this "
                                    + "is unexpected. The value(s) from the \"after\" snapshot are "
                                    + "%s.",
                            metricKey,
                            beforekeyToFormatterOutput.get(atomName).get(metricKey),
                            atomName,
                            beforeValues,
                            afterValues);
                    continue;
                }
                if (afterValues.size() > 1) {
                    logWarning(
                            "Metric %s (from formatter(s) %s) of atom %s has multiple values %s in "
                                    + "the \"after\" snapshot, which will result in meaningless "
                                    + "delta values. Delta calculation for this metric will be "
                                    + "skipped. Please double check your metric formatters if this "
                                    + "is unexpected. The value(s) from the \"before\" snapshot "
                                    + "are %s.",
                            metricKey,
                            afterkeyToFormatterOutput.get(atomName).get(metricKey),
                            atomName,
                            afterValues,
                            beforeValues);
                    continue;
                }

                try {
                    deltaMetrics
                            .get(atomName)
                            .put(
                                    metricKey,
                                    String.valueOf(
                                            Double.valueOf(afterValues.get(0))
                                                    - Double.valueOf(beforeValues.get(0))));
                } catch (NumberFormatException e) {
                    logWarning(
                            "Metric %s of atom %s (from formatter(s) %s) has non-numeric before "
                                    + "and/or after values %s, %s, skipping delta calculation.",
                            metricKey,
                            atomName,
                            beforekeyToFormatterOutput.get(atomName).get(metricKey),
                            beforeValues.get(0),
                            afterValues.get(0));
                }
            }
        }

        Map<String, Metric.Builder> finalMetrics = new HashMap<>();
        finalMetrics.putAll(finalizeMetrics(deltaMetrics, "delta"));
        if (mAlsoReportBeforeAfter) {
            finalMetrics.putAll(finalizeMetrics(beforeMetrics, "before"));
            finalMetrics.putAll(finalizeMetrics(afterMetrics, "after"));
        }

        return finalMetrics;
    }

    /**
     * Parse metrics from a {@link GaugeBucketInfo} instance using the metric formatters.
     *
     * @param bucket The {@link GaugeBucketInfo} to parse metrics from.
     * @param metricsOutput The map to store the new metrics into, keyed by atom name and then
     *     metric key.
     * @param keyToFormatterOutput The map where a mapping of parsed metric keys to formatters are
     *     stored, keyed by atom name and then the parsed metric key, used for generating useful
     *     warnings when a formatter unexpectedly results in multiple metric values, which presents
     *     issues with delta calculation.
     */
    private void parseMetricsByFormatters(
            GaugeBucketInfo bucket,
            Map<String, MultiMap<String, String>> metricsOutput,
            Map<String, Map<String, Set<String>>> keyToFormatterOutput) {
        List<Atom> atoms = bucket.getAtomList();
        if (atoms.isEmpty()) {
            atoms = new ArrayList<>();
            for (AggregatedAtomInfo info : bucket.getAggregatedAtomInfoList()) {
                atoms.add(info.getAtom());
            }
        }
        for (Atom atom : atoms) {
            Map<FieldDescriptor, Object> atomFields = atom.getAllFields();
            for (FieldDescriptor field : atomFields.keySet()) {
                if (!mMetricFormatters.containsKey(field.getName())) {
                    // Skip if field is not an atom or does not have corresponding formatters.
                    continue;
                }
                String atomName = field.getName();
                metricsOutput.computeIfAbsent(atomName, k -> new MultiMap<String, String>());
                keyToFormatterOutput.computeIfAbsent(
                        atomName, k -> new HashMap<String, Set<String>>());
                Message atomContent = (Message) atom.getField(field);
                List<String> formatters = mMetricFormatters.get(atomName);
                for (String formatter : formatters) {
                    String keyFormatter = formatter.split("=")[0];
                    String valueFormatter = formatter.split("=")[1];
                    List<String> parsedKeys = fillInPlaceholders(keyFormatter, atomContent);
                    List<String> parsedValues = fillInPlaceholders(valueFormatter, atomContent);
                    if (parsedKeys.size() > 1 && parsedValues.size() > 1) {
                        // This condition comes from having repeated fields in both key and value.
                        // The current implementation will result in a cross-product of their
                        // values, which is probably not ideal.
                        logWarning(
                                "Found repeated fields in both metric key and value in formatting "
                                        + "pair %s: %s. This is unsupported as it presents "
                                        + "ambiguity in pairing of repeated field values for a "
                                        + "metric, and could result in meaningless data. Skipping.",
                                atomName, formatter);
                        continue;
                    }
                    for (String key : parsedKeys) {
                        keyToFormatterOutput
                                .get(atomName)
                                .computeIfAbsent(key, k -> new HashSet<String>());
                        keyToFormatterOutput.get(atomName).get(key).add(formatter);
                        for (String value : parsedValues) {
                            metricsOutput.get(atomName).put(key, value);
                        }
                    }
                }
            }
        }
    }

    /** Fill in the placeholders in the formatter using the proto message as source. */
    private List<String> fillInPlaceholders(String formatter, Message atomContent) {
        Matcher matcher = FIELD_REF_PATTERN.matcher(formatter);
        List<String> results = Arrays.asList(formatter);
        while (matcher.find()) {
            String placeholder = matcher.group();
            // Strip the brackets.
            String fieldReference = placeholder.substring(1, placeholder.length() - 1);
            List<String> actual =
                    ProtoUtil.getNestedFieldFromMessageAsStrings(
                            atomContent, Arrays.asList(fieldReference.split("\\.")));
            if (results.size() > 1 && actual.size() > 1) {
                // This condition results from having multiple repeated fields in a formatter. The
                // current implementation will result in a cross-product of their values, which is
                // probably not ideal.
                logWarning(
                        "Found multiple repeated fields in formatter %s. This is unsupported as it "
                                + "presents ambiguity in pairing of repeated field values, and "
                                + "could result in meaningless data. Skipping reporting on this "
                                + "formatter.",
                        formatter);
                return new ArrayList<>();
            }
            List<String> updatedResults =
                    results.stream()
                            .flatMap(r -> actual.stream().map(a -> r.replace(placeholder, a)))
                            .collect(Collectors.toList());
            results = updatedResults;
        }
        return results;
    }

    /**
     * Format the metrics into the reporting form.
     *
     * <p>As an example, {@code metric_key} under {@code atom_name} will become {@code
     * gauge-before-atom_name-metric_key} if {@code type} is {@code before}. If {@code metric_key}
     * is empty, the resulting final metric key will be {@code gauge-before-atom_name}.
     *
     * @param metrics Metrics keyed by atom.
     * @param type Whether the metrics are "before", "after" or "delta" metrics.
     * @return The finalized metrics.
     */
    private Map<String, Metric.Builder> finalizeMetrics(
            Map<String, MultiMap<String, String>> metrics, String type) {
        Map<String, Metric.Builder> finalMetrics = new HashMap<>();
        for (String atomName : metrics.keySet()) {
            for (String metricKey : metrics.get(atomName).keySet()) {
                finalMetrics.put(
                        String.format(
                                "gauge-%s-%s%s",
                                atomName, type, metricKey.isEmpty() ? "" : "-" + metricKey),
                        TfMetricProtoUtil.stringToMetric(
                                        String.join(",", metrics.get(atomName).get(metricKey)))
                                .toBuilder());
            }
        }
        return finalMetrics;
    }

    /**
     * Format metrics for a single atom for logging.
     *
     * <p>The output will look like: (assuming one level indent of 2 spaces)
     *
     * <pre>
     *   metric_1: 1
     *   metric_2: 2
     * ...
     * </pre>
     *
     * @param metricsForAtom Metrics for a single atom, keyed by metric keys.
     * @param keys The subset of metric keys in {@code metricsForAtom} to be logged.
     * @param indent Indent level represented by number of tabs.
     * @return The formatted metrics as a string.
     */
    private String formatAtomMetricsForLoggingByMetricKeys(
            MultiMap<String, String> metricsForAtom, Collection<String> keys, int indent) {
        return keys.stream()
                .map(
                        k ->
                                String.join("", Collections.nCopies(indent, "\t"))
                                        + (k.isEmpty() ? "<empty>" : k)
                                        + ": "
                                        + String.join(",", metricsForAtom.get(k)))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format metrics from a set of atoms for logging.
     *
     * <p>The output will look like: (assuming one level indent of 2 spaces)
     *
     * <pre>
     * atom_name_1:
     *   metric_1: 1
     *   metric_2: 2
     * atom_name_2:
     *   metric_3: 3
     * ...
     * </pre>
     *
     * @param metricsByAtom Metrics keyed by atom.
     * @param atomNames Subset of atom names from {@code metricsByAtom} to be logged.
     * @return The formatted metrics as a string.
     */
    private String formatMetricsForLoggingByAtoms(
            Map<String, MultiMap<String, String>> metricsByAtom, Collection<String> atomNames) {
        return atomNames
                .stream()
                .map(
                        a ->
                                "\t"
                                        + a
                                        + ":\n"
                                        + formatAtomMetricsForLoggingByMetricKeys(
                                                metricsByAtom.get(a),
                                                metricsByAtom.get(a).keySet(),
                                                2))
                .collect(Collectors.joining(","));
    }

    /** Wrapper for {@code CLog.w()} to facilitate testing. */
    private void logWarning(String formatter, Object... args) {
        String formatted = String.format(formatter, args);
        logFormattedWarning(formatted);
    }

    /** Wrapper around {@code CLog.w()} that enables tests to observe the formatted warning. */
    @VisibleForTesting
    protected void logFormattedWarning(String message) {
        CLog.w(message);
    }
}
