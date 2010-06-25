/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.ddmlib.Client;
import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.log.LogReceiver;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import org.easymock.EasyMock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import junit.framework.TestCase;

/**
 * Unit tests for {@link TestDevice}.
 */
public class TestDeviceTest extends TestCase {

    private IDevice mMockIDevice;
    private ICancelableReceiver mMockReceiver;
    private TestDevice mTestDevice;
    private IDeviceRecovery mMockRecovery;
    private IDeviceStateMonitor mMockMonitor;
    private IRunUtil mMockRunUtil;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockIDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockIDevice.getSerialNumber()).andReturn("serial").anyTimes();
        mMockReceiver = EasyMock.createMock(ICancelableReceiver.class);
        mMockRecovery = EasyMock.createMock(IDeviceRecovery.class);
        mMockMonitor = EasyMock.createMock(IDeviceStateMonitor.class);
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mTestDevice = new TestDevice(mMockIDevice, mMockRecovery, mMockMonitor);
        mTestDevice.setCommandTimeout(100);
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in fastboot and IDevice has not
     * cached product type property
     */
    public void testGetProductType_fastboot() throws DeviceNotAvailableException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        fastbootResult.setStdout("product: nexusone\n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult);
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRunUtil);
        TestDevice testDevice = new TestDevice(mMockIDevice, mMockRecovery, mMockMonitor) {
            @Override
            IRunUtil getRunUtil() {
                return mMockRunUtil;
            }
        };
        testDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals("nexusone", testDevice.getProductType());
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in adb and IDevice has not cached
     * product type property
     */
    public void testGetProductType_adb() throws DeviceNotAvailableException, IOException {
        mMockIDevice.getProperty((String)EasyMock.anyObject());
        EasyMock.expectLastCall().andReturn((String)null);
        final String expectedOutput = "nexusone";
        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                byte[] inputData = expectedOutput.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRunUtil);
        assertEquals(expectedOutput, mTestDevice.getProductType());
    }

    /**
     * Test {@link TestDevice#clearErrorDialogs()} when both a error and anr dialog are present.
     */
    public void testClearErrorDialogs() throws IOException, DeviceNotAvailableException {
        final String anrOutput = "debugging=false crashing=false null notResponding=true "
                + "com.android.server.am.AppNotRespondingDialog@4534aaa0 bad=false\n blah\n";
        final String crashOutput = "debugging=false crashing=true "
                + "com.android.server.am.AppErrorDialog@45388a60 notResponding=false null bad=false"
                + "blah \n";
        // construct a string with 2 error dialogs of each type to ensure proper detection
        final String fourErrors = anrOutput + anrOutput + crashOutput + crashOutput;
        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject());
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                byte[] inputData = fourErrors.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
            }
        });

        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject());
        // expect 4 key events to be sent - one for each dialog
        // and expect another dialog query - but return nothing
        EasyMock.expectLastCall().times(5);

        EasyMock.replay(mMockIDevice);
        mTestDevice.clearErrorDialogs();
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice.
     */
    public void testExecuteShellCommand_receiver() throws IOException, DeviceNotAvailableException {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.replay(mMockIDevice);
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice, and shell output is collected.
     */
    public void testExecuteShellCommand() throws IOException, DeviceNotAvailableException {
        final String testCommand = "simple command";
        final String expectedOutput = "this is the output\r\n in two lines\r\n";

        // expect shell command to be called, with any receiver
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), (IShellOutputReceiver)
                EasyMock.anyObject());
        EasyMock.expectLastCall().andDelegateTo(
              new MockDevice() {
                  @Override
                  public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                      byte[] inputData = expectedOutput.getBytes();
                      receiver.addOutput(inputData, 0, inputData.length);
                  }
              });
        EasyMock.replay(mMockIDevice);
        assertEquals(expectedOutput, mTestDevice.executeShellCommand(testCommand));
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and recovery immediately fails.
     * <p/>
     * Verify that a DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryFail() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        try {
            mTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and recovery succeeds.
     * <p/>
     * Verify that command is re-tried.
     */
    public void testExecuteShellCommand_recoveryRetry() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.expectLastCall();
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall();
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * command times out and recovery succeeds.
     * <p/>
     * Verify that command is re-tried.
     */
    public void testExecuteShellCommand_recoveryTimeoutRetry() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called - and never return from that call
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall().andDelegateTo(new MockDevice() {
            @Override
            public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                RunUtil.getInstance().sleep(1000);
            }
        });
        mMockReceiver.cancel();
        EasyMock.expectLastCall();
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.expectLastCall();
        // now expect shellCommand to be executed again, and succeed
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall();
        EasyMock.replay(mMockIDevice, mMockRecovery, mMockReceiver);
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} repeatedly throws IOException and recovery succeeds.
     * <p/>
     * Verify that DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryAttempts() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(testCommand, mMockReceiver);
        EasyMock.expectLastCall().andThrow(new IOException()).anyTimes();
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        try {
            mTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify that output of 'adb shell df' command is parsed correctly.
     */
    public void testGetExternalStoreFreeSpace() throws Exception {
        final String mntPoint = "/mnt/sdcard";
        final String expectedCmd = "df " + mntPoint;
        final String dfOutput =
            "/mnt/sdcard: 3864064K total, 1282880K used, 2581184K available (block size 32768)";

        EasyMock.expect(mMockIDevice.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE)).andReturn(
                mntPoint);
        // expect shell command to be called, and return the test df output
        mMockIDevice.executeShellCommand(EasyMock.eq(expectedCmd), (IShellOutputReceiver)
                EasyMock.anyObject());
        EasyMock.expectLastCall().andDelegateTo(
              new MockDevice() {
                  @Override
                  public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                      byte[] inputData = dfOutput.getBytes();
                      receiver.addOutput(inputData, 0, inputData.length);
                  }
              });
        EasyMock.replay(mMockIDevice);
        assertEquals(2581184, mTestDevice.getExternalStoreFreeSpace());
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify behavior when 'df' command returns unexpected content
     */
    public void testGetExternalStoreFreeSpace_badOutput() throws Exception {
        final String mntPoint = "/mnt/sdcard";
        final String expectedCmd = "df " + mntPoint;
        final String dfOutput =
            "/mnt/sdcard: blaH";

        EasyMock.expect(mMockIDevice.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE)).andReturn(
                mntPoint);
        // expect shell command to be called, and return the test df output
        mMockIDevice.executeShellCommand(EasyMock.eq(expectedCmd), (IShellOutputReceiver)
                EasyMock.anyObject());
        EasyMock.expectLastCall().andDelegateTo(
              new MockDevice() {
                  @Override
                  public void executeShellCommand(String cmd, IShellOutputReceiver receiver) {
                      byte[] inputData = dfOutput.getBytes();
                      receiver.addOutput(inputData, 0, inputData.length);
                  }
              });
        EasyMock.replay(mMockIDevice);
        assertEquals(0, mTestDevice.getExternalStoreFreeSpace());
    }

    /**
     * Unit test for {@link TestDevice#syncFiles)}.
     * <p/>
     * Verify behavior when given local file does not exist
     */
    public void testSyncFiles_missingLocal() throws Exception {
        EasyMock.replay(mMockIDevice);
        assertFalse(mTestDevice.syncFiles(new File("idontexist"), "/sdcard"));
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * success case.
     */
    public void testRunInstrumentationTests() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(0);
        // expect runner.run command to be called
        mockRunner.run(listeners);
        EasyMock.replay(mockRunner);
        mTestDevice.runInstrumentationTests(mockRunner, listeners);
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * when recovery fails.
     */
    public void testRunInstrumentationTests_recoveryFails() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(1);
        ITestRunListener listener = EasyMock.createMock(ITestRunListener.class);
        listeners.add(listener);
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        try {
            mTestDevice.runInstrumentationTests(mockRunner, listeners);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, Collection)}
     * when recovery succeeds.
     */
    public void testRunInstrumentationTests_recoverySucceeds() throws Exception {
        IRemoteAndroidTestRunner mockRunner = EasyMock.createMock(IRemoteAndroidTestRunner.class);
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(1);
        ITestRunListener listener = EasyMock.createMock(ITestRunListener.class);
        listeners.add(listener);
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        mMockRecovery.recoverDevice(mMockMonitor);
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        mTestDevice.runInstrumentationTests(mockRunner, listeners);
    }

    /**
     * Test that state changes are ignore while {@link TestDevice#executeFastbootCommand(String...)}
     * is active.
     */
    public void testExecuteFastbootCommand_state() {
        // TODO: implement this when RunUtil.runTimedCommand can be mocked
    }

    /**
     * Concrete mock implementation of {@link IDevice}.
     * <p/>
     * Needed in order to handle the EasyMock andDelegateTo operation.
     */
    private static class MockDevice implements IDevice {

        public boolean createForward(int localPort, int remotePort) {
            return false;
        }

        public void executeShellCommand(String command, IShellOutputReceiver receiver)
                throws IOException {
        }

        public String getAvdName() {
            return null;
        }

        public Client getClient(String applicationName) {
            return null;
        }

        public String getClientName(int pid) {
            return null;
        }

        public Client[] getClients() {
            return null;
        }

        public FileListingService getFileListingService() {
            return null;
        }

        public Map<String, String> getProperties() {
            return null;
        }

        public String getProperty(String name) {
            return null;
        }

        public int getPropertyCount() {
            return 0;
        }

        public String getMountPoint(String name) {
            return null;
        }

        public RawImage getScreenshot() throws IOException {
            return null;
        }

        public String getSerialNumber() {
            return null;
        }

        public DeviceState getState() {
            return null;
        }

        public SyncService getSyncService() throws IOException {
            return null;
        }

        public boolean hasClients() {
            return false;
        }

        public String installPackage(String packageFilePath, boolean reinstall) throws IOException {
            return null;
        }

        public String installRemotePackage(String remoteFilePath, boolean reinstall)
                throws IOException {
            return null;
        }

        public boolean isBootLoader() {
            return false;
        }

        public boolean isEmulator() {
            return false;
        }

        public boolean isOffline() {
            return false;
        }

        public boolean isOnline() {
            return false;
        }

        public boolean removeForward(int localPort, int remotePort) {
            return false;
        }

        public void removeRemotePackage(String remoteFilePath) throws IOException {
        }

        public void runEventLogService(LogReceiver receiver) throws IOException {
        }

        public void runLogService(String logname, LogReceiver receiver) throws IOException {
        }

        public String syncPackageToDevice(String localFilePath) throws IOException {
            return null;
        }

        public String uninstallPackage(String packageName) throws IOException {
            return null;
        }
        public void reboot(String into) throws IOException {
        }
    }
}
