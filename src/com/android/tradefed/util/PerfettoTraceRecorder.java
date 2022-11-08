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

package com.android.tradefed.util;

import com.android.loganalysis.util.config.Option;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A utility class for recording perfetto trace on a {@link ITestDevice}. */
public class PerfettoTraceRecorder {

    private static final String TRACE_NAME_FORMAT = "device-trace_%s_";

    @Option(
            name = "perfetto-executable",
            description = "Perfetto script file which will be used to record trace.")
    private File perfettoExecutable = null;

    @Option(name = "output-path", description = "Path where the files will be saved.")
    private String outputPath = System.getProperty("java.io.tmpdir");

    // A device-metadata map to store trace related metadata.
    private Map<ITestDevice, DeviceTraceMetadata> deviceMetadataMap = new LinkedHashMap<>();

    /**
     * Starts recording perfetto trace on device. Must call {@link
     * PerfettoTraceRecorder#stopTrace(ITestDevice)} afterwards to stop the trace recording.
     *
     * @param device A {@link ITestDevice} where trace will be recorded.
     */
    public void startTrace(ITestDevice device) throws IOException {
        if (deviceMetadataMap.containsKey(device)) {
            CLog.d(
                    "Already recording trace on %s in pid %s.",
                    device.getSerialNumber(), deviceMetadataMap.get(device).getPid());
            return;
        }
        // Stores metadata related to this trace
        DeviceTraceMetadata deviceTraceMetadata = new DeviceTraceMetadata();

        // Get the perfetto executable
        if (perfettoExecutable == null) {
            perfettoExecutable = FileUtil.createTempFile("record_android_trace", ".txt");
            perfettoExecutable.createNewFile();
            InputStream script =
                    PerfettoTraceRecorder.class.getResourceAsStream(
                            "/perfetto/record_android_trace");
            FileUtil.writeToFile(script, perfettoExecutable);
            deviceTraceMetadata.setPerfettoScript(perfettoExecutable, true);
        } else {
            deviceTraceMetadata.setPerfettoScript(perfettoExecutable, false);
        }

        // Make the script executable
        RunUtil.getDefault()
                .runTimedCmd(10000, "chmod", "u+x", perfettoExecutable.getAbsolutePath());

        // Get the trace config file from resource
        File traceConfigFile = FileUtil.createTempFile("trace_config", ".textproto");
        traceConfigFile.createNewFile();
        InputStream config =
                PerfettoTraceRecorder.class.getResourceAsStream("/perfetto/trace_config.textproto");
        FileUtil.writeToFile(config, traceConfigFile);
        deviceTraceMetadata.setTraceConfig(traceConfigFile, true);

        File traceOutput =
                FileUtil.createTempFile(
                        String.format(TRACE_NAME_FORMAT, device.getSerialNumber()),
                        ".perfetto-trace");
        deviceTraceMetadata.setTraceOutput(traceOutput, false);

        // start trace
        List<String> cmd =
                Arrays.asList(
                        perfettoExecutable.getAbsolutePath(),
                        "-c",
                        traceConfigFile.getAbsolutePath(),
                        "-s",
                        device.getSerialNumber(),
                        "-o",
                        traceOutput.getAbsolutePath(),
                        "-n");
        Process process = RunUtil.getDefault().runCmdInBackground(cmd);
        deviceTraceMetadata.setPid("" + process.pid());
        deviceMetadataMap.put(device, deviceTraceMetadata);
    }

    /**
     * Stops recording perfetto trace on the device.
     *
     * <p>Must have called {@link PerfettoTraceRecorder#startTrace(ITestDevice)} before.
     *
     * @param device device for which to stop the recording. @Return Returns the perfetto trace
     *     file.
     */
    public File stopTrace(ITestDevice device) {
        if (deviceMetadataMap.containsKey(device)) {
            // remove the metadata from the map so that a new trace can be started.
            DeviceTraceMetadata metadata = deviceMetadataMap.remove(device);
            CommandResult result =
                    RunUtil.getDefault().runTimedCmd(10000, "kill", "-2", metadata.getPid());
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.d(result.getStderr());
                return null;
            }
            // wait for the recorder to finish and pull the trace file
            RunUtil.getDefault().sleep(5000);
            metadata.cleanUp();
            return metadata.getTraceOutput();
        }
        return null;
    }

    /** Stores metadata related to a trace running on an {@link ITestDevice}. */
    private class DeviceTraceMetadata {
        // pid of the process running the script
        public String pid;
        // config file which was used to collect trace
        private File traceConfig;
        // Output file where traces will be pulled after trace is stopped.
        private File traceOutput;
        // Script used to run the trace
        private File perfettoScript;

        private List<File> tempFiles = new ArrayList<>();

        public String getPid() {
            return pid;
        }

        public void setPid(String pid) {
            this.pid = pid;
        }

        public File getTraceConfig() {
            return traceConfig;
        }

        public void setTraceConfig(File traceConfig, boolean needToDelete) {
            this.traceConfig = traceConfig;
            if (needToDelete) {
                tempFiles.add(traceConfig);
            }
        }

        public File getTraceOutput() {
            return traceOutput;
        }

        public void setTraceOutput(File traceOutput, boolean needToDelete) {
            this.traceOutput = traceOutput;
            if (needToDelete) {
                tempFiles.add(traceOutput);
            }
        }

        public File getPerfettoScript() {
            return perfettoScript;
        }

        public void setPerfettoScript(File perfettoScript, boolean needToDelete) {
            this.perfettoScript = perfettoScript;
            if (needToDelete) {
                tempFiles.add(perfettoScript);
            }
        }

        public void cleanUp() {
            for (File file : tempFiles) {
                FileUtil.deleteFile(file);
            }
        }
    }
}
