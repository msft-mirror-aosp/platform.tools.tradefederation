/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.media.tests;

import com.google.common.collect.ImmutableMap;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.StubTestInvocationListener;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.InstrumentationTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This test invocation runs android.hardware.camera2.cts.PerformanceTest -
 * Camera2 API use case performance KPIs, such as camera open time, session creation time,
 * shutter lag etc. The KPI data will be parsed and reported to dashboard.
 */
public class CameraPerformanceTest implements IDeviceTest, IRemoteTest {

    private static final String LOG_TAG = CameraPerformanceTest.class.getSimpleName();
    private static final String TEST_CLASS_NAME =
            "android.hardware.camera2.cts.PerformanceTest";
    private static final String TEST_PACKAGE_NAME = "com.android.cts.hardware";
    private static final String TEST_RUNNER_NAME =
            "android.support.test.runner.AndroidJUnitRunner";

    @Option(name = "ru-key", description = "Result key to use when posting to the dashboard.")
    private String RU_KEY = "CameraFrameworkPerformance";

    private final int MAX_TEST_TIMEOUT = 10 * 60 * 1000; // 10 mins

    @Option(name="method", shortName = 'm',
            description="Used to specify a specific test method to run")
    private String mMethodName = null;

    private ITestDevice mDevice = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        CollectingListener collectingListener = new CollectingListener();
        runTest(collectingListener);
        Map<String, String> parsedMetrics = parseResult(collectingListener.mStdout);
        postMetrics(listener, parsedMetrics);
    }

    private void runTest(ITestInvocationListener listener) throws DeviceNotAvailableException {
        InstrumentationTest instr = new InstrumentationTest();
        instr.setDevice(getDevice());
        instr.setPackageName(TEST_PACKAGE_NAME);
        instr.setRunnerName(TEST_RUNNER_NAME);
        instr.setClassName(TEST_CLASS_NAME);
        if (mMethodName != null) {
            instr.setMethodName(mMethodName);
        }
        instr.setShellTimeout(MAX_TEST_TIMEOUT);
        instr.run(listener);
    }

    /**
     * Parse Camera Performance KPIs result from the stdout generated by each test run.
     * Then put them all together to post the final report
     *
     * @return a {@link HashMap} that contains pairs of kpiName and kpiValue
     */
    private Map<String, String> parseResult(Map<String, String> metrics) {
        Map<String, String> resultsAll = new HashMap<String, String>();
        Camera2KpiParser parser = new Camera2KpiParser();
        for (Map.Entry<String, String> metric : metrics.entrySet()) {
            String testMethod = metric.getKey();
            String stdout = metric.getValue();
            CLog.d("test name %s", testMethod);
            CLog.d("stdout %s", stdout);

            // Get pairs of { KPI name, KPI value } from stdout that each test outputs.
            // Assuming that a device has both the front and back cameras, parser will return
            // 2 KPIs in HashMap. For an example of testCameraLaunch,
            //   {
            //     ("Camera 0 Camera launch time", "379.20"),
            //     ("Camera 1 Camera launch time", "272.80"),
            //   }
            Map<String, String> testKpis = parser.parse(stdout, testMethod);
            for (String k : testKpis.keySet()) {
                if (resultsAll.containsKey(k)) {
                    throw new RuntimeException(String.format("KPI name (%s) conflicts with " +
                            "the existing names. ", k));
                }
            }

            // Put each result together to post the final result
            resultsAll.putAll(testKpis);
        }
        return resultsAll;
    }

    /**
     * A listener to collect the stdout from each test run.
     */
    private class CollectingListener extends StubTestInvocationListener {
        public Map<String, String> mStdout = new HashMap<String, String>();

        @Override
        public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
            for (Map.Entry<String, String> metric : testMetrics.entrySet()) {
                // capture only test name and stdout generated by test
                mStdout.put(test.getTestName(), metric.getValue());
            }
        }
    }

    /**
     * Data class of Camera Performance KPIs separated into summary and KPI items
     */
    private class Camera2KpiData {
        public class KpiItem {
            private String mTestId;     // "android.hardware.camera2.cts.PerformanceTest#testSingleCapture"
            private String mCameraId;   // "0" or "1"
            private String mKpiName;    // "Camera capture latency"
            private String mType;       // "lower_better"
            private String mUnit;       // "ms"
            private String mKpiValue;   // "736.0 688.0 679.0 667.0 686.0"
            private String mKey;        // primary key = cameraId + kpiName
            private KpiItem(String testId, String cameraId, String kpiName, String type,
                    String unit, String kpiValue) {
                mTestId = testId;
                mCameraId = cameraId;
                mKpiName = kpiName;
                mType = type;
                mUnit = unit;
                mKpiValue = kpiValue;
                // Note that the key shouldn't contain ":" for side by side report.
                mKey = String.format("Camera %s %s", cameraId, kpiName);
            }
            public String getTestId() { return mTestId; }
            public String getCameraId() { return mCameraId; }
            public String getKpiName() { return mKpiName; }
            public String getType() { return mType; }
            public String getUnit() { return mUnit; }
            public String getKpiValue() { return mKpiValue; }
            public String getKey() { return mKey; }
        }

        private KpiItem mSummary;
        private Map<String, KpiItem> mKpis = new HashMap<String, KpiItem>();

        public KpiItem createItem(String testId, String cameraId, String kpiName, String type,
                String unit, String kpiValue) {
            return new KpiItem(testId, cameraId, kpiName, type, unit, kpiValue);
        }
        public KpiItem getSummary() { return mSummary; }
        public void setSummary(KpiItem summary) { mSummary = summary; }
        public List<KpiItem> getKpisByKpiName(String kpiName) {
            List<KpiItem> kpiItems = new ArrayList<KpiItem>();
            for (KpiItem log : mKpis.values()) {
                if (log.getKpiName().equals(kpiName)) {
                    kpiItems.add(log);
                }
            }
            return kpiItems;
        }
        public void addKpi(KpiItem kpiItem) {
            mKpis.put(kpiItem.getKey(), kpiItem);
        }
    }

    /**
     * Parses the stdout generated by the underlying instrumentation test
     * and returns it to test runner for later reporting.
     *
     * Format:
     *   (summary message)| |(type)|(unit)|(value) ++++
     *   (test id)|(message)|(type)|(unit)|(value)... +++
     *   ...
     *
     * Example:
     *   Camera launch average time for Camera 1| |lower_better|ms|586.6++++
     *   android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera open time|lower_better|ms|74.0 100.0 70.0 67.0 82.0 +++
     *   android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera configure stream time|lower_better|ms|9.0 5.0 5.0 8.0 5.0
     *   ...
     *
     * See also com.android.cts.util.ReportLog for the format detail.
     *
     */
    private class Camera2KpiParser {
        private static final String LOG_SEPARATOR = "\\+\\+\\+";
        private static final String SUMMARY_SEPARATOR = "\\+\\+\\+\\+";
        private static final String LOG_ELEM_SEPARATOR = "|";
        private final Pattern SUMMARY_REGEX = Pattern.compile(
                "^(?<message>[^|]+)\\| \\|(?<type>[^|]+)\\|(?<unit>[^|]+)\\|(?<value>[0-9 .]+)");
        private final Pattern KPI_REGEX = Pattern.compile(
                "^(?<testId>[^|]+)\\|(?<message>[^|]+)\\|(?<type>[^|]+)\\|(?<unit>[^|]+)\\|(?<values>[0-9 .]+)");
        // eg. "Camera 0: Camera capture latency"
        private final Pattern KPI_KEY_REGEX = Pattern.compile(
                "^Camera\\s+(?<cameraId>\\d+):\\s+(?<kpiName>.*)");

        // HashMap that contains pairs of (testMethod), (the name of KPI to be reported)
        // TODO(hyungtaekim) : Use MultiMap instead if more than one KPI need to be reported
        private final ImmutableMap<String, String> REPORTING_KPIS =
                new ImmutableMap.Builder<String, String>()
                        .put("testCameraLaunch", "Camera launch time")
                        .put("testSingleCapture", "Camera capture result latency")
                        .build();

        /**
         * Parse Camera Performance KPIs result first, then leave the only KPIs that matter.
         *
         * @param input String to be parsed
         * @param testMethod test method name used to leave the only metric that matters
         * @return a {@link HashMap} that contains kpiName and kpiValue
         */
        public Map<String, String> parse(String input, String testMethod) {
            return filter(parseToData(input), testMethod);
        }

        private Map<String, String> filter(Camera2KpiData data, String testMethod) {
            Map<String, String> filtered = new HashMap<String, String>();
            String kpiToReport = REPORTING_KPIS.get(testMethod);
            // report the only selected items
            List<Camera2KpiData.KpiItem> items = data.getKpisByKpiName(kpiToReport);
            for (Camera2KpiData.KpiItem item : items) {
                filtered.put(item.getKey(), item.getKpiValue());
            }
            return filtered;
        }

        private Camera2KpiData parseToData(String input) {
            Camera2KpiData data = new Camera2KpiData();

            // Split summary and KPIs from stdout passes as parameter.
            String[] output = input.split(SUMMARY_SEPARATOR);
            if (output.length != 2) {
                throw new RuntimeException("Value not in correct format");
            }
            Matcher summaryMatcher = SUMMARY_REGEX.matcher(output[0].trim());

            // Parse summary.
            // Example: "Camera launch average time for Camera 1| |lower_better|ms|586.6++++"
            if (summaryMatcher.matches()) {
                data.setSummary(data.createItem(null,
                        "-1",
                        summaryMatcher.group("message"),
                        summaryMatcher.group("type"),
                        summaryMatcher.group("unit"),
                        summaryMatcher.group("value")));
            } else {
                // Currently malformed summary won't block a test as it's not used for report.
                CLog.w("Summary not in correct format");
            }

            // Parse KPIs.
            // Example: "android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera open time|lower_better|ms|74.0 100.0 70.0 67.0 82.0 +++"
            String[] kpis = output[1].split(LOG_SEPARATOR);
            for (String kpi : kpis) {
                Matcher kpiMatcher = KPI_REGEX.matcher(kpi.trim());
                if (kpiMatcher.matches()) {
                    String message = kpiMatcher.group("message");
                    Matcher m = KPI_KEY_REGEX.matcher(message.trim());
                    if (!m.matches()) {
                        throw new RuntimeException("Value not in correct format");
                    }
                    String cameraId = m.group("cameraId");
                    String kpiName = m.group("kpiName");
                    // get average of kpi values
                    String[] values = kpiMatcher.group("values").split("\\s+");
                    double sum = 0;
                    for (String value : values) {
                        sum += Double.parseDouble(value);
                    }
                    String kpiValue = String.format("%.1f", sum / values.length);
                    data.addKpi(data.createItem(kpiMatcher.group("testId"),
                            cameraId,
                            kpiName,
                            kpiMatcher.group("type"),
                            kpiMatcher.group("unit"),
                            kpiValue));
                } else {
                    throw new RuntimeException("KPI not in correct format");
                }
            }
            return data;
        }
    }

    /**
     * Report run metrics by creating an empty test run to stick them in.
     *
     * @param listener The {@link ITestInvocationListener} of test results
     * @param metrics The {@link Map} that contains metrics for the given test
     */
    private void postMetrics(ITestInvocationListener listener, Map<String, String> metrics) {
        listener.testRunStarted(RU_KEY, 1);
        TestIdentifier testId = new TestIdentifier(RU_KEY, LOG_TAG);
        listener.testStarted(testId);
        listener.testEnded(testId, Collections.<String, String> emptyMap());
        listener.testRunEnded(0, metrics);
    }
}
