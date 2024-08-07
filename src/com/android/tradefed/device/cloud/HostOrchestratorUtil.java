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
package com.android.tradefed.device.cloud;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.device.cloud.OxygenClient.LHPTunnelMode;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GceRemoteCmdFormatter;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;

import com.google.common.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Utility to execute commands via Host Orchestrator on remote instances. */
public class HostOrchestratorUtil {
    public static final String URL_HOST_KERNEL_LOG = "_journal/entries?_TRANSPORT=kernel";
    public static final String URL_HO_LOG =
            "_journal/entries?_SYSTEMD_UNIT=cuttlefish-host_orchestrator.service";
    private static final long CMD_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final String OXYGEN_TUNNEL_PARAM = "-L%s:127.0.0.1:2080";
    private static final String URL_HO_BASE = "http://%s:%s/%s";
    private static final String URL_CVD_DEVICE_LOG = "runtimeartifacts/:pull";
    private static final String URL_HO_POWERWASH = "cvds/%s/%s/:powerwash";
    private static final String CVD_HOST_LOGZ = "cvd_hostlog_zip";
    private static final String UNSUPPORTED_API_RESPONSE = "404 page not found";
    private boolean mUseOxygenation = false;
    private boolean mUseCvdOxygen = false;
    private File mSshPrivateKeyPath;
    private String mInstanceUser;
    private GceAvdInfo mGceAvd;
    private OxygenClient mOxygenClient;

    public HostOrchestratorUtil(
            boolean useOxygenation,
            boolean useCvdOxygen,
            File sshPrivateKeyPath,
            String instanceUser,
            GceAvdInfo gceAvd,
            File avdDriverBinary) {
        this(
                useOxygenation,
                useCvdOxygen,
                sshPrivateKeyPath,
                instanceUser,
                gceAvd,
                new OxygenClient(avdDriverBinary));
    }

    public HostOrchestratorUtil(
            boolean useOxygenation,
            boolean useCvdOxygen,
            File sshPrivateKeyPath,
            String instanceUser,
            GceAvdInfo gceAvd,
            OxygenClient oxygenClient) {
        mUseOxygenation = useOxygenation;
        mUseCvdOxygen = useCvdOxygen;
        mSshPrivateKeyPath = sshPrivateKeyPath;
        mInstanceUser = instanceUser;
        mGceAvd = gceAvd;
        mOxygenClient = oxygenClient;
    }

    /**
     * Execute a command via Host Orchestrator and log its output
     *
     * @param testLogger The {@link ITestLogger} where to log the files.
     * @param logName the log name to use when reporting to the {@link ITestLogger}
     * @param url the Host Orchestrator API to be executed.
     */
    public void collectLogByCommand(ITestLogger testLogger, String logName, String url) {
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        File tempFile = null;
        try {
            tempFile = Files.createTempFile(logName, ".txt").toFile();
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                return;
            }
            CommandResult commandRes =
                    curlCommandExecution(
                            mGceAvd.hostAndPort().getHost(),
                            portNumber,
                            "GET",
                            url,
                            false,
                            "--compressed",
                            "-o",
                            tempFile.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
                CLog.e("Failed logging cvd logs via Host Orchestrator: %s", commandRes.getStdout());
                return;
            }
            testLogger.testLog(
                    logName, LogDataType.CUTTLEFISH_LOG, new FileInputStreamSource(tempFile));
        } catch (IOException e) {
            CLog.e("Failed logging cvd logs via Host Orchestrator: %s", e);
        } finally {
            FileUtil.deleteFile(tempFile);
            mOxygenClient.closeLHPConnection(tunnel);
        }
    }

    /** Pull CF host logs via Host Orchestrator. */
    public File pullCvdHostLogs() {
        // Basically, the rough processes to pull CF host logs are
        // 1. Portforward the CURL tunnel
        // 2. Compose CURL command and execute it to pull CF logs.
        // TODO(easoncylee): Flesh out this section when it's ready.
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        File cvdLogsDir = null;
        File cvdLogsZip = null;
        try {
            cvdLogsZip = Files.createTempFile(CVD_HOST_LOGZ, ".zip").toFile();
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                return null;
            }
            CommandResult commandRes =
                    curlCommandExecution(
                            mGceAvd.hostAndPort().getHost(),
                            portNumber,
                            "POST",
                            URL_CVD_DEVICE_LOG,
                            true,
                            "--output",
                            cvdLogsZip.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
                CLog.e("Failed pulling cvd logs via Host Orchestrator: %s", commandRes.getStdout());
                return null;
            }
            cvdLogsDir = ZipUtil2.extractZipToTemp(cvdLogsZip, "cvd_logs");
        } catch (IOException e) {
            CLog.e("Failed pulling cvd logs via Host Orchestrator: %s", e);
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
            cvdLogsZip.delete();
        }
        return cvdLogsDir;
    }

    /**
     * Attempt to powerwash a GCE instance via Host Orchestrator.
     *
     * @return A {@link CommandResult} containing the status and logs.
     */
    public CommandResult powerwashGce() {
        // Basically, the rough processes to powerwash a GCE instance are
        // 1. Portforward CURL tunnel
        // 2. Obtain the necessary information to powerwash a GCE instance via Host Orchestrator.
        // 3. Attempt to powerwash a GCE instance via Host Orchestrator.
        // TODO(easoncylee): Flesh out this section when it's ready.
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        CommandResult curlRes = new CommandResult(CommandStatus.EXCEPTION);
        try {
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                String msg = "Failed portforwarding Host Orchestrator tunnel.";
                CLog.e(msg);
                curlRes.setStderr(msg);
                return curlRes;
            }
            curlRes =
                    curlCommandExecution(
                            mGceAvd.hostAndPort().getHost(), portNumber, "GET", "cvds", true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed getting cvd status via Host Orchestrator: %s", curlRes.getStdout());
                return curlRes;
            }
            String cvdGroup = parseCvdOutput(curlRes.getStdout(), "group");
            String cvdName = parseCvdOutput(curlRes.getStdout(), "name");
            if (cvdGroup == null || cvdGroup.isEmpty() || cvdName == null || cvdName.isEmpty()) {
                CLog.e("Failed parsing cvd group and cvd name.");
                curlRes.setStatus(CommandStatus.FAILED);
                return curlRes;
            }
            curlRes =
                    curlCommandExecution(
                            mGceAvd.hostAndPort().getHost(),
                            portNumber,
                            "POST",
                            String.format(URL_HO_POWERWASH, cvdGroup, cvdName),
                            true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed powerwashing cvd via Host Orchestrator: %s", curlRes.getStdout());
            }
        } catch (IOException e) {
            CLog.e("Failed powerwashing gce via Host Orchestrator: %s", e);
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
        }
        return curlRes;
    }

    /** Attempt to stop a Cuttlefish instance via Host Orchestrator. */
    public CommandResult stopGce() {
        // TODO(b/339304559): Flesh out this section when the host orchestrator is supported.
        return new CommandResult(CommandStatus.EXCEPTION);
    }

    /** Attempt to snapshot a Cuttlefish instance via Host Orchestrator. */
    public CommandResult snapshotGce() {
        // TODO(b/339304559): Flesh out this section when the host orchestrator is supported.
        return new CommandResult(CommandStatus.EXCEPTION);
    }

    /** Attempt to restore snapshot of a Cuttlefish instance via Host Orchestrator. */
    public CommandResult restoreSnapshotGce() {
        // TODO(b/339304559): Flesh out this section when the host orchestrator is supported.
        return new CommandResult(CommandStatus.EXCEPTION);
    }

    /**
     * Create Host Orchestrator Tunnel with a given port number.
     *
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @return A {@link Process} of the Host Orchestrator connection between CuttleFish and TF.
     */
    @VisibleForTesting
    Process createHostOrchestratorTunnel(String portNumber) throws IOException {
        // Basically, to portforwad the CURL tunnel, the rough process would be
        // if it's oxygenation device -> portforward the CURL tunnel via LHP.
        // if `use_cvd` is set -> portforward the CURL tunnel via SSH.
        // TODO(easoncylee): Flesh out this section when it's ready.
        if (mUseOxygenation) {
            CLog.d("Portforwarding Host Orchestrator service via LHP for Oxygenation CF.");
            return mOxygenClient.createTunnelViaLHP(
                    LHPTunnelMode.CURL,
                    portNumber,
                    mGceAvd.instanceName(),
                    mGceAvd.getOxygenationDeviceId());
        } else if (mUseCvdOxygen) {
            CLog.d("Portforarding Host Orchestrator service via SSH tunnel for Oxygen CF.");
            List<String> tunnelParam = new ArrayList<>();
            tunnelParam.add(String.format(OXYGEN_TUNNEL_PARAM, portNumber));
            tunnelParam.add("-N");
            List<String> cmd =
                    GceRemoteCmdFormatter.getSshCommand(
                            mSshPrivateKeyPath,
                            tunnelParam,
                            mInstanceUser,
                            mGceAvd.hostAndPort().getHost(),
                            "" /* no command */);
            return getRunUtil().runCmdInBackground(cmd);
        }
        CLog.d("Skip portforwarding Host Orchestrator service for neither Oxygen nor Oxygenation.");
        return null;
    }

    /**
     * Execute a curl command via Host Orchestrator.
     *
     * @param hostName The name of the host.
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param method The HTTP Request containing GET, POST, PUT, DELETE, PATCH, etc...
     * @param api The API that Host Orchestrator supports.
     * @param commands The command to be executed.
     * @return A {@link CommandResult} containing the status and logs.
     */
    @VisibleForTesting
    CommandResult curlCommandExecution(
            String hostName,
            String portNumber,
            String method,
            String api,
            boolean shouldDisplay,
            String... commands) {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-0");
        cmd.add("-v");
        cmd.add("-X");
        cmd.add(method);
        cmd.add(String.format(URL_HO_BASE, hostName, portNumber, api));
        for (String cmdOption : commands) {
            cmd.add(cmdOption);
        }
        CommandResult commandRes =
                getRunUtil().runTimedCmd(CMD_TIMEOUT_MS, null, null, cmd.toArray(new String[0]));
        if (shouldDisplay) {
            CLog.logAndDisplay(
                    LogLevel.INFO,
                    "Executing Host Orchestrator curl command: %s, Output: %s, Status: %s",
                    cmd,
                    commandRes.getStdout(),
                    commandRes.getStatus());
        }
        if (commandRes.getStdout().contains(UNSUPPORTED_API_RESPONSE)) {
            commandRes.setStatus(CommandStatus.FAILED);
        }
        return commandRes;
    }

    /** Return the return by parsing the cvd output with a given keyword. */
    private String parseCvdOutput(String content, String keyword) {
        JSONTokener tokener = new JSONTokener(content);
        String output = null;
        try {
            JSONObject root = new JSONObject(tokener);
            JSONArray array = root.getJSONArray("cvds");
            JSONObject object = array.getJSONObject(0);
            output = object.getString(keyword);
        } catch (JSONException e) {
            CLog.e(e);
        }
        return output;
    }

    /** Get {@link IRunUtil} to use. Exposed for unit testing. */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /** Return the unsupported api response. Exposed for unit testing. */
    @VisibleForTesting
    String getUnsupportedHoResponse() {
        return UNSUPPORTED_API_RESPONSE;
    }

}
