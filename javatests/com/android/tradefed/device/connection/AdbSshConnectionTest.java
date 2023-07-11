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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.IConfigurableVirtualDevice;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.device.connection.DefaultConnection.ConnectionBuilder;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import com.google.common.net.HostAndPort;

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

    private AdbSshConnection mConnection;

    private TestDeviceOptions mOptions;
    @Mock IManagedTestDevice mMockDevice;
    @Mock TestableConfigurableVirtualDevice mMockIDevice;
    @Mock IDeviceStateMonitor mMockMonitor;
    @Mock IRunUtil mMockRunUtil;
    @Mock IBuildInfo mMockBuildInfo;
    @Mock ITestLogger mMockLogger;
    @Mock GceManager mGceHandler;

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
                };
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
}
