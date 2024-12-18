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

import static com.android.tradefed.util.avd.HostOrchestratorClient.ErrorResponseException;
import static com.android.tradefed.util.avd.HostOrchestratorClient.HoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.IHoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.Operation;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.sendRequest;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
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
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Utility to execute commands via Host Orchestrator on remote instances. */
public class HostOrchestratorUtil {
    public static final String URL_HOST_KERNEL_LOG = "_journal/entries?_TRANSPORT=kernel";
    public static final String URL_HO_LOG =
            "_journal/entries?_SYSTEMD_UNIT=cuttlefish-host_orchestrator.service";
    public static final String URL_OXYGEN_CONTAINER_LOG = "_journal/entries?CONTAINER_NAME=oxygen";
    private static final long CMD_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final long WAIT_FOR_OPERATION_MS = 5 * 1000; // 5 sec
    private static final long WAIT_FOR_OPERATION_TIMEOUT_MS = 5 * 6 * 1000 * 10; // 5 min
    private static final String CVD_HOST_LOGZ = "cvd_hostlog_zip";
    private static final String URL_CVD_DEVICE_LOG = "cvds/%s/:bugreport";
    private static final String URL_CVD_BUGREPORTS = "cvdbugreports/%s";
    private static final String URL_HO_POWERWASH = "cvds/%s/%s/:powerwash";
    private static final String URL_HO_STOP = "cvds/%s/%s";
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
    private IHoHttpClient mHttpClient;
    private String mHOPortNumber = "2080";
    private Process mHOTunnel;

    public HostOrchestratorUtil(
            boolean useOxygenation,
            Map<String, String> extraOxygenArgs,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            String targetRegion,
            String accountingUser,
            OxygenClient oxygenClient) {
        this(
                useOxygenation,
                extraOxygenArgs,
                instanceName,
                host,
                oxygenationDeviceId,
                targetRegion,
                accountingUser,
                oxygenClient,
                new HoHttpClient());
    }

    public HostOrchestratorUtil(
            boolean useOxygenation,
            Map<String, String> extraOxygenArgs,
            String instanceName,
            String host,
            String oxygenationDeviceId,
            String targetRegion,
            String accountingUser,
            OxygenClient oxygenClient,
            IHoHttpClient httpClient) {
        mUseOxygenation = useOxygenation;
        mExtraOxygenArgs = extraOxygenArgs;
        mInstanceName = instanceName;
        mHost = host;
        mOxygenationDeviceId = oxygenationDeviceId;
        mTargetRegion = targetRegion;
        mAccountingUser = accountingUser;
        mOxygenClient = oxygenClient;
        mHttpClient = httpClient;
        if (mUseOxygenation) {
            mHOTunnel = createHostOrchestratorTunnel();
        }
    }

    /**
     * Execute a command via Host Orchestrator and log its output
     *
     * @param logName the log name to use when reporting to the {@link ITestLogger}
     * @param url the Host Orchestrator API to be executed.
     */
    public File collectLogByCommand(String logName, String url) {
        File tempFile = null;
        try {
            tempFile = Files.createTempFile(logName, ".txt").toFile();
            if (mUseOxygenation) {
                if (mHOTunnel == null || !mHOTunnel.isAlive()) {
                    CLog.e("Failed portforwarding Host Orchestrator tunnel.");
                    FileUtil.deleteFile(tempFile);
                    return null;
                }
            }
            CommandResult commandRes =
                    curlCommandExecution(
                            mHOPortNumber,
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
        File cvdLogsDir = null;
        File cvdLogsZip = null;
        try {
            cvdLogsZip = Files.createTempFile(CVD_HOST_LOGZ, ".zip").toFile();
            if (mUseOxygenation) {
                if (mHOTunnel == null || !mHOTunnel.isAlive()) {
                    CLog.e("Failed portforwarding Host Orchestrator CURL tunnel.");
                    return null;
                }
            }
            CommandResult curlRes = curlCommandExecution(mHOPortNumber, "GET", "cvds", true);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed getting cvd status via Host Orchestrator: %s", curlRes.getStdout());
                return null;
            }
            String cvdGroup = parseListCvdOutput(curlRes.getStdout(), "group");
            curlRes =
                    cvdOperationExecution(
                            mHttpClient,
                            mHOPortNumber,
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
                            mHOPortNumber,
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
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed pulling cvd host logs via Host Orchestrator: %s", e);
        } finally {
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
        if (mUseOxygenation) {
            if (mHOTunnel == null || !mHOTunnel.isAlive()) {
                CLog.e("Failed portforwarding Host Orchestrator CURL tunnel.");
                return false;
            }
        }
        long maxEndTime = System.currentTimeMillis() + maxWaitTime;
        while (System.currentTimeMillis() < maxEndTime) {
            CommandResult curlRes = curlCommandExecution(mHOPortNumber, "GET", "cvds", true);
            if (CommandStatus.SUCCESS.equals(curlRes.getStatus())
                    && parseListCvdOutput(curlRes.getStdout(), "status").equals("Running")) {
                return true;
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
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
        CommandResult curlRes = new CommandResult(CommandStatus.EXCEPTION);
        try {
            if (mUseOxygenation) {
                if (mHOTunnel == null || !mHOTunnel.isAlive()) {
                    String msg = "Failed portforwarding Host Orchestrator tunnel.";
                    CLog.e(msg);
                    curlRes.setStderr(msg);
                    return curlRes;
                }
            }
            curlRes = curlCommandExecution(mHOPortNumber, "GET", "cvds", true);
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
                            mHttpClient,
                            mHOPortNumber,
                            "POST",
                            String.format(URL_HO_POWERWASH, cvdGroup, cvdName),
                            WAIT_FOR_OPERATION_TIMEOUT_MS);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed powerwashing cvd via Host Orchestrator: %s", curlRes.getStdout());
            }
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed powerwashing gce via Host Orchestrator: %s", e);
        }
        return curlRes;
    }

    /** Attempt to stop a Cuttlefish instance via Host Orchestrator. */
    public CommandResult stopGce() {
        // Basically, the rough processes to powerwash a GCE instance are
        // 1. Portforward CURL tunnel
        // 2. Obtain the necessary information to powerwash a GCE instance via Host Orchestrator.
        // 3. Attempt to stop a GCE instance via Host Orchestrator.
        CommandResult curlRes = new CommandResult(CommandStatus.EXCEPTION);
        try {
            if (mUseOxygenation) {
                if (mHOTunnel == null || !mHOTunnel.isAlive()) {
                    String msg = "Failed portforwarding Host Orchestrator tunnel.";
                    CLog.e(msg);
                    curlRes.setStderr(msg);
                    return curlRes;
                }
            }
            curlRes = curlCommandExecution(mHOPortNumber, "GET", "cvds", true);
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
                            mHttpClient,
                            mHOPortNumber,
                            "DELETE",
                            String.format(URL_HO_STOP, cvdGroup, cvdName),
                            WAIT_FOR_OPERATION_TIMEOUT_MS);
            if (!CommandStatus.SUCCESS.equals(curlRes.getStatus())) {
                CLog.e("Failed stopping gce via Host Orchestrator: %s", curlRes.getStdout());
            }
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed stopping gce via Host Orchestrator: %s", e);
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

    /** Attempt to delete snapshot of a Cuttlefish instance via Host Orchestrator. */
    public CommandResult deleteSnapshotGce(String snapshotId) {
        // TODO(b/339304559): Flesh out this section when the host orchestrator is supported.
        return new CommandResult(CommandStatus.EXCEPTION);
    }

    /**
     * Create Host Orchestrator Tunnel with an automatically created port number.
     *
     * @return A {@link Process} of the Host Orchestrator connection between CuttleFish and TF.
     */
    @VisibleForTesting
    Process createHostOrchestratorTunnel() {
        mHOPortNumber = Integer.toString(mOxygenClient.createServerSocket());
        if (mTunnelLog == null || !mTunnelLog.exists()) {
            try {
                mTunnelLog = FileUtil.createTempFile("host-orchestrator-connection", ".txt");
                mTunnelLogStream = new FileOutputStream(mTunnelLog, true);
            } catch (IOException e) {
                FileUtil.deleteFile(mTunnelLog);
                CLog.e(e);
            }
        }
        CLog.i("Portforwarding host orchestrator for oxygenation CF.");
        return mOxygenClient.createTunnelViaLHP(
                LHPTunnelMode.CURL,
                mHOPortNumber,
                mInstanceName,
                mHost,
                mTargetRegion,
                mAccountingUser,
                mOxygenationDeviceId,
                mExtraOxygenArgs,
                mTunnelLogStream);
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
        cmd.add(getHOBaseUrl(portNumber) + "/"  + api);
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
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricLogger.InvocationMetricKey.UNSUPPORTED_HOST_ORCHESTRATOR_API,
                    api);
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
            IHoHttpClient client,
            String portNumber,
            String method,
            String request,
            long maxWaitTime)
            throws IOException, InterruptedException, ErrorResponseException {
        CommandResult commandRes = curlCommandExecution(portNumber, method, request, true);
        if (!CommandStatus.SUCCESS.equals(commandRes.getStatus())) {
            CLog.e("Failed running %s, error: %s", request, commandRes.getStdout());
            return commandRes;
        }
        String operationId = parseCvdContent(commandRes.getStdout(), "name");
        long maxEndTime = System.currentTimeMillis() + maxWaitTime;
        while (System.currentTimeMillis() < maxEndTime) {
            HttpRequest httpRequest =
                buildGetOperationRequest(getHOBaseUrl(portNumber), operationId);
            Operation op = sendRequest(client, httpRequest, Operation.class);
            if (op.done) {
                request = String.format(URL_QUERY_OPERATION_RESULT, operationId);
                return curlCommandExecution(portNumber, "GET", request, true);
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        CLog.e("Running long operation cvd request timedout!");
        // Return the last command result and change the status to TIMED_OUT.
        commandRes.setStatus(CommandStatus.TIMED_OUT);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricLogger.InvocationMetricKey.CVD_LONG_OPERATION_TIMEOUT_API, request);
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

    /** Return the host orchestrator URL. */
    String getHOBaseUrl(String port) {
        String host = mUseOxygenation ? "127.0.0.1" : mHost;
        return String.format("http://%s:%s", host, port);
    }

    /** Close the connection to the remote oxygenation device with a given {@link Process}. */
    public void closeTunnelConnection() {
        if (mUseOxygenation) {
            mOxygenClient.closeLHPConnection(mHOTunnel);
        }
    }
}
