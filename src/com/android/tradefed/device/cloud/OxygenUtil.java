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

import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GCSFileDownloader;
import com.android.tradefed.util.avd.LogCollector;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utility to interact with Oxygen service. */
public class OxygenUtil {

    // URL for retrieving instance metadata related to the computing zone.
    private static final String ZONE_METADATA_URL =
            "http://metadata/computeMetadata/v1/instance/zone";

    // Default region if no specific zone is provided.
    private static final String DEFAULT_REGION = "us-west1";

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
                                    Pattern.compile(".*bugreport.*txt"), LogDataType.BUGREPORT),
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile(".*tombstones-zip.*zip"),
                                    LogDataType.TOMBSTONEZ))
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

        File localDir = LogCollector.downloadLaunchFailureLogs(errorMessage, mDownloader);
        if (localDir == null) {
            return;
        }
        try {
            String oxygenVersion = LogCollector.collectOxygenVersion(localDir);
            if (!Strings.isNullOrEmpty(oxygenVersion)) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey.CF_OXYGEN_VERSION,
                        oxygenVersion);
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope("avd:collectErrorSignature")) {
                List<String> signatures = LogCollector.collectErrorSignatures(localDir);
                if (signatures.size() > 0) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.DEVICE_ERROR_SIGNATURES,
                            String.join(",", signatures));
                }
            }
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
            CLog.e("Failed to parse Oxygen log from %s", localDir);
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


    /**
     * Retrieves the target region based on the provided device options. If the target region is
     * explicitly set in the device options, it returns the specified region. If the target region
     * is not set, it retrieves the region based on the instance's zone.
     *
     * @param deviceOptions The TestDeviceOptions object containing device options.
     * @return The target region.
     */
    public static String getTargetRegion(TestDeviceOptions deviceOptions) {
        if (deviceOptions.getOxygenTargetRegion() != null) {
            return deviceOptions.getOxygenTargetRegion();
        }
        try {
            URL url = new URL(ZONE_METADATA_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Metadata-Flavor", "Google");

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            return getRegionFromZoneMeta(response.toString());
        } catch (Exception e) {
            // Error occurred while fetching zone information, fallback to default region.
            CLog.e(e);
            return DEFAULT_REGION;
        }
    }

    /**
     * Retrieves the region from a given zone string.
     *
     * @param zone The input zone string in the format "projects/12345/zones/us-west12-a".
     * @return The extracted region string, e.g., "us-west12".
     */
    public static String getRegionFromZoneMeta(String zone) {
        int lastSlashIndex = zone.lastIndexOf("/");
        String region = zone.substring(lastSlashIndex + 1);
        int lastDashIndex = region.lastIndexOf("-");
        return region.substring(0, lastDashIndex);
    }
}
