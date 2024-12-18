/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.device.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IConfigurableVirtualDevice;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.AbstractTunnelMonitor;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.device.cloud.GceLHPTunnelMonitor;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.device.cloud.GceSshTunnelMonitor;
import com.android.tradefed.device.connection.DefaultConnection.ConnectionBuilder;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.avd.HostOrchestratorUtil;

import com.google.common.net.HostAndPort;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Unit tests for {@link AdbSshConnection}. */
@RunWith(JUnit4.class)
public class AdbSshConnectionTest {

    private static final String MOCK_DEVICE_SERIAL = "localhost:1234";
    private static final long WAIT_FOR_TUNNEL_TIMEOUT = 10;

    private AdbSshConnection mConnection;

    private TestDeviceOptions mOptions;
    @Mock IManagedTestDevice mMockDevice;
    @Mock TestableConfigurableVirtualDevice mMockIDevice;
    @Mock IDeviceStateMonitor mMockMonitor;
    @Mock IRunUtil mMockRunUtil;
    @Mock IBuildInfo mMockBuildInfo;
    @Mock ITestLogger mMockLogger;
    @Mock GceManager mGceHandler;
    @Mock GceSshTunnelMonitor mGceSshMonitor;
    @Mock GceLHPTunnelMonitor mGceLhpMonitor;
    @Mock ITestDevice mMockTestDevice;
    @Mock GceAvdInfo mMockAvdInfo;
    @Mock File mMockFile;
    @Mock HostOrchestratorUtil mMockHOUtil;

    public static interface TestableConfigurableVirtualDevice
            extends IDevice, IConfigurableVirtualDevice {}

    @BeforeClass
    public static void setUpClass() throws ConfigurationException {
        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {"empty"});
        } catch (IllegalStateException ignore) {
            // ignore
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockFile = Mockito.mock(File.class);
        MockitoAnnotations.initMocks(this);
        mOptions = new TestDeviceOptions();
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");
        when(mMockDevice.getSerialNumber()).thenReturn(MOCK_DEVICE_SERIAL);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getOptions()).thenReturn(mOptions);
        when(mMockDevice.getMonitor()).thenReturn(mMockMonitor);

        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public AbstractTunnelMonitor getGceTunnelMonitor() {
                        return mGceSshMonitor;
                    }
                };
    }

    @After
    public void tearDown() {
        if (mConnection.getGceTunnelMonitor() != null) {
            mConnection.getGceTunnelMonitor().shutdown();
        }
    }

    /** Run powerwash() but GceAvdInfo = null. */
    @Test
    public void testPowerwashNoAvdInfo() throws Exception {
        final String expectedException = "Can not get GCE AVD Info. launch GCE first?";

        try {
            mConnection.powerwashGce(null, null);
            fail("Should have thrown an exception");
        } catch (TargetSetupError expected) {
            assertEquals(expectedException, expected.getMessage());
        }
    }

    /** Test powerwash GCE command */
    @Test
    public void testPowerwashGce() throws Exception {
        String instanceUser = "user1";
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        String powerwashCommand = String.format("/home/%s/bin/powerwash_cvd", instanceUser);
        String avdConnectHost = String.format("%s@127.0.0.1", instanceUser);
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromHost("127.0.0.1"),
                        null,
                        null,
                        GceStatus.SUCCESS);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        OutputStream stdout = null;
        OutputStream stderr = null;
        CommandResult powerwashCmdResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq(powerwashCommand)))
                .thenReturn(powerwashCmdResult);
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        // Launch GCE before powerwash.
        mConnection.initializeConnection();
        mConnection.powerwashGce(instanceUser, null);
    }

    /** Test powerwash cvd in oxygen command */
    @Test
    public void testPowerwashCvdOxygen() throws Exception {
        String instanceUser = "user1";
        mOptions.setAvdDriverBinary(mMockFile);
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        setter.setOptionValue("extra-oxygen-args", "use_cvd", "");
        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public HostOrchestratorUtil createHostOrchestratorUtil(GceAvdInfo gceAvdInfo) {
                        return mMockHOUtil;
                    }
                };
        when(mMockDevice.getOptions()).thenReturn(mOptions);
        when(mMockFile.exists()).thenReturn(true);
        when(mMockFile.canExecute()).thenReturn(true);
        when(mMockFile.getAbsolutePath()).thenReturn("a/b/c/script");
        when(mMockAvdInfo.instanceName()).thenReturn("instance");
        when(mMockAvdInfo.getOxygenationDeviceId()).thenReturn("device_id");
        when(mMockAvdInfo.hostAndPort()).thenReturn(HostAndPort.fromString("127.0.0.1"));
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromHost("127.0.0.1"),
                        null,
                        null,
                        GceStatus.SUCCESS);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        CommandResult powerwashCmdResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockHOUtil.powerwashGce()).thenReturn(powerwashCmdResult);
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        // Launch GCE before powerwash.
        mConnection.initializeConnection();
        mConnection.powerwashGce(instanceUser, null);
        verify(mMockHOUtil, times(1)).powerwashGce();
    }

    @Test
    public void testPowerwashOxygenGce() throws Exception {
        String instanceUser = "user1";
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        setter.setOptionValue("use-oxygen", "true");
        String avdConnectHost = String.format("%s@127.0.0.1", instanceUser);
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromHost("127.0.0.1"),
                        null,
                        null,
                        GceStatus.SUCCESS);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        OutputStream stdout = null;
        OutputStream stderr = null;
        CommandResult locateCmdResult = new CommandResult(CommandStatus.SUCCESS);
        locateCmdResult.setStdout("/tmp/cf_dir/bin/powerwash_cvd");
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq("toybox"),
                        Mockito.eq("find"),
                        Mockito.eq("/tmp"),
                        Mockito.eq("-name"),
                        Mockito.eq("powerwash_cvd")))
                .thenReturn(locateCmdResult);
        CommandResult powerwashCmdResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq("HOME=/tmp/cf_dir"),
                        Mockito.eq("/tmp/cf_dir/bin/powerwash_cvd")))
                .thenReturn(powerwashCmdResult);
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        // Launch GCE before powerwash.
        mConnection.initializeConnection();
        mConnection.powerwashGce(instanceUser, null);
    }

    @Test
    public void testPowerwashMultiInstance() throws Exception {
        String instanceUser = "vsoc-1";
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        String avdConnectHost = String.format("%s@127.0.0.1", instanceUser);
        String powerwashCvdBinaryPath = "acloud_cf_3/bin/powerwash_cvd";
        String cvdHomeDir = "HOME=/home/vsoc-1/acloud_cf_3";

        when(mMockIDevice.getKnownDeviceIp()).thenReturn("127.0.0.1");
        when(mMockIDevice.getKnownUser()).thenReturn(instanceUser);
        when(mMockIDevice.getDeviceNumOffset()).thenReturn(2);

        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }
                };

        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromString("127.0.0.1:6922"),
                        null,
                        null,
                        GceStatus.SUCCESS);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.eq("127.0.0.1"),
                        Mockito.eq(instanceUser),
                        Mockito.eq(2),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        OutputStream stdout = null;
        OutputStream stderr = null;
        CommandResult powerwashCmdResult = new CommandResult(CommandStatus.SUCCESS);

        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq(cvdHomeDir),
                        Mockito.eq(powerwashCvdBinaryPath),
                        Mockito.eq("-instance_num"),
                        Mockito.eq("3")))
                .thenReturn(powerwashCmdResult);
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        // Launch GCE before powerwash.
        mConnection.initializeConnection();
        mConnection.powerwashGce(instanceUser, 2);
    }

    @Test
    public void testGetRemoteTombstone() throws Exception {
        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    boolean fetchRemoteDir(File localDir, String remotePath) {
                        try {
                            FileUtil.createTempFile("tombstone_00", "", localDir);
                            FileUtil.createTempFile("tombstone_01", "", localDir);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return true;
                    }
                };
        List<File> tombstones = mConnection.getTombstones();
        try {
            assertEquals(2, tombstones.size());
        } finally {
            for (File f : tombstones) {
                FileUtil.deleteFile(f);
            }
        }
    }

    /**
     * Test that an exception thrown in the parser should be propagated to the top level and should
     * not be caught.
     */
    @Test
    public void testExceptionFromParser() throws Exception {
        final String expectedException =
                "acloud errors: Could not get a valid instance name, check the gce driver's "
                        + "output.The instance may not have booted up at all.\nGCE driver stderr: ";

        String echoFilePath = null;
        try {
            final File echoFile = FileUtil.createTempFile("echo", ".sh");
            echoFilePath = echoFile.getAbsolutePath();
            FileUtil.writeToFile("#!/bin/bash\necho $#", echoFile);
            FileUtil.chmodGroupRWX(echoFile);
            mConnection =
                    new AdbSshConnection(
                            new ConnectionBuilder(
                                    mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                        @Override
                        GceManager getGceHandler() {
                            TestDeviceOptions deviceOptions = new TestDeviceOptions();
                            // Make the command line a no-op.
                            deviceOptions.setAvdDriverBinary(echoFile);
                            return new GceManager(
                                    getDevice().getDeviceDescriptor(),
                                    deviceOptions,
                                    mMockBuildInfo) {};
                        }
                    };

            mConnection.initializeConnection();
            fail("A TargetSetupError should have been thrown");
        } catch (TargetSetupError expected) {
            assertTrue(expected.getMessage().startsWith(expectedException));
        } finally {
            FileUtil.deleteFile(new File(echoFilePath));
        }
    }

    /** Test that in case of BOOT_FAIL, RemoteAndroidVirtualDevice choose to throw exception. */
    @Test
    public void testLaunchGce_bootFail() throws Exception {
        String instanceUser = "user1";
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromHost("127.0.0.1"),
                        null,
                        "acloud error",
                        GceStatus.BOOT_FAIL);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));

        try {
            mConnection.initializeConnection();
            fail("Should have thrown an exception");
        } catch (TargetSetupError expected) {
            // expected
        }
    }

    /**
     * Test {@link AdbSshConnection#initializeConnection()} when device is launched and mGceAvdInfo
     * is set.
     */
    @Test
    public void testPreInvocationLaunchedDeviceSetup() throws Exception {
        GceAvdInfo mockGceAvdInfo = Mockito.mock(GceAvdInfo.class);
        ConnectionBuilder builder =
                new ConnectionBuilder(mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger);
        builder.setExistingAvdInfo(mockGceAvdInfo);
        when(mockGceAvdInfo.getStatus()).thenReturn(GceStatus.SUCCESS);
        mConnection =
                new AdbSshConnection(builder) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    protected void launchGce(
                            IBuildInfo buildInfo, MultiMap<String, String> attributes)
                            throws TargetSetupError {
                        fail("Should not launch a Gce because the device should already launched");
                    }

                    @Override
                    void createGceTunnelMonitor(
                            ITestDevice device,
                            IBuildInfo buildInfo,
                            GceAvdInfo gceAvdInfo,
                            TestDeviceOptions deviceOptions) {
                        // Ignore
                    }

                    @Override
                    public AbstractTunnelMonitor getGceTunnelMonitor() {
                        return mGceSshMonitor;
                    }

                    @Override
                    protected void waitForAdbConnect(String serial, long waitTime)
                            throws DeviceNotAvailableException {
                        // Ignore
                    }
                };
        doReturn(true).when(mGceSshMonitor).isTunnelAlive();
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        mConnection.initializeConnection();
        assertEquals(mockGceAvdInfo, mConnection.getAvdInfo());
    }

    /**
     * Test {@link AdbSshConnection#waitForTunnelOnline(long)} return without exception when tunnel
     * is online.
     */
    @Test
    public void testWaitForTunnelOnline() throws Exception {
        doReturn(true).when(mGceSshMonitor).isTunnelAlive();

        mConnection.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
    }

    /**
     * Test {@link AdbSshConnection#waitForTunnelOnline(long)} throws an exception when the tunnel
     * returns not alive.
     */
    @Test
    public void testWaitForTunnelOnline_notOnline() throws Exception {
        doReturn(false).when(mGceSshMonitor).isTunnelAlive();

        try {
            mConnection.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            // expected.
        }
    }

    /**
     * Test {@link AdbSshConnection#waitForTunnelOnline(long)} throws an exception when the tunnel
     * object is null, meaning something went wrong during its setup.
     */
    @Test
    public void testWaitForTunnelOnline_tunnelTerminated() throws Exception {
        mGceSshMonitor = null;

        try {
            mConnection.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            assertEquals(
                    String.format(
                            "Tunnel did not come back online after %sms", WAIT_FOR_TUNNEL_TIMEOUT),
                    expected.getMessage());
        }
    }

    /** Test snapshot restore GCE command */
    @Test
    public void testSnapshotGce() throws Exception {
        String instanceUser = "user1";
        String snapshotId = "snapshot_user1";
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue("instance-user", instanceUser);
        String snapshotPath = String.format("/tmp/%s/snapshots/%s", instanceUser, snapshotId);
        String snapshotCommandPath = String.format("--snapshot_path=%s", snapshotPath);
        String restoreSnapshotCommandPath = String.format("--snapshot_path=%s", snapshotPath);
        String avdConnectHost = String.format("%s@127.0.0.1", instanceUser);
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        instanceUser,
                        HostAndPort.fromHost("127.0.0.1"),
                        null,
                        null,
                        GceStatus.SUCCESS);
        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public AbstractTunnelMonitor getGceTunnelMonitor() {
                        return mGceSshMonitor;
                    }

                    @Override
                    protected void waitForAdbConnect(String serial, long waitTime)
                            throws DeviceNotAvailableException {
                        // Ignore
                    }
                };
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        OutputStream stdout = null;
        OutputStream stderr = null;
        CommandResult successCmdResult = new CommandResult(CommandStatus.SUCCESS);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq(String.format("/home/%s/bin/snapshot_util_cvd", instanceUser)),
                        Mockito.eq("--subcmd=snapshot_take"),
                        Mockito.eq("--force"),
                        Mockito.eq("--auto_suspend"),
                        Mockito.eq(snapshotCommandPath)))
                .thenReturn(successCmdResult);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq(String.format("/home/%s/bin/stop_cvd", instanceUser))))
                .thenReturn(successCmdResult);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq(stdout),
                        Mockito.eq(stderr),
                        Mockito.eq("ssh"),
                        Mockito.eq("-o"),
                        Mockito.eq("LogLevel=ERROR"),
                        Mockito.eq("-o"),
                        Mockito.eq("UserKnownHostsFile=/dev/null"),
                        Mockito.eq("-o"),
                        Mockito.eq("StrictHostKeyChecking=no"),
                        Mockito.eq("-o"),
                        Mockito.eq("ServerAliveInterval=10"),
                        Mockito.eq("-i"),
                        Mockito.any(),
                        Mockito.eq(avdConnectHost),
                        Mockito.eq(String.format("/home/%s/bin/launch_cvd", instanceUser)),
                        Mockito.eq(restoreSnapshotCommandPath)))
                .thenReturn(successCmdResult);
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("connected to");
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);

        // Launch GCE before snapshot restore.
        mConnection.initializeConnection();
        mConnection.snapshotGce(instanceUser, null, snapshotId);
        // TODO: replace "snapshot" with the snapshot_id.
        // TODO: enable restore when restoreSnapshotGce is implemented
        mConnection.restoreSnapshotGce(instanceUser, null, snapshotId);
    }

    /** Test device launched with right kernel when --kernel-build-id is passed. */
    @Test
    public void testVerifyKernel_rightKernel() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOptions = new TestDeviceOptions();
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");
        setter.setOptionValue("gce-driver-param", "--kernel-build-id");
        setter.setOptionValue("gce-driver-param", "10465484");
        when(mMockDevice.getSerialNumber()).thenReturn(MOCK_DEVICE_SERIAL);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getOptions()).thenReturn(mOptions);
        when(mMockDevice.getMonitor()).thenReturn(mMockMonitor);

        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public AbstractTunnelMonitor getGceTunnelMonitor() {
                        return mGceSshMonitor;
                    }
                };

        when(mMockDevice.executeShellCommand(Mockito.eq("uname -r")))
                .thenReturn("5.15.110-android14-11-00096-g2a75568a6b9d-ab10465484");
        mConnection.verifyKernel();
    }

    /** Test that in case of wrong kernel, RemoteAndroidVirtualDevice choose to throw exception. */
    @Test
    public void testVerifyKernel_wrongKernel() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOptions = new TestDeviceOptions();
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");
        setter.setOptionValue("gce-driver-param", "--kernel-build-id");
        setter.setOptionValue("gce-driver-param", "1234567");
        when(mMockDevice.getSerialNumber()).thenReturn(MOCK_DEVICE_SERIAL);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getOptions()).thenReturn(mOptions);
        when(mMockDevice.getMonitor()).thenReturn(mMockMonitor);

        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public AbstractTunnelMonitor getGceTunnelMonitor() {
                        return mGceSshMonitor;
                    }
                };

        when(mMockDevice.executeShellCommand(Mockito.eq("uname -r")))
                .thenReturn("5.15.110-android14-11-00096-g2a75568a6b9d-ab10465484");

        try {
            mConnection.verifyKernel();
            fail("A TargetSetupError should have been thrown");
        } catch (TargetSetupError expected) {
            // expected
        }
    }

    /** Test SSH tunnel monitor will be initialized when use-oxygenation-device is False */
    @Test
    public void testCreateGceTunnelMonitor_SSHTunnel() throws Exception {
        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger));
        mOptions = new TestDeviceOptions();
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");
        setter.setOptionValue("use-oxygenation-device", "false");
        mOptions.setSshPrivateKeyPath(mMockFile);
        when(mMockFile.canRead()).thenReturn(true);
        mConnection.createGceTunnelMonitor(mMockTestDevice, mMockBuildInfo, mMockAvdInfo, mOptions);
        assertTrue(mConnection.getGceTunnelMonitor() instanceof GceSshTunnelMonitor);
    }

    /** Test host orchestrator will not be initialized for neither Oxygenation nor Oxygen. */
    @Test
    public void testNoHOCreated() throws Exception {
        mConnection =
                new AdbSshConnection(
                        new ConnectionBuilder(
                                mMockRunUtil, mMockDevice, mMockBuildInfo, mMockLogger)) {
                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    void createGceTunnelMonitor(
                            ITestDevice device,
                            IBuildInfo buildInfo,
                            GceAvdInfo gceAvdInfo,
                            TestDeviceOptions deviceOptions) {
                        // Ignore
                    }
                };
        mOptions = new TestDeviceOptions();
        mOptions.setAvdDriverBinary(mMockFile);
        OptionSetter setter = new OptionSetter(mOptions);
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");
        setter.setOptionValue("use-oxygenation-device", "false");
        when(mMockDevice.getOptions()).thenReturn(mOptions);
        when(mMockFile.exists()).thenReturn(true);
        when(mMockFile.canExecute()).thenReturn(true);
        GceAvdInfo gceAvd =
                new GceAvdInfo(
                        "user", HostAndPort.fromHost("127.0.0.1"), null, null, GceStatus.SUCCESS);
        doReturn(gceAvd)
                .when(mGceHandler)
                .startGce(
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(0),
                        Mockito.any(),
                        Mockito.eq(mMockLogger));
        when(mMockMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);
        mConnection.initializeConnection();
        assertNull(mConnection.createHostOrchestratorUtil(gceAvd));
    }
}
