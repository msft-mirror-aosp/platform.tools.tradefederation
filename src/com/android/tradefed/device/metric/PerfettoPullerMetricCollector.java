/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Base implementation of {@link FilePullerDeviceMetricCollector} that allows
 * pulling the perfetto files from the device and collect the metrics from it.
 */
@OptionClass(alias = "perfetto-metric-collector")
public class PerfettoPullerMetricCollector extends FilePullerDeviceMetricCollector {

    // Timeout for the script to process the trace files.
    // This value is arbitarily choosen to be 5 mins to prevent the test spending more time
    // in processing the files.
    private static final long MAX_SCRIPT_TIMEOUT_MSECS = 300000;
    private static final String LINE_SEPARATOR = "\\r?\\n";
    private static final String KEY_VALUE_SEPARATOR = ":";

    @Option(
            name = "perfetto-binary-path",
            description = "Path to the script files used to analyze the trace files.")
    private List<String> mScriptPaths = new ArrayList<>();

    @Option(
            name = "perfetto-metric-prefix",
            description = "Prefix to be used with the metrics collected from perfetto.")
    private String mMetricPrefix = "perfetto";

    /**
     * Process the perfetto trace file for the additional metrics and add it to final metrics.
     *
     * @param key the option key associated to the file that was pulled from the device.
     * @param metricFile the {@link File} pulled from the device matching the option key.
     * @param data where metrics will be stored.
     */
    @Override
    public void processMetricFile(String key, File metricFile,
            DeviceMetricData data) {
        // Extract the metrics from the trace file.
        for (String scriptPath : mScriptPaths) {
            List<String> commandArgsList = new ArrayList<String>();
            commandArgsList.add(scriptPath);
            commandArgsList.add("-trace_file");
            commandArgsList.add(metricFile.getAbsolutePath());

            CommandResult cr = runHostCommand(commandArgsList.toArray(new String[commandArgsList
                    .size()]));
            if (CommandStatus.SUCCESS.equals(cr.getStatus())) {
                String[] metrics = cr.getStdout().split(LINE_SEPARATOR);
                for (String metric : metrics) {
                    // Expected script test outRput format.
                    // Key1:Value1
                    // Key2:Value2
                    String[] pair = metric.split(KEY_VALUE_SEPARATOR);
                    Metric.Builder metricBuilder = Metric.newBuilder();
                    metricBuilder
                            .getMeasurementsBuilder()
                            .setSingleString(pair[1]);
                    if (pair.length == 2) {
                        data.addMetric(String.format("%s_%s", mMetricPrefix, pair[0]),
                                metricBuilder.setType(DataType.RAW));
                    } else {
                        CLog.e("Output %s not in the expected format.", metric);
                    }
                }
                CLog.i(cr.getStdout());
            } else {
                CLog.e("Unable to parse the trace file %s due to %s - Status - %s ",
                        metricFile.getName(), cr.getStderr(), cr.getStatus());
            }
        }

        // Upload and delete the host trace file.
        try (InputStreamSource source = new FileInputStreamSource(metricFile, true)) {
            testLog(metricFile.getName(), LogDataType.PB, source);
        }
    }

    @Override
    public void processMetricDirectory(String key, File metricDirectory, DeviceMetricData runData) {
        // Implement if all the files under specific directory have to be post processed.
    }

    /**
     * Run a host command with the given array of command args.
     *
     * @param commandArgs args to be used to construct the host command.
     * @return return the command results.
     */
    @VisibleForTesting
    protected CommandResult runHostCommand(String[] commandArgs) {
        return RunUtil.getDefault().runTimedCmd(MAX_SCRIPT_TIMEOUT_MSECS, commandArgs);
    }
}

