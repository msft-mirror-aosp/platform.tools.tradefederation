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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceRecovery;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;

import com.google.common.net.HostAndPort;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link RemoteAndroidVirtualDevice}. */
@RunWith(JUnit4.class)
public class RemoteAndroidVirtualDeviceTest {

    private static final String MOCK_DEVICE_SERIAL = "localhost:1234";
    private static final long WAIT_FOR_TUNNEL_TIMEOUT = 10;
    @Mock IDevice mMockIDevice;
    @Mock ITestLogger mTestLogger;
    @Mock IDeviceStateMonitor mMockStateMonitor;
    @Mock IRunUtil mMockRunUtil;
    @Mock IDeviceMonitor mMockDvcMonitor;
    @Mock IDeviceRecovery mMockRecovery;
    private RemoteAndroidVirtualDevice mTestDevice;
    private GceSshTunnelMonitor mGceSshMonitor;
    private boolean mUseRealTunnel = false;

    private GceManager mGceHandler;
    private IBuildInfo mMockBuildInfo;

    /** A {@link TestDevice} that is suitable for running tests against */
    private class TestableRemoteAndroidVirtualDevice extends RemoteAndroidVirtualDevice {
        public TestableRemoteAndroidVirtualDevice() {
            super(mMockIDevice, mMockStateMonitor, mMockDvcMonitor);
            mOptions = new TestDeviceOptions();
            // Disable connection to test the internal codepath
            mOptions.setUseConnection(false);
        }

        @Override
        protected IRunUtil getRunUtil() {
            return mMockRunUtil;
        }

        @Override
        public GceSshTunnelMonitor getGceSshMonitor() {
            if (mUseRealTunnel) {
                return super.getGceSshMonitor();
            }
            return mGceSshMonitor;
        }

        @Override
        public IDevice getIDevice() {
            return mMockIDevice;
        }

        @Override
        public DeviceDescriptor getDeviceDescriptor() {
            DeviceDescriptor desc =
                    new DeviceDescriptor(
                            "", false, DeviceAllocationState.Allocated, "", "", "", "", "");
            return desc;
        }

        @Override
        public String getSerialNumber() {
            return MOCK_DEVICE_SERIAL;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mUseRealTunnel = false;

        when(mMockIDevice.getSerialNumber()).thenReturn(MOCK_DEVICE_SERIAL);

        // A TestDevice with a no-op recoverDevice() implementation
        mTestDevice = new TestableRemoteAndroidVirtualDevice();
        mTestDevice.setRecovery(mMockRecovery);

        mGceSshMonitor = Mockito.mock(GceSshTunnelMonitor.class);
        mGceHandler = Mockito.mock(GceManager.class);

        mMockBuildInfo = new BuildInfo();

        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {"empty"});
        } catch (IllegalStateException e) {
            // Ignore reinit
        }
    }

    @After
    public void tearDown() {
        FileUtil.deleteFile(mTestDevice.getExecuteShellCommandLog());
    }

    /**
     * Test that an exception thrown in the parser should be propagated to the top level and should
     * not be caught.
     */
    @Test
    public void testExceptionFromParser() {
        final String expectedException =
                "acloud errors: Could not get a valid instance name, check the gce driver's "
                        + "output.The instance may not have booted up at all.\nGCE driver stderr: ";
        mTestDevice =
                new TestableRemoteAndroidVirtualDevice() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    void createGceSshMonitor(
                            ITestDevice device,
                            IBuildInfo buildInfo,
                            HostAndPort hostAndPort,
                            TestDeviceOptions deviceOptions) {
                        // ignore
                    }

                    @Override
                    GceManager getGceHandler() {
                        return new GceManager(
                                getDeviceDescriptor(), new TestDeviceOptions(), mMockBuildInfo) {
                            @Override
                            protected List<String> buildGceCmd(
                                    File reportFile,
                                    IBuildInfo b,
                                    String ipDevice,
                                    String user,
                                    Integer offset,
                                    MultiMap<String, String> attributes) {
                                FileUtil.deleteFile(reportFile);
                                List<String> tmp = new ArrayList<String>();
                                tmp.add("");
                                return tmp;
                            }
                        };
                    }
                };

        try {
            mTestDevice.launchGce(mMockBuildInfo, null);
            fail("A TargetSetupError should have been thrown");
        } catch (TargetSetupError expected) {
            assertTrue(expected.getMessage().startsWith(expectedException));
        }
    }

    /**
     * Test {@link RemoteAndroidVirtualDevice#waitForTunnelOnline(long)} return without exception
     * when tunnel is online.
     */
    @Test
    public void testWaitForTunnelOnline() throws Exception {
        doReturn(true).when(mGceSshMonitor).isTunnelAlive();

        mTestDevice.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
    }

    /**
     * Test {@link RemoteAndroidVirtualDevice#waitForTunnelOnline(long)} throws an exception when
     * the tunnel returns not alive.
     */
    @Test
    public void testWaitForTunnelOnline_notOnline() throws Exception {

        doReturn(false).when(mGceSshMonitor).isTunnelAlive();

        try {
            mTestDevice.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            // expected.
        }
    }

    /**
     * Test {@link RemoteAndroidVirtualDevice#waitForTunnelOnline(long)} throws an exception when
     * the tunnel object is null, meaning something went wrong during its setup.
     */
    @Test
    public void testWaitForTunnelOnline_tunnelTerminated() throws Exception {
        mGceSshMonitor = null;

        try {
            mTestDevice.waitForTunnelOnline(WAIT_FOR_TUNNEL_TIMEOUT);
            fail("Should have thrown an exception.");
        } catch (DeviceNotAvailableException expected) {
            assertEquals(
                    String.format(
                            "Tunnel did not come back online after %sms", WAIT_FOR_TUNNEL_TIMEOUT),
                    expected.getMessage());
        }
    }

    /**
     * Test {@link RemoteAndroidVirtualDevice#preInvocationDeviceSetup(IBuildInfo, MultiMap)} when
     * device is launched and mGceAvdInfo is set.
     */
    @Test
    public void testPreInvocationLaunchedDeviceSetup() throws Exception {
        IBuildInfo mMockBuildInfo = mock(IBuildInfo.class);
        mTestDevice =
                new TestableRemoteAndroidVirtualDevice() {
                    @Override
                    protected void launchGce(
                            IBuildInfo buildInfo, MultiMap<String, String> attributes)
                            throws TargetSetupError {
                        fail("Should not launch a Gce because the device should already launched");
                        // ignore
                    }

                    @Override
                    public IDevice getIDevice() {
                        return mMockIDevice;
                    }

                    @Override
                    public boolean enableAdbRoot() throws DeviceNotAvailableException {
                        return true;
                    }

                    @Override
                    public void startLogcat() {
                        // ignore
                    }

                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }
                };
        when(mMockStateMonitor.waitForDeviceAvailable(Mockito.anyLong())).thenReturn(mMockIDevice);
        when(mMockIDevice.getState()).thenReturn(DeviceState.ONLINE);
        GceAvdInfo mockGceAvdInfo = Mockito.mock(GceAvdInfo.class);
        when(mockGceAvdInfo.getStatus()).thenReturn(GceStatus.SUCCESS);

        mTestDevice.setAvdInfo(mockGceAvdInfo);
        mTestDevice.preInvocationSetup(mMockBuildInfo, null);
        assertEquals(mockGceAvdInfo, mTestDevice.getAvdInfo());
    }

    /** Test that in case of BOOT_FAIL, RemoteAndroidVirtualDevice choose to throw exception. */
    @Test
    public void testLaunchGce_bootFail() throws Exception {
        mTestDevice =
                new TestableRemoteAndroidVirtualDevice() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    void createGceSshMonitor(
                            ITestDevice device,
                            IBuildInfo buildInfo,
                            HostAndPort hostAndPort,
                            TestDeviceOptions deviceOptions) {
                        // ignore
                    }

                    @Override
                    GceManager getGceHandler() {
                        return mGceHandler;
                    }

                    @Override
                    public DeviceDescriptor getDeviceDescriptor() {
                        return null;
                    }
                };
        doReturn(
                        new GceAvdInfo(
                                "ins-name",
                                HostAndPort.fromHost("127.0.0.1"),
                                null,
                                "acloud error",
                                GceStatus.BOOT_FAIL))
                .when(mGceHandler)
                .startGce(null, null, null, null, mTestLogger);

        try {
            mTestDevice.setTestLogger(mTestLogger);
            mTestDevice.launchGce(new BuildInfo(), null);
            fail("Should have thrown an exception");
        } catch (TargetSetupError expected) {
            // expected
        }
    }

    @Test
    public void testGetRemoteTombstone() throws Exception {
        mTestDevice =
                new TestableRemoteAndroidVirtualDevice() {
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
        OptionSetter setter = new OptionSetter(mTestDevice.getOptions());
        setter.setOptionValue(TestDeviceOptions.INSTANCE_TYPE_OPTION, "CUTTLEFISH");

        List<File> tombstones = mTestDevice.getTombstones();
        try {
            assertEquals(2, tombstones.size());
        } finally {
            for (File f : tombstones) {
                FileUtil.deleteFile(f);
            }
        }
    }

    /** Test setAvdInfo() */
    @Test
    public void testSetGceAvdInfo() throws Exception {
        GceAvdInfo mockGceAvdInfo = Mockito.mock(GceAvdInfo.class);
        when(mockGceAvdInfo.getStatus()).thenReturn(GceStatus.SUCCESS);

        assertEquals(null, mTestDevice.getAvdInfo());

        mTestDevice.setAvdInfo(mockGceAvdInfo);
        assertEquals(mockGceAvdInfo, mTestDevice.getAvdInfo());

        try {
            // Attempt override, which is not permitted
            mTestDevice.setAvdInfo(mockGceAvdInfo);
            fail("Should have thrown an exception");
        } catch (TargetSetupError e) {
            // Expected
        }
    }
}
