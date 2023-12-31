/*
 * Copyright (C) 2019 The Android Open Source Project
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
import com.android.os.StatsLog.EventMetricData;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.StatsLogReport;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.ProtoUtil;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A post processor that processes event metrics in statsd reports into key-value pairs, using the
 * formatters specified on the processor.
 */
@OptionClass(alias = "statsd-event-metric-processor")
public class StatsdEventMetricPostProcessor extends StatsdGenericPostProcessor {
    @Option(
            name = "metric-formatter",
            description =
                    "A formatter to format a statsd atom into a key-value pair for a metric."
                        + " Format: Use the atom field name as key and a 'key=value' string as"
                        + " value, and enclose atom field reference in square brackets, where they"
                        + " will be substituted with the field values in the atom. Example: key:"
                        + " app_start_occurred, value:"
                        + " [type]_startup_[pkg_name]=[windows_drawn_delay_millis]. Additionally,"
                        + " use [_elapsed_timestamp_nanos] for the elapsed_timestamp_nanos field"
                        + " that records when the event occurred. At most one reference to"
                        + " repeated fields in each formatter is supported. Field definitions can"
                        + " be found in the atoms.proto file under frameworks/proto_logging/stats"
                        + " in the source tree.")
    private MultiMap<String, String> mMetricFormatters = new MultiMap<>();

    // Corresponds to a field reference, e.g., "[field1_name.field2_name.field3_name]".
    private static final Pattern FIELD_REF_PATTERN =
            Pattern.compile("\\[(?:[a-zA-Z_]+\\.)*(?:[a-zA-Z_]+)\\]");

    /**
     * Parse the event metrics from the {@link ConfigMetricsReportList} using the atom formatters.
     *
     * <p>Event metrics resulting in duplicate keys will be stored as comma separated values.
     */
    @Override
    protected Map<String, Metric.Builder> parseMetricsFromReportList(
            ConfigMetricsReportList reportList) {
        // A multimap is used to store metrics with multiple potential values.
        MultiMap<String, String> parsedMetrics = new MultiMap<>();
        for (ConfigMetricsReport report : reportList.getReportsList()) {
            for (StatsLogReport metric : report.getMetricsList()) {
                if (!metric.hasEventMetrics()) {
                    continue;
                }
                // Look through each EventMetricData object's atom's every field, and extract
                // metrics when the atom field name matches a set of configured metric formatters.
                List<EventMetricData> dataItems = metric.getEventMetrics().getDataList();
                for (EventMetricData data : dataItems) {
                    Message atomParent = data;
                    Atom atom = null;
                    if (data.hasAtom()) {
                        atom = data.getAtom();
                    } else if (data.hasAggregatedAtomInfo()) {
                        // In this case, the message housing the "elapsed_timestamp_nanos" field
                        // becomes the aggregated_atom_info field.
                        atomParent = data.getAggregatedAtomInfo();
                        atom = data.getAggregatedAtomInfo().getAtom();
                    }
                    if (atom == null) {
                        continue;
                    }
                    Map<FieldDescriptor, Object> atomFields = atom.getAllFields();
                    for (FieldDescriptor field : atomFields.keySet()) {
                        if (mMetricFormatters.containsKey(field.getName())) {
                            parsedMetrics.putAll(
                                    getMetricsByFormatters(
                                            atomParent,
                                            atom,
                                            field,
                                            mMetricFormatters.get(field.getName())));
                        }
                    }
                }
            }
        }
        // Convert the multimap to a normal map with list of values turned into comma separated
        // values.
        Map<String, Metric.Builder> finalMetrics = new HashMap<>();
        for (String key : parsedMetrics.keySet()) {
            // TODO(b/140434593): Move to repeated String fields and make sure that is supported in
            // other post processors as well.
            String value = String.join(",", parsedMetrics.get(key));
            finalMetrics.put(key, TfMetricProtoUtil.stringToMetric(value).toBuilder());
        }
        return finalMetrics;
    }

    /**
     * Helper method to get metrics from an {@link Atom} and its parent using the desired atom field
     * and metric formatters.
     */
    private MultiMap<String, String> getMetricsByFormatters(
            Message atomParent, Atom atom, FieldDescriptor atomField, List<String> formatters) {
        MultiMap<String, String> metrics = new MultiMap<>();
        Message atomContent = (Message) atom.getField(atomField);
        for (String formatter : formatters) {
            String keyFormatter = formatter.split("=")[0];
            String valueFormatter = formatter.split("=")[1];
            List<String> metricKeys = fillInPlaceholders(keyFormatter, atomParent, atomContent);
            List<String> metricValues = fillInPlaceholders(valueFormatter, atomParent, atomContent);
            if (metricKeys.size() > 1 && metricValues.size() > 1) {
                // If a repeated field is referenced in more than one location in the same
                // formatter, it would be hard to determine whether there is a "pairing" relation
                // between the two fields with the current field reference syntax. Specifically, one
                // might want repeated_field.field1 and repeated_field.field2 to only appear paired
                // within the same repeated_field instance, but the current logic produces a cross
                // product between all repeated_field.field1 and repeated_field.field2 values, hence
                // the warning below.
                CLog.w(
                        "Found repeated fields in both metric key and value in formatting pair "
                                + "%s: %s. This is currently unsupported and could result in "
                                + "meaningless data. Skipping reporting on this pair.",
                        atomField.getName(), formatter);
                continue;
            }
            for (String metricKey : metricKeys) {
                for (String metricValue : metricValues) {
                    metrics.put(metricKey, metricValue);
                }
            }
        }
        return metrics;
    }

    /** Fill in the placeholders in the formatter using the proto message as source. */
    private List<String> fillInPlaceholders(
            String formatter, Message atomParent, Message atomContent) {
        Matcher matcher = FIELD_REF_PATTERN.matcher(formatter);
        List<String> results = Arrays.asList(formatter);
        while (matcher.find()) {
            String placeholder = matcher.group();
            // Strip the brackets.
            String fieldReference = placeholder.substring(1, placeholder.length() - 1);
            List<String> actual = new ArrayList<>();
            if (fieldReference.startsWith("_")) {
                actual.addAll(
                        ProtoUtil.getNestedFieldFromMessageAsStrings(
                                atomParent,
                                Arrays.asList(fieldReference.substring(1).split("\\."))));
            } else {
                actual.addAll(
                        ProtoUtil.getNestedFieldFromMessageAsStrings(
                                atomContent, Arrays.asList(fieldReference.split("\\."))));
            }
            // If both the existing expansion results and newly expanded results have multiple
            // entries, then both the existing expansion and new expansion referred to repeated
            // fields.
            if (results.size() > 1 && actual.size() > 1) {
                // If a repeated field is referenced in more than one location in the same
                // formatter, it would be hard to determine whether there is a "pairing" relation
                // between the two fields with the current field reference syntax. Specifically, one
                // might want repeated_field.field1 and repeated_field.field2 to only appear paired
                // within the same repeated_field instance, but the current logic produces a cross
                // product between all repeated_field.field1 and repeated_field.field2 values, hence
                // the warning below.
                CLog.w(
                        "Found repeated fields in both metric key and value in formatter %s. This "
                                + "is currently unsupported and could result in meaningless data. "
                                + "Skipping reporting on this formatter.",
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
}
