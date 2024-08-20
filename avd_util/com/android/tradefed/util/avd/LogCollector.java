/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tradefed.util.avd;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.gcs.GCSFileDownloaderBase;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
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

/** A utility for collecting logs from AVD and host VM */
public class LogCollector {
    // Maximum size of tailing part of a file to search for error signature.
    private static final long MAX_FILE_SIZE_FOR_ERROR = 10 * 1024 * 1024;

    private static final Map<Pattern, AbstractMap.SimpleEntry<String, String>>
            REMOTE_LOG_NAME_PATTERN_TO_ERROR_SIGNATURE_MAP =
                    Stream.of(
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "Address already in use",
                                                    "launch_cvd_port_collision")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "vcpu hw run failure: 0x7",
                                                    "crosvm_vcpu_hw_run_failure_7")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "Unable to connect to vsock server",
                                                    "unable_to_connect_to_vsock_server")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "failed to initialize fetch system images",
                                                    "fetch_cvd_failure")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*vdl_stdout.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "failed to initialize fetch system images",
                                                    "fetch_cvd_failure")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "failed to read from socket, retry",
                                                    "rootcanal_socket_error")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "VIRTUAL_DEVICE_BOOT_PENDING: Bluetooth",
                                                    "bluetooth_pending")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "another cuttlefish device already running",
                                                    "another_device_running")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*launcher.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "Setup failed for cuttlefish::ConfigServer",
                                                    "config_server_failed")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile(".*(launcher|kernel|logcat).*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "VIRTUAL_DEVICE_BOOT_FAILED: Dependencies not"
                                                            + " ready after 10 checks: Bluetooth",
                                                    "bluetooth_failed")),
                                    new AbstractMap.SimpleEntry<>(
                                            Pattern.compile("^logcat.*"),
                                            new AbstractMap.SimpleEntry<>(
                                                    "System zygote died with fatal exception",
                                                    "zygote_fatal_exception")))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * Download error logs from GCS when Oxygen failed to launch a virtual device.
     *
     * @param errorMessage error message raised when leasing device through Oxygen service.
     * @param downloader: {@link GCSFileDownloaderBase} used to download files from GCS. If set to
     *     null, the default downloader will be used, which only supports default credential (e.g.,
     *     service account used by the node). *
     * @return The {@link File} object of directory storing the logs.
     */
    public static File downloadLaunchFailureLogs(
            String errorMessage, GCSFileDownloaderBase downloader) {
        CLog.d("Downloading device launch failure logs based on error message: %s", errorMessage);
        Pattern pattern = Pattern.compile(".*/storage/browser/(.*)\\?&project=.*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(errorMessage);
        if (!matcher.find()) {
            CLog.d("Error message doesn't contain expected GCS link.");
            return null;
        }
        String remoteFilePath = "gs://" + matcher.group(1);
        File localDir;
        try {
            if (downloader == null) {
                downloader = new GCSFileDownloaderBase();
            }
            return downloader.downloadFile(remoteFilePath);
        } catch (Exception e) {
            CLog.e("Failed to download Oxygen log from %s", remoteFilePath);
            CLog.e(e);
            return null;
        }
    }

    /**
     * Collect oxygen version info from oxygeen_version.txt.
     *
     * @param logDir directory of logs pulled from remote host.
     * @return a string of Oxygen version
     */
    public static String collectOxygenVersion(File logDir) {
        CLog.d("Collect Oxygen version from logs under: %s.", logDir);
        try {
            Set<String> files = FileUtil.findFiles(logDir, "^oxygen_version\\.txt.*");
            if (files.size() == 0) {
                CLog.d("There is no oxygen_version.txt found.");
                return null;
            }
            // Trim the tailing spaces and line breakers at the end of the string.
            return FileUtil.readStringFromFile(new File(files.iterator().next()))
                    .replaceAll("(?s)\\n+$", "")
                    .trim();
        } catch (Exception e) {
            CLog.e("Failed to read oxygen_version.txt .");
            CLog.e(e);
            return null;
        }
    }

    /**
     * Collect error signatures from logs.
     *
     * @param logDir directory of logs pulled from remote host.
     * @return a list of error signatures.
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
                List<AbstractMap.SimpleEntry<String, String>> pairs = new ArrayList<>();
                for (Map.Entry<Pattern, AbstractMap.SimpleEntry<String, String>> entry :
                        REMOTE_LOG_NAME_PATTERN_TO_ERROR_SIGNATURE_MAP.entrySet()) {
                    Matcher matcher = entry.getKey().matcher(fileName);
                    if (matcher.find()) {
                        pairs.add(entry.getValue());
                    }
                }
                if (pairs.size() == 0) {
                    continue;
                }
                try (FileInputStream stream = new FileInputStream(file)) {
                    long skipSize = Files.size(file.toPath()) - MAX_FILE_SIZE_FOR_ERROR;
                    if (skipSize > 0) {
                        stream.skip(skipSize);
                    }
                    try (Scanner scanner = new Scanner(stream)) {
                        List<AbstractMap.SimpleEntry<String, String>> pairsToRemove =
                                new ArrayList<>();
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine();
                            for (AbstractMap.SimpleEntry<String, String> pair : pairs) {
                                if (line.indexOf(pair.getKey()) != -1) {
                                    pairsToRemove.add(pair);
                                    signatures.add(pair.getValue());
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
     * @return a list of launch metrics: [fetch_time, launch_time]
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
            // Keep collecting cuttlefish-common for legacy
            double cuttlefishCommon = 0;
            // cuttlefish-host-resources and cuttlefish-operator replaces cuttlefish-common
            // in recent versions of cuttlefish debian packages.
            double cuttlefishHostResources = 0;
            double cuttlefishOperator = 0;
            double launchDevice = 0;
            double mainstart = 0;
            Pattern cuttlefishCommonPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishCommon");
            Pattern cuttlefishHostResourcesPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishHostResources");
            Pattern cuttlefishOperatorPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishOperator");
            Pattern launchDevicePatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sLaunchDevice");
            Pattern mainstartPatteren =
                    Pattern.compile(".*\\|\\s*(\\d+\\.\\d+)\\s*\\|\\sCuttlefishLauncherMainstart");
            try (Scanner scanner = new Scanner(vdlStdout)) {
                boolean metricsPending = false;
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (!metricsPending) {
                        if (line.indexOf("launch_cvd exited") != -1) {
                            metricsPending = true;
                        } else {
                            continue;
                        }
                    }
                    Matcher matcher;
                    if (cuttlefishCommon == 0) {
                        matcher = cuttlefishCommonPatteren.matcher(line);
                        if (matcher.find()) {
                            cuttlefishCommon = Double.parseDouble(matcher.group(1));
                        }
                    }
                    if (cuttlefishHostResources == 0) {
                        matcher = cuttlefishHostResourcesPatteren.matcher(line);
                        if (matcher.find()) {
                            cuttlefishHostResources = Double.parseDouble(matcher.group(1));
                        }
                    }
                    if (cuttlefishOperator == 0) {
                        matcher = cuttlefishOperatorPatteren.matcher(line);
                        if (matcher.find()) {
                            cuttlefishOperator = Double.parseDouble(matcher.group(1));
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
                metrics[0] =
                        (long)
                                ((mainstart
                                                - launchDevice
                                                - cuttlefishCommon
                                                - cuttlefishHostResources
                                                - cuttlefishOperator)
                                        * 1000);
                metrics[1] = (long) (launchDevice * 1000);
            }
        } catch (Exception e) {
            CLog.e("Failed to parse device launch time from vdl_stdout.txt.");
            CLog.e(e);
        }
        return metrics;
    }
}
