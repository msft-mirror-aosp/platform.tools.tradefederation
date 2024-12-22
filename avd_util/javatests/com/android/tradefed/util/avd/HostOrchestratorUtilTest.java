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

import static com.android.tradefed.util.avd.HostOrchestratorClient.IHoHttpClient;

import static org.mockito.Mockito.times;

import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.avd.OxygenClient.LHPTunnelMode;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

/** Unit tests for {@link HostOrchestratorUtil} */
@RunWith(JUnit4.class)
public class HostOrchestratorUtilTest {

    private HostOrchestratorUtil mHOUtil;
    private static final String INSTANCE_NAME = "instance";
    private static final String OXYGENATION_DEVICE_ID = "id";
    private static final String TARGET_REGION = "target_region";
    private static final String ACCOUNTING_USER = "accounting_user";
    private static final String HOST = "host";
    private OxygenClient mMockClient;
    private IRunUtil mMockRunUtil;
    private Process mMockProcess;
    private File mMockFile;

    @Mock private HostOrchestratorClient.IHoHttpClient mMockHttpClient;

    private static final String LIST_CVD_RES =
            "{\"cvds\":[{\"group\":\"cvd_1\",\"name\":\"ins-1\",\"build_source\":{},"
                    + "\"status\":\"Running\",\"displays\":[\"720 x 1280 ( 320 )\"],"
                    + "\"webrtc_device_id\":\"cvd-1\"}]}";
    private static final String LIST_CVD_STARTING_RES =
            "{\"cvds\":[{\"group\":\"cvd_1\",\"name\":\"ins-1\",\"build_source\":{},"
                    + "\"status\":\"Starting\",\"displays\":[\"720 x 1280 ( 320 )\"],"
                    + "\"webrtc_device_id\":\"cvd-1\"}]}";
    private static final String OPERATION_RES = "{\"name\":\"some_id\"}";
    private static final String OPERATION_TIMEOUT_RES = "{\"name\":\"some_id\", \"done\":false}";
    private static final String OPERATION_DONE_RES = "{\"name\":\"some_id\", \"done\":true}";
    private static final String LIST_CVD_BADRES =
            "{\"cvds\":[{\"build_source\":{},"
                    + "\"status\":\"Running\",\"displays\":[\"720 x 1280 ( 320 )\"],"
                    + "\"webrtc_device_id\":\"cvd-1\"}]}";
    private HashMap<String, String> mExtraOxygenArgs;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mExtraOxygenArgs = new HashMap<>();
        mExtraOxygenArgs.put("arg1", "value1");
        mMockClient = Mockito.mock(OxygenClient.class);
        mMockProcess = Mockito.mock(Process.class);
        mMockRunUtil = Mockito.mock(IRunUtil.class);
    }

    @After
    public void tearDown() {
        FileUtil.deleteFile(mHOUtil.getTunnelLog());
    }

    @Test
    public void testCollectLogByCommand_Success() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout("some output");
        cvdCommandRes.setStderr("some error");
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        (String[]) Mockito.any());
        File tempFile =
                mHOUtil.collectLogByCommand("log", HostOrchestratorUtil.URL_HOST_KERNEL_LOG);
        FileUtil.deleteFile(tempFile);

        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq(
                                String.format(
                                        "http://127.0.0.1:1111/%s",
                                        HostOrchestratorUtil.URL_HOST_KERNEL_LOG)),
                        Mockito.eq("--compressed"),
                        Mockito.eq("-o"),
                        Mockito.any());
    }

    @Test
    public void testCollectLogByCommand_Fail() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.FAILED);
        cvdCommandRes.setStdout("some output");
        cvdCommandRes.setStderr("some error");
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        (String[]) Mockito.any());
        File tempFile =
                mHOUtil.collectLogByCommand("log", HostOrchestratorUtil.URL_HOST_KERNEL_LOG);
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq(
                                String.format(
                                        "http://127.0.0.1:1111/%s",
                                        HostOrchestratorUtil.URL_HOST_KERNEL_LOG)),
                        Mockito.eq("--compressed"),
                        Mockito.eq("-o"),
                        Mockito.any());
        FileUtil.deleteFile(tempFile);
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_Success() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    public CommandResult cvdOperationExecution(
                            IHoHttpClient client,
                            String portNumber,
                            String method,
                            String request,
                            long maxWaitTime) {
                        CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                        res.setStdout("operation_id");
                        return res;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout(LIST_CVD_RES);
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        CommandResult commandRes = new CommandResult(CommandStatus.SUCCESS);
        commandRes.setStdout("some output");
        Mockito.doReturn(commandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
        mHOUtil.pullCvdHostLogs();
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_CurlFailedGetCvd() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.FAILED);
        cvdCommandRes.setStderr("some error");
        cvdCommandRes.setStdout("some output");
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        (String[]) Mockito.any());
        Assert.assertNull(mHOUtil.pullCvdHostLogs());
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_CreateHOFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(null)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        Assert.assertNull(mHOUtil.pullCvdHostLogs());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_FailedDownload() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    public CommandResult cvdOperationExecution(
                            IHoHttpClient client,
                            String portNumber,
                            String method,
                            String request,
                            long maxWaitTime) {
                        CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                        res.setStdout("operation_id");
                        return res;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout(LIST_CVD_RES);
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        CommandResult commandRes = new CommandResult(CommandStatus.FAILED);
        commandRes.setStdout("some output");
        commandRes.setStderr("some error");
        Mockito.doReturn(commandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
        Assert.assertNull(mHOUtil.pullCvdHostLogs());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_404() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStderr("some error");
        cvdCommandRes.setStdout(mHOUtil.getUnsupportedHoResponse());
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        (String[]) Mockito.any());
        Assert.assertNull(mHOUtil.pullCvdHostLogs());
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvdbugreports/operation_id"),
                        Mockito.eq("--output"),
                        Mockito.any());
    }

    @Test
    public void testPowerwashGce() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(LIST_CVD_RES);
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        CommandResult powerwashRes = new CommandResult(CommandStatus.SUCCESS);
        powerwashRes.setStdout(OPERATION_RES);
        Mockito.doReturn(powerwashRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
        Mockito.when(mMockHttpClient.send(Mockito.any()))
                .thenReturn(mockHttpResponse(200, OPERATION_DONE_RES));
        CommandResult successRes = new CommandResult(CommandStatus.SUCCESS);
        successRes.setStdout("operation_id");
        Mockito.doReturn(successRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/operations/some_id/result"));
        Assert.assertNotNull(mHOUtil.powerwashGce());
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_CreateHOFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(null)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        Assert.assertEquals(CommandStatus.EXCEPTION, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_ListCvdFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.FAILED);
        cvdRes.setStdout("output");
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_ListCvd404() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(mHOUtil.getUnsupportedHoResponse());
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_NoCvdOutput() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(LIST_CVD_BADRES);
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testStopGce() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(LIST_CVD_RES);
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        CommandResult stopRes = new CommandResult(CommandStatus.SUCCESS);
        stopRes.setStdout(OPERATION_RES);
        Mockito.doReturn(stopRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
        Mockito.when(mMockHttpClient.send(Mockito.any()))
                .thenReturn(mockHttpResponse(200, OPERATION_DONE_RES));
        CommandResult successRes = new CommandResult(CommandStatus.SUCCESS);
        successRes.setStdout("operation_id");
        Mockito.doReturn(successRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/operations/some_id/result"));
        Assert.assertNotNull(mHOUtil.stopGce());
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(1))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
    }

    @Test
    public void testStopGce_CreateHOFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(null)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        Assert.assertEquals(CommandStatus.EXCEPTION, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
    }

    @Test
    public void testStopGce_ListCvdFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.FAILED);
        cvdRes.setStdout("output");
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
    }

    @Test
    public void testStopGce_ListCvd404() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(mHOUtil.getUnsupportedHoResponse());
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
    }

    @Test
    public void testStopGce_NoCvdOutput() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(LIST_CVD_BADRES);
        Mockito.doReturn(cvdRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertEquals(CommandStatus.FAILED, mHOUtil.powerwashGce().getStatus());
        Mockito.verify(mMockRunUtil, times(0))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("DELETE"),
                        Mockito.eq("http://127.0.0.1:1111/cvds/cvd_1/ins-1"));
    }

    @Test
    public void testCvdOperationExecution_Failed() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.FAILED);
        cvdCommandRes.setStdout("some output");
        cvdCommandRes.setStderr("some error");
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/request"));
        Assert.assertEquals(
                CommandStatus.FAILED,
                mHOUtil.cvdOperationExecution(null, "1111", "POST", "request", 5).getStatus());
    }

    @Test
    public void testCvdOperationExecution_FailedOperation() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult commandRes = new CommandResult(CommandStatus.SUCCESS);
        commandRes.setStdout(OPERATION_RES);
        Mockito.doReturn(commandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/request"));
        Mockito.when(mMockHttpClient.send(Mockito.any()))
                .thenReturn(mockHttpResponse(200, OPERATION_DONE_RES));
        CommandResult failedRes = new CommandResult(CommandStatus.FAILED);
        failedRes.setStdout("some output");
        Mockito.doReturn(failedRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/operations/some_id/result"));
        Assert.assertEquals(
                CommandStatus.FAILED,
                mHOUtil.cvdOperationExecution(mMockHttpClient, "1111", "POST", "request", 5)
                        .getStatus());
    }

    @Test
    public void testCvdOperationExecution_Success() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult commandRes = new CommandResult(CommandStatus.SUCCESS);
        commandRes.setStdout(OPERATION_RES);
        Mockito.doReturn(commandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/request"));
        Mockito.when(mMockHttpClient.send(Mockito.any()))
                .thenReturn(mockHttpResponse(200, OPERATION_DONE_RES));
        CommandResult successRes = new CommandResult(CommandStatus.SUCCESS);
        successRes.setStdout("operation_id");
        Mockito.doReturn(successRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/operations/some_id/result"));
        Assert.assertEquals(
                "operation_id",
                mHOUtil.cvdOperationExecution(mMockHttpClient, "1111", "POST", "request", 5)
                        .getStdout());
    }

    @Test
    public void testCvdOperationExecution_Timedout() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult commandRes = new CommandResult(CommandStatus.SUCCESS);
        commandRes.setStdout(OPERATION_RES);
        Mockito.doReturn(commandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("POST"),
                        Mockito.eq("http://127.0.0.1:1111/request"));
        Mockito.when(mMockHttpClient.send(Mockito.any()))
                .thenReturn(mockHttpResponse(200, OPERATION_TIMEOUT_RES));
        Assert.assertEquals(
                CommandStatus.TIMED_OUT,
                mHOUtil.cvdOperationExecution(mMockHttpClient, "1111", "POST", "request", 5)
                        .getStatus());
    }

    @Test
    public void testParseListCvdOutput_success() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient);
        Assert.assertEquals("cvd_1", mHOUtil.parseListCvdOutput(LIST_CVD_RES, "group"));
    }

    @Test
    public void testParseListCvdOutput_failed() throws Exception {
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        null);
        Assert.assertEquals("", mHOUtil.parseListCvdOutput(LIST_CVD_BADRES, "group"));
    }

    @Test
    public void testDeviceBootCompleted_success() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout(LIST_CVD_RES);
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertTrue(mHOUtil.deviceBootCompleted(10));
    }

    @Test
    public void testDeviceBootCompleted_failed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(mMockProcess)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout(LIST_CVD_STARTING_RES);
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq("curl"),
                        Mockito.eq("-0"),
                        Mockito.eq("-v"),
                        Mockito.eq("-X"),
                        Mockito.eq("GET"),
                        Mockito.eq("http://127.0.0.1:1111/cvds"));
        Assert.assertFalse(mHOUtil.deviceBootCompleted(10));
    }

    @Test
    public void testDeviceBootCompleted_CreateHOFailed() throws Exception {
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(null)
                .when(mMockClient)
                .createTunnelViaLHP(
                        Mockito.eq(LHPTunnelMode.CURL),
                        Mockito.eq("1111"),
                        Mockito.eq(INSTANCE_NAME),
                        Mockito.eq(HOST),
                        Mockito.eq(TARGET_REGION),
                        Mockito.eq(ACCOUNTING_USER),
                        Mockito.eq(OXYGENATION_DEVICE_ID),
                        Mockito.eq(mExtraOxygenArgs),
                        Mockito.any());
        mHOUtil =
                new HostOrchestratorUtil(
                        true,
                        mExtraOxygenArgs,
                        INSTANCE_NAME,
                        HOST,
                        OXYGENATION_DEVICE_ID,
                        TARGET_REGION,
                        ACCOUNTING_USER,
                        mMockClient,
                        mMockHttpClient) {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        Assert.assertFalse(mHOUtil.deviceBootCompleted(10));
    }

    private static HttpResponse<String> mockHttpResponse(int statusCode, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return null;
            }

            @Override
            public HttpClient.Version version() {
                return null;
            }
        };
    }
}
