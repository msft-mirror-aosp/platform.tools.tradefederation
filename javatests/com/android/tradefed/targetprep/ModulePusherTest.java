/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import com.google.common.collect.ImmutableMultimap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;

/** Unit test for {@link ModulePusher} */
@RunWith(JUnit4.class)
public final class ModulePusherTest {
    public static final String TESTHARNESS_ENABLE = "cmd testharness enable";
    private static final String APEX_PACKAGE_NAME = "com.android.FAKE_APEX_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME = "com.android.FAKE_APK_PACKAGE_NAME";
    private static final String SPLIT_APK_PACKAGE_NAME = "com.android.SPLIT_FAKE_APK_PACKAGE_NAME";
    private static final String APEX_PRELOAD_NAME = APEX_PACKAGE_NAME + ".apex";
    private static final String APK_PRELOAD_NAME = APK_PACKAGE_NAME + ".apk";
    private static final String SPLIT_APK_PRELOAD_NAME = SPLIT_APK_PACKAGE_NAME + ".apk";
    private static final String APEX_PATH_ON_DEVICE = "/system/apex/" + APEX_PRELOAD_NAME;
    private static final String APK_PATH_ON_DEVICE = "/system/apps/" + APK_PRELOAD_NAME;
    public static final String SPLIT_APK_PACKAGE_ON_DEVICE =
            "/system/apps/com.android.SPLIT_FAKE_APK_PACKAGE_NAME";
    private static final String SPLIT_APK_PATH_ON_DEVICE =
            SPLIT_APK_PACKAGE_ON_DEVICE + "/" + SPLIT_APK_PRELOAD_NAME;
    private static final String HDPI_PATH_ON_DEVICE =
            SPLIT_APK_PACKAGE_ON_DEVICE + "/com.android.SPLIT_FAKE_APK_PACKAGE_NAME-hdpi.apk";
    private static final String TEST_APEX_NAME = "fakeApex.apex";
    private static final String TEST_APK_NAME = "fakeApk.apk";
    private static final String TEST_SPLIT_APK_NAME = "FakeSplit/base-master.apk";
    private static final String TEST_HDPI_APK_NAME = "FakeSplit/base-hdpi.apk";

    @Rule public TemporaryFolder testDir = new TemporaryFolder();
    private static final String SERIAL = "serial";
    private ModulePusher mPusher;
    @Mock ITestDevice mMockDevice;
    private File mFakeApex;
    private File mFakeApk;
    private File mFakeSplitDir;
    private File mFakeSplitApk;
    private File mFakeHdpiApk;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mFakeApex = testDir.newFile(TEST_APEX_NAME);
        mFakeApk = testDir.newFile(TEST_APK_NAME);
        mFakeSplitDir = testDir.newFolder("FakeSplit");
        mFakeSplitApk = testDir.newFile(TEST_SPLIT_APK_NAME);
        mFakeHdpiApk = testDir.newFile(TEST_HDPI_APK_NAME);

        when(mMockDevice.executeAdbCommand("disable-verity")).thenReturn("disabled");
        when(mMockDevice.executeAdbCommand("remount")).thenReturn("remount succeeded");
        when(mMockDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockDevice.getDeviceDescriptor()).thenReturn(null);
        CommandResult cr = getCommandResult("Good!");
        when(mMockDevice.executeShellV2Command("pm get-moduleinfo | grep 'com.google'"))
                .thenReturn(cr);
        when(mMockDevice.executeShellV2Command("cmd testharness enable")).thenReturn(cr);
        CommandResult cr1 =
                getCommandResult("package:/system/apex/com.android.FAKE_APEX_PACKAGE_NAME.apex\n");
        when(mMockDevice.executeShellV2Command("pm path " + APEX_PACKAGE_NAME)).thenReturn(cr1);
        CommandResult cr2 =
                getCommandResult("package:/system/apps/com.android.FAKE_APK_PACKAGE_NAME.apk\n");
        when(mMockDevice.executeShellV2Command("pm path " + APK_PACKAGE_NAME)).thenReturn(cr2);
        CommandResult cr3 =
                getCommandResult(
                        String.format(
                                "package:%s\npackage:%s\n",
                                SPLIT_APK_PATH_ON_DEVICE, HDPI_PATH_ON_DEVICE));
        when(mMockDevice.executeShellV2Command("pm path " + SPLIT_APK_PACKAGE_NAME))
                .thenReturn(cr3);
        CommandResult cr4 =
                getCommandResult(
                        "com.android.SPLIT_FAKE_APK_PACKAGE_NAME.apk\n"
                                + "com.android.SPLIT_FAKE_APK_PACKAGE_NAME-hdpi.apk\n");
        when(mMockDevice.executeShellV2Command(
                        "ls /system/apps/com.android.SPLIT_FAKE_APK_PACKAGE_NAME"))
                .thenReturn(cr4);

        mPusher =
                new ModulePusher(mMockDevice, 0, 0) {
                    @Override
                    protected ModulePusher.ModuleInfo retrieveModuleInfo(File packageFile) {
                        if (mFakeApex.equals(packageFile)) {
                            return ModuleInfo.create(APEX_PACKAGE_NAME, "2", false);
                        } else if (mFakeApk.equals(packageFile)) {
                            return ModuleInfo.create(APK_PACKAGE_NAME, "2", true);
                        } else {
                            return ModuleInfo.create(SPLIT_APK_PACKAGE_NAME, "2", true);
                        }
                    }

                    @Override
                    protected void waitForDeviceToBeResponsive(long waitTime) {}
                };
    }

    /** Test getPackageVersioncodeOnDevice picks the right version among similar packages. */
    @Test
    public void testGetPackageVersioncodeOnDevice() throws Exception {
        String packageName1 = "com.google.android.media";
        String packageName2 = "com.google.android.mediaprovider";
        String packageName3 = "com.google.android.media.swcodec";
        CommandResult versionResult =
                getCommandResult(
                        "package:com.google.android.media versionCode:301800200\n"
                            + "package:com.google.android.mediaprovider versionCode:301501700\n"
                            + "package:com.google.android.media.swcodec versionCode:301700000\n");
        when(mMockDevice.executeShellV2Command(any())).thenReturn(versionResult);

        String actual1 =
                mPusher.getPackageVersioncodeOnDevice(mMockDevice, packageName1, /*isAPK=*/ false);
        String actual2 =
                mPusher.getPackageVersioncodeOnDevice(mMockDevice, packageName2, /*isAPK=*/ false);
        String actual3 =
                mPusher.getPackageVersioncodeOnDevice(mMockDevice, packageName3, /*isAPK=*/ false);

        assertEquals("301800200", actual1);
        assertEquals("301501700", actual2);
        assertEquals("301700000", actual3);
    }

    /** Test getting paths on device. */
    @Test
    public void testGetPathsOnDevice() throws Exception {
        String[] files = mPusher.getPathsOnDevice(mMockDevice, SPLIT_APK_PACKAGE_NAME);

        assertArrayEquals(new String[] {SPLIT_APK_PATH_ON_DEVICE, HDPI_PATH_ON_DEVICE}, files);
    }

    /** Test getting preload file paths for split apks. */
    @Test
    public void testGetPreLoadFilePathOnSplitApk() throws Exception {
        File[] files = new File[] {mFakeSplitApk, mFakeHdpiApk};
        Path[] actual =
                new Path[] {
                    Paths.get(SPLIT_APK_PACKAGE_ON_DEVICE),
                    Paths.get(SPLIT_APK_PATH_ON_DEVICE),
                    Paths.get(HDPI_PATH_ON_DEVICE)
                };

        Path[] results = mPusher.getPreloadPaths(mMockDevice, files, SPLIT_APK_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test getting preload file paths for apex */
    @Test
    public void testGetPreLoadFilePathsOnApex() throws Exception {
        File[] files = new File[] {mFakeApex};
        Path[] actual = new Path[] {Paths.get(APEX_PATH_ON_DEVICE)};

        Path[] results = mPusher.getPreloadPaths(mMockDevice, files, APEX_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test getting preload file paths for non-split apk. */
    @Test
    public void testGetPreLoadFilePathsOnApk() throws Exception {
        File[] files = new File[] {mFakeApk};
        Path[] actual = new Path[] {Paths.get(APK_PATH_ON_DEVICE)};

        Path[] results = mPusher.getPreloadPaths(mMockDevice, files, APK_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test setup when there are non-split files to push. */
    @Test
    public void testInstallModuleSuccess() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedApk = dir.resolve(APK_PRELOAD_NAME).toFile();
        when(mMockDevice.pushFile(renamedApex, APEX_PATH_ON_DEVICE)).thenReturn(true);
        when(mMockDevice.pushFile(renamedApk, APK_PATH_ON_DEVICE)).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        setVersionCodeOnDevice(APK_PACKAGE_NAME, "2");
        activateVersion(2);

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex, APK_PACKAGE_NAME, mFakeApk),
                /*factory_reset=*/ true);

        verify(mMockDevice, atLeastOnce()).getActiveApexes();
        verify(mMockDevice, times(2)).reboot();
        verify(mMockDevice, times(1)).executeShellV2Command("cmd testharness enable");
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushFile(renamedApk, APK_PATH_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeApk.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isRegularFile(renamedApk.toPath()));
    }

    /** Test setup when there are non-split files to push with reboot. */
    @Test
    public void testInstallModuleSuccessViaReboot() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedApk = dir.resolve(APK_PRELOAD_NAME).toFile();
        when(mMockDevice.pushFile(renamedApex, APEX_PATH_ON_DEVICE)).thenReturn(true);
        when(mMockDevice.pushFile(renamedApk, APK_PATH_ON_DEVICE)).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        setVersionCodeOnDevice(APK_PACKAGE_NAME, "2");
        activateVersion(2);

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex, APK_PACKAGE_NAME, mFakeApk),
                /*factory_reset=*/ false);

        verify(mMockDevice, atLeastOnce()).getActiveApexes();
        verify(mMockDevice, never()).executeShellV2Command("cmd testharness enable");
        verify(mMockDevice, times(3)).reboot();
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushFile(renamedApk, APK_PATH_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeApk.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isRegularFile(renamedApk.toPath()));
    }

    /** Test setup when there are apks to push. */
    @Test
    public void testInstallModulesSuccessWithApks() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedSplitApk = dir.resolve(SPLIT_APK_PACKAGE_NAME).toFile();
        when(mMockDevice.pushFile(renamedApex, APEX_PATH_ON_DEVICE)).thenReturn(true);
        when(mMockDevice.pushDir(renamedSplitApk, SPLIT_APK_PACKAGE_ON_DEVICE)).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        setVersionCodeOnDevice(SPLIT_APK_PACKAGE_NAME, "2");
        activateVersion(2);

        mPusher.installModules(
                ImmutableMultimap.of(
                        APEX_PACKAGE_NAME,
                        mFakeApex,
                        SPLIT_APK_PACKAGE_NAME,
                        mFakeSplitApk,
                        SPLIT_APK_PACKAGE_NAME,
                        mFakeHdpiApk),
                /*factory_reset=*/ true);

        verify(mMockDevice, atLeastOnce()).getActiveApexes();
        verify(mMockDevice, times(1)).executeShellV2Command("cmd testharness enable");
        verify(mMockDevice, times(2)).reboot();
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushDir(renamedSplitApk, SPLIT_APK_PACKAGE_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeSplitDir.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isDirectory(renamedSplitApk.toPath()));
        assertTrue(Files.isRegularFile(renamedSplitApk.toPath().resolve("base-master.apk")));
        assertTrue(Files.isRegularFile(renamedSplitApk.toPath().resolve("base-hdpi.apk")));
    }

    /** Throws ModulePushError if the only file push fails. */
    @Test(expected = ModulePusher.ModulePushError.class)
    public void testInstallModulesFailureIfPushFails() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(false);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex), /*factory_reset=*/ true);

        verify(mMockDevice, never()).executeShellV2Command(TESTHARNESS_ENABLE);
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
    }

    /** Throws ModulePushError if any file push fails and there are more than one test files. */
    @Test(expected = ModulePusher.ModulePushError.class)
    public void testInstallModulesFailureIfAnyPushFails() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedApk = dir.resolve(APK_PRELOAD_NAME).toFile();
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);
        when(mMockDevice.pushFile(any(), eq(APK_PATH_ON_DEVICE))).thenReturn(false);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        setVersionCodeOnDevice(APK_PACKAGE_NAME, "2");

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex, APK_PACKAGE_NAME, mFakeApk),
                /*factory_reset=*/ true);

        verify(mMockDevice, never()).executeShellV2Command(TESTHARNESS_ENABLE);
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushFile(renamedApk, APK_PATH_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeApk.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isRegularFile(renamedApk.toPath()));
    }

    /** Throws ModulePushError if activated version code is different. */
    @Test(expected = ModulePusher.ModulePushError.class)
    public void testInstallModulesFailureIfActivationVersionCodeDifferent() throws Exception {
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        activateVersion(1);

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex), /*factory_reset=*/ true);

        verify(mMockDevice, times(1)).pushFile(any(), any());
    }

    /** Throws ModulePushError if failed to activate. */
    @Test(expected = ModulePusher.ModulePushError.class)
    public void testInstallModulesFailureIfActivationFailed() throws Exception {
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "2");
        when(mMockDevice.getActiveApexes()).thenReturn(new HashSet<>());

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex), /*factory_reset=*/ true);

        verify(mMockDevice, times(1)).pushFile(any(), any());
    }

    /** Throws ModulePushError if version code not updated. */
    @Test(expected = ModulePusher.ModulePushError.class)
    public void testInstallModulesFailureIfVersionCodeDifferent() throws Exception {
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);
        setVersionCodeOnDevice(APEX_PACKAGE_NAME, "1");
        activateVersion(2);

        mPusher.installModules(
                ImmutableMultimap.of(APEX_PACKAGE_NAME, mFakeApex), /*factory_reset=*/ true);

        verify(mMockDevice, times(1)).pushFile(any(), any());
    }

    private void setVersionCodeOnDevice(String packageName, String updatedVersionCode)
            throws Exception {
        CommandResult result1 =
                getCommandResult(String.format("package:%s versionCode:%s", packageName, "1"));
        CommandResult result2 =
                getCommandResult(
                        String.format(
                                "package:%s versionCode:%s", packageName, updatedVersionCode));
        when(mMockDevice.executeShellV2Command(
                        "cmd package list packages --apex-only --show-versioncode| grep "
                                + packageName))
                .thenReturn(result1)
                .thenReturn(result2);
        when(mMockDevice.executeShellV2Command(
                        "cmd package list packages --show-versioncode| grep " + packageName))
                .thenReturn(result1)
                .thenReturn(result2);
    }

    private void activateVersion(long versionCode) throws DeviceNotAvailableException {
        ITestDevice.ApexInfo fakeApexData =
                new ITestDevice.ApexInfo(APEX_PACKAGE_NAME, versionCode, APEX_PATH_ON_DEVICE);
        when(mMockDevice.getActiveApexes())
                .thenReturn(new HashSet<>(Collections.singletonList(fakeApexData)));
    }

    private static CommandResult getCommandResult(String output) {
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        result.setStdout(output);
        result.setExitCode(0);
        return result;
    }
}
