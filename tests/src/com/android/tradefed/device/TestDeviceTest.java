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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.StreamUtil;

import junit.framework.TestCase;

import org.easymock.EasyMock;
import org.easymock.IAnswer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link TestDevice}.
 */
public class TestDeviceTest extends TestCase {

    private IDevice mMockIDevice;
    private IShellOutputReceiver mMockReceiver;
    private TestDevice mTestDevice;
    private TestDevice mRecoveryTestDevice;
    private TestDevice mNoFastbootTestDevice;
    private IDeviceRecovery mMockRecovery;
    private IDeviceStateMonitor mMockMonitor;
    private IRunUtil mMockRunUtil;
    private IWifiHelper mMockWifi;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockIDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockIDevice.getSerialNumber()).andReturn("serial").anyTimes();
        mMockReceiver = EasyMock.createMock(IShellOutputReceiver.class);
        mMockRecovery = EasyMock.createMock(IDeviceRecovery.class);
        mMockMonitor = EasyMock.createMock(IDeviceStateMonitor.class);
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mMockWifi = EasyMock.createMock(IWifiHelper.class);

        class TestableTestDevice extends TestDevice {
            public TestableTestDevice() {
                super(mMockIDevice, mMockMonitor);
            }

            @Override
            public void postBootSetup() {
                // too annoying to mock out postBootSetup actions everyone, so do nothing
            }

            @Override
            IRunUtil getRunUtil() {
                return mMockRunUtil;
            }

            @Override
            void doReboot() throws DeviceNotAvailableException, UnsupportedOperationException {
            }
        }

        // A TestDevice with a no-op recoverDevice() implementation
        mTestDevice = new TestableTestDevice() {
            @Override
            public void recoverDevice() throws DeviceNotAvailableException {
                // ignore
            }

            @Override
            IWifiHelper createWifiHelper() {
                return mMockWifi;
            }
        };
        mTestDevice.setRecovery(mMockRecovery);
        mTestDevice.setCommandTimeout(100);
        mTestDevice.setLogStartDelay(-1);

        // TestDevice with intact recoverDevice()
        mRecoveryTestDevice = new TestableTestDevice();
        mRecoveryTestDevice.setRecovery(mMockRecovery);
        mRecoveryTestDevice.setCommandTimeout(100);
        mRecoveryTestDevice.setLogStartDelay(-1);

        // TestDevice without fastboot
        mNoFastbootTestDevice = new TestableTestDevice();
        mNoFastbootTestDevice.setFastbootEnabled(false);
        mNoFastbootTestDevice.setRecovery(mMockRecovery);
        mNoFastbootTestDevice.setCommandTimeout(100);
        mNoFastbootTestDevice.setLogStartDelay(-1);
    }

    /**
     * Test {@link TestDevice#enableAdbRoot()} when adb is already root
     */
    public void testEnableAdbRoot_alreadyRoot() throws Exception {
        injectShellResponse("id", "uid=0(root) gid=0(root)");
        EasyMock.replay(mMockIDevice);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Test {@link TestDevice#enableAdbRoot()} when adb is not root
     */
    public void testEnableAdbRoot_notRoot() throws Exception {
        setEnableAdbRootExpectations();
        EasyMock.replay(mMockIDevice, mMockRunUtil, mMockMonitor);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Configure EasyMock expectations for a successful adb root call
     */
    private void setEnableAdbRootExpectations() throws Exception {
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        injectShellResponse("id", "uid=0(root) gid=0(root)");
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("restarting adbd as root");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbResult);
        EasyMock.expect(mMockMonitor.waitForDeviceNotAvailable(EasyMock.anyLong())).andReturn(
                Boolean.TRUE);
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice);
    }

    /**
     * Test that {@link TestDevice#enableAdbRoot()} reattempts adb root
     */
    public void testEnableAdbRoot_rootRetry() throws Exception {
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        injectShellResponse("id", "uid=0(root) gid=0(root)");
        CommandResult adbBadResult = new CommandResult(CommandStatus.SUCCESS);
        adbBadResult.setStdout("");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbBadResult);
        CommandResult adbResult = new CommandResult(CommandStatus.SUCCESS);
        adbResult.setStdout("restarting adbd as root");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                        EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("root"))).andReturn(
                adbResult);
        EasyMock.expect(mMockMonitor.waitForDeviceNotAvailable(EasyMock.anyLong())).andReturn(
                Boolean.TRUE).times(2);
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice).times(2);
        EasyMock.replay(mMockIDevice, mMockRunUtil, mMockMonitor);
        assertTrue(mTestDevice.enableAdbRoot());
    }

    /**
     * Test that {@link TestDevice#isAdbRoot()} for device without adb root.
     */
    public void testIsAdbRootForNonRoot() throws Exception {
        injectShellResponse("id", "uid=2000(shell) gid=2000(shell)");
        EasyMock.replay(mMockIDevice);
        assertFalse(mTestDevice.isAdbRoot());
    }

    /**
     * Test that {@link TestDevice#isAdbRoot()} for device with adb root.
     */
    public void testIsAdbRootForRoot() throws Exception {
        injectShellResponse("id", "uid=0(root) gid=0(root)");
        EasyMock.replay(mMockIDevice);
        assertTrue(mTestDevice.isAdbRoot());
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in fastboot and IDevice has not
     * cached product type property
     */
    public void testGetProductType_fastboot() throws DeviceNotAvailableException {
        EasyMock.expect(mMockIDevice.arePropertiesSet()).andReturn(false);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: nexusone\n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult);
        EasyMock.replay(mMockIDevice, mMockRunUtil);
        mRecoveryTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals("nexusone", mRecoveryTestDevice.getProductType());
    }

    /**
     * Test {@link TestDevice#getProductType()} for a device with a non-alphanumeric fastboot
     * product type
     */
    public void testGetProductType_fastbootNonalpha() throws DeviceNotAvailableException {
        EasyMock.expect(mMockIDevice.arePropertiesSet()).andReturn(false);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: foo-bar\n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult);
        EasyMock.replay(mMockIDevice, mMockRunUtil);
        mRecoveryTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals("foo-bar", mRecoveryTestDevice.getProductType());
    }

    /**
     * Verify that {@link TestDevice#getProductType()} throws an exception if requesting a product
     * type directly fails while the device is in fastboot.
     */
    public void testGetProductType_fastbootFail() throws DeviceNotAvailableException {
        EasyMock.expect(mMockIDevice.arePropertiesSet()).andStubReturn(false);
        CommandResult fastbootResult = new CommandResult();
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        // output of this cmd goes to stderr
        fastbootResult.setStdout("");
        fastbootResult.setStderr("product: \n" + "finished. total time: 0.001s");
        EasyMock.expect(
                mMockRunUtil.runTimedCmd(EasyMock.anyLong(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject(),
                        (String)EasyMock.anyObject(), (String)EasyMock.anyObject())).andReturn(
                fastbootResult).anyTimes();
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRunUtil);
        mTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        try {
            String type = mTestDevice.getProductType();
            fail(String.format("DeviceNotAvailableException not thrown; productType was '%s'",
                    type));
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#getProductType()} when device is in adb and IDevice has not cached
     * product type property
     */
    public void testGetProductType_adb() throws Exception {
        EasyMock.expect(mMockIDevice.arePropertiesSet()).andReturn(false);
        final String expectedOutput = "nexusone";
        EasyMock.expect(mMockIDevice.getPropertyCacheOrSync("ro.hardware")).andReturn(expectedOutput);
        EasyMock.replay(mMockIDevice);
        assertEquals(expectedOutput, mTestDevice.getProductType());
    }

    /**
     * Verify that {@link TestDevice#getProductType()} throws an exception if requesting a product
     * type directly still fails.
     */
    public void testGetProductType_adbFail() throws Exception {
        EasyMock.expect(mMockIDevice.arePropertiesSet()).andStubReturn(false);
        final String expectedOutput = "";
        EasyMock.expect(mMockIDevice.getPropertyCacheOrSync("ro.hardware"))
                .andReturn(expectedOutput)
                .times(3);
        EasyMock.replay(mMockIDevice);
        try {
            mTestDevice.getProductType();
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#clearErrorDialogs()} when both a error and anr dialog are present.
     */
    public void testClearErrorDialogs() throws Exception {
        final String anrOutput = "debugging=false crashing=false null notResponding=true "
                + "com.android.server.am.AppNotRespondingDialog@4534aaa0 bad=false\n blah\n";
        final String crashOutput = "debugging=false crashing=true "
                + "com.android.server.am.AppErrorDialog@45388a60 notResponding=false null bad=false"
                + "blah \n";
        // construct a string with 2 error dialogs of each type to ensure proper detection
        final String fourErrors = anrOutput + anrOutput + crashOutput + crashOutput;
        injectShellResponse(null, fourErrors);
        mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
        // expect 4 key events to be sent - one for each dialog
        // and expect another dialog query - but return nothing
        EasyMock.expectLastCall().times(5);

        EasyMock.replay(mMockIDevice);
        mTestDevice.clearErrorDialogs();
    }

    /**
     * Test the log file size limiting.
     */
    public void testLogCatReceiver() throws IOException, InterruptedException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mTestDevice.setTmpLogcatSize(10);
        final String input = "this is the output of greater than 10 bytes.";
        final String input2 = "this is the second output of greater than 10 bytes.";
        final String input3 = "<10bytes";
        final Object notifier = new Object();
        LogcatReceiver receiver = null;

        // mock the call to get system build id
        EasyMock.expect(mMockIDevice.getProperty((String)EasyMock.anyObject())).andStubReturn("1");

        try {
            IAnswer<Object> shellAnswer = new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                    IShellOutputReceiver receiver =
                        (IShellOutputReceiver)EasyMock.getCurrentArguments()[1];
                    byte[] inputData = input.getBytes();
                    // add log data > maximum. This will trigger a log swap, where inputData
                    // will be moved to the backup log file
                    receiver.addOutput(inputData, 0, inputData.length);
                    // inject the second input data > maximum. This will trigger another log
                    // swap, that will discard inputData. the backup log file will have
                    // inputData2, and the current log file will be empty
                    byte[] inputData2 = input2.getBytes();
                    receiver.addOutput(inputData2, 0, inputData2.length);
                    // inject log data smaller than max log data - that will not trigger a
                    // log swap. The backup log file should contain inputData2, and the
                    // current should contain inputData3
                    byte[] inputData3 = input3.getBytes();
                    receiver.addOutput(inputData3, 0, inputData3.length);
                    synchronized (notifier) {
                        notifier.notify();
                        try {
                          // block until interrupted
                          notifier.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                    return null;
                }
            };
            // expect shell command to be called, with any receiver
            mMockIDevice.executeShellCommand((String)EasyMock.anyObject(), (IShellOutputReceiver)
                    EasyMock.anyObject(), EasyMock.eq(0));
            EasyMock.expectLastCall().andAnswer(shellAnswer);
            EasyMock.replay(mMockIDevice);
            receiver = mTestDevice.createLogcatReceiver();
            receiver.start();
            synchronized (notifier) {
                notifier.wait();
            }
            InputStreamSource iss = receiver.getLogcatData();
            String actualString = "";
            try {
                actualString = StreamUtil.getStringFromStream(iss.createInputStream());
            } finally {
                iss.cancel();
            }
            // verify that data from both the backup log file (input2) and current log file
            // (input3) is retrieved
            assertFalse(actualString.contains(input));
            assertTrue(actualString.contains(input2));
            assertTrue(actualString.contains(input3));
        } finally {
            if (receiver != null) {
                receiver.stop();
            }
        }
    }

    public void testGetBugreport_deviceUnavail() throws Exception {
        final String testCommand = "bugreport";
        final String expectedOutput = "this is the output\r\n in two lines\r\n";
        // FIXME: this isn't actually causing a DeviceNotAvailableException to be thrown
        injectShellResponse(testCommand, expectedOutput);
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());

        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        assertEquals(expectedOutput, StreamUtil.getStringFromStream(
                mTestDevice.getBugreport().createInputStream()));
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice.
     */
    public void testExecuteShellCommand_receiver() throws IOException, DeviceNotAvailableException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        final String testCommand = "simple command";
        // expect shell command to be called
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.replay(mMockIDevice);
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /**
     * Simple normal case test for
     * {@link TestDevice#executeShellCommand(String)}.
     * <p/>
     * Verify that the shell command is routed to the IDevice, and shell output is collected.
     */
    public void testExecuteShellCommand() throws Exception {
        final String testCommand = "simple command";
        final String expectedOutput = "this is the output\r\n in two lines\r\n";
        injectShellResponse(testCommand, expectedOutput);
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
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(mMockIDevice);
        EasyMock.replay(mMockRecovery);
        try {
            mRecoveryTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#executeShellCommand(String, IShellOutputReceiver)} behavior when
     * {@link IDevice} throws IOException and device is in recovery until online mode.
     * <p/>
     * Verify that a DeviceNotAvailableException is thrown.
     */
    public void testExecuteShellCommand_recoveryUntilOnline() throws Exception {
        final String testCommand = "simple command";
        // expect shell command to be called
        mRecoveryTestDevice.setRecoveryMode(RecoveryMode.ONLINE);
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(true));
        setEnableAdbRootExpectations();
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.replay(mMockIDevice, mMockRecovery, mMockRunUtil, mMockMonitor);
        mRecoveryTestDevice.executeShellCommand(testCommand, mMockReceiver);
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
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException());
        assertRecoverySuccess();
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        replayMocks();
        mTestDevice.executeShellCommand(testCommand, mMockReceiver);
    }

    /** Set expectations for a successful recovery operation
     */
    private void assertRecoverySuccess() throws DeviceNotAvailableException, IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        // expect post boot up steps
        mMockIDevice.executeShellCommand(EasyMock.eq(mTestDevice.getDisableKeyguardCmd()),
                (IShellOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt());
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
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new TimeoutException());
        assertRecoverySuccess();
        // now expect shellCommand to be executed again, and succeed
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        replayMocks();
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
        mMockIDevice.executeShellCommand(EasyMock.eq(testCommand), EasyMock.eq(mMockReceiver),
                EasyMock.anyInt());
        EasyMock.expectLastCall().andThrow(new IOException()).times(
                TestDevice.MAX_RETRY_ATTEMPTS+1);
        for (int i=0; i <= TestDevice.MAX_RETRY_ATTEMPTS; i++) {
            assertRecoverySuccess();
        }
        replayMocks();
        try {
            mTestDevice.executeShellCommand(testCommand, mMockReceiver);
            fail("DeviceUnresponsiveException not thrown");
        } catch (DeviceUnresponsiveException e) {
            // expected
        }
    }

    /**
     * Puts all the mock objects into replay mode
     */
    private void replayMocks() {
        EasyMock.replay(mMockIDevice, mMockRecovery, mMockMonitor, mMockRunUtil, mMockWifi);
    }

    /**
     * Verify all the mock objects
     */
    private void verifyMocks() {
        EasyMock.verify(mMockIDevice, mMockRecovery, mMockMonitor, mMockRunUtil, mMockWifi);
    }


    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify that output of 'adb shell df' command is parsed correctly.
     */
    public void testGetExternalStoreFreeSpace() throws Exception {
        final String dfOutput =
            "/mnt/sdcard: 3864064K total, 1282880K used, 2581184K available (block size 32768)";
        assertGetExternalStoreFreeSpace(dfOutput, 2581184);
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify that the table-based output of 'adb shell df' command is parsed correctly.
     */
    public void testGetExternalStoreFreeSpace_table() throws Exception {
        final String dfOutput =
            "Filesystem             Size   Used   Free   Blksize\n" +
            "/mnt/sdcard              3G   787M     2G   4096";
        assertGetExternalStoreFreeSpace(dfOutput, 2 * 1024 * 1024);
    }

    /**
     * Unit test for {@link TestDevice#getExternalStoreFreeSpace()}.
     * <p/>
     * Verify behavior when 'df' command returns unexpected content
     */
    public void testGetExternalStoreFreeSpace_badOutput() throws Exception {
        final String dfOutput =
            "/mnt/sdcard: blaH";
        assertGetExternalStoreFreeSpace(dfOutput, 0);
    }

    /**
     * Helper method to verify the {@link TestDevice#getExternalStoreFreeSpace()} method under
     * different conditions.
     *
     * @param dfOutput the test output to inject
     * @param expectedFreeSpaceKB the expected free space
     */
    private void assertGetExternalStoreFreeSpace(final String dfOutput, long expectedFreeSpaceKB)
            throws Exception {
        final String mntPoint = "/mnt/sdcard";
        final String expectedCmd = "df " + mntPoint;
        EasyMock.expect(mMockMonitor.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE)).andReturn(
                mntPoint);
        // expect shell command to be called, and return the test df output
        injectShellResponse(expectedCmd, dfOutput);
        EasyMock.replay(mMockIDevice, mMockMonitor);
        assertEquals(expectedFreeSpaceKB, mTestDevice.getExternalStoreFreeSpace());
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
        EasyMock.expect(mockRunner.getPackageName()).andStubReturn("com.example");
        Collection<ITestRunListener> listeners = new ArrayList<ITestRunListener>(0);
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
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
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        mMockRecovery.recoverDevice(EasyMock.eq(mMockMonitor), EasyMock.eq(false));
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        try {
            mRecoveryTestDevice.runInstrumentationTests(mockRunner, listeners);
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
        mockRunner.setMaxtimeToOutputResponse(EasyMock.anyInt());
        mockRunner.run(listeners);
        EasyMock.expectLastCall().andThrow(new IOException());
        EasyMock.expect(mockRunner.getPackageName()).andReturn("foo");
        listener.testRunFailed((String)EasyMock.anyObject());
        assertRecoverySuccess();
        EasyMock.replay(listener, mockRunner, mMockIDevice, mMockRecovery);
        mTestDevice.runInstrumentationTests(mockRunner, listeners);
    }

    /**
     * Test {@link TestDevice#executeFastbootCommand(String...)} throws an exception when fastboot
     * is not available.
     */
    public void testExecuteFastbootCommand_nofastboot() throws Exception {
        try {
            mNoFastbootTestDevice.executeFastbootCommand("");
            fail("UnsupportedOperationException not thrown");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    /**
     * Test {@link TestDevice#executeLongFastbootCommand(String...)} throws an exception when
     * fastboot is not available.
     */
    public void testExecuteLongFastbootCommand_nofastboot() throws Exception {
        try {
            mNoFastbootTestDevice.executeFastbootCommand("");
            fail("UnsupportedOperationException not thrown");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    /**
     * Test that state changes are ignore while {@link TestDevice#executeFastbootCommand(String...)}
     * is active.
     * @throws InterruptedException
     */
    public void testExecuteFastbootCommand_state() throws InterruptedException {
        // build a fastboot response that will block
        IAnswer<CommandResult> blockResult = new IAnswer<CommandResult>() {
            @Override
            public CommandResult answer() throws Throwable {
                synchronized(this) {
                    // first inform this test that fastboot cmd is executing
                    notifyAll();
                    // now wait for test to unblock us when its done testing logic
                    wait(1000);
                }
                return new CommandResult(CommandStatus.SUCCESS);
            }
        };
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("fastboot"),
                EasyMock.eq("-s"),EasyMock.eq("serial"), EasyMock.eq("foo"))).andAnswer(
                        blockResult);

        // expect
        mMockMonitor.setState(TestDeviceState.FASTBOOT);
        mMockMonitor.setState(TestDeviceState.NOT_AVAILABLE);
        replayMocks();

        mTestDevice.setDeviceState(TestDeviceState.FASTBOOT);
        assertEquals(TestDeviceState.FASTBOOT, mTestDevice.getDeviceState());

        // start fastboot command in background thread
        Thread fastbootThread = new Thread() {
            @Override
            public void run() {
                try {
                    mTestDevice.executeFastbootCommand("foo");
                } catch (DeviceNotAvailableException e) {
                    CLog.e(e);
                }
            }
        };
        fastbootThread.start();
        try {
            synchronized (blockResult) {
                blockResult.wait(1000);
            }
            // expect to ignore this
            mTestDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
            assertEquals(TestDeviceState.FASTBOOT, mTestDevice.getDeviceState());
        } finally {
            synchronized (blockResult) {
                blockResult.notifyAll();
            }
        }
        fastbootThread.join();
        mTestDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
        assertEquals(TestDeviceState.NOT_AVAILABLE, mTestDevice.getDeviceState());
    }

    /**
     * Test recovery mode is entered when fastboot command fails
     */
    public void testExecuteFastbootCommand_recovery() throws UnsupportedOperationException,
           DeviceNotAvailableException {
        CommandResult result = new CommandResult(CommandStatus.EXCEPTION);
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("fastboot"),
                EasyMock.eq("-s"),EasyMock.eq("serial"), EasyMock.eq("foo"))).andReturn(result);
        mMockRecovery.recoverDeviceBootloader((IDeviceStateMonitor)EasyMock.anyObject());
        CommandResult successResult = new CommandResult(CommandStatus.SUCCESS);
        successResult.setStderr("");
        successResult.setStdout("");
        // now expect a successful retry
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("fastboot"),
                EasyMock.eq("-s"),EasyMock.eq("serial"), EasyMock.eq("foo"))).andReturn(
                        successResult);
        replayMocks();
        mTestDevice.executeFastbootCommand("foo");
        verifyMocks();

    }

    /**
     * Basic test for encryption if encryption is not supported.
     * <p>
     * Calls {@link TestDevice#encryptDevice(boolean)}, {@link TestDevice#unlockDevice()}, and
     * {@link TestDevice#unencryptDevice()} and makes sure that a
     * {@link UnsupportedOperationException} is thrown for each method.
     * </p>
     */
    public void testEncryptionUnsupported() throws Exception {
        setEncryptedUnsupportedExpectations();
        setEncryptedUnsupportedExpectations();
        setEncryptedUnsupportedExpectations();
        EasyMock.replay(mMockIDevice, mMockRunUtil, mMockMonitor);

        try {
            mTestDevice.encryptDevice(false);
            fail("encryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        try {
            mTestDevice.unlockDevice();
            fail("decryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        try {
            mTestDevice.unencryptDevice();
            fail("unencryptUserData() did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
        return;
    }

    /**
     * Configure EasyMock for a encryption check call, that returns that encryption is unsupported
     */
    private void setEncryptedUnsupportedExpectations() throws Exception {
        setEnableAdbRootExpectations();
        injectShellResponse("vdc cryptfs enablecrypto", "\r\n");
    }

    /**
     * Simple test for {@link TestDevice#switchToAdbUsb()}
     */
    public void testSwitchToAdbUsb() throws Exception  {
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("usb"))).andReturn(
                        new CommandResult(CommandStatus.SUCCESS));
        replayMocks();
        mTestDevice.switchToAdbUsb();
        verifyMocks();
    }

    /**
     * Test for {@link TestDevice#switchToAdbTcp()} when device has no ip address
     */
    public void testSwitchToAdbTcp_noIp() throws Exception {
        EasyMock.expect(mMockWifi.getIpAddress()).andReturn(null);
        replayMocks();
        assertNull(mTestDevice.switchToAdbTcp());
        verifyMocks();
    }

    /**
     * Test normal success case for {@link TestDevice#switchToAdbTcp()}.
     */
    public void testSwitchToAdbTcp() throws Exception {
        EasyMock.expect(mMockWifi.getIpAddress()).andReturn("ip");
        EasyMock.expect(mMockRunUtil.runTimedCmd(EasyMock.anyLong(), EasyMock.eq("adb"),
                EasyMock.eq("-s"), EasyMock.eq("serial"), EasyMock.eq("tcpip"),
                EasyMock.eq("5555"))).andReturn(
                        new CommandResult(CommandStatus.SUCCESS));
        replayMocks();
        assertEquals("ip:5555", mTestDevice.switchToAdbTcp());
        verifyMocks();
    }

    /**
     * Test simple success case for
     * {@link TestDevice#installPackage(File, File, boolean, String...)}.
     */
    public void testInstallPackages() throws Exception {
        final String certFile = "foo.dc";
        final String apkFile = "foo.apk";
        EasyMock.expect(mMockIDevice.syncPackageToDevice(EasyMock.contains(certFile))).andReturn(
                certFile);
        EasyMock.expect(mMockIDevice.syncPackageToDevice(EasyMock.contains(apkFile))).andReturn(
                apkFile);
        // expect apk path to be passed as extra arg
        EasyMock.expect(
                mMockIDevice.installRemotePackage(EasyMock.eq(certFile), EasyMock.eq(true),
                        EasyMock.eq("-l"), EasyMock.contains(apkFile))).andReturn(null);
        mMockIDevice.removeRemotePackage(certFile);
        mMockIDevice.removeRemotePackage(apkFile);

        replayMocks();

        assertNull(mTestDevice.installPackage(new File(apkFile), new File(certFile), true, "-l"));
    }

    /**
     * Helper method to build a response to a executeShellCommand call
     *
     * @param expectedCommand the shell command to expect or null to skip verification of command
     * @param response the response to simulate
     */
    @SuppressWarnings("unchecked")
    private void injectShellResponse(final String expectedCommand, final String response)
            throws Exception {
        IAnswer shellAnswer = new IAnswer() {
            @Override
            public Object answer() throws Throwable {
                IShellOutputReceiver receiver =
                    (IShellOutputReceiver)EasyMock.getCurrentArguments()[1];
                byte[] inputData = response.getBytes();
                receiver.addOutput(inputData, 0, inputData.length);
                return null;
            }
        };
        if (expectedCommand != null) {
            mMockIDevice.executeShellCommand(EasyMock.eq(expectedCommand),
                    (IShellOutputReceiver)EasyMock.anyObject(),
                    EasyMock.anyInt());
        } else {
            mMockIDevice.executeShellCommand((String)EasyMock.anyObject(),
                    (IShellOutputReceiver)EasyMock.anyObject(),
                    EasyMock.anyInt());

        }
        EasyMock.expectLastCall().andAnswer(shellAnswer);
    }

    /**
     * Test normal success case for {@link TestDevice#reboot()}
     */
    public void testReboot() throws Exception {
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice);
        setEnableAdbRootExpectations();
        setEncryptedUnsupportedExpectations();
        EasyMock.expect(mMockMonitor.waitForDeviceAvailable(EasyMock.anyLong())).andReturn(
                mMockIDevice);
        replayMocks();
        mTestDevice.reboot();
        verifyMocks();
    }

    /**
     * Test {@link TestDevice#reboot()} attempts a recovery upon failure
     */
    public void testRebootRecovers() throws Exception {
        EasyMock.expect(mMockMonitor.waitForDeviceOnline()).andReturn(
                mMockIDevice);
        setEnableAdbRootExpectations();
        setEncryptedUnsupportedExpectations();
        EasyMock.expect(mMockMonitor.waitForDeviceAvailable(EasyMock.anyLong())).andReturn(null);
        mMockRecovery.recoverDevice(mMockMonitor, false);
        replayMocks();
        mRecoveryTestDevice.reboot();
        verifyMocks();
    }

    /**
     * Unit test for {@link TestDevice#getInstalledPackageNames()}.
     */
    public void testgetInstalledPackageNames() throws Exception {
        final String output = "package:com.android.wallpaper\n"
                + "package:com.android.wallpaper.livepickler";
        Set<String> expected = new HashSet<String>();
        expected.add("com.android.wallpaper");
        expected.add("com.android.wallpaper.livepickler");
        assertgetInstalledPackageNames(output, expected);
    }

    /**
     * Unit test for {@link TestDevice#getInstalledPackageNames()}.
     * <p/>
     * Test bad bad output.
     */
    public void testgetInstalledPackageNamesForBadOutput() throws Exception {
        final String output = "junk output";
        Set<String> expected = new HashSet<String>();
        assertgetInstalledPackageNames(output, expected);
    }

    /**
     * Helper method to verify the {@link TestDevice#getInstalledPackageNames()} method under
     * different conditions.
     *
     * @param output the test output to inject
     * @param expectedPackages the expected {@link Set} of packages to expect
     */
    private void assertgetInstalledPackageNames(final String output, Set<String> expectedPackages)
            throws Exception {
        final String expectedCmd = "pm list packages";
        // expect shell command to be called, and return the test shell output
        injectShellResponse(expectedCmd, output);
        EasyMock.replay(mMockIDevice, mMockMonitor);
        assertEquals(expectedPackages, mTestDevice.getInstalledPackageNames());
    }
}

