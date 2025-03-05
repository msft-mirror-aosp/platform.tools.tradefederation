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

import static com.android.tradefed.util.avd.HostOrchestratorClient.Cvd;
import static com.android.tradefed.util.avd.HostOrchestratorClient.ErrorResponseException;
import static com.android.tradefed.util.avd.HostOrchestratorClient.HoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.IHoHttpClient;
import static com.android.tradefed.util.avd.HostOrchestratorClient.ListCvdsResponse;
import static com.android.tradefed.util.avd.HostOrchestratorClient.Operation;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildCreateBugreportRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationResultRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildListCvdsRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildPowerwashRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildRemoveInstanceRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.saveToFile;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
    private static final String URL_CVD_BUGREPORTS = "cvdbugreports/%s";
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
     * Download log files.
     *
     * @param logName the log name to use when reporting to the {@link ITestLogger}
     * @param urlPath url path indicating the log to download.
     */
    public File downloadLogFile(String logName, String urlPath) {
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
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest request =
                    HttpRequest.newBuilder().uri(URI.create(baseUrl + "/" + urlPath)).build();
            saveToFile(mHttpClient, request, Paths.get(tempFile.getAbsolutePath()));
            return tempFile;
        } catch (IOException | InterruptedException | ErrorResponseException e) {
            CLog.e("Failed downloading logs with url path %s: %s", urlPath, e);
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
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            String cvdGroup = listCvdsRes.cvds.get(0).group;
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildCreateBugreportRequest(baseUrl, cvdGroup);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
            httpRequest = buildGetOperationResultRequest(baseUrl, operation.name);
            String bugreportId = sendRequest(mHttpClient, httpRequest, String.class);
            CommandResult curlRes =
                    curlCommandExecution(
                            mHOPortNumber,
                            "GET",
                            String.format(URL_CVD_BUGREPORTS, bugreportId),
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
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
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
            ListCvdsResponse listCvdsRes = null;
            try {
                listCvdsRes = listCvds();
            } catch (IOException | InterruptedException | ErrorResponseException e) {
                CLog.e("Failed listing cvds: %s", e);
                return false;
            }
            if (listCvdsRes.cvds.size() > 0 && listCvdsRes.cvds.get(0).status.equals("Running")) {
                return true;
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        return false;
    }

    /**
     * Performs list cvds request against HO.
     *
     * @return A {@link ListCvdsResponse} response of list cvds request.
     */
    ListCvdsResponse listCvds() throws InterruptedException, IOException, ErrorResponseException {
        String baseUrl = getHOBaseUrl(mHOPortNumber);
        HttpRequest httpRequest = buildListCvdsRequest(baseUrl);
        return sendRequest(mHttpClient, httpRequest, ListCvdsResponse.class);
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
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildPowerwashRequest(baseUrl, cvd.group, cvd.name);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed powerwashing gce via Host Orchestrator: %s", e);
        }
        return curlRes;
    }

    /** Remove Cuttlefish instance via Host Orchestrator. */
    public CommandResult removeInstance() {
        // Basically, the rough processes to remove an instance are
        // 1. Portforward CURL tunnel
        // 2. Obtain the group and instance name.
        // 3. Attempt to remove the Instance via Host Orchestrator.
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
            ListCvdsResponse listCvdsRes = listCvds();
            if (listCvdsRes.cvds.size() == 0) {
                CLog.e("No cvd found.");
                return null;
            }
            Cvd cvd = listCvdsRes.cvds.get(0);
            String baseUrl = getHOBaseUrl(mHOPortNumber);
            HttpRequest httpRequest = buildRemoveInstanceRequest(baseUrl, cvd.group, cvd.name);
            Operation operation = sendRequest(mHttpClient, httpRequest, Operation.class);
            waitForOperation(mHttpClient, baseUrl, operation.name, WAIT_FOR_OPERATION_TIMEOUT_MS);
        } catch (IOException | InterruptedException | ErrorResponseException | TimeoutException e) {
            CLog.e("Failed removing instance via Host Orchestrator: %s", e);
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
                    "Executing Host Orchestrator curl command: %s, Stdout: %s, Stderr: %s, Status:"
                            + " %s",
                    cmd,
                    commandRes.getStdout(),
                    commandRes.getStderr(),
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
                return commandRes;
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

    /**
     * Wait for operation to finish or timeout.
     *
     * @param client http client to perm
     * @param name Operation name.
     * @param maxWaitTime waiting time, if reached out, an execption will be thrown.
     */
    public void waitForOperation(
            IHoHttpClient client, String baseUrl, String name, long maxWaitTimeMs)
            throws IOException, InterruptedException, TimeoutException, ErrorResponseException {
        long maxEndTime = System.currentTimeMillis() + maxWaitTimeMs;
        while (System.currentTimeMillis() < maxEndTime) {
            HttpRequest httpRequest = buildGetOperationRequest(baseUrl, name);
            Operation op = sendRequest(client, httpRequest, Operation.class);
            if (op.done) {
                return;
            }
            getRunUtil().sleep(WAIT_FOR_OPERATION_MS);
        }
        CLog.e("Timeout waiting for operation: " + name);
        throw new TimeoutException("Operation wait timeout, operation name: " + name);
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
