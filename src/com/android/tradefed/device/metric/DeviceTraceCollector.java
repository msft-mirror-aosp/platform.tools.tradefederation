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
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.PerfettoTraceRecorder;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Collector that will start perfetto trace when a test run starts and log trace file at the end.
 */
public class DeviceTraceCollector extends BaseDeviceMetricCollector {
    private static final String NAME_FORMAT = "device-trace_%s_";
    private PerfettoTraceRecorder mPerfettoTraceRecorder = new PerfettoTraceRecorder();
    // package name for an instrumentation test, null otherwise.
    private String mInstrumentationPkgName;

    @Override
    public void extraInit(IInvocationContext context, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        super.extraInit(context, listener);
        for (ITestDevice device : getRealDevices()) {
            try {
                Map<String, String> extraConfigs = new LinkedHashMap<>();
                if (mInstrumentationPkgName != null) {
                    extraConfigs.put(
                            "atrace_apps", String.format("\"%s\"", mInstrumentationPkgName));
                }
                mPerfettoTraceRecorder.startTrace(device, extraConfigs);
            } catch (IOException e) {
                CLog.d(
                        "Failed to start perfetto trace on %s with error: %s",
                        device.getSerialNumber(), e.getMessage());
            }
        }
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
            String name = String.format(NAME_FORMAT, device.getSerialNumber());
            try (FileInputStreamSource source = new FileInputStreamSource(traceFile, true)) {
                super.testLog(name, LogDataType.PERFETTO, source);
            }
        }
    }

    public void setInstrumentationPkgName(String packageName) {
        mInstrumentationPkgName = packageName;
    }
}
