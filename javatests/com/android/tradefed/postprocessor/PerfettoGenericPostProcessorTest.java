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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.MockitoAnnotations.initMocks;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.postprocessor.PerfettoGenericPostProcessor.METRIC_FILE_FORMAT;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import perfetto.protos.PerfettoMergedMetrics.TraceMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link PerfettoGenericPostProcessor}. */
@RunWith(JUnit4.class)
public class PerfettoGenericPostProcessorTest {

    @Mock private ITestInvocationListener mListener;
    private PerfettoGenericPostProcessor mProcessor;
    private OptionSetter mOptionSetter;

    private static final String PREFIX_OPTION = "perfetto-proto-file-prefix";
    private static final String PREFIX_OPTION_VALUE = "metric-perfetto";
    private static final String INDEX_OPTION = "perfetto-indexed-list-field";
    private static final String KEY_PREFIX_OPTION = "perfetto-prefix-key-field";
    private static final String REGEX_OPTION_VALUE = "perfetto-metric-filter-regex";
    private static final String ALL_METRICS_OPTION = "perfetto-include-all-metrics";
    private static final String ALL_METRICS_PREFIX_OPTION = "perfetto-all-metric-prefix";
    private static final String REPLACE_REGEX_OPTION = "perfetto-metric-replace-prefix";
    private static final String FILE_FORMAT_OPTION = "trace-processor-output-format";
    private static final String ALTERNATIVE_PARSE_FORMAT_OPTION =
            "perfetto-alternative-parse-format";

    File perfettoMetricProtoFile = null;
    File perfettoV2MetricProtoFile = null;

    private static final Boolean DEBUG = false;

    @Before
    public void setUp() throws ConfigurationException {
        initMocks(this);
        mProcessor = new PerfettoGenericPostProcessor();
        mProcessor.init(mListener);
        mOptionSetter = new OptionSetter(mProcessor);
    }

    /**
     * Test metrics count should be zero if "perfetto-include-all-metrics" is not set or set to
     * false;
     */
    @Test
    public void testNoMetricsByDefault() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertTrue(
                "Number of metrics parsed without indexing is incorrect.",
                parsedMetrics.size() == 0);
    }

    /**
     * Test metrics are filtered correctly when filter regex are passed and
     * "perfetto-include-all-metrics" is set to false (Note: by default false)
     */
    @Test
    public void testMetricsFilterWithRegEx() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(parsedMetrics, "perfetto_android_startup-startup-1-startup_id", 1);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_startup-startup-1-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                36175473);
    }

    /**
     * Test metrics are filtered correctly when filter regex are passed and prefix are replaced with
     * the given string.
     */
    @Test
    public void testMetricsFilterWithRegExAndReplacePrefix()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        mOptionSetter.setOptionValue(REPLACE_REGEX_OPTION, "android_startup-startup-1",
                "newprefix");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor
                .processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertFalse("Metric key not expected but found",
                parsedMetrics.containsKey("android_startup-startup-1-startup_id"));
        assertMetricsContain(parsedMetrics, "perfetto_newprefix-startup_id", 1);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_newprefix-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                36175473);
    }

    /**
     * Test all metrics are included when "perfetto-include-all-metrics" is set to true and ignores
     * any of the filter regex set.
     */
    @Test
    public void testAllMetricsOptionIgnoresFilter() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        // Test for non startup metrics exists.
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test that the post processor can parse reports from test metrics. */
    @Test
    public void testParsingTestMetrics() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processTestMetricsAndLogs(
                        new TestDescription("class", "test"), new HashMap<>(), testLogs);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test custom all metric suffix is applied correctly. */
    @Test
    public void testParsingWithAllMetricsPrefix() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(ALL_METRICS_PREFIX_OPTION, "custom_all_prefix");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processTestMetricsAndLogs(
                        new TestDescription("class", "test"), new HashMap<>(), testLogs);
        assertMetricsContain(
                parsedMetrics,
                "custom_all_prefix_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test the post processor can parse reports from run metrics. */
    @Test
    public void testParsingRunMetrics() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /**
     * Test metrics count and metrics without indexing. In case of app startup metrics startup
     * messages for same package name will be overridden without indexing.
     */
    @Test
    public void testParsingWithoutIndexing() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContain(parsedMetrics, "perfetto_android_startup-startup-startup_id", 2);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_startup-startup-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                53102401);
    }

    /**
     * Test metrics count and metrics with indexing. In case of app startup metrics, startup
     * messages for same package name will not be overridden with indexing.
     */
    @Test
    public void testParsingWithIndexing() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(parsedMetrics, "perfetto_android_startup-startup-1-startup_id", 1);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_startup-startup-1-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                36175473);
        assertMetricsContain(parsedMetrics, "perfetto_android_startup-startup-2-startup_id", 2);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_startup-startup-2-package_name-com.google."
                        + "android.apps.nexuslauncher-to_first_frame-dur_ns",
                53102401);
    }

    /** Test metrics enabled with key and string value prefixing. */
    @Test
    public void testParsingWithKeyAndStringValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION,
                "perfetto.protos.ProcessRenderInfo.process_name");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor
                .processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_hwui_metric-process_info-process_name-com.android.systemui-all_mem_min",
                15120269);
    }

    /** Test metrics enabled with multiple key and string value prefixing. */
    @Test
    public void testParsingWithMultipleKeyAndStringValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION,
                "perfetto.protos.ProcessRenderInfo.process_name");
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION,
                "perfetto.protos.ProcessRenderInfo.rt_cpu_time_ms");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor
                .processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_hwui_metric-process_info-process_name-com.android.systemui-"
                + "rt_cpu_time_ms-2481-all_mem_min",
                15120269);
    }

    /** Test metrics enabled with key and integer value prefixing. */
    @Test
    public void testParsingWithKeyAndIntegerValuePrefixing()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(
                KEY_PREFIX_OPTION, "perfetto.protos.AndroidCpuMetric.CoreData.id");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor
                .processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContain(parsedMetrics, "perfetto_android_cpu-process_info-name-com.google."
                + "android.apps.messaging-threads-name-BG Thread #1-core-id-1-metrics-runtime_ns",
                14376405);
    }

    /** Test the post processor can parse binary perfetto metric proto format. */
    @Test
    public void testParsingBinaryProto() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.binary, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(FILE_FORMAT_OPTION, "binary");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.PB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /** Test the post processor can parse binary perfetto metric proto format. */
    @Test
    public void testNoSupportForJsonParsing() throws ConfigurationException, IOException {
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        mOptionSetter.setOptionValue(FILE_FORMAT_OPTION, "json");
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertTrue("Should not have any metrics if json format is set", parsedMetrics.size() == 0);
    }

    /**
     * Test the post processor can parse reports from run metrics when the text proto file is
     * compressed format.
     */
    @Test
    public void testParsingRunMetricsWithCompressedFile()
            throws ConfigurationException, IOException {
        // Setup compressed text proto metric file.
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_mem-process_metrics-process_name-"
                        + ".dataservices-total_counters-anon_rss-min",
                27938816);
    }

    /**
     * Test metrics are parsed correctly when there are files with and without corresponding proto
     * definition
     */
    @Test
    public void testMetricsWithAndWithoutProto() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, false);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALTERNATIVE_PARSE_FORMAT_OPTION, "json");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        File metricProtoFile = perfettoMetricProtoFile;
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        testLogs.put(
                PREFIX_OPTION_VALUE + "-2",
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(
                parsedMetrics,
                "perfetto.protos.camera_app_metrics-camera_close_latencies-close_ms",
                388.299723);

        if (null != metricProtoFile) {
            metricProtoFile.delete();
        }
    }

    /**
     * Test metrics are parsed correctly when there are files with and without corresponding proto
     * definition
     */
    @Test
    public void testMetricsWithPrefixInnerMessageField()
            throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION,
                "perfetto.protos.AndroidJankCujMetric.Cuj.name");
        mOptionSetter.setOptionValue("perfetto-prefix-inner-message-key-field",
                "perfetto.protos.AndroidProcessMetadata.name");
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor
                .processRunMetricsAndLogs(new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedMetrics);
        }
        assertMetricsContain(
                parsedMetrics,
                "android_jank_cuj-cuj-name-com.android.systemui-name-NOTIFICATION_ADD-timeline_"
                + "metrics-frame_dur_avg",
                5040562);
        assertMetricsContain(
                parsedMetrics,
                "perfetto_android_jank_cuj-cuj-name-NOTIFICATION_ADD-dur",
                460793302);
    }

    @Test
    public void testBlockingCallsMetric() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(KEY_PREFIX_OPTION, "perfetto.protos.AndroidBlockingCall.name");

        mOptionSetter.setOptionValue(REPLACE_REGEX_OPTION, "android_blocking_calls_cuj_metric",
                "android_blocking_call");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE,
                "android_blocking_calls_cuj_metric.*calls-name-.*"
                        + "(min_dur_ms|max_dur|total_dur|cnt).*");

        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(PREFIX_OPTION_VALUE,
                new LogFile(perfettoMetricProtoFile.getAbsolutePath(), "some.url",
                        LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics = mProcessor.processRunMetricsAndLogs(
                new HashMap<>(), testLogs);

        if (DEBUG) {
            printOutputMetricsForDebug(parsedMetrics);
        }
        assertMetricsContain(parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-total_dur_ms", 1);

        assertMetricsContain(parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-min_dur_ms", 0);
        assertMetricsContain(parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-TASKBAR_EXPAND-blocking_calls-name-AIDL"
                        + "::java::ITrustManager::isDeviceSecure::server-max_dur_ms", 1);

        assertMetricsContain(parsedMetrics,
                "perfetto_android_blocking_call-cuj-name-ACTION_REQUEST_IME_HIDDEN"
                        + "::HIDE_SOFT_INPUT-blocking_calls-name-AIDL::java::ITrustManager"
                        + "::isDeviceSecure::server-total_dur_ms", 1);
    }

    private void printOutputMetricsForDebug(Map<String, Metric.Builder> metrics) {
        System.out.println("\n\noutput metrics:\n\n");
        metrics.forEach((k, v) -> System.out.println(k + " value: " + v));
    }

    /** Test that post processor runtime is reported if metrics are present. */
    @Test
    public void testReportsRuntime() throws ConfigurationException, IOException {
        setupPerfettoMetricFile(METRIC_FILE_FORMAT.text, true, true);
        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(INDEX_OPTION, "perfetto.protos.AndroidStartupMetric.startup");
        mOptionSetter.setOptionValue(REGEX_OPTION_VALUE, "android_startup-startup-1.*");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoMetricProtoFile.getAbsolutePath(), "some.url", LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedMetrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertTrue(parsedMetrics.containsKey(PerfettoGenericPostProcessor.RUNTIME_METRIC_KEY));
    }

    /**
     * Test the default metric type is set to RAW.
     */
    @Test
    public void testMetricTypeIsRaw() {
        assertTrue(mProcessor.getMetricType().equals(DataType.RAW));
    }

    /** Test v2 metrics are filtered correctly */
    @Test
    public void testParsingRunV2Metrics() throws ConfigurationException, IOException {
        setupPerfettoV2MetricFile(METRIC_FILE_FORMAT.text, true);

        mOptionSetter.setOptionValue(PREFIX_OPTION, PREFIX_OPTION_VALUE);
        mOptionSetter.setOptionValue(ALL_METRICS_OPTION, "true");
        Map<String, LogFile> testLogs = new HashMap<>();
        testLogs.put(
                PREFIX_OPTION_VALUE,
                new LogFile(
                        perfettoV2MetricProtoFile.getAbsolutePath(),
                        "some.url",
                        LogDataType.TEXTPB));
        Map<String, Metric.Builder> parsedV2Metrics =
                mProcessor.processRunMetricsAndLogs(new HashMap<>(), testLogs);

        assertMetricsContain(
                parsedV2Metrics,
                "memory_per_process-avg_rss_and_swap-.ShannonImsService",
                String.format("%f", 121380864.000000));
        assertMetricsContain(
                parsedV2Metrics,
                "memory_per_process-avg_rss_and_swap-.adservices",
                String.format("%f", 123408384.000000));
        assertMetricsContain(
                parsedV2Metrics,
                "memory_per_process-avg_rss_and_swap-/apex/com.android.adbd/bin/adbd",
                String.format("%f", 10464441.000000));

        assertMetricsContain(
                parsedV2Metrics,
                "total_runtime_per_thread_for_systemui_process-(Paused)KernelPreparation-Signal"
                        + " Catcher-com.android.systemui",
                String.format("%f", 260051.000000));
        assertMetricsContain(
                parsedV2Metrics,
                "total_runtime_per_thread_for_systemui_process-(Paused)KernelPreparation-binder:12907_9-com.android.systemui",
                String.format("%f", 158854.000000));
        assertMetricsContain(
                parsedV2Metrics,
                "total_runtime_per_thread_for_systemui_process-(Paused)MarkingPause-Signal"
                        + " Catcher-com.android.systemui",
                String.format("%f", 548624.000000));
    }

    /**
     * Creates sample perfetto metric proto file used for testing.
     *
     * @param hasProto TODO
     */
    private File setupPerfettoMetricFile(
            METRIC_FILE_FORMAT format, boolean isCompressed, boolean hasProto) throws IOException {
        String perfettoTextContent =
                "android_mem {\n"
                        + "  process_metrics {\n"
                        + "    process_name: \".dataservices\"\n"
                        + "    total_counters {\n"
                        + "      anon_rss {\n"
                        + "        min: 27938816\n"
                        + "        max: 27938816\n"
                        + "        avg: 27938816\n"
                        + "      }\n"
                        + "      file_rss {\n"
                        + "        min: 62390272\n"
                        + "        max: 62390272\n"
                        + "        avg: 62390272\n"
                        + "      }\n"
                        + "      swap {\n"
                        + "        min: 0\n"
                        + "        max: 0\n"
                        + "        avg: 0\n"
                        + "      }\n"
                        + "      anon_and_swap {\n"
                        + "        min: 27938816\n"
                        + "        max: 27938816\n"
                        + "        avg: 27938816\n"
                        + "      }\n"
                        + "    }\n"
                        + "}}"
                        + "android_startup {\n"
                        + "  startup {\n"
                        + "    startup_id: 1\n"
                        + "    package_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    process_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    zygote_new_process: false\n"
                        + "    to_first_frame {\n"
                        + "      dur_ns: 36175473\n"
                        + "      main_thread_by_task_state {\n"
                        + "        running_dur_ns: 11496200\n"
                        + "        runnable_dur_ns: 487290\n"
                        + "        uninterruptible_sleep_dur_ns: 0\n"
                        + "        interruptible_sleep_dur_ns: 23645107\n"
                        + "      }\n"
                        + "      other_processes_spawned_count: 0\n"
                        + "      time_activity_manager {\n"
                        + "        dur_ns: 4135001\n"
                        + "      }\n"
                        + "      time_activity_resume {\n"
                        + "        dur_ns: 345105\n"
                        + "      }\n"
                        + "      time_choreographer {\n"
                        + "        dur_ns: 15314324\n"
                        + "      }\n"
                        + "    }\n"
                        + "    activity_hosting_process_count: 1\n"
                        + "  }\n"
                        + "  startup {\n"
                        + "    startup_id: 2\n"
                        + "    package_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    process_name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "    zygote_new_process: false\n"
                        + "    to_first_frame {\n"
                        + "      dur_ns: 53102401\n"
                        + "      main_thread_by_task_state {\n"
                        + "        running_dur_ns: 9766774\n"
                        + "        runnable_dur_ns: 320103\n"
                        + "        uninterruptible_sleep_dur_ns: 0\n"
                        + "        interruptible_sleep_dur_ns: 42358858\n"
                        + "      }\n"
                        + "      other_processes_spawned_count: 0\n"
                        + "      time_activity_manager {\n"
                        + "        dur_ns: 4742396\n"
                        + "      }\n"
                        + "      time_activity_resume {\n"
                        + "        dur_ns: 280208\n"
                        + "      }\n"
                        + "      time_choreographer {\n"
                        + "        dur_ns: 13705366\n"
                        + "      }\n"
                        + "    }\n"
                        + "    activity_hosting_process_count: 1\n"
                        + "  }\n"
                        + "}\n"
                        + "android_hwui_metric {\n"
                        + "  process_info {\n"
                        + "    process_name: \"com.android.systemui\"\n"
                        + "    rt_cpu_time_ms: 2481\n"
                        + "    draw_frame_count: 889\n"
                        + "    draw_frame_max: 21990523\n"
                        + "    draw_frame_min: 660573\n"
                        + "    draw_frame_avg: 3515215.0101237344\n"
                        + "    flush_count: 884\n"
                        + "    flush_max: 8101094\n"
                        + "    flush_min: 127760\n"
                        + "    flush_avg: 773943.91515837109\n"
                        + "    prepare_tree_count: 889\n"
                        + "    prepare_tree_max: 1718593\n"
                        + "    prepare_tree_min: 25052\n"
                        + "    prepare_tree_avg: 133403.03374578178\n"
                        + "    gpu_completion_count: 572\n"
                        + "    gpu_completion_max: 8600365\n"
                        + "    gpu_completion_min: 3594\n"
                        + "    gpu_completion_avg: 737765.40209790214\n"
                        + "    ui_record_count: 889\n"
                        + "    ui_record_max: 7079949\n"
                        + "    ui_record_min: 4583\n"
                        + "    ui_record_avg: 477551.82902137231\n"
                        + "    graphics_cpu_mem_max: 265242\n"
                        + "    graphics_cpu_mem_min: 244198\n"
                        + "    graphics_cpu_mem_avg: 260553.33484162897\n"
                        + "    graphics_gpu_mem_max: 34792176\n"
                        + "    graphics_gpu_mem_min: 9855728\n"
                        + "    graphics_gpu_mem_avg: 19030174.914027151\n"
                        + "    texture_mem_max: 5217091\n"
                        + "    texture_mem_min: 5020343\n"
                        + "    texture_mem_avg: 5177376.0407239823\n"
                        + "    all_mem_max: 40274509\n"
                        + "    all_mem_min: 15120269\n"
                        + "    all_mem_avg: 24468104.289592762\n"
                        + "  }\n"
                        + "}\n"
                        + "android_jank_cuj {\n"
                        + "  cuj {\n"
                        + "    id: 1\n"
                        + "    name: \"NOTIFICATION_ADD\"\n"
                        + "    process {\n"
                        + "      name: \"com.android.systemui\"\n"
                        + "      uid: 10240\n"
                        + "    }\n"
                        + "    ts: 70088466677776\n"
                        + "    dur: 460793302\n"
                        + "     counter_metrics {\n"
                        + "      }\n"
                        + "      trace_metrics {\n"
                        + "        total_frames: 54\n"
                        + "        missed_frames: 0\n"
                        + "        missed_app_frames: 0\n"
                        + "        missed_sf_frames: 0\n"
                        + "        frame_dur_max: 9845520\n"
                        + "        frame_dur_avg: 6003033\n"
                        + "        frame_dur_p50: 5871663\n"
                        + "        frame_dur_p90: 7130112\n"
                        + "        frame_dur_p95: 7406218\n"
                        + "        frame_dur_p99: 9188145\n"
                        + "     }\n"
                        + "      timeline_metrics {\n"
                        + "        total_frames: 54\n"
                        + "        missed_frames: 0\n"
                        + "        missed_app_frames: 0\n"
                        + "        missed_sf_frames: 0\n"
                        + "        frame_dur_max: 9111735\n"
                        + "        frame_dur_avg: 5040562\n"
                        + "        frame_dur_p50: 4961384\n"
                        + "        frame_dur_p90: 6045320\n"
                        + "        frame_dur_p95: 6621224\n"
                        + "        frame_dur_p99: 7827968\n"
                        + "      }\n"
                        + "    }\n"
                        + " }\n"
                        + "android_cpu {\n"
                        + "  process_info {\n"
                        + "    name: \"com.google.android.apps.messaging\"\n"
                        + "    metrics {\n"
                        + "      mcycles: 139\n"
                        + "      runtime_ns: 639064902\n"
                        + "      min_freq_khz: 576000\n"
                        + "      max_freq_khz: 2016000\n"
                        + "      avg_freq_khz: 324000\n"
                        + "    }\n"
                        + "    threads {\n"
                        + "      name: \"BG Thread #1\"\n"
                        + "      core {\n"
                        + "        id: 0\n"
                        + "        metrics {\n"
                        + "          runtime_ns: 8371202\n"
                        + "        }\n"
                        + "      }\n"
                        + "      core {\n"
                        + "        id: 1\n"
                        + "        metrics {\n"
                        + "          mcycles: 0\n"
                        + "          runtime_ns: 14376405\n"
                        + "          min_freq_khz: 1785600\n"
                        + "          max_freq_khz: 1785600\n"
                        + "          avg_freq_khz: 57977\n"
                        + "        }\n"
                        + "      }\n"
                        + "      metrics {\n"
                        + "        mcycles: 0\n"
                        + "        runtime_ns: 22747607\n"
                        + "        min_freq_khz: 1785600\n"
                        + "        max_freq_khz: 1785600\n"
                        + "        avg_freq_khz: 36000\n"
                        + "      }\n"
                        + "      core_type {\n"
                        + "        type: \"little\"\n"
                        + "        metrics {\n"
                        + "          mcycles: 0\n"
                        + "          runtime_ns: 22747607\n"
                        + "          min_freq_khz: 1785600\n"
                        + "          max_freq_khz: 1785600\n"
                        + "          avg_freq_khz: 36000\n"
                        + "        }\n"
                        + "      }\n"
                        + "    }\n"
                        + " }\n"
                        + "}\n"
                        + "android_blocking_calls_cuj_metric {\n"
                        + "  cuj {\n"
                        + "    id: 1\n"
                        + "    name: \"TASKBAR_EXPAND\"\n"
                        + "    process {\n"
                        + "      name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "      uid: 10248\n"
                        + "      pid: 8834\n"
                        + "    }\n"
                        + "    ts: 5124565418314\n"
                        + "    dur: 215758545\n"
                        + "    blocking_calls {\n"
                        + "      name: \"AIDL::java::ITrustManager::isDeviceSecure::server\"\n"
                        + "      cnt: 3\n"
                        + "      total_dur_ms: 1\n"
                        + "      max_dur_ms: 1\n"
                        + "      min_dur_ms: 0\n"
                        + "    }\n"
                        + "  }\n"
                        + "  cuj {\n"
                        + "    id: 2\n"
                        + "    name: \"ACTION_REQUEST_IME_HIDDEN::HIDE_SOFT_INPUT\"\n"
                        + "    process {\n"
                        + "      name: \"com.google.android.apps.nexuslauncher\"\n"
                        + "      uid: 10248\n"
                        + "      pid: 8834\n"
                        + "    }\n"
                        + "    ts: 5125365576395\n"
                        + "    dur: 306844\n"
                        + "    blocking_calls {\n"
                        + "      name: \"AIDL::java::ITrustManager::isDeviceSecure::server\"\n"
                        + "      cnt: 3\n"
                        + "      total_dur_ms: 1\n"
                        + "      max_dur_ms: 1\n"
                        + "      min_dur_ms: 0\n"
                        + "    }\n"
                        + "  }\n"
                        + "}";

        String perfettoTextContentWithoutMetricProto =
                "{\n"
                        + "  \"perfetto.protos.camera_app_metrics\": {\n"
                        + "    \"camera_close_latencies\": {\n"
                        + "      \"close_ms\": 388.299723\n"
                        + "    }\n"
                        + "  }\n"
                        + "}";

        FileWriter fileWriter = null;
        try {
            perfettoMetricProtoFile = FileUtil.createTempFile("metric_perfetto", "");
            fileWriter = new FileWriter(perfettoMetricProtoFile);
            fileWriter.write(
                    hasProto ? perfettoTextContent : perfettoTextContentWithoutMetricProto);
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }

        if (format.equals(METRIC_FILE_FORMAT.binary)) {
            File perfettoBinaryFile = FileUtil.createTempFile("metric_perfetto_binary", ".pb");
            try (BufferedReader bufferedReader =
                    new BufferedReader(new FileReader(perfettoMetricProtoFile))) {
                TraceMetrics.Builder builder = TraceMetrics.newBuilder();
                TextFormat.merge(bufferedReader, builder);
                builder.build().writeTo(new FileOutputStream(perfettoBinaryFile));
            } catch (ParseException e) {
                CLog.e("Failed to merge the perfetto metric file." + e.getMessage());
            } catch (IOException ioe) {
                CLog.e(
                        "IOException happened when reading the perfetto metric file."
                                + ioe.getMessage());
            } finally {
                perfettoMetricProtoFile.delete();
                perfettoMetricProtoFile = perfettoBinaryFile;
            }
            return perfettoMetricProtoFile;
        }

        if (isCompressed) {
            perfettoMetricProtoFile = compressFile(perfettoMetricProtoFile);
        }
        return perfettoMetricProtoFile;
    }

    /**
     * Creates sample perfetto metric proto file used for testing.
     *
     * @param hasProto TODO
     */
    private File setupPerfettoV2MetricFile(METRIC_FILE_FORMAT format, boolean isCompressed)
            throws IOException {
        String perfettoTextContent =
                "metric {\n"
                        + "  spec {\n"
                        + "    id: \"memory_per_process-avg_rss_and_swap\"\n"
                        + "    dimensions: \"process_name\"\n"
                        + "    value: \"avg_rss_and_swap\"\n"
                        + "    query {\n"
                        + "      table {\n"
                        + "        table_name: \"memory_rss_and_swap_per_process\"\n"
                        + "        module_name: \"linux.memory.process\"\n"
                        + "      }\n"
                        + "      filters {\n"
                        + "        column_name: \"process_name\"\n"
                        + "        op: GLOB\n"
                        + "        string_rhs: \"*\"\n"
                        + "      }\n"
                        + "      group_by {\n"
                        + "        column_names: \"process_name\"\n"
                        + "        aggregates {\n"
                        + "          column_name: \"rss_and_swap\"\n"
                        + "          op: DURATION_WEIGHTED_MEAN\n"
                        + "          result_column_name: \"avg_rss_and_swap\"\n"
                        + "        }\n"
                        + "      }\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \".ShannonImsService\"\n"
                        + "    }\n"
                        + "    value: 121380864.000000\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \".adservices\"\n"
                        + "    }\n"
                        + "    value: 123408384.000000\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"/apex/com.android.adbd/bin/adbd\"\n"
                        + "    }\n"
                        + "    value: 10464441.000000\n"
                        + "  }\n"
                        + "}\n"
                        + "metric {\n"
                        + "  spec {\n"
                        + "    id: \"total_runtime_per_thread_for_systemui_process\"\n"
                        + "    dimensions: \"slice_name\"\n"
                        + "    dimensions: \"thread_name\"\n"
                        + "    dimensions: \"process_name\"\n"
                        + "    value: \"total_runtime\"\n"
                        + "    query {\n"
                        + "      id: \"group_by_simple_slices_source\"\n"
                        + "      simple_slices {\n"
                        + "        process_name_glob: \"com.android.systemui\"\n"
                        + "      }\n"
                        + "      group_by {\n"
                        + "        column_names: \"slice_name\"\n"
                        + "        column_names: \"thread_name\"\n"
                        + "        column_names: \"process_name\"\n"
                        + "        aggregates {\n"
                        + "          column_name: \"dur\"\n"
                        + "          op: SUM\n"
                        + "          result_column_name: \"total_time\"\n"
                        + "        }\n"
                        + "      }\n"
                        + "    }\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)KernelPreparation\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"Signal Catcher\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"com.android.systemui\"\n"
                        + "    }\n"
                        + "    value: 260051.000000\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)KernelPreparation\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"binder:12907_9\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"com.android.systemui\"\n"
                        + "    }\n"
                        + "    value: 158854.000000\n"
                        + "  }\n"
                        + "  row {\n"
                        + "    dimension {\n"
                        + "      string_value: \"(Paused)MarkingPause\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"Signal Catcher\"\n"
                        + "    }\n"
                        + "    dimension {\n"
                        + "      string_value: \"com.android.systemui\"\n"
                        + "    }\n"
                        + "    value: 548624.000000\n"
                        + "  }\n"
                        + "}\n";

        FileWriter fileWriter = null;
        try {
            perfettoV2MetricProtoFile = FileUtil.createTempFile("metric_v2_perfetto", "");
            fileWriter = new FileWriter(perfettoV2MetricProtoFile);
            fileWriter.write(perfettoTextContent);
        } finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }

        if (format.equals(METRIC_FILE_FORMAT.binary)) {
            File perfettoBinaryFile = FileUtil.createTempFile("metric_v2_perfetto_binary", ".pb");
            try (BufferedReader bufferedReader =
                    new BufferedReader(new FileReader(perfettoV2MetricProtoFile))) {
                TraceMetrics.Builder builder = TraceMetrics.newBuilder();
                TextFormat.merge(bufferedReader, builder);
                builder.build().writeTo(new FileOutputStream(perfettoBinaryFile));
            } catch (ParseException e) {
                CLog.e("Failed to merge the perfetto v2 metric file." + e.getMessage());
            } catch (IOException ioe) {
                CLog.e(
                        "IOException happened when reading the perfetto v2 metric file."
                                + ioe.getMessage());
            } finally {
                perfettoV2MetricProtoFile.delete();
                perfettoV2MetricProtoFile = perfettoBinaryFile;
            }
            return perfettoV2MetricProtoFile;
        }

        if (isCompressed) {
            perfettoV2MetricProtoFile = compressFile(perfettoV2MetricProtoFile);
        }
        return perfettoV2MetricProtoFile;
    }

    /** Create a zip file with perfetto metric proto file */
    private File compressFile(File decompressedFile) throws IOException {
        File compressedFile = FileUtil.createTempFile("compressed_temp", ".zip");
        try {
            ZipUtil.createZip(decompressedFile, compressedFile);
        } catch (IOException ioe) {
            CLog.e("Unable to gzip the file.");
        } finally {
            decompressedFile.delete();
        }
        return compressedFile;
    }

    @After
    public void teardown() {
        if (perfettoMetricProtoFile != null) {
            perfettoMetricProtoFile.delete();
        }
        if (perfettoV2MetricProtoFile != null) {
            perfettoV2MetricProtoFile.delete();
        }
    }

    /** Assert that metrics contain a key and a corresponding value. */
    private void assertMetricsContain(
            Map<String, Metric.Builder> metrics, String key, Object value) {
        assertTrue(
                String.format(
                        "Metric with key containing %s and value %s was expected but not found.",
                        key, value),
                metrics.entrySet()
                        .stream()
                        .anyMatch(
                                e ->
                                        e.getKey().contains(key)
                                                && String.valueOf(value)
                                                        .equals(
                                                                e.getValue()
                                                                        .build()
                                                                        .getMeasurements()
                                                                        .getSingleString())));
    }
}

