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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;

import java.io.File;

/**
 * Logger of the file reported by the device-side. This logger is allowed to live inside a module
 * (AndroidTest.xml). TODO: When device-side reporting gets better, fix the LogDataType to be more
 * accurate.
 */
@OptionClass(alias = "file-puller-log-collector")
public class FilePullerLogCollector extends FilePullerDeviceMetricCollector {
    @Option(
            name = "log-data-type",
            description = "Type to assign to pulled logs (default: autodetect from extension)")
    private String mLogDataType;

    @Override
    public final void processMetricFile(String key, File metricFile, DeviceMetricData runData) {
        try {
            postProcessMetricFile(key, metricFile, runData);
        } finally {
            try (InputStreamSource source = new FileInputStreamSource(metricFile, true)) {
                LogDataType type = guessLogDataType(metricFile);

                testLog(FileUtil.getBaseName(metricFile.getName()), type, source);
            }
        }
    }

    private LogDataType guessLogDataType(File metricFile) {
        if (mLogDataType != null && mLogDataType.length() > 0) {
            try {
                return LogDataType.valueOf(mLogDataType);
            } catch (IllegalArgumentException e) {
                CLog.e("Invalid log-data-type option: " + mLogDataType);
            }
        }

        // Try to infer the type. This will be improved eventually, see todo on the class.
        String ext = FileUtil.getExtension(metricFile.getName()).toLowerCase();
        if (".hprof".equals(ext)) {
            return LogDataType.HPROF;
        } else if (".mp4".equals(ext)) {
            return LogDataType.MP4;
        } else if (".pb".equals(ext)) {
            return LogDataType.PB;
        } else if (".png".equals(ext)) {
            return LogDataType.PNG;
        } else if (".perfetto-trace".equals(ext)) {
            return LogDataType.PERFETTO;
        } else if (".zip".equals(ext)) {
            return LogDataType.ZIP;
        } else if (".uix".equals(ext)) {
            return LogDataType.UIX;
        } else if (".textproto".equals(ext)
                && FileUtil.getBaseName(metricFile.getName()).contains("_goldResult")) {
            return LogDataType.GOLDEN_RESULT_PROTO;
        } else if (".trace".equals(ext)) {
            return LogDataType.TRACE;
        } else if (".log".equals(ext)) {
            return LogDataType.BT_SNOOP_LOG;
        } else if (".json".equals(ext)) {
            return LogDataType.JSON;
        }
        return LogDataType.TEXT;
    }

    @Override
    public void processMetricDirectory(
            String key, File metricDirectory, DeviceMetricData runData) {
        for (File file : metricDirectory.listFiles()) {
            if (file.isDirectory()) {
                processMetricDirectory(key, file, runData);
            } else {
                processMetricFile(key, file, runData);
            }
        }
        FileUtil.recursiveDelete(metricDirectory);
    }

    /**
     * Possible processing of a pulled file to extract some metrics.
     *
     * @param key Key of the file pulled
     * @param metricFile The {@link File} that was pulled.
     * @param runData The metric storage were to put extracted metrics.
     */
    protected void postProcessMetricFile(String key, File metricFile, DeviceMetricData runData) {}
}
