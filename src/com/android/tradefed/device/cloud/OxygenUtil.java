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
import com.android.tradefed.util.Pair;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
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
                                    Pattern.compile(".*bugreport.*txt"), LogDataType.BUGREPORT),
                            new AbstractMap.SimpleEntry<>(
                                    Pattern.compile(".*tombstones-zip.*zip"),
                                    LogDataType.TOMBSTONEZ))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final Map<Pattern, Pair<Pattern, String>>
            REMOTE_LOG_NAME_PATTERN_TO_ERROR_SIGNATURE_MAP =
                    Stream.of(
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile("^launcher\\.log.*"),
                                            Pair.create(
                                                    Pattern.compile(".*Address already in use.*"),
                                                    "launch_cvd_port_collision")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile("^launcher\\.log.*"),
                                            Pair.create(
                                                    Pattern.compile(".*vcpu hw run failure: 0x7.*"),
                                                    "crosvm_vcpu_hw_run_failure_7")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile("^launcher\\.log.*"),
                                            Pair.create(
                                                    Pattern.compile(
                                                            ".*Unable to connect to vsock"
                                                                    + " server.*"),
                                                    "unable_to_connect_to_vsock_server")))
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

        CLog.d("Downloading device launch failure logs based on error message: %s", errorMessage);
        Pattern pattern = Pattern.compile(".*/storage/browser/(.*)\\?&project=.*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(errorMessage);
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

    /**
     * Collect error signatures from logs.
     *
     * @param logDir directory of logs pulled from remote host.
     */
    public static List<String> collectErrorSignatures(File logDir) {
        CLog.d("Collect error signature from logs under: %s.", logDir);
        List<String> signatures = new ArrayList<>();
        try {
            Set<String> files = FileUtil.findFiles(logDir, ".*");
            for (String f : files) {
                File file = new File(f);
                if (file.isDirectory()) {
                    continue;
                }
                String fileName = file.getName();
                List<Pair<Pattern, String>> pairs = new ArrayList<>();
                for (Map.Entry<Pattern, Pair<Pattern, String>> entry :
                        REMOTE_LOG_NAME_PATTERN_TO_ERROR_SIGNATURE_MAP.entrySet()) {
                    Matcher matcher = entry.getKey().matcher(fileName);
                    if (matcher.find()) {
                        pairs.add(entry.getValue());
                    }
                }
                if (pairs.size() == 0) {
                    continue;
                }
                try (Scanner scanner = new Scanner(file)) {
                    List<Pair<Pattern, String>> pairsToRemove = new ArrayList<>();
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        for (Pair<Pattern, String> pair : pairs) {
                            if (pair.first.matcher(line).find()) {
                                pairsToRemove.add(pair);
                                signatures.add(pair.second);
                            }
                        }
                        if (pairsToRemove.size() > 0) {
                            pairs.removeAll(pairsToRemove);
                            if (pairs.size() == 0) {
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            CLog.e("Failed to collect error signature.");
            CLog.e(e);
        }
        Collections.sort(signatures);
        return signatures;
    }

    /**
     * Collect device launcher metrics from vdl_stdout.
     *
     * @param logDir directory of logs pulled from remote host.
     */
    public static long[] collectDeviceLaunchMetrics(File logDir) {
        CLog.d("Collect device launcher metrics from logs under: %s.", logDir);
        long[] metrics = {-1, -1};
        try {
            Set<String> files = FileUtil.findFiles(logDir, "^vdl_stdout\\.txt.*");
            if (files.size() == 0) {
                CLog.d("There is no vdl_stdout.txt found.");
                return metrics;
            }
            File vdlStdout = new File(files.iterator().next());
            double cuttlefishCommon = 0;
            double launchDevice = 0;
            double mainstart = 0;
            Pattern cuttlefishCommonPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishCommon");
            Pattern launchDevicePatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sLaunchDevice");
            Pattern mainstartPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishLauncherMainstart");
            try (Scanner scanner = new Scanner(vdlStdout)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    Matcher matcher;
                    if (cuttlefishCommon == 0) {
                        matcher = cuttlefishCommonPatteren.matcher(line);
                        if (matcher.find()) {
                            cuttlefishCommon = Double.parseDouble(matcher.group(1));
                        }
                    }
                    if (launchDevice == 0) {
                        matcher = launchDevicePatteren.matcher(line);
                        if (matcher.find()) {
                            launchDevice = Double.parseDouble(matcher.group(1));
                        }
                    }
                    if (mainstart == 0) {
                        matcher = mainstartPatteren.matcher(line);
                        if (matcher.find()) {
                            mainstart = Double.parseDouble(matcher.group(1));
                        }
                    }
                }
            }
            if (mainstart > 0) {
                metrics[0] = (long) ((mainstart - launchDevice - cuttlefishCommon) * 1000);
                metrics[1] = (long) (launchDevice * 1000);
            }
        } catch (Exception e) {
            CLog.e("Failed to parse device launch time from vdl_stdout.txt.");
            CLog.e(e);
        }
        return metrics;
    }
}
