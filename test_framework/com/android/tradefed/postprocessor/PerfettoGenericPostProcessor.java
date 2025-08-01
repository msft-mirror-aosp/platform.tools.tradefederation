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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import perfetto.protos.PerfettoMergedMetrics.TraceMetrics;
import perfetto.protos.File.TraceSummary;
import perfetto.protos.V2Metric.TraceMetricV2;
import perfetto.protos.V2Metric.TraceMetricV2.MetricRow;
import perfetto.protos.V2Metric.TraceMetricV2.MetricRow.Dimension;

/**
 * A post processor that processes text/binary metric perfetto proto file into key-value pairs by
 * recursively expanding the proto messages and fields with string values until the field with
 * numeric value is encountered. Treats enum and boolean as string values while constructing the
 * keys.
 *
 * <p>It optionally supports indexing list fields when there are duplicates while constructing the
 * keys. For example
 *
 * <p>"perfetto-indexed-list-field" - perfetto.protos.AndroidStartupMetric.Startup
 *
 * <p>"perfetto-prefix-key-field" - perfetto.protos.ProcessRenderInfo.process_name
 *
 * <p>android_startup-startup#1-package_name-com.calculator-to_first_frame-dur_ns: 300620342
 * android_startup-startup#2-package_name-com.nexuslauncher-to_first_frame-dur_ns: 49257713
 * android_startup-startup#3-package_name-com.calculator-to_first_frame-dur_ns: 261382005
 */
@OptionClass(alias = "perfetto-generic-processor")
public class PerfettoGenericPostProcessor extends BasePostProcessor {

    private static final TextFormat.Parser ALLOW_UNKNOWN_FIELD =
            TextFormat.Parser.newBuilder().setAllowUnknownFields(true).build();
    private static final String METRIC_SEP = "-";
    @VisibleForTesting static final String RUNTIME_METRIC_KEY = "perfetto_post_processor_runtime";

    public enum METRIC_FILE_FORMAT {
        text,
        binary,
        json,
    }

    public enum AlternativeParseFormat {
        json,
        none,
    }

    @Option(
            name = "perfetto-proto-file-prefix",
            description = "Prefix for identifying a perfetto metric file name.")
    private Set<String> mPerfettoProtoMetricFilePrefix = new HashSet<>();

    @Option(
            name = "perfetto-indexed-list-field",
            description = "List fields in perfetto proto metric file that has to be indexed.")
    private Set<String> mPerfettoIndexedListFields = new HashSet<>();

    @Option(
            name = "perfetto-prefix-key-field",
            description =
                    "String value field need to be prefixed with the all the other"
                            + "numeric value field keys in the proto message.")
    private Set<String> mPerfettoPrefixKeyFields = new HashSet<>();

    @Option(
            name = "perfetto-prefix-inner-message-key-field",
            description =
                    "String value field need to be prefixed with the all the other"
                            + "numeric value field keys outside of the current proto message.")
    private Set<String> mPerfettoPrefixInnerMessagePrefixFields = new HashSet<>();

    @Option(
            name = "perfetto-include-all-metrics",
            description =
                    "If this flag is turned on, all the metrics parsed from the perfetto file will"
                            + " be included in the final result map and ignores the regex passed"
                            + " in the filters.")
    private boolean mPerfettoIncludeAllMetrics = false;

    @Option(
            name = "perfetto-metric-filter-regex",
            description =
                    "Regular expression that will be used for filtering the metrics parsed"
                            + " from the perfetto proto metric file.")
    private Set<String> mPerfettoMetricFilterRegEx = new HashSet<>();

    @Option(
            name = "trace-processor-output-format",
            description = "Trace processor output format. One of [binary|text|json]")
    private METRIC_FILE_FORMAT mTraceProcessorOutputFormat = METRIC_FILE_FORMAT.text;

    @Option(
            name = "decompress-perfetto-timeout",
            description = "Timeout to decompress perfetto compressed file.",
            isTimeVal = true)
    private long mDecompressTimeoutMs = TimeUnit.MINUTES.toMillis(20);

    @Deprecated
    @Option(
            name = "processed-metric",
            description =
                    "True if the metric is final and shouldn't be processed any more,"
                            + " false if the metric can be handled by another post-processor.")
    private boolean mProcessedMetric = true;

    @Option(
            name = "perfetto-metric-replace-prefix",
            description =
                    "Replace the prefix in metricsfrom the metric proto file. Key is the prefix to"
                        + " look for in the metrickeys parsed and value is be the replacement"
                        + " string.")
    private Map<String, String> mReplacePrefixMap = new LinkedHashMap<String, String>();

    @Option(
            name = "perfetto-all-metric-prefix",
            description =
                    "Prefix to be used with the metrics collected from perfetto."
                            + "This will be applied before any other prefixes to metrics.")
    private String mAllMetricPrefix = "perfetto";

    @Option(
            name = "perfetto-alternative-parse-format",
            description =
                    "Parse the metrics as key/value pair or JSON when corresponding proto "
                            + "definition is not found. One of [json|none]")
    private AlternativeParseFormat mAlternativeParseFormat = AlternativeParseFormat.none;

    // Matches 1.73, 1.73E+2
    private Pattern mNumberWithExponentPattern =
            Pattern.compile("[-+]?[0-9]*[\\.]?[0-9]+([eE][-+]?[0-9]+)?");

    // Matches numbers without exponent format.
    private Pattern mNumberPattern = Pattern.compile("[-+]?[0-9]*[\\.]?[0-9]+");

    private List<Pattern> mMetricPatterns = new ArrayList<>();

    private String mPrefixFromInnerMessage = "";

    @Override
    public Map<String, Metric.Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        buildMetricFilterPatterns();
        return processPerfettoMetrics(filterPerfeticMetricFiles(testLogs));
    }

    @Override
    public Map<String, Metric.Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs) {
        buildMetricFilterPatterns();
        return processPerfettoMetrics(filterPerfeticMetricFiles(runLogs));
    }

    /**
     * Filter the perfetto metric file based on the prefix.
     *
     * @param logs
     * @return files matched the prefix.
     */
    private List<File> filterPerfeticMetricFiles(Map<String, LogFile> logs) {
        List<File> perfettoMetricFiles = new ArrayList<>();
        for (String key : logs.keySet()) {
            Optional<String> reportPrefix =
                    mPerfettoProtoMetricFilePrefix.stream()
                            .filter(prefix -> key.startsWith(prefix))
                            .findAny();

            if (!reportPrefix.isPresent()) {
                continue;
            }
            CLog.i("Adding perfetto metric file: " + logs.get(key).getPath());
            perfettoMetricFiles.add(new File(logs.get(key).getPath()));
        }
        return perfettoMetricFiles;
    }

    /**
     * Process perfetto metric files into key, value pairs.
     *
     * @param perfettoMetricFiles perfetto metric files to be processed.
     * @return key, value pairs processed from the metrics.
     */
    private Map<String, Metric.Builder> processPerfettoMetrics(List<File> perfettoMetricFiles) {
        Map<String, Metric.Builder> parsedMetrics = new HashMap<>();
        long startTime = System.currentTimeMillis();
        File uncompressedDir = null;
        for (File perfettoMetricFile : perfettoMetricFiles) {
            // Text files by default are compressed before uploading. Decompress the text proto
            // file before post processing.
            try {
                if (!(mTraceProcessorOutputFormat == METRIC_FILE_FORMAT.binary)
                        && ZipUtil.isZipFileValid(perfettoMetricFile, true)) {
                    ZipFile perfettoZippedFile = new ZipFile(perfettoMetricFile);
                    uncompressedDir = FileUtil.createTempDir("uncompressed_perfetto_metric");
                    ZipUtil2.extractZip(perfettoZippedFile, uncompressedDir);
                    perfettoMetricFile = uncompressedDir.listFiles()[0];
                    perfettoZippedFile.close();
                }
            } catch (IOException e) {
                CLog.e(
                        "IOException happened when unzipping the perfetto metric proto"
                                + " file."
                                + e.getMessage());
            }

            // Parse the perfetto proto file.
            try (BufferedReader bufferedReader =
                    new BufferedReader(new FileReader(perfettoMetricFile))) {
                switch (mTraceProcessorOutputFormat) {
                    case text:
                        if (perfettoMetricFile.getName().contains("v2")) {
                            TraceSummary.Builder builderV2 = TraceSummary.newBuilder();
                            ALLOW_UNKNOWN_FIELD.merge(bufferedReader, builderV2);
                            parsedMetrics.putAll(convertPerfettoProtoMessageV2(builderV2.build()));
                        } else {
                            TraceMetrics.Builder builder = TraceMetrics.newBuilder();
                            ALLOW_UNKNOWN_FIELD.merge(bufferedReader, builder);
                            parsedMetrics.putAll(
                                    handlePrefixForProcessedMetrics(
                                            convertPerfettoProtoMessage(builder.build())));
                        }
                        break;
                    case binary:
                        if (perfettoMetricFile.getName().contains("v2")) {
                            TraceSummary traceSummary =
                                    TraceSummary.parseFrom(new FileInputStream(perfettoMetricFile));
                            parsedMetrics.putAll(convertPerfettoProtoMessageV2(traceSummary));
                        } else {
                            TraceMetrics metricProto = null;
                            metricProto =
                                    TraceMetrics.parseFrom(new FileInputStream(perfettoMetricFile));
                            parsedMetrics.putAll(
                                    handlePrefixForProcessedMetrics(
                                            convertPerfettoProtoMessage(metricProto)));
                        }
                        break;
                    case json:
                        CLog.w("JSON perfetto metric file processing not supported.");
                }
            } catch (ParseException e) {
                if (AlternativeParseFormat.none == mAlternativeParseFormat) {
                    CLog.e("Failed to merge the perfetto metric file. " + e.getMessage());
                } else {
                    CLog.w("Failed to merge the perfetto metric file, trying alternative");
                    parsedMetrics.putAll(
                            handlePrefixForProcessedMetrics(
                                    processPerfettoMetricsWithAlternativeMethods(
                                            perfettoMetricFile)));
                }
            } catch (IOException ioe) {
                CLog.e(
                        "IOException happened when reading the perfetto metric file. "
                                + ioe.getMessage());
            } finally {
                // Delete the uncompressed perfetto metric proto file directory.
                FileUtil.recursiveDelete(uncompressedDir);
            }
        }

        if (parsedMetrics.size() > 0) {
            parsedMetrics.put(
                    RUNTIME_METRIC_KEY,
                    TfMetricProtoUtil.stringToMetric(
                            Long.toString(System.currentTimeMillis() - startTime))
                            .toBuilder());
        }

        return parsedMetrics;
    }

    /**
     * Process perfetto metric files that does not have proto defined in TraceMetrics into key,
     * value pairs.
     *
     * @param perfettoMetricFile perfetto metric file to be processed.
     * @return key, value pairs processed from the metrics.
     */
    private Map<String, Metric.Builder> processPerfettoMetricsWithAlternativeMethods(
            File perfettoMetricFile) {
        CLog.w("Entering processPerfettoMetricsWithAlternativeMethods");
        Map<String, Metric.Builder> result = new HashMap<>();
        try (BufferedReader bufferedReader =
                new BufferedReader(new FileReader(perfettoMetricFile))) {
            if (AlternativeParseFormat.json == mAlternativeParseFormat) {
                JsonObject node = new Gson().fromJson(bufferedReader, JsonObject.class);
                node.entrySet().forEach(nested -> flattenJson(result, nested, new ArrayList<>()));
                return result;
            }
        } catch (JsonSyntaxException jse) {
            CLog.e(
                    "JsonSyntaxException happened when parsing perfetto metric file. "
                            + jse.getMessage());
        } catch (IOException ioe) {
            CLog.e(
                    "IOException happened when reading the perfetto metric file. "
                            + ioe.getMessage());
        }

        return result;
    }

    /**
     * Flatten a json into key, value pairs where key is the concatenation of keys in each level of
     * json, and the value is the value of the leaf node.
     *
     * @param result the map to store the result
     * @param node the node to process from
     * @names the list of the names that has been added so far above the current node.
     * @return key, value pairs of the flattened json.
     */
    private Map<String, Metric.Builder> flattenJson(
            Map<String, Metric.Builder> result,
            Entry<String, JsonElement> node,
            List<String> names) {
        names.add(node.getKey());
        if (node.getValue().isJsonObject()) {
            node.getValue()
                    .getAsJsonObject()
                    .entrySet()
                    .forEach(nested -> flattenJson(result, nested, new ArrayList<>(names)));
        } else {
            String name = names.stream().collect(Collectors.joining(METRIC_SEP));
            result.put(
                    name,
                    TfMetricProtoUtil.stringToMetric(node.getValue().getAsString()).toBuilder());
        }

        return result;
    }

    private Map<String, Metric.Builder> handlePrefixForProcessedMetrics(
            Map<String, Metric.Builder> processedMetrics) {
        Map<String, Metric.Builder> result = new HashMap<>();
        result.putAll(filterMetrics(processedMetrics));
        replacePrefix(result);
        // Generic prefix string is applied to all the metrics parsed from perfetto trace file.
        replaceAllMetricPrefix(result);
        return result;
    }

    /**
     * Replace the prefix in the metric key parsed from the proto file with the given string.
     *
     * @param processPerfettoMetrics metrics parsed from the perfetto proto file.
     */
    private void replacePrefix(Map<String, Metric.Builder> processPerfettoMetrics) {
        if (mReplacePrefixMap.isEmpty()) {
            return;
        }
        Map<String, Metric.Builder> finalMetrics = new HashMap<String, Metric.Builder>();
        for (Map.Entry<String, Metric.Builder> metric : processPerfettoMetrics.entrySet()) {
            boolean isReplaced = false;
            for (Map.Entry<String, String> replaceEntry : mReplacePrefixMap.entrySet()) {
                if (metric.getKey().startsWith(replaceEntry.getKey())) {
                    String newKey =
                            metric.getKey()
                                    .replaceFirst(replaceEntry.getKey(), replaceEntry.getValue());
                    finalMetrics.put(newKey, metric.getValue());
                    isReplaced = true;
                    break;
                }
            }
            // If key is not replaced put the original key and value in the final metrics.
            if (!isReplaced) {
                finalMetrics.put(metric.getKey(), metric.getValue());
            }
        }
        processPerfettoMetrics.clear();
        processPerfettoMetrics.putAll(finalMetrics);
    }

    /**
     * Prefix all the metrics key with given string.
     *
     * @param processPerfettoMetrics metrics parsed from the perfetto proto file.
     */
    private void replaceAllMetricPrefix(Map<String, Metric.Builder> processPerfettoMetrics) {
        if (mAllMetricPrefix == null || mAllMetricPrefix.isEmpty()) {
            return;
        }
        Map<String, Metric.Builder> finalMetrics = new HashMap<String, Metric.Builder>();
        for (Map.Entry<String, Metric.Builder> metric : processPerfettoMetrics.entrySet()) {
            String newKey = String.format("%s_%s", mAllMetricPrefix, metric.getKey());
            finalMetrics.put(newKey, metric.getValue());
            CLog.d("Perfetto trace metric: key: %s value: %s", newKey, metric.getValue());
        }
        processPerfettoMetrics.clear();
        processPerfettoMetrics.putAll(finalMetrics);
    }

    /**
     * Expands the metric proto file as tree structure and converts it into key, value pairs by
     * recursively constructing the key using the message name, proto fields with string values
     * until the numeric proto field is encountered.
     *
     * <p>android_startup-startup-package_name-com.calculator-to_first_frame-dur_ns: 300620342
     * android_startup-startup-package_name-com.nexuslauncher-to_first_frame-dur_ns: 49257713
     *
     * <p>It also supports indexing the list proto fields optionally. This will be used if the list
     * generates duplicate key's when recursively expanding the messages to prevent overriding the
     * results.
     *
     * <p>"perfetto-indexed-list-field" - perfetto.protos.AndroidStartupMetric.Startup
     *
     * <p><android_startup-startup#1-package_name-com.calculator-to_first_frame-dur_ns: 300620342
     * android_startup-startup#2-package_name-com.nexuslauncher-to_first_frame-dur_ns: 49257713
     * android_startup-startup#3-package_name-com.calculator-to_first_frame-dur_ns: 261382005
     *
     * <p>"perfetto-prefix-key-field" - perfetto.protos.ProcessRenderInfo.process_name
     * android_hwui_metric-process_info-process_name-system_server-cache_miss_avg
     */
    private Map<String, Metric.Builder> convertPerfettoProtoMessage(Message reportMessage) {
        Map<FieldDescriptor, Object> fields = reportMessage.getAllFields();
        Map<String, Metric.Builder> convertedMetrics = new HashMap<String, Metric.Builder>();
        List<String> keyPrefixes = new ArrayList<String>();

        // Key that will be used to prefix the other keys in the same proto message.
        String keyPrefixOtherFields = "";
        // If the flag is set then the prefix is set from the current message. Used
        // to clear the prefix text after all the metrics are prefixed.
        boolean prefixSetInCurrentMessage = false;

        // TODO(b/15014555): Cleanup the parsing logic.
        for (Entry<FieldDescriptor, Object> entry : fields.entrySet()) {
            if (!(entry.getValue() instanceof Message) && !(entry.getValue() instanceof List)) {
                if (isNumeric(entry.getValue().toString())) {
                    // Check if the current field has to be used as prefix for other fields
                    // and add it to the list of prefixes.
                    if (mPerfettoPrefixKeyFields.contains(entry.getKey().toString())) {
                        if (!keyPrefixOtherFields.isEmpty()) {
                            keyPrefixOtherFields = keyPrefixOtherFields.concat("-");
                        }
                        keyPrefixOtherFields =
                                keyPrefixOtherFields.concat(
                                        String.format(
                                                "%s-%s",
                                                entry.getKey().getName().toString(),
                                                entry.getValue().toString()));
                        continue;
                    }

                    if (mPerfettoPrefixInnerMessagePrefixFields.contains(
                            entry.getKey().toString())) {
                        if (!mPrefixFromInnerMessage.isEmpty()) {
                            mPrefixFromInnerMessage = mPrefixFromInnerMessage.concat("-");
                        }
                        mPrefixFromInnerMessage =
                                mPrefixFromInnerMessage.concat(
                                        String.format(
                                                "%s-%s",
                                                entry.getKey().getName().toString(),
                                                entry.getValue().toString()));
                        prefixSetInCurrentMessage = true;
                        continue;
                    }

                    // Otherwise treat this numeric field as metric.
                    if (mNumberPattern.matcher(entry.getValue().toString()).matches()) {
                        convertedMetrics.put(
                                entry.getKey().getName(),
                                TfMetricProtoUtil.stringToMetric(entry.getValue().toString())
                                        .toBuilder());
                    } else {
                        // Parse the exponent notation of string before adding it to metric.
                        convertedMetrics.put(
                                entry.getKey().getName(),
                                TfMetricProtoUtil.stringToMetric(
                                        Long.toString(
                                                Double.valueOf(entry.getValue().toString())
                                                        .longValue()))
                                        .toBuilder());
                    }
                } else {
                    // Add to prefix list if string value is encountered.
                    keyPrefixes.add(
                            String.join(
                                    METRIC_SEP,
                                    entry.getKey().getName().toString(),
                                    entry.getValue().toString()));
                    if (mPerfettoPrefixKeyFields.contains(entry.getKey().toString())) {
                        if (!keyPrefixOtherFields.isEmpty()) {
                            keyPrefixOtherFields = keyPrefixOtherFields.concat("-");
                        }
                        keyPrefixOtherFields =
                                keyPrefixOtherFields.concat(
                                        String.format(
                                                "%s-%s",
                                                entry.getKey().getName().toString(),
                                                entry.getValue().toString()));
                    }

                    if (mPerfettoPrefixInnerMessagePrefixFields.contains(
                            entry.getKey().toString())) {
                        if (!mPrefixFromInnerMessage.isEmpty()) {
                            mPrefixFromInnerMessage = mPrefixFromInnerMessage.concat("-");
                        }
                        mPrefixFromInnerMessage =
                                mPrefixFromInnerMessage.concat(
                                        String.format(
                                                "%s-%s",
                                                entry.getKey().getName().toString(),
                                                entry.getValue().toString()));
                        prefixSetInCurrentMessage = true;
                        continue;
                    }
                }
            }
        }

        // Recursively expand the proto messages and repeated fields(i.e list).
        // Recursion when there are no messages or list with in the current message.
        // Used to cache the message prefix.
        String innerMessagePrefix = "";
        for (Entry<FieldDescriptor, Object> entry : fields.entrySet()) {
            if (entry.getValue() instanceof Message) {
                Map<String, Metric.Builder> messageMetrics =
                        convertPerfettoProtoMessage((Message) entry.getValue());
                if (!mPrefixFromInnerMessage.isEmpty()) {
                    innerMessagePrefix = mPrefixFromInnerMessage;
                }
                for (Entry<String, Metric.Builder> metricEntry : messageMetrics.entrySet()) {
                    // Add prefix to the metrics parsed from this message.
                    for (String prefix : keyPrefixes) {
                        convertedMetrics.put(
                                String.join(
                                        METRIC_SEP,
                                        prefix,
                                        entry.getKey().getName(),
                                        metricEntry.getKey()),
                                metricEntry.getValue());
                    }
                    if (keyPrefixes.isEmpty()) {
                        convertedMetrics.put(
                                String.join(
                                        METRIC_SEP, entry.getKey().getName(), metricEntry.getKey()),
                                metricEntry.getValue());
                    }
                }
            } else if (entry.getValue() instanceof List) {
                List<? extends Object> listMetrics = (List) entry.getValue();
                for (int i = 0; i < listMetrics.size(); i++) {
                    String metricKeyRoot;
                    // Use indexing if the current field is chosen for indexing.
                    // Use it if metrics keys generated has duplicates to prevent overriding.
                    if (mPerfettoIndexedListFields.contains(entry.getKey().toString())) {
                        metricKeyRoot =
                                String.join(
                                        METRIC_SEP,
                                        entry.getKey().getName(),
                                        String.valueOf(i + 1));
                    } else {
                        metricKeyRoot = String.join(METRIC_SEP, entry.getKey().getName());
                    }
                    if (listMetrics.get(i) instanceof Message) {
                        Map<String, Metric.Builder> messageMetrics =
                                convertPerfettoProtoMessage((Message) listMetrics.get(i));
                        if (!mPrefixFromInnerMessage.isEmpty()) {
                            innerMessagePrefix = mPrefixFromInnerMessage;
                        }
                        for (Entry<String, Metric.Builder> metricEntry :
                                messageMetrics.entrySet()) {
                            for (String prefix : keyPrefixes) {
                                convertedMetrics.put(
                                        String.join(
                                                METRIC_SEP,
                                                prefix,
                                                metricKeyRoot,
                                                metricEntry.getKey()),
                                        metricEntry.getValue());
                            }
                            if (keyPrefixes.isEmpty()) {
                                convertedMetrics.put(
                                        String.join(
                                                METRIC_SEP, metricKeyRoot, metricEntry.getKey()),
                                        metricEntry.getValue());
                            }
                        }
                    } else {
                        convertedMetrics.put(
                                metricKeyRoot,
                                TfMetricProtoUtil.stringToMetric(listMetrics.get(i).toString())
                                        .toBuilder());
                    }
                }
            }
        }

        // Add prefix key to all the keys in current proto message which has numeric values.
        Map<String, Metric.Builder> additionalConvertedMetrics =
                new HashMap<String, Metric.Builder>();
        if (!keyPrefixOtherFields.isEmpty()) {
            for (Map.Entry<String, Metric.Builder> currentMetric : convertedMetrics.entrySet()) {
                additionalConvertedMetrics.put(
                        String.format("%s-%s", keyPrefixOtherFields, currentMetric.getKey()),
                        currentMetric.getValue());
            }
        }

        if (!mPrefixFromInnerMessage.isEmpty() || !innerMessagePrefix.isEmpty()) {
            String prefixToUse =
                    !mPrefixFromInnerMessage.isEmpty()
                            ? mPrefixFromInnerMessage
                            : innerMessagePrefix;
            for (Map.Entry<String, Metric.Builder> currentMetric : convertedMetrics.entrySet()) {
                additionalConvertedMetrics.put(
                        String.format("%s-%s", prefixToUse, currentMetric.getKey()),
                        currentMetric.getValue());
            }
        }

        if (!prefixSetInCurrentMessage) {
            mPrefixFromInnerMessage = "";
        }

        // Not cleaning up the other metrics without prefix fields.
        convertedMetrics.putAll(additionalConvertedMetrics);

        return convertedMetrics;
    }

    /**
     * Expands the metric v2 proto file as tree structure and converts it into key, value pairs by
     * recursively constructing the key using the id and dimensions string values, proto fields with
     * string values until the numeric proto field is encountered.
     *
     * <p>memory_per_process-avg_rss_and_swap-.ShannonImsService: 121380864.000000
     * memory_per_process-avg_rss_and_swap-/apex/com.android.adbd/bin/adbd: 10464441.000000
     */
    private Map<String, Metric.Builder> convertPerfettoProtoMessageV2(TraceSummary reportMessage) {
        CLog.d("convertPerfettoProtoMessageV2 reportMessage : " + reportMessage);
        Map<String, Metric.Builder> convertedMetrics = new HashMap<String, Metric.Builder>();
        for (TraceMetricV2 metric : reportMessage.getMetricList()) {
            for (MetricRow row : metric.getRowList()) {
                String rowKey = metric.getSpec().getId();
                double rowValue = row.getValue();
                for (Dimension dimension : row.getDimensionList()) {
                    Map<FieldDescriptor, Object> fields = dimension.getAllFields();
                    for (Entry<FieldDescriptor, Object> entry : fields.entrySet()) {
                        if (!(entry.getValue() instanceof Message)) {
                            rowKey = rowKey.concat("-").concat(entry.getValue().toString());
                        }
                    }
                }
                convertedMetrics.put(
                        rowKey,
                        TfMetricProtoUtil.stringToMetric(String.format("%f", rowValue))
                                .toBuilder());
                CLog.d(
                        "Perfetto trace v2 metric: key: %s value: %s",
                        rowKey, Double.toString(rowValue));
            }
        }
        return convertedMetrics;
    }

    /**
     * Check if the given string is number. It matches the string with exponent notation as well.
     *
     * <p>For example returns true for Return true for 1.73, 1.73E+2
     */
    private boolean isNumeric(String strNum) {
        if (strNum == null) {
            return false;
        }
        return mNumberWithExponentPattern.matcher(strNum).matches();
    }

    /** Build regular expression patterns to filter the metrics. */
    private void buildMetricFilterPatterns() {
        if (!mPerfettoMetricFilterRegEx.isEmpty() && mMetricPatterns.isEmpty()) {
            for (String regEx : mPerfettoMetricFilterRegEx) {
                mMetricPatterns.add(Pattern.compile(regEx));
            }
        }
    }

    /**
     * Filter parsed metrics from the proto metric files based on the regular expression. If
     * "mPerfettoIncludeAllMetrics" is enabled then filters will be ignored and returns all the
     * parsed metrics.
     */
    private Map<String, Metric.Builder> filterMetrics(Map<String, Metric.Builder> parsedMetrics) {
        if (mPerfettoIncludeAllMetrics) {
            return parsedMetrics;
        }
        Map<String, Metric.Builder> filteredMetrics = new HashMap<>();
        for (Entry<String, Metric.Builder> metricEntry : parsedMetrics.entrySet()) {
            for (Pattern pattern : mMetricPatterns) {
                if (pattern.matcher(metricEntry.getKey()).matches()) {
                    filteredMetrics.put(metricEntry.getKey(), metricEntry.getValue());
                    break;
                }
            }
        }
        return filteredMetrics;
    }

    /** Set the metric type to RAW metric. */
    @Override
    protected DataType getMetricType() {
        return DataType.RAW;
    }
}
