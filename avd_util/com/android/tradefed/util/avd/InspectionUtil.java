/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;

import com.google.common.base.Strings;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A utility for inspecting AVD and host VM */
public class InspectionUtil {
    public static final Integer DISK_USAGE_MAX = 95;

    // A map of expected process names and the corresponding error identifier if they are missing.
    // The name string should be a substring of a process list.
    public static final Map<String, ErrorIdentifier> EXPECTED_PROCESSES =
            Stream.of(
                            new AbstractMap.SimpleEntry<>(
                                    " netsimd",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_BLUETOOTH),
                            new AbstractMap.SimpleEntry<>(
                                    " openwrt_control_server",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_OPENWRT),
                            new AbstractMap.SimpleEntry<>(
                                    " webRTC",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_WEBRTC_CRASH),
                            new AbstractMap.SimpleEntry<>(
                                    " crosvm",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_CROSVM),
                            new AbstractMap.SimpleEntry<>(
                                    " nginx", InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_NGINX))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final Map<String, ErrorIdentifier> ERROR_SIGNATURE_TO_IDENTIFIER_MAP =
            Stream.of(
                            new AbstractMap.SimpleEntry<>(
                                    "bluetooth_failed",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_BLUETOOTH),
                            new AbstractMap.SimpleEntry<>(
                                    "fetch_cvd_failure_resolve_host",
                                    InfraErrorIdentifier
                                            .CUTTLEFISH_LAUNCH_FAILURE_CVD_RESOLVE_HOST),
                            new AbstractMap.SimpleEntry<>(
                                    "fetch_cvd_failure_connect_server",
                                    InfraErrorIdentifier
                                            .CUTTLEFISH_LAUNCH_FAILURE_CVD_SERVER_CONNECTION),
                            new AbstractMap.SimpleEntry<>(
                                    "launch_cvd_port_collision",
                                    InfraErrorIdentifier
                                            .CUTTLEFISH_LAUNCH_FAILURE_CVD_PORT_COLLISION),
                            new AbstractMap.SimpleEntry<>(
                                    "fetch_cvd_failure_general",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_CVD_FETCH),
                            new AbstractMap.SimpleEntry<>(
                                    "cf_webrtc_crash",
                                    InfraErrorIdentifier.CUTTLEFISH_LAUNCH_FAILURE_WEBRTC_CRASH),
                            new AbstractMap.SimpleEntry<>(
                                    "fetch_cvd_failure_artifact_not_found",
                                    InfraErrorIdentifier.ARTIFACT_NOT_FOUND))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * Convert error signature to ErrorIdentifier if possible
     *
     * @param errorSignatures a string of comma separated error signatures
     * @return {@link ErrorIdentifier}
     */
    public static ErrorIdentifier convertErrorSignatureToIdentifier(String errorSignatures) {
        if (errorSignatures == null) {
            return null;
        }
        for (String signature : errorSignatures.split(",")) {
            if (ERROR_SIGNATURE_TO_IDENTIFIER_MAP.containsKey(signature)) {
                return ERROR_SIGNATURE_TO_IDENTIFIER_MAP.get(signature);
            }
        }
        return null;
    }

    /**
     * Parse the df command output to return the disk usage percentage
     *
     * @param diskspaceInfo output of command `df -P \`
     * @return An Optional<Integer> containing the percentage of used disk space if found, or an
     *     empty Optional if not found or an error occurred.
     */
    public static Optional<Integer> getDiskspaceUsage(String diskspaceInfo) {
        Pattern pattern = Pattern.compile("\\s(\\d+)%\\s", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(diskspaceInfo);

        if (matcher.find()) {
            String percentageString = matcher.group(1);
            return Optional.of(Integer.parseInt(percentageString));
        }
        return Optional.empty();
    }

    /**
     * Search for a process matching with the substring
     *
     * @param allProcesses string of a list of processes generated from top or ps command
     * @param process substring of the process to search for
     * @return true if the process is found, false otherwise
     */
    public static boolean searchProcess(String allProcesses, String process) {
        if (Strings.isNullOrEmpty(allProcesses)) {
            return false;
        }
        for (String line : allProcesses.split("\\n")) {
            if (line.indexOf(process) != -1) {
                return true;
            }
        }
        return false;
    }
}
