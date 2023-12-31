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
 * limitations under the License
 */

package com.android.tradefed.device.metric;

import com.android.loganalysis.item.GenericTimingItem;
import com.android.loganalysis.parser.TimingsLogParser;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LogcatReceiver;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Measurements;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;

import com.google.common.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A metric collector that collects timing information (e.g. user switch time) from logcat during
 * one or multiple repeated tests by using given regex patterns to parse start and end signals of an
 * event from logcat lines.
 */
@OptionClass(alias = "logcat-timing-metric-collector")
public class LogcatTimingMetricCollector extends BaseDeviceMetricCollector {

    private static final String LOGCAT_NAME_FORMAT = "device_%s_test_logcat";

    @Option(
            name = "start-pattern",
            description =
                    "Key-value pairs to specify the timing metric start patterns to capture from"
                            + " logcat. Key: metric name, value: regex pattern of logcat line"
                            + " indicating the start of the timing metric")
    private final Map<String, String> mStartPatterns = new HashMap<>();

    @Option(
            name = "end-pattern",
            description =
                    "Key-value pairs to specify the timing metric end patterns to capture from"
                            + " logcat. Key: metric name, value: regex pattern of logcat line"
                            + " indicating the end of the timing metric")
    private final Map<String, String> mEndPatterns = new HashMap<>();

    @Option(
            name = "logcat-buffer",
            description =
                    "Logcat buffers where the timing metrics are captured. Default buffers will be"
                            + " used if not specified.")
    private final List<String> mLogcatBuffers = new ArrayList<>();

    @Option(
            name = "per-run",
            description =
                    "Collect timing metrics at test run level if true, otherwise collect at "
                            + "test level.")
    private boolean mPerRun = true;

    private final Map<ITestDevice, LogcatReceiver> mLogcatReceivers = new HashMap<>();
    private final TimingsLogParser mParser = new TimingsLogParser();
    private String mLogcatCmd = "logcat *:D -T 150";

    @Override
    public void onTestRunStart(DeviceMetricData testData) throws DeviceNotAvailableException {
        // Adding patterns
        mParser.clearDurationPatterns();
        for (Map.Entry<String, String> entry : mStartPatterns.entrySet()) {
            String name = entry.getKey();
            if (!mEndPatterns.containsKey(name)) {
                CLog.w("Metric %s is missing end pattern, skipping.", name);
                continue;
            }
            Pattern start = Pattern.compile(entry.getValue());
            Pattern end = Pattern.compile(mEndPatterns.get(name));
            CLog.d("Adding metric: %s", name);
            mParser.addDurationPatternPair(name, start, end);
        }
        if (!mLogcatBuffers.isEmpty()) {
            mLogcatCmd += " -b " + String.join(",", mLogcatBuffers);
        }
        if (mPerRun) {
            startCollection();
        }
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData testData, final Map<String, Metric> currentTestCaseMetrics) {
        if (mPerRun) {
            collectMetrics(testData);
            stopCollection();
        }
    }

    @Override
    public void onTestStart(DeviceMetricData testData) throws DeviceNotAvailableException {
        if (!mPerRun) {
            startCollection();
        }
    }

    @Override
    public void onTestEnd(DeviceMetricData testData, Map<String, Metric> currentTestCaseMetrics) {
        if (!mPerRun) {
            collectMetrics(testData);
            stopCollection();
        }
    }

    @Override
    public void onTestFail(DeviceMetricData testData, TestDescription test) {
        for (ITestDevice device : getDevices()) {
            try (InputStreamSource logcatData = mLogcatReceivers.get(device).getLogcatData()) {
                testLog(
                        String.format(LOGCAT_NAME_FORMAT, device.getSerialNumber()),
                        LogDataType.TEXT,
                        logcatData);
            }
        }
        stopCollection();
    }

    private void startCollection() throws DeviceNotAvailableException {
        for (ITestDevice device : getDevices()) {
            CLog.d(
                    "Creating logcat receiver on device %s with command %s",
                    device.getSerialNumber(), mLogcatCmd);
            mLogcatReceivers.put(device, createLogcatReceiver(device, mLogcatCmd));
            device.executeShellCommand("logcat -c");
            mLogcatReceivers.get(device).start();
        }
    }

    private void collectMetrics(DeviceMetricData testData) {
        boolean isMultiDevice = getDevices().size() > 1;
        for (ITestDevice device : getDevices()) {
            try (InputStreamSource logcatData = mLogcatReceivers.get(device).getLogcatData()) {
                Map<String, List<Double>> metrics = parse(logcatData);
                for (Map.Entry<String, List<Double>> entry : metrics.entrySet()) {
                    String name = entry.getKey();
                    List<Double> values = entry.getValue();
                    if (isMultiDevice) {
                        testData.addMetricForDevice(device, name, createMetric(values));
                    } else {
                        testData.addMetric(name, createMetric(values));
                    }
                    CLog.d(
                            "Metric: %s with value: %s, added to device %s",
                            name, values, device.getSerialNumber());
                }
                testLog(
                        String.format(LOGCAT_NAME_FORMAT, device.getSerialNumber()),
                        LogDataType.TEXT,
                        logcatData);
            }
        }
    }

    private void stopCollection() {
        for (ITestDevice device : getDevices()) {
            mLogcatReceivers.get(device).stop();
            mLogcatReceivers.get(device).clear();
        }
    }

    @VisibleForTesting
    Map<String, List<Double>> parse(InputStreamSource logcatData) {
        Map<String, List<Double>> metrics = new HashMap<>();
        try (InputStream inputStream = logcatData.createInputStream();
                InputStreamReader logcatReader = new InputStreamReader(inputStream);
                BufferedReader br = new BufferedReader(logcatReader)) {
            List<GenericTimingItem> items = mParser.parseGenericTimingItems(br);
            for (GenericTimingItem item : items) {
                String metricKey = item.getName();
                if (!metrics.containsKey(metricKey)) {
                    metrics.put(metricKey, new ArrayList<>());
                }
                metrics.get(metricKey).add(item.getDuration());
            }
        } catch (IOException e) {
            CLog.e("Failed to parse timing metrics from logcat %s", e);
        }
        return metrics;
    }

    @VisibleForTesting
    LogcatReceiver createLogcatReceiver(ITestDevice device, String logcatCmd) {
        return new LogcatReceiver(device, logcatCmd, device.getOptions().getMaxLogcatDataSize(), 0);
    }

    private Metric.Builder createMetric(List<Double> values) {
        // TODO: Fix post processors to handle double values. For now use concatenated string as we
        // prefer to use AggregatedPostProcessor
        String stringValue =
                values.stream()
                        .map(value -> Double.toString(value))
                        .collect(Collectors.joining(","));
        return Metric.newBuilder()
                .setType(DataType.RAW)
                .setMeasurements(Measurements.newBuilder().setSingleString(stringValue));
    }
}
