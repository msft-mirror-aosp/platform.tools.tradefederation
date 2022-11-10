/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tradefed.device.metric;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.PerfettoTraceRecorder;

import java.io.File;
import java.io.IOException;
import java.util.Map;
/**
 * Collector that will start perfetto trace when a test run starts and log trace file at the end.
 */
public class DeviceTraceCollector extends BaseDeviceMetricCollector {
    private PerfettoTraceRecorder mPerfettoTraceRecorder = new PerfettoTraceRecorder();

    @Override
    public void onTestRunStart(DeviceMetricData runData) throws DeviceNotAvailableException {
        for (ITestDevice device : getRealDevices()) {
            try {
                mPerfettoTraceRecorder.startTrace(device);
            } catch (IOException e) {
                CLog.d(
                        "Failed to start perfetto trace on %s with error: %s",
                        device.getSerialNumber(), e.getMessage());
            }
        }
    }

    @Override
    public void onTestRunFailed(DeviceMetricData testData, FailureDescription failure) {
        logTraceFile();
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData, Map<String, MetricMeasurement.Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        logTraceFile();
    }

    private void logTraceFile() {
        for (ITestDevice device : getRealDevices()) {
            File traceFile = mPerfettoTraceRecorder.stopTrace(device);
            if (traceFile == null) {
                CLog.d("Failed to collect device trace from %s.", device.getSerialNumber());
                continue;
            }
            try (FileInputStreamSource source = new FileInputStreamSource(traceFile, true)) {
                super.testLog(traceFile.getName(), LogDataType.PERFETTO, source);
            }
        }
    }
}