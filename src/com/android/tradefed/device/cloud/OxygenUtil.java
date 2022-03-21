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
package com.android.tradefed.device.cloud;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GCSFileDownloader;

import java.io.File;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility to interact with Oxygen service. */
public class OxygenUtil {

    private GCSFileDownloader mDownloader;

    /** Default constructor of OxygenUtil */
    public OxygenUtil() {
        mDownloader = new GCSFileDownloader(true);
    }

    /**
     * Constructor of OxygenUtil
     *
     * @param downloader {@link GCSFileDownloader} to download file from GCS.
     */
    @VisibleForTesting
    OxygenUtil(GCSFileDownloader downloader) {
        mDownloader = downloader;
    }

    /**
     * Download error logs from GCS when Oxygen failed to launch a virtual device.
     *
     * @param error The error message returned from Oxygen service
     * @param logger The {@link ITestLogger} where to log the file
     */
    public void downloadLaunchFailureLogs(String error, ITestLogger logger) {
        CLog.d("Downloading device launch failure logs based on error message: %s", error);
        Pattern pattern = Pattern.compile(".*/storage/browser/(.*)\\?&project=.*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(error);
        if (!matcher.find()) {
            CLog.d("Error message doesn't contain expected GCS link.");
            return;
        }
        String remoteFilePath = "gs://" + matcher.group(1);
        File localDir;
        try {
            localDir = mDownloader.downloadFile(remoteFilePath);
            Set<String> files = FileUtil.findFiles(localDir, ".*");
            for (String f : files) {
                File file = new File(f);
                if (file.isDirectory()) {
                    continue;
                }
                CLog.d("Logging %s", f);
                try (FileInputStreamSource data = new FileInputStreamSource(file)) {
                    String logFileName =
                            "oxygen_"
                                    + localDir.toPath()
                                            .relativize(file.toPath())
                                            .toString()
                                            .replace(File.separatorChar, '_');
                    logger.testLog(logFileName, LogDataType.TEXT, data);
                }
            }
        } catch (Exception e) {
            CLog.e("Failed to download Oxygen log from %s", remoteFilePath);
            CLog.e(e);
        }
    }
}
