/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tradefed.targetprep;

import static org.junit.Assert.fail;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;

/** Unit tests for {@link GkiDevicePreparer}. */
@RunWith(JUnit4.class)
public class GkiDeviceFlashPreparerTest {

    private IDeviceManager mMockDeviceManager;
    private IDeviceFlasher mMockFlasher;
    private GkiDeviceFlashPreparer mPreparer;
    private ITestDevice mMockDevice;
    private IDeviceBuildInfo mBuildInfo;
    private File mTmpDir;
    private TestInformation mTestInfo;
    private OptionSetter mOptionSetter;
    private CommandResult mSuccessResult;
    private CommandResult mFailureResult;
    private IRunUtil mMockRunUtil;
    private DeviceDescriptor mDeviceDescriptor;

    @Before
    public void setUp() throws Exception {
        mDeviceDescriptor =
                new DeviceDescriptor(
                        "serial_1",
                        false,
                        DeviceAllocationState.Available,
                        "unknown",
                        "unknown",
                        "unknown",
                        "unknown",
                        "unknown");
        mMockDeviceManager = EasyMock.createMock(IDeviceManager.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("serial_1");
        EasyMock.expect(mMockDevice.getDeviceDescriptor()).andStubReturn(mDeviceDescriptor);
        EasyMock.expect(mMockDevice.getOptions()).andReturn(new TestDeviceOptions()).anyTimes();
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mPreparer =
                new GkiDeviceFlashPreparer() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    IDeviceManager getDeviceManager() {
                        return mMockDeviceManager;
                    }
                };
        // Reset default settings
        mOptionSetter = new OptionSetter(mPreparer);
        mTmpDir = FileUtil.createTempDir("tmp");
        mBuildInfo = new DeviceBuildInfo("0", "");
        mBuildInfo.setBuildFlavor("flavor");
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        context.addDeviceBuildInfo("device", mBuildInfo);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
        mSuccessResult = new CommandResult(CommandStatus.SUCCESS);
        mSuccessResult.setStderr("OKAY [  0.043s]");
        mSuccessResult.setStdout("");
        mFailureResult = new CommandResult(CommandStatus.FAILED);
        mFailureResult.setStderr("FAILED (remote: 'Partition error')");
        mFailureResult.setStdout("");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mTmpDir);
    }

    /** Set EasyMock expectations for a normal setup call */
    private void doSetupExpectations() throws Exception {
        mMockRunUtil.sleep(EasyMock.anyLong());
        mMockDevice.rebootUntilOnline();
        EasyMock.expect(mMockDevice.enableAdbRoot()).andStubReturn(Boolean.TRUE);
        mMockDevice.setDate(null);
        mMockDevice.waitForDeviceAvailable(EasyMock.anyLong());
        mMockDevice.setRecoveryMode(RecoveryMode.AVAILABLE);
        mMockDevice.postBootSetup();
    }

    /* Verifies that preparer will throw exception when there is no valid GKI boot.img*/
    @Test
    public void testNoValidGkiImage() throws Exception {
        try {
            mPreparer.setUp(mTestInfo);
            fail("TargetSetupError is expected");
        } catch (TargetSetupError e) {
            // expected
            mPreparer.tearDown(mTestInfo, e);
        }
    }

    /* Verifies that preparer can flash GKI boot image */
    @Test
    public void testSetup_Success() throws Exception {
        File bootImg = FileUtil.createTempFile("boot", ".img", mTmpDir);
        bootImg.renameTo(new File(mTmpDir, "boot.img"));
        FileUtil.writeToFile("ddd", bootImg);
        mBuildInfo.setFile("gki_boot.img", bootImg, "0");
        mMockDevice.waitForDeviceOnline();
        mMockDevice.rebootIntoBootloader();
        mMockRunUtil.allowInterrupt(false);
        EasyMock.expect(
                        mMockDevice.executeLongFastbootCommand(
                                "flash",
                                "boot",
                                mBuildInfo.getFile("gki_boot.img").getAbsolutePath()))
                .andReturn(mSuccessResult);
        mMockRunUtil.allowInterrupt(true);
        doSetupExpectations();
        EasyMock.replay(mMockDevice, mMockRunUtil);
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, null);
        EasyMock.verify(mMockDevice, mMockRunUtil);
    }

    /* Verifies that preparer will throw TargetSetupError with GKI flash failure*/
    @Test
    public void testSetUp_GkiFlashFailure() throws Exception {
        File bootImg = FileUtil.createTempFile("boot", ".img", mTmpDir);
        bootImg.renameTo(new File(mTmpDir, "boot.img"));
        FileUtil.writeToFile("ddd", bootImg);
        mBuildInfo.setFile("gki_boot.img", bootImg, "0");
        File deviceImg = FileUtil.createTempFile("device_image", ".zip", mTmpDir);
        FileUtil.writeToFile("not an empty file", deviceImg);
        mBuildInfo.setDeviceImageFile(deviceImg, "0");
        mMockDevice.waitForDeviceOnline();
        mMockDevice.rebootIntoBootloader();
        mMockRunUtil.allowInterrupt(false);
        EasyMock.expect(
                        mMockDevice.executeLongFastbootCommand(
                                "flash",
                                "boot",
                                mBuildInfo.getFile("gki_boot.img").getAbsolutePath()))
                .andReturn(mFailureResult);
        mMockRunUtil.allowInterrupt(true);
        EasyMock.replay(mMockDevice, mMockRunUtil);
        try {
            mPreparer.setUp(mTestInfo);
            fail("Expect to get TargetSetupError from setUp");
        } catch (TargetSetupError e) {
            // expected
        }
        EasyMock.verify(mMockDevice, mMockRunUtil);
    }

    /* Verifies that preparer will throw DeviceNotAvailableException if device fails to boot up */
    @Test
    public void testSetUp_BootFailure() throws Exception {
        File bootImg = FileUtil.createTempFile("boot", ".img", mTmpDir);
        bootImg.renameTo(new File(mTmpDir, "boot.img"));
        FileUtil.writeToFile("ddd", bootImg);
        mBuildInfo.setFile("gki_boot.img", bootImg, "0");
        File deviceImg = FileUtil.createTempFile("device_image", ".zip", mTmpDir);
        FileUtil.writeToFile("not an empty file", deviceImg);
        mBuildInfo.setDeviceImageFile(deviceImg, "0");
        mMockDevice.waitForDeviceOnline();
        mMockDevice.rebootIntoBootloader();
        mMockRunUtil.allowInterrupt(false);
        EasyMock.expect(
                        mMockDevice.executeLongFastbootCommand(
                                "flash",
                                "boot",
                                mBuildInfo.getFile("gki_boot.img").getAbsolutePath()))
                .andReturn(mSuccessResult);
        mMockRunUtil.allowInterrupt(true);
        mMockRunUtil.sleep(EasyMock.anyLong());
        mMockDevice.rebootUntilOnline();
        EasyMock.expectLastCall().andThrow(new DeviceNotAvailableException());
        EasyMock.replay(mMockDevice, mMockRunUtil);
        try {
            mPreparer.setUp(mTestInfo);
            fail("Expect to get DeviceNotAvailableException from setUp");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
        EasyMock.verify(mMockDevice, mMockRunUtil);
    }
}
