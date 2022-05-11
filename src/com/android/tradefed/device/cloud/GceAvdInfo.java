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
package com.android.tradefed.device.cloud;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.net.HostAndPort;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structure to hold relevant data for a given GCE AVD instance. */
public class GceAvdInfo {

    public static final List<String> BUILD_VARS =
            Arrays.asList(
                    "build_id",
                    "build_target",
                    "branch",
                    "kernel_build_id",
                    "kernel_build_target",
                    "kernel_branch",
                    "system_build_id",
                    "system_build_target",
                    "system_branch",
                    "emulator_build_id",
                    "emulator_build_target",
                    "emulator_branch");

    private String mInstanceName;
    private HostAndPort mHostAndPort;
    private ErrorIdentifier mErrorType;
    private String mErrors;
    private GceStatus mStatus;
    private HashMap<String, String> mBuildVars;
    private Map<String, LogDataType> mLogs;
    private boolean mIsIpPreconfigured = false;

    public static enum GceStatus {
        SUCCESS,
        FAIL,
        BOOT_FAIL,
        DEVICE_OFFLINE,
    }

    public GceAvdInfo(String instanceName, HostAndPort hostAndPort) {
        mInstanceName = instanceName;
        mHostAndPort = hostAndPort;
        mBuildVars = new HashMap<String, String>();
        mLogs = new HashMap<String, LogDataType>();
    }

    public GceAvdInfo(
            String instanceName,
            HostAndPort hostAndPort,
            ErrorIdentifier errorType,
            String errors,
            GceStatus status) {
        this(instanceName, hostAndPort);
        mErrorType = errorType;
        mErrors = errors;
        mStatus = status;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "GceAvdInfo [mInstanceName="
                + mInstanceName
                + ", mHostAndPort="
                + mHostAndPort
                + ", mErrorType="
                + mErrorType
                + ", mErrors="
                + mErrors
                + ", mStatus="
                + mStatus
                + ", mIsIpPreconfigured="
                + mIsIpPreconfigured
                + ", mBuildVars="
                + mBuildVars.toString()
                + ", mLogs="
                + mLogs.toString()
                + "]";
    }

    public String instanceName() {
        return mInstanceName;
    }

    public HostAndPort hostAndPort() {
        return mHostAndPort;
    }

    public ErrorIdentifier getErrorType() {
        return mErrorType;
    }

    public String getErrors() {
        return mErrors;
    }

    /** Return the map from local or remote log paths to types. */
    public Map<String, LogDataType> getLogs() {
        return mLogs;
    }

    public GceStatus getStatus() {
        return mStatus;
    }

    public void setStatus(GceStatus status) {
        mStatus = status;
    }

    private void addBuildVar(String buildKey, String buildValue) {
        mBuildVars.put(buildKey, buildValue);
    }

    public void setIpPreconfigured(boolean isIpPreconfigured) {
        mIsIpPreconfigured = isIpPreconfigured;
    }

    public boolean isIpPreconfigured() {
        return mIsIpPreconfigured;
    }

    /**
     * Return build variable information hash of GCE AVD device.
     *
     * <p>Possible build variables keys are described in BUILD_VARS for example: build_id,
     * build_target, branch, kernel_build_id, kernel_build_target, kernel_branch, system_build_id,
     * system_build_target, system_branch, emulator_build_id, emulator_build_target,
     * emulator_branch.
     */
    public HashMap<String, String> getBuildVars() {
        return new HashMap<String, String>(mBuildVars);
    }

    /**
     * Parse a given file to obtain the GCE AVD device info.
     *
     * @param f {@link File} file to read the JSON output from GCE Driver.
     * @param descriptor the descriptor of the device that needs the info.
     * @param remoteAdbPort the remote port that should be used for adb connection
     * @return the {@link GceAvdInfo} of the device if found, or null if error.
     */
    public static GceAvdInfo parseGceInfoFromFile(
            File f, DeviceDescriptor descriptor, int remoteAdbPort) throws TargetSetupError {
        String data;
        try {
            data = FileUtil.readStringFromFile(f);
        } catch (IOException e) {
            CLog.e("Failed to read result file from GCE driver:");
            CLog.e(e);
            return null;
        }
        return parseGceInfoFromString(data, descriptor, remoteAdbPort);
    }

    /**
     * Parse a given string to obtain the GCE AVD device info.
     *
     * @param data JSON string.
     * @param descriptor the descriptor of the device that needs the info.
     * @param remoteAdbPort the remote port that should be used for adb connection
     * @return the {@link GceAvdInfo} of the device if found, or null if error.
     */
    public static GceAvdInfo parseGceInfoFromString(
            String data, DeviceDescriptor descriptor, int remoteAdbPort) throws TargetSetupError {
        if (Strings.isNullOrEmpty(data)) {
            CLog.w("No data provided");
            return null;
        }
        InfraErrorIdentifier errorId = null;
        String errors = data;
        try {
            errors = parseErrorField(data);
            JSONObject res = new JSONObject(data);
            String status = res.getString("status");
            GceStatus gceStatus = GceStatus.valueOf(status);
            String errorType = res.has("error_type") ? res.getString("error_type") : null;
            errorId =
                    GceStatus.SUCCESS.equals(gceStatus)
                            ? null
                            : determineAcloudErrorType(errorType);
            if (errorId == InfraErrorIdentifier.ACLOUD_OXYGEN_LEASE_ERROR) {
                errorId = refineOxygenErrorType(errors);
            }
            JSONArray devices = null;
            if (GceStatus.FAIL.equals(gceStatus) || GceStatus.BOOT_FAIL.equals(gceStatus)) {
                // In case of failure we still look for instance name to shutdown if needed.
                if (res.getJSONObject("data").has("devices_failing_boot")) {
                    devices = res.getJSONObject("data").getJSONArray("devices_failing_boot");
                }
            } else {
                devices = res.getJSONObject("data").getJSONArray("devices");
            }
            if (devices != null) {
                if (devices.length() == 1) {
                    JSONObject d = (JSONObject) devices.get(0);
                    addCfStartTimeMetrics(d);
                    String ip = d.getString("ip");
                    String instanceName = d.getString("instance_name");
                    GceAvdInfo avdInfo =
                            new GceAvdInfo(
                                    instanceName,
                                    HostAndPort.fromString(ip).withDefaultPort(remoteAdbPort),
                                    errorId,
                                    errors,
                                    gceStatus);
                    avdInfo.mLogs.putAll(parseLogField(d));
                    for (String buildVar : BUILD_VARS) {
                        if (d.has(buildVar) && !d.getString(buildVar).trim().isEmpty()) {
                            avdInfo.addBuildVar(buildVar, d.getString(buildVar).trim());
                        }
                    }
                    return avdInfo;
                } else {
                    CLog.w("Expected only one device to return but found %d", devices.length());
                }
            } else {
                CLog.w("No device information, device was not started.");
            }
        } catch (JSONException e) {
            CLog.e("Failed to parse JSON %s:", data);
            CLog.e(e);
        }

        // If errors are found throw an exception with the acloud message.
        if (errorId == null) {
            errorId = InfraErrorIdentifier.ACLOUD_UNDETERMINED;
        }
        throw new TargetSetupError(
                String.format("acloud errors: %s", !errors.isEmpty() ? errors : data),
                descriptor,
                errorId);
    }

    /**
     * Parse a given command line output from Oxygen client binary to obtain leased AVD info.
     *
     * @param oxygenRes the {@link CommandResult} from Oxygen client command execution.
     * @param remoteAdbPort the remote port that should be used for adb connection
     * @return the {@link GceAvdInfo} of the device successfully leased. Will throw {@link
     *     TargetSetupError} if failed to lease a device.
     */
    public static GceAvdInfo parseGceInfoFromOxygenClientOutput(
            CommandResult oxygenRes, int remoteAdbPort) throws TargetSetupError {
        CommandStatus oxygenCliStatus = oxygenRes.getStatus();
        if (CommandStatus.SUCCESS.equals(oxygenCliStatus)) {
            return parseSucceedOxygenClientOutput(
                    oxygenRes.getStdout() + oxygenRes.getStderr(), remoteAdbPort);
        } else if (CommandStatus.TIMED_OUT.equals(oxygenCliStatus)) {
            return new GceAvdInfo(
                    null,
                    null,
                    InfraErrorIdentifier.OXYGEN_CLIENT_BINARY_TIMEOUT,
                    "Oxygen client binary CLI timed out",
                    GceStatus.FAIL);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "OxygenClient - CommandStatus: %s, output: %s",
                            oxygenCliStatus, oxygenRes.getStdout() + " " + oxygenRes.getStderr()),
                    InfraErrorIdentifier.OXYGEN_CLIENT_BINARY_ERROR);
        }
    }

    private static GceAvdInfo parseSucceedOxygenClientOutput(String output, int remoteAdbPort)
            throws TargetSetupError {
        CLog.d("Parsing oxygen client output: %s", output);

        Pattern pattern =
                Pattern.compile("session_id:\"(.*)\".*server_url:\"(.*)\".*ports", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(output);
        if (!matcher.find()) {
            throw new TargetSetupError(
                    String.format("Failed to parse the output: %s", output),
                    InfraErrorIdentifier.OXYGEN_CLIENT_BINARY_ERROR);
        }
        String sessionId = matcher.group(1);
        String serverUrl = matcher.group(2);

        return new GceAvdInfo(
                sessionId,
                HostAndPort.fromString(serverUrl).withDefaultPort(remoteAdbPort),
                null,
                null,
                GceStatus.SUCCESS);
    }

    /**
     * Search error message from Oxygen service for more accurate error code.
     *
     * @param errors error messages returned by Oxygen service.
     * @return InfraErrorIdentifier for the Oxygen service error.
     */
    private static InfraErrorIdentifier refineOxygenErrorType(String errors) {
        if (errors.contains("Lease aborted due to launcher failure")) {
            return InfraErrorIdentifier.OXYGEN_DEVICE_LAUNCHER_FAILURE;
        } else if (errors.contains("server_shutting_down")) {
            return InfraErrorIdentifier.OXYGEN_SERVER_SHUTTING_DOWN;
        } else if (errors.contains("UNAVAILABLE: HTTP status code 502")) {
            return InfraErrorIdentifier.OXYGEN_BAD_GATEWAY_ERROR;
        } else if (errors.contains("DeadlineExceeded")) {
            return InfraErrorIdentifier.OXYGEN_REQUEST_TIMEOUT;
        } else if (errors.contains("RESOURCE_EXHAUSTED")) {
            return InfraErrorIdentifier.OXYGEN_RESOURCE_EXHAUSTED;
        } else if (errors.contains("502:Bad Gateway")) {
            return InfraErrorIdentifier.OXYGEN_SERVER_CONNECTION_FAILURE;
        }

        return InfraErrorIdentifier.ACLOUD_OXYGEN_LEASE_ERROR;
    }

    private static String parseErrorField(String data) throws JSONException {
        String res = "";
        JSONObject response = new JSONObject(data);
        JSONArray errors = response.getJSONArray("errors");
        for (int i = 0; i < errors.length(); i++) {
            res += (errors.getString(i) + "\n");
        }
        return res;
    }

    /**
     * Parse log paths from a device object.
     *
     * @param device the device object in JSON.
     * @return a map from log paths to {@link LogDataType}.
     * @throws JSONException if any required property is missing.
     */
    private static Map<String, LogDataType> parseLogField(JSONObject device) throws JSONException {
        Map<String, LogDataType> logs = new HashMap<String, LogDataType>();
        JSONArray logArray = device.optJSONArray("logs");
        if (logArray == null) {
            return logs;
        }
        for (int i = 0; i < logArray.length(); i++) {
            JSONObject logObject = logArray.getJSONObject(i);
            String path = logObject.getString("path");
            String typeString = logObject.getString("type");
            LogDataType type;
            try {
                type = LogDataType.valueOf(typeString);
            } catch (IllegalArgumentException e) {
                CLog.w("Unknown log type in GCE AVD info: %s", typeString);
                type = LogDataType.UNKNOWN;
            }
            if (logs.put(path, type) != null) {
                CLog.w("Repeated log path in GCE AVD info: %s", path);
            }
        }
        return logs;
    }

    @VisibleForTesting
    static InfraErrorIdentifier determineAcloudErrorType(String errorType) {
        InfraErrorIdentifier identifier;
        if (errorType == null || errorType.isEmpty()) {
            return InfraErrorIdentifier.ACLOUD_UNRECOGNIZED_ERROR_TYPE;
        }
        try {
            identifier = InfraErrorIdentifier.valueOf(errorType);
        } catch (Exception e) {
            identifier = InfraErrorIdentifier.ACLOUD_UNRECOGNIZED_ERROR_TYPE;
        }
        return identifier;
    }

    @VisibleForTesting
    static void addCfStartTimeMetrics(JSONObject json) {
        // These metrics may not be available for all GCE.
        String fetch_artifact_time = json.optString("fetch_artifact_time");
        if (!fetch_artifact_time.isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CF_FETCH_ARTIFACT_TIME,
                    Double.valueOf(Double.parseDouble(fetch_artifact_time) * 1000).longValue());
        }
        String gce_create_time = json.optString("gce_create_time");
        if (!gce_create_time.isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CF_GCE_CREATE_TIME,
                    Double.valueOf(Double.parseDouble(gce_create_time) * 1000).longValue());
        }
        String launch_cvd_time = json.optString("launch_cvd_time");
        if (!launch_cvd_time.isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CF_LAUNCH_CVD_TIME,
                    Double.valueOf(Double.parseDouble(launch_cvd_time) * 1000).longValue());
        }
        if (!InvocationMetricLogger.getInvocationMetrics()
                .containsKey(InvocationMetricKey.CF_INSTANCE_COUNT.toString())) {
            InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.CF_INSTANCE_COUNT, 1);
        }
    }
}
