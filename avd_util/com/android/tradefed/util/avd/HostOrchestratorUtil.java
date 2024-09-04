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

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GceRemoteCmdFormatter;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.avd.OxygenClient.LHPTunnelMode;

import com.google.common.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Utility to execute commands via Host Orchestrator on remote instances. */
public class HostOrchestratorUtil {
    public static final String URL_HOST_KERNEL_LOG = "_journal/entries?_TRANSPORT=kernel";
    public static final String URL_HO_LOG =
            "_journal/entries?_SYSTEMD_UNIT=cuttlefish-host_orchestrator.service";
    private static final long CMD_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final long WAIT_FOR_OPERATION_MS = 5 * 6 * 1000; // 30 sec
    private static final long WAIT_FOR_OPERATION_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final String CVD_HOST_LOGZ = "cvd_hostlog_zip";
    private static final String OXYGEN_TUNNEL_PARAM = "-L%s:127.0.0.1:2080";
    private static final String URL_CVD_DEVICE_LOG = "cvds/%s/:bugreport";
    private static final String URL_CVD_BUGREPORTS = "cvdbugreports/%s";
    private static final String URL_HO_BASE = "http://127.0.0.1:%s/%s";
    private static final String URL_HO_POWERWASH = "cvds/%s/%s/:powerwash";
    private static final String URL_HO_STOP = "cvds/%s/%s";
    private static final String URL_QUERY_OPERATION = "operations/%s";
    private static final String URL_QUERY_OPERATION_RESULT = "operations/%s/result";
    private static final String UNSUPPORTED_API_RESPONSE = "404 page not found";

    private File mTunnelLog;
    private FileOutputStream mTunnelLogStream;
    private boolean mUseOxygenation = false;
    private boolean mUseCvdOxygen = false;

    // User name and key file to ssh to the host VM
    private File mSshPrivateKeyPath;
    private String mInstanceUser;

    // Oxygen instance name, host name, target region, accounting user, device ID, and other oxygen
    // arguments.
    private String mInstanceName;
    private String mHost;
    private String mOxygenationDeviceId;
    private String mTargetRegion;
    private String mAccountingUser;
    private Map<String, String> mExtraOxygenArgs;

    private OxygenClient mOxygenClient;

    @Deprecated
    public HostOrchestratorUtil(
            boolean useOxygenation,
            boolean useCvdOxygen,
            File sshPrivateKeyPath,
            String instanceUser,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            File avdDriverBinary) {
        this(
                useOxygenation,
                useCvdOxygen,
                sshPrivateKeyPath,
                instanceUser,
                instanceName,
                host,
                oxygenationDeviceId,
                new OxygenClient(Arrays.asList(avdDriverBinary.getAbsolutePath())));
    }

    @Deprecated
    public HostOrchestratorUtil(
            boolean useOxygenation,
            boolean useCvdOxygen,
            File sshPrivateKeyPath,
            String instanceUser,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            OxygenClient oxygenClient) {
        mUseOxygenation = useOxygenation;
        mUseCvdOxygen = useCvdOxygen;
        mSshPrivateKeyPath = sshPrivateKeyPath;
        mInstanceUser = instanceUser;
        mInstanceName = instanceName;
        mHost = host;
        mOxygenationDeviceId = oxygenationDeviceId;
        mOxygenClient = oxygenClient;
    }

    public HostOrchestratorUtil(
            boolean useOxygenation,
            Map<String, String> extraOxygenArgs,
            File sshPrivateKeyPath,
            String instanceUser,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            String targetRegion,
            String accountingUser,
            OxygenClient oxygenClient) {
        mUseOxygenation = useOxygenation;
        mExtraOxygenArgs = extraOxygenArgs;
        mSshPrivateKeyPath = sshPrivateKeyPath;
        mInstanceUser = instanceUser;
        mInstanceName = instanceName;
        mHost = host;
        mOxygenationDeviceId = oxygenationDeviceId;
        mTargetRegion = targetRegion;
        mAccountingUser = accountingUser;
        mOxygenClient = oxygenClient;
        mUseCvdOxygen = extraOxygenArgs.containsKey("use_cvd");
    }

    /**
     * Execute a command via Host Orchestrator and log its output
     *
     * @param logName the log name to use when reporting to the {@link ITestLogger}
     * @param url the Host Orchestrator API to be executed.
     */
    public File collectLogByCommand(String logName, String url) {
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        File tempFile = null;
        try {
            tempFile = Files.createTempFile(logName, ".txt").toFile();
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                FileUtil.deleteFile(tempFile);
                return null;
            }
            CommandResult commandRes =
                    curlCommandExecution(
                            portNumber,
                            "GET",
                            url,
                            false,
                            "--compressed",
                            "-o",
                            tempFile.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
                CLog.e("Failed logging cvd logs via Host Orchestrator: %s", commandRes.getStdout());
                FileUtil.deleteFile(tempFile);
                return null;
            }
            return tempFile;
        } catch (IOException e) {
            CLog.e("Failed logging cvd logs via Host Orchestrator: %s", e);
            FileUtil.deleteFile(tempFile);
            return null;
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
        }
    }

    /** Pull CF host logs via Host Orchestrator. */
    public File pullCvdHostLogs() {
        // Basically, the rough processes to pull CF host logs are
        // 1. Portforward CURL tunnel.
        // 2. Run /cvds API and parse the json to get ${GROUP_NAME}.
        // 3. Run /cvds/${GROUP_NAME}/:bugreport to get ${OPERATION_ID}
        // 4. Periodically run /operations/${OPERATION_ID}, parse the json util get "done":true.
        // 5. Run /operations/${OPERATION_ID}/result to get the ${UUID}.
        // 6. Run /cvdbugreports/${UUID} to download the artifact.
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        File cvdLogsDir = null;
        File cvdLogsZip = null;
        try {
            cvdLogsZip = Files.createTempFile(CVD_HOST_LOGZ, ".zip").toFile();
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator CURL tunnel.");
                return null;
            }
            CommandResult curlRes = curlCommandExecution(portNumber, "GET", "cvds", true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed getting cvd status via Host Orchestrator: %s", curlRes.getStdout());
                return null;
            }
            String cvdGroup = parseListCvdOutput(curlRes.getStdout(), "group");
            curlRes =
                    cvdOperationExecution(
                            portNumber,
                            "POST",
                            String.format(URL_CVD_DEVICE_LOG, cvdGroup),
                            WAIT_FOR_OPERATION_TIMEOUT_MS);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e(
                        "Failed running cvd operation via Host Orchestrator: %s",
                        curlRes.getStdout());
                return null;
            }
            String operationId = curlRes.getStdout().strip().replaceAll("\"", "");
            curlRes =
                    curlCommandExecution(
                            portNumber,
                            "GET",
                            String.format(URL_CVD_BUGREPORTS, operationId),
                            true,
                            "--output",
                            cvdLogsZip.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e(
                        "Failed downloading cvd host logs via Host Orchestrator: %s",
                        curlRes.getStdout());
                return null;
            }
            cvdLogsDir = ZipUtil2.extractZipToTemp(cvdLogsZip, "cvd_logs");
        } catch (IOException e) {
            CLog.e("Failed pulling cvd host logs via Host Orchestrator: %s", e);
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
            cvdLogsZip.delete();
        }
        return cvdLogsDir;
    }

    /**
     * Get CF running status via Host Orchestrator.
     *
     * @param maxWaitTime The max timeout expected to getting the CF running status.
     * @return True if device boot complete, false otherwise.
     */
    public boolean deviceBootCompleted(long maxWaitTime) {
        String portNumber = Integer.toString(mOxygenClient.createServerSocket());
        Process tunnel = null;
        try {
            tunnel = createHostOrchestratorTunnel(portNumber);
            if (tunnel == null || !tunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                return false;
            }
            long maxEndTime = System.currentTimeMillis() + maxWaitTime;
            while (System.currentTimeMillis() < maxEndTime) {
                CommandResult curlRes = curlCommandExecution(portNumber, "GET", "cvds", true);
                if (CommandStatus.SUCCESS.equals(curlRes.getStatus())
                        && parseListCvdOutput(curlRes.getStdout(), "status").equals("Running")) {
                    return true;
                }
                getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
            }
        } catch (IOException e) {
            CLog.e("Failed getting gce status via Host Orchestrator: %s", e);
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
        }
        return false;
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
            curlRes = curlCommandExecution(portNumber, "GET", "cvds", true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed getting cvd status via Host Orchestrator: %s", curlRes.getStdout());
                return curlRes;
            }
            String cvdGroup = parseListCvdOutput(curlRes.getStdout(), "group");
            String cvdName = parseListCvdOutput(curlRes.getStdout(), "name");
            if (cvdGroup == null || cvdGroup.isEmpty() || cvdName == null || cvdName.isEmpty()) {
                CLog.e("Failed parsing cvd group and cvd name.");
                curlRes.setStatus(CommandStatus.FAILED);
                return curlRes;
            }
            curlRes =
                    cvdOperationExecution(
                            portNumber,
                            "POST",
                            String.format(URL_HO_POWERWASH, cvdGroup, cvdName),
                            WAIT_FOR_OPERATION_TIMEOUT_MS);
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
        // Basically, the rough processes to powerwash a GCE instance are
        // 1. Portforward CURL tunnel
        // 2. Obtain the necessary information to powerwash a GCE instance via Host Orchestrator.
        // 3. Attempt to stop a GCE instance via Host Orchestrator.
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
            curlRes = curlCommandExecution(portNumber, "GET", "cvds", true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed getting cvd status via Host Orchestrator: %s", curlRes.getStdout());
                return curlRes;
            }
            String cvdGroup = parseListCvdOutput(curlRes.getStdout(), "group");
            String cvdName = parseListCvdOutput(curlRes.getStdout(), "name");
            if (cvdGroup == null || cvdGroup.isEmpty() || cvdName == null || cvdName.isEmpty()) {
                CLog.e("Failed parsing cvd group and cvd name.");
                curlRes.setStatus(CommandStatus.FAILED);
                return curlRes;
            }
            curlRes =
                    cvdOperationExecution(
                            portNumber,
                            "DELETE",
                            String.format(URL_HO_STOP, cvdGroup, cvdName),
                            WAIT_FOR_OPERATION_TIMEOUT_MS);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed stopping gce via Host Orchestrator: %s", curlRes.getStdout());
            }
        } catch (IOException e) {
            CLog.e("Failed stopping gce via Host Orchestrator: %s", e);
        } finally {
            mOxygenClient.closeLHPConnection(tunnel);
        }
        return curlRes;
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
        if (mTunnelLog == null || !mTunnelLog.exists()) {
            try {
                mTunnelLog = FileUtil.createTempFile("host-orchestrator-connection", ".txt");
                mTunnelLogStream = new FileOutputStream(mTunnelLog, true);
            } catch (IOException e) {
                FileUtil.deleteFile(mTunnelLog);
                CLog.e(e);
            }
        }
        if (mUseOxygenation) {
            CLog.i("Portforwarding host orchestrator for oxygenation CF.");
            return mOxygenClient.createTunnelViaLHP(
                    LHPTunnelMode.CURL,
                    portNumber,
                    mInstanceName,
                    mHost,
                    mTargetRegion,
                    mAccountingUser,
                    mOxygenationDeviceId,
                    mExtraOxygenArgs,
                    mTunnelLogStream);
        } else if (mUseCvdOxygen) {
            CLog.i("Portforarding host orchestrator for oxygen CF.");
            List<String> tunnelParam = new ArrayList<>();
            tunnelParam.add(String.format(OXYGEN_TUNNEL_PARAM, portNumber));
            tunnelParam.add("-N");
            List<String> cmd =
                    GceRemoteCmdFormatter.getSshCommand(
                            mSshPrivateKeyPath,
                            tunnelParam,
                            mInstanceUser,
                            mHost,
                            "" /* no command */);
            Process res = getRunUtil().runCmdInBackground(cmd.toArray(new String[0]));
            // TODO(b/358494412): Try to find a better way to check when the tunnel is ready.
            CLog.i("Wait 5s for host orchestrator SSH tunnel to be ready.");
            getRunUtil().sleep(5 * 1000);
            return res;
        }
        CLog.i("Skip portforwarding Host Orchestrator for neither Oxygen nor Oxygenation.");
        return null;
    }

    /**
     * Execute a curl command via Host Orchestrator.
     *
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param method The HTTP Request containing GET, POST, PUT, DELETE, PATCH, etc...
     * @param api The API that Host Orchestrator supports.
     * @param commands The command to be executed.
     * @return A {@link CommandResult} containing the status and logs.
     */
    @VisibleForTesting
    CommandResult curlCommandExecution(
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
        cmd.add(String.format(URL_HO_BASE, portNumber, api));
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

    /** Return the value by parsing the output of list cvds with a given keyword. */
    @VisibleForTesting
    String parseListCvdOutput(String content, String keyword) {
        // An example output of the given content is:
        // {"cvds":
        //      [{
        //          "group":"cvd_1",
        //          "name":"ins-1",
        //          "build_source":{},
        //          "status":"Running",
        //          "displays":["720 x 1280 ( 320 )"],
        //          "webrtc_device_id":"cvd-1",
        //          "adb_serial":"0.0.0.0:6520"
        //      }]
        // }
        JSONTokener tokener = new JSONTokener(content);
        String output = "";
        try {
            JSONObject root = new JSONObject(tokener);
            JSONArray array = root.getJSONArray("cvds");
            output = parseCvdContent(array.getJSONObject(0).toString(), keyword);
        } catch (JSONException e) {
            CLog.e(e);
        }
        return output;
    }

    /** Return the value by parsing the simple JSON content with a given keyword. */
    private String parseCvdContent(String content, String keyword) {
        String output = "";
        try {
            JSONObject object = new JSONObject(content);
            output = object.get(keyword).toString();
        } catch (JSONException e) {
            CLog.e(e);
        }
        return output;
    }

    /**
     * Execute long-run operations via Host Orchestrator. A certain HO APIs would take longer time
     * to complete, in order not to execute a long-run operations and wait for the output. This
     * method calls the operation, get the operation id, periodically do quick check the operation's
     * status util it's done, and return the result.
     *
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param method The HTTP Request containing GET, POST, PUT, DELETE, PATCH, etc...
     * @param request The HTTP request to be executed.
     * @param maxWaitTime The max timeout expected to execute the HTTP request.
     * @return A CommandResult containing the status and logs after running curl command.
     */
    @VisibleForTesting
    CommandResult cvdOperationExecution(
            String portNumber, String method, String request, long maxWaitTime) {
        CommandResult commandRes = curlCommandExecution(portNumber, method, request, true);
        if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
            CLog.e("Failed running %s, error: %s", request, commandRes.getStdout());
            return commandRes;
        }

        String operationId = parseCvdContent(commandRes.getStdout(), "name");
        long maxEndTime = System.currentTimeMillis() + maxWaitTime;
        while (System.currentTimeMillis() < maxEndTime) {
            commandRes =
                    curlCommandExecution(
                            portNumber,
                            "GET",
                            String.format(URL_QUERY_OPERATION, operationId),
                            true);
            if (CommandStatus.SUCCESS.equals(commandRes.getStatus())
                    && parseCvdContent(commandRes.getStdout(), "done").equals("true")) {
                request = String.format(URL_QUERY_OPERATION_RESULT, operationId);
                return curlCommandExecution(portNumber, "GET", request, true);
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        CLog.e("Running long operation cvd request timedout!");
        // Return the last command result and change the status to TIMED_OUT.
        commandRes.setStatus(CommandStatus.TIMED_OUT);
        return commandRes;
    }

    /** Get {@link IRunUtil} to use. Exposed for unit testing. */
    // TODO(dshi): Restore VisibleForTesting after the unittest is moved to the same package
    // (tradefed-avd-util)
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /** Return the unsupported api response. Exposed for unit testing. */
    @VisibleForTesting
    String getUnsupportedHoResponse() {
        return UNSUPPORTED_API_RESPONSE;
    }

    /** Return the host orchestrator tunnel log file. */
    public File getTunnelLog() {
        return mTunnelLog;
    }
}
