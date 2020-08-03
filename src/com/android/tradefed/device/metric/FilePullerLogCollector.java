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

import com.android.tradefed.config.OptionClass;
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

    @Override
    public final void processMetricFile(String key, File metricFile, DeviceMetricData runData) {
        try {
            postProcessMetricFile(key, metricFile, runData);
        } finally {
            try (InputStreamSource source = new FileInputStreamSource(metricFile, true)) {
                // Try to infer the type. This will be improved eventually, see todo on the class.
                LogDataType type = LogDataType.TEXT;
                String ext = FileUtil.getExtension(metricFile.getName()).toLowerCase();
                if (".png".equals(ext)) {
                    type = LogDataType.PNG;
                } else if (".pb".equals(ext)) {
                    type = LogDataType.PB;
                } else if (".mp4".equals(ext)) {
                    type = LogDataType.MP4;
                }
                testLog(metricFile.getName(), type, source);
            }
        }
    }

    @Override
    public final void processMetricDirectory(
            String key, File metricDirectory, DeviceMetricData runData) {
        for (File f : metricDirectory.listFiles()) {
            if (f.isDirectory()) {
                processMetricDirectory(key, f, runData);
            } else {
                processMetricFile(key, f, runData);
            }
        }
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
