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

package com.android.tradefed.targetprep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.targetprep.IDeviceFlasher.UserDataFlashOption;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;

@RunWith(JUnit4.class)
public class OtaUpdateDeviceFlasherTest {
    private OtaUpdateDeviceFlasher mFlasher = null;
    @Mock private ITestDevice mMockDevice;
    @Mock private IRunUtil mMockRunUtil;
    private InOrder mInOrder;

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mMockDevice, mMockRunUtil);
        mFlasher =
                new OtaUpdateDeviceFlasher() {
                    @Override
                    protected IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }
                };
    }

    @Test
    public void testUserDataWipeOptions_wipe() throws Exception {
        mFlasher.setUserDataFlashOption(UserDataFlashOption.TESTS_ZIP);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
        mFlasher.setUserDataFlashOption(UserDataFlashOption.WIPE_RM);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
        mFlasher.setUserDataFlashOption(UserDataFlashOption.WIPE);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
        mFlasher.setUserDataFlashOption(UserDataFlashOption.FLASH);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
        mFlasher.setUserDataFlashOption(UserDataFlashOption.FLASH_IMG_ZIP);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
        mFlasher.setUserDataFlashOption(UserDataFlashOption.FORCE_WIPE);
        assertEquals(
                "Any userdata option that's not retain should be reset to wipe",
                UserDataFlashOption.WIPE,
                mFlasher.getUserDataFlashOption());
    }

    @Test
    public void testUserDataWipeOptions_retain() throws Exception {
        mFlasher.setUserDataFlashOption(UserDataFlashOption.RETAIN);
        assertEquals(
                "RETAIN userdata option should not be changed",
                UserDataFlashOption.RETAIN,
                mFlasher.getUserDataFlashOption());
    }

    @Test(expected = TargetSetupError.class)
    public void testUpdateScriptNotSet() throws Exception {
        DeviceBuildInfo dbi = new DeviceBuildInfo();
        mFlasher.preFlashOperations(mMockDevice, dbi);
        fail("Device build info without update-device-script set should cause an exception");
    }

    @Test(expected = TargetSetupError.class)
    public void testInvalidUpdateScriptSet() throws Exception {
        DeviceBuildInfo dbi = new DeviceBuildInfo();
        File tmpFolder = mTempFolder.getRoot();
        dbi.setFile(
                OtaUpdateDeviceFlasher.UPDATE_DEVICE_SCRIPT, new File(tmpFolder, "foo-bar"), "0");
        mFlasher.preFlashOperations(mMockDevice, dbi);
    }

    @Test(expected = TargetSetupError.class)
    public void testOtaPackageNotSet() throws Exception {
        DeviceBuildInfo dbi = new DeviceBuildInfo();
        File fakeScript =
                new File(mTempFolder.getRoot(), OtaUpdateDeviceFlasher.IN_ZIP_SCRIPT_PATH);
        assertTrue(
                "Failed to create temp parent folder for fake script for test",
                fakeScript.getParentFile().mkdirs());
        assertTrue("Failed to create fake script for test", fakeScript.createNewFile());
        dbi.setFile(OtaUpdateDeviceFlasher.UPDATE_DEVICE_SCRIPT, mTempFolder.getRoot(), "0");
        mFlasher.preFlashOperations(mMockDevice, dbi);
    }

    private IDeviceBuildInfo setupDeviceBuildInfoForOta() throws Exception {
        IDeviceBuildInfo dbi = new DeviceBuildInfo();
        File fakeScript =
                new File(mTempFolder.getRoot(), OtaUpdateDeviceFlasher.IN_ZIP_SCRIPT_PATH);
        assertTrue(
                "Failed to create temp parent folder for fake script for test",
                fakeScript.getParentFile().mkdirs());
        assertTrue("Failed to create fake script for test", fakeScript.createNewFile());
        dbi.setFile(OtaUpdateDeviceFlasher.UPDATE_DEVICE_SCRIPT, mTempFolder.getRoot(), "0");
        dbi.setFile(BuildInfoFileKey.OTA_IMAGE, mTempFolder.newFile(), "0");
        return dbi;
    }

    @Test
    public void testPreFlashOperations() throws Exception {
        mFlasher.preFlashOperations(mMockDevice, setupDeviceBuildInfoForOta());
    }

    @Test
    public void testFlash_success() throws Exception {
        // prep
        mFlasher.setUserDataFlashOption(UserDataFlashOption.WIPE);
        when(mMockDevice.enableAdbRoot()).thenReturn(true);
        when(mMockDevice.setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1")))
                .thenReturn(true);
        CommandResult cr = new CommandResult();
        cr.setStatus(CommandStatus.SUCCESS);
        cr.setStderr(OtaUpdateDeviceFlasher.UPDATE_SUCCESS_OUTPUT);
        when(mMockRunUtil.runTimedCmd(Mockito.any(long.class), Mockito.any())).thenReturn(cr);
        doNothing().when(mMockDevice).rebootUntilOnline();
        // test
        IDeviceBuildInfo dbi = setupDeviceBuildInfoForOta();
        mFlasher.preFlashOperations(mMockDevice, dbi);
        mFlasher.flash(mMockDevice, dbi);
        // verify
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("stop");
        mInOrder.verify(mMockDevice).executeShellCommand("rm -rf /data/*");
        mInOrder.verify(mMockDevice).reboot();
        mInOrder.verify(mMockDevice).waitForDeviceAvailable();
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("svc power stayon true");
        mInOrder.verify(mMockDevice)
                .setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1"));
        mInOrder.verify(mMockRunUtil).runTimedCmd(Mockito.any(long.class), Mockito.any());
        mInOrder.verify(mMockDevice).rebootUntilOnline();
    }

    @Test(expected = TargetSetupError.class)
    public void testFlash_no_success_output() throws Exception {
        // prep
        mFlasher.setUserDataFlashOption(UserDataFlashOption.WIPE);
        when(mMockDevice.enableAdbRoot()).thenReturn(true);
        when(mMockDevice.setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1")))
                .thenReturn(true);
        CommandResult cr = new CommandResult();
        cr.setStatus(CommandStatus.SUCCESS);
        cr.setStderr("onPayloadApplicationComplete(ErrorCode::kInstallDeviceOpenError (7))");
        when(mMockRunUtil.runTimedCmd(Mockito.any(long.class), Mockito.any())).thenReturn(cr);
        doNothing().when(mMockDevice).rebootUntilOnline();
        // test
        IDeviceBuildInfo dbi = setupDeviceBuildInfoForOta();
        mFlasher.preFlashOperations(mMockDevice, dbi);
        mFlasher.flash(mMockDevice, dbi);
        // verify
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("stop");
        mInOrder.verify(mMockDevice).executeShellCommand("rm -rf /data/*");
        mInOrder.verify(mMockDevice).reboot();
        mInOrder.verify(mMockDevice).waitForDeviceAvailable();
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("svc power stayon true");
        mInOrder.verify(mMockDevice)
                .setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1"));
        mInOrder.verify(mMockRunUtil).runTimedCmd(Mockito.any(long.class), Mockito.any());
        mInOrder.verify(mMockDevice).rebootUntilOnline();
    }

    @Test(expected = TargetSetupError.class)
    public void testFlash_command_failure() throws Exception {
        // prep
        mFlasher.setUserDataFlashOption(UserDataFlashOption.WIPE);
        when(mMockDevice.enableAdbRoot()).thenReturn(true);
        when(mMockDevice.setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1")))
                .thenReturn(true);
        CommandResult cr = new CommandResult();
        cr.setStatus(CommandStatus.FAILED);
        cr.setStderr(OtaUpdateDeviceFlasher.UPDATE_SUCCESS_OUTPUT);
        when(mMockRunUtil.runTimedCmd(Mockito.any(long.class), Mockito.any())).thenReturn(cr);
        doNothing().when(mMockDevice).rebootUntilOnline();
        // test
        IDeviceBuildInfo dbi = setupDeviceBuildInfoForOta();
        mFlasher.preFlashOperations(mMockDevice, dbi);
        mFlasher.flash(mMockDevice, dbi);
        // verify
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("stop");
        mInOrder.verify(mMockDevice).executeShellCommand("rm -rf /data/*");
        mInOrder.verify(mMockDevice).reboot();
        mInOrder.verify(mMockDevice).waitForDeviceAvailable();
        mInOrder.verify(mMockDevice).enableAdbRoot();
        mInOrder.verify(mMockDevice).executeShellCommand("svc power stayon true");
        mInOrder.verify(mMockDevice)
                .setProperty(
                        Mockito.eq(OtaUpdateDeviceFlasher.OTA_DOWNGRADE_PROP), Mockito.eq("1"));
        mInOrder.verify(mMockRunUtil).runTimedCmd(Mockito.any(long.class), Mockito.any());
        mInOrder.verify(mMockDevice).rebootUntilOnline();
    }
}
