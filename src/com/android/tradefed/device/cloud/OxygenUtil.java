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
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GCSFileDownloader;

import java.io.File;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utility to interact with Oxygen service. */
public class OxygenUtil {

    private GCSFileDownloader mDownloader;

    // TODO: Support more type of log data types
    private static final Map<Pattern, LogDataType> REMOTE_LOG_NAME_PATTERN_TO_TYPE_MAP =
            Stream.of(
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile("^logcat.*"), LogDataType.LOGCAT),
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile(".*kernel.*"), LogDataType.KERNEL_LOG),
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile(".*bugreport.*zip"), LogDataType.BUGREPORTZ),
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile(".*bugreport.*txt"), LogDataType.BUGREPORT))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

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
     * @param error TargetSetupError raised when leasing device through Oxygen service.
     * @param logger The {@link ITestLogger} where to log the file
     */
    public void downloadLaunchFailureLogs(TargetSetupError error, ITestLogger logger) {
        String errorMessage = error.getMessage();
        if (error.getCause() != null) {
            // Also include the message from the internal cause.
            errorMessage = String.format("%s %s", errorMessage, error.getCause().getMessage());
        }
        downloadLaunchFailureLogs(errorMessage, logger);
    }

    /**
     * Download error logs from GCS when Oxygen failed to launch a virtual device.
     *
     * <p>TODO(dshi): Remove this method after GceBootTest is updated.
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
                    LogDataType logDataType = getDefaultLogType(logFileName);
                    if (logDataType == LogDataType.UNKNOWN) {
                        // Default log type to be CUTTLEFISH_LOG to avoid compression.
                        logDataType = LogDataType.CUTTLEFISH_LOG;
                    }
                    logger.testLog(logFileName, logDataType, data);
                }
            }
        } catch (Exception e) {
            CLog.e("Failed to download Oxygen log from %s", remoteFilePath);
            CLog.e(e);
        }
    }

    /**
     * Determine a log file's log data type based on its name.
     *
     * @param logFileName The remote log file's name.
     * @return A {@link LogDataType} which the log file associates with. Will return the type
     *     UNKNOWN if unable to determine the log data type based on its name.
     */
    public static LogDataType getDefaultLogType(String logFileName) {
        for (Map.Entry<Pattern, LogDataType> entry :
                REMOTE_LOG_NAME_PATTERN_TO_TYPE_MAP.entrySet()) {
            Matcher matcher = entry.getKey().matcher(logFileName);
            if (matcher.find()) {
                return entry.getValue();
            }
        }
        CLog.d(
                String.format(
                        "Unable to determine log type of the remote log file %s, log type is"
                                + " UNKNOWN",
                        logFileName));
        return LogDataType.UNKNOWN;
    }
}
