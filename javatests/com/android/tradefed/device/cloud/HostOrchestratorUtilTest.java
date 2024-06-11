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

import static org.mockito.Mockito.times;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.OxygenClient.LHPTunnelMode;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import com.google.common.net.HostAndPort;

import java.io.OutputStream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for {@link HostOrchestratorUtil} */
@RunWith(JUnit4.class)
public class HostOrchestratorUtilTest {

    private HostOrchestratorUtil mHOUtil;
    private GceAvdInfo mMockGceAvd;
    private ITestDevice mMockDevice;
    private OxygenClient mMockClient;
    private TestDeviceOptions mTestDeviceOptions;
    private IRunUtil mMockRunUtil;
    private Process mMockProcess;
    private static final String LIST_CVD_RES =
            "{\"cvds\":[{\"group\":\"cvd_1\",\"name\":\"ins-1\",\"build_source\":{},"
                    + "\"status\":\"Running\",\"displays\":[\"720 x 1280 ( 320 )\"],"
                    + "\"webrtc_device_id\":\"cvd-1\"}]}";
    private static final String LIST_CVD_BADRES =
            "{\"cvds\":[{\"build_source\":{},"
                    + "\"status\":\"Running\",\"displays\":[\"720 x 1280 ( 320 )\"],"
                    + "\"webrtc_device_id\":\"cvd-1\"}]}";

    @Before
    public void setUp() throws Exception {
        mMockDevice = Mockito.mock(ITestDevice.class);
        mMockGceAvd = Mockito.mock(GceAvdInfo.class);
        mMockClient = Mockito.mock(OxygenClient.class);
        mMockProcess = Mockito.mock(Process.class);
        mMockRunUtil = Mockito.mock(IRunUtil.class);
        mTestDeviceOptions = new TestDeviceOptions();
        mHOUtil = new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient);
    }

    @After
    public void tearDown() {}

    @Test
    public void testCreateHostOrchestratorTunnel_NoCVDNoOxygenation() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "false");
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.verify(mMockClient, times(0))
                .createTunnelViaLHP(LHPTunnelMode.CURL, "1111", "instance", "id");
        Assert.assertNull(mHOUtil.createHostOrchestratorTunnel("1111"));
    }

    @Test
    public void testCreateHostOrchestratorTunnel_Oxygenation() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn("instance").when(mMockGceAvd).instanceName();
        Mockito.doReturn("id").when(mMockGceAvd).getOxygenationDeviceId();
        mHOUtil.createHostOrchestratorTunnel("1111");
        Mockito.verify(mMockClient, times(1))
                .createTunnelViaLHP(LHPTunnelMode.CURL, "1111", "instance", "id");
    }

    @Test
    public void testCreateHostOrchestratorTunnel_Oxygen_UseCVD() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "false");
        setter.setOptionValue("extra-oxygen-args", "use_cvd", "value");
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        Mockito.verify(mMockClient, times(0))
                .createTunnelViaLHP(LHPTunnelMode.CURL, "1111", "instance", "id");
        mHOUtil.createHostOrchestratorTunnel("1111");
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_Success() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdCommandRes = new CommandResult(CommandStatus.SUCCESS);
        cvdCommandRes.setStdout("some output");
        Mockito.doReturn(cvdCommandRes)
                .when(mMockRunUtil)
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq((OutputStream) null),
                        Mockito.eq((OutputStream) null),
                        (String[]) Mockito.any());
        mHOUtil.pullCvdHostLogs();
        Mockito.verify(mMockClient, times(1)).closeLHPConnection(mMockProcess);
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_CurlFailed() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
        Mockito.verify(mMockClient, times(1)).closeLHPConnection(mMockProcess);
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
                        Mockito.eq("http://host:1111/runtimeartifacts/:pull"),
                        Mockito.eq("--output"),
                        Mockito.anyString());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_CreateHOFailed() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return null;
                    }

                    @Override
                    IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        Assert.assertNull(mHOUtil.pullCvdHostLogs());
        Mockito.verify(mMockClient, times(1)).closeLHPConnection(null);
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
                        Mockito.eq("http://host:1111/runtimeartifacts/:pull"),
                        Mockito.eq("--output"),
                        Mockito.anyString());
    }

    @Test
    public void testPullCvdHostLogs_Oxygenation_404() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
        Mockito.verify(mMockClient, times(1)).closeLHPConnection(mMockProcess);
    }

    @Test
    public void testPowerwashGce() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
        CommandResult cvdRes = new CommandResult(CommandStatus.SUCCESS);
        cvdRes.setStdout(LIST_CVD_RES);
        CommandResult powerwashRes = new CommandResult(CommandStatus.SUCCESS);
        powerwashRes.setStdout("");
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_CreateHOFailed() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return null;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_ListCvdFailed() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_ListCvd404() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
    }

    @Test
    public void testPowerwashGce_NoCvdOutput() throws Exception {
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("use-oxygenation-device", "true");
        Mockito.doReturn(1111).when(mMockClient).createServerSocket();
        Mockito.doReturn(mTestDeviceOptions).when(mMockDevice).getOptions();
        Mockito.doReturn(true).when(mMockProcess).isAlive();
        Mockito.doReturn(HostAndPort.fromString("host:2080")).when(mMockGceAvd).hostAndPort();
        mHOUtil =
                new HostOrchestratorUtil(mMockDevice, mMockGceAvd, mMockClient) {
                    @Override
                    Process createHostOrchestratorTunnel(String portNumber) {
                        return mMockProcess;
                    }

                    @Override
                    IRunUtil getRunUtil() {
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
                        Mockito.eq("http://host:1111/cvds"));
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
                        Mockito.eq("http://host:1111/cvds/cvd_1/ins-1/:powerwash"));
    }
}
