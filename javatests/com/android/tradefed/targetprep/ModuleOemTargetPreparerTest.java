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

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Unit test for {@link ModuleOemTargetPreparerTest} */
@RunWith(JUnit4.class)
public class ModuleOemTargetPreparerTest {
    public static final String TESTHARNESS_ENABLE = "cmd testharness enable";
    private static final String APEX_PACKAGE_NAME = "com.android.FAKE_APEX_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME = "com.android.FAKE_APK_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME2 = "com.android.FAKE_APK_PACKAGE_NAME2";
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
    private static final String TEST_SPLIT_APK_APKS_NAME = "fakeApk.apks";
    private static final String TEST_SPLIT_APK_NAME = "FakeSplit/base-master.apk";
    private static final String TEST_HDPI_APK_NAME = "FakeSplit/base-hdpi.apk";

    @Rule public TemporaryFolder testDir = new TemporaryFolder();
    private static final String SERIAL = "serial";
    private ModuleOemTargetPreparer mModuleOemTargetPreparer;
    @Mock ITestDevice mMockDevice;
    private TestInformation mTestInfo;
    private File mFakeApex;
    private File mFakeApk;
    private File mFakeApkApks;
    private File mFakeSplitDir;
    private File mFakeSplitApk;
    private File mFakeHdpiApk;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mFakeApex = testDir.newFile(TEST_APEX_NAME);
        mFakeApk = testDir.newFile(TEST_APK_NAME);
        mFakeApkApks = testDir.newFile(TEST_SPLIT_APK_APKS_NAME);
        mFakeSplitDir = testDir.newFolder("FakeSplit");
        mFakeSplitApk = testDir.newFile(TEST_SPLIT_APK_NAME);
        mFakeHdpiApk = testDir.newFile(TEST_HDPI_APK_NAME);

        when(mMockDevice.getApiLevel()).thenReturn(30 /* Build.VERSION_CODES.R */);
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
                        "package:/system/apps/com.android.SPLIT_FAKE_APK_PACKAGE_NAME/com.android.SPLIT_FAKE_APK_PACKAGE_NAME.apk\n"
                            + "package:/system/apps/com.android"
                            + ".SPLIT_FAKE_APK_PACKAGE_NAME/com.android.SPLIT_FAKE_APK_PACKAGE_NAME-hdpi.apk\n");
        when(mMockDevice.executeShellV2Command("pm path " + SPLIT_APK_PACKAGE_NAME))
                .thenReturn(cr3);
        CommandResult cr4 =
                getCommandResult(
                        "com.android.SPLIT_FAKE_APK_PACKAGE_NAME.apk\n"
                                + "com.android.SPLIT_FAKE_APK_PACKAGE_NAME-hdpi.apk\n");
        when(mMockDevice.executeShellV2Command(
                        "ls /system/apps/com.android.SPLIT_FAKE_APK_PACKAGE_NAME"))
                .thenReturn(cr4);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
        mModuleOemTargetPreparer =
                new ModuleOemTargetPreparer() {
                    @Override
                    protected String parsePackageName(
                            File testAppFile, DeviceDescriptor deviceDescriptor) {
                        if (testAppFile.getName().endsWith(".apex")) {
                            return APEX_PACKAGE_NAME;
                        }
                        if (TEST_APK_NAME.equals(testAppFile.getName())) {
                            return APK_PACKAGE_NAME;
                        }
                        return SPLIT_APK_PACKAGE_NAME;
                    }

                    @Override
                    protected List<File> getSplitsForApks(
                            TestInformation testInfo, File moduleFile) {
                        List<File> result = new ArrayList<>();
                        result.add(mFakeSplitApk);
                        result.add(mFakeHdpiApk);
                        return result;
                    }

                    @Override
                    protected void setupDevice(ITestDevice device) {}

                    @Override
                    protected String getPackageVersioncode(
                            ITestDevice device, String packageName, boolean isAPK) {
                        return "V2";
                    }

                    @Override
                    protected void waitForDeviceToBeResponsive(long waitTime) {}
                };
    }

    /** Test getting paths on device. */
    @Test
    public void testGetPathsOnDevice() throws Exception {
        String[] files =
                mModuleOemTargetPreparer.getPathsOnDevice(mMockDevice, SPLIT_APK_PACKAGE_NAME);

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

        Path[] results =
                mModuleOemTargetPreparer.getPreloadPaths(
                        mMockDevice, files, SPLIT_APK_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test getting preload file paths for apex */
    @Test
    public void testGetPreLoadFilePathsOnApex() throws Exception {
        File[] files = new File[] {mFakeApex};
        Path[] actual = new Path[] {Paths.get(APEX_PATH_ON_DEVICE)};

        Path[] results =
                mModuleOemTargetPreparer.getPreloadPaths(mMockDevice, files, APEX_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test getting preload file paths for non-split apk. */
    @Test
    public void testGetPreLoadFilePathsOnApk() throws Exception {
        File[] files = new File[] {mFakeApk};
        Path[] actual = new Path[] {Paths.get(APK_PATH_ON_DEVICE)};

        Path[] results =
                mModuleOemTargetPreparer.getPreloadPaths(mMockDevice, files, APK_PACKAGE_NAME);

        assertArrayEquals(results, actual);
    }

    /** Test getting apk modules on device. */
    @Test
    public void testGetApkModules() {
        ITestDevice.ApexInfo fakeApexData =
                new ITestDevice.ApexInfo(APEX_PACKAGE_NAME, 1, APEX_PATH_ON_DEVICE);
        Set<String> modules =
                new HashSet<>(
                        Arrays.asList(APK_PACKAGE_NAME, APK_PACKAGE_NAME2, APEX_PACKAGE_NAME));
        Set<ITestDevice.ApexInfo> apexes = new HashSet<>(Collections.singletonList(fakeApexData));
        Set<String> expected = new HashSet<>(Arrays.asList(APK_PACKAGE_NAME, APK_PACKAGE_NAME2));

        assertEquals(expected, mModuleOemTargetPreparer.getApkModules(modules, apexes));
    }

    /** Test setup when there are non-split files to push. */
    @Test
    public void testSetupSuccess() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedApk = dir.resolve(APK_PRELOAD_NAME).toFile();
        prepareTestFiles(mFakeApk, mFakeApex);
        when(mMockDevice.pushFile(renamedApex, APEX_PATH_ON_DEVICE)).thenReturn(true);
        when(mMockDevice.pushFile(renamedApk, APK_PATH_ON_DEVICE)).thenReturn(true);

        mModuleOemTargetPreparer.setUp(mTestInfo);

        verify(mMockDevice, atLeastOnce()).getActiveApexes();
        verify(mMockDevice, times(1)).executeShellV2Command("cmd testharness enable");
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushFile(renamedApk, APK_PATH_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeApk.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isRegularFile(renamedApk.toPath()));
    }

    /** Test setup when there are apks to push. */
    @Test
    public void testSetupSuccessWithApks() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedSplitApk = dir.resolve(SPLIT_APK_PACKAGE_NAME).toFile();
        prepareTestFiles(mFakeApex, mFakeApkApks);
        when(mMockDevice.pushFile(renamedApex, APEX_PATH_ON_DEVICE)).thenReturn(true);
        when(mMockDevice.pushDir(renamedSplitApk, SPLIT_APK_PACKAGE_ON_DEVICE)).thenReturn(true);

        mModuleOemTargetPreparer.setUp(mTestInfo);

        verify(mMockDevice, atLeastOnce()).getActiveApexes();
        verify(mMockDevice, times(1)).executeShellV2Command("cmd testharness enable");
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushDir(renamedSplitApk, SPLIT_APK_PACKAGE_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeSplitDir.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isDirectory(renamedSplitApk.toPath()));
        assertTrue(Files.isRegularFile(renamedSplitApk.toPath().resolve("base-master.apk")));
        assertTrue(Files.isRegularFile(renamedSplitApk.toPath().resolve("base-hdpi.apk")));
    }

    /** Throws TargetSetupError if the only file push fails. */
    @Test(expected = TargetSetupError.class)
    public void testSetupFailureIfPushFails() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        prepareTestFiles(mFakeApex);
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(false);

        mModuleOemTargetPreparer.setUp(mTestInfo);

        verify(mMockDevice, never()).executeShellV2Command(TESTHARNESS_ENABLE);
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
    }

    /** Throws TargetSetupError if any file push fails and there are more than one test files. */
    @Test(expected = TargetSetupError.class)
    public void testSetupFailureIfAnyPushFails() throws Exception {
        Path dir = mFakeApex.toPath().getParent();
        File renamedApex = dir.resolve(APEX_PRELOAD_NAME).toFile();
        File renamedApk = dir.resolve(APK_PRELOAD_NAME).toFile();
        prepareTestFiles(mFakeApex, mFakeApk);
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);
        when(mMockDevice.pushFile(any(), eq(APK_PATH_ON_DEVICE))).thenReturn(false);

        mModuleOemTargetPreparer.setUp(mTestInfo);

        verify(mMockDevice, never()).executeShellV2Command(TESTHARNESS_ENABLE);
        verify(mMockDevice, times(1)).pushFile(renamedApex, APEX_PATH_ON_DEVICE);
        verify(mMockDevice, times(1)).pushFile(renamedApk, APK_PATH_ON_DEVICE);
        assertFalse(Files.exists(mFakeApex.toPath()));
        assertFalse(Files.exists(mFakeApk.toPath()));
        assertTrue(Files.isRegularFile(renamedApex.toPath()));
        assertTrue(Files.isRegularFile(renamedApk.toPath()));
    }

    /** Throws TargetSetupError if failed to activate. */
    @Test(expected = TargetSetupError.class)
    public void testSetupFailureIfActivationFails() throws Exception {
        prepareTestFiles(mFakeApex);
        when(mMockDevice.getActiveApexes()).thenReturn(new HashSet<>());
        when(mMockDevice.pushFile(any(), eq(APEX_PATH_ON_DEVICE))).thenReturn(true);

        mModuleOemTargetPreparer.setUp(mTestInfo);

        verify(mMockDevice, times(1)).pushFile(any(), any());
    }

    /** Test that teardown without setup does not cause a NPE. */
    @Test
    public void testTearDown() throws Exception {
        mModuleOemTargetPreparer.tearDown(mTestInfo, null);
    }

    private void prepareTestFiles(File... testFiles) throws DeviceNotAvailableException {
        Set<String> installableModules = new HashSet<>();
        for (File testFile : testFiles) {
            mModuleOemTargetPreparer.addTestFile(testFile);
            if (mFakeApk.equals(testFile)) {
                installableModules.add(APK_PACKAGE_NAME);
            } else if (mFakeApex.equals(testFile)) {
                installableModules.add(APEX_PACKAGE_NAME);
            } else {
                installableModules.add(SPLIT_APK_PACKAGE_NAME);
            }
        }
        ApexInfo fakeApexData = new ApexInfo(APEX_PACKAGE_NAME, 1, APEX_PATH_ON_DEVICE);
        when(mMockDevice.getActiveApexes())
                .thenReturn(new HashSet<>(Collections.singletonList(fakeApexData)));
        when(mMockDevice.getInstalledPackageNames()).thenReturn(installableModules);
    }

    private static CommandResult getCommandResult(String output) {
        CommandResult result = new CommandResult();
        result.setStatus(CommandStatus.SUCCESS);
        result.setStdout(output);
        result.setExitCode(0);
        return result;
    }
}
