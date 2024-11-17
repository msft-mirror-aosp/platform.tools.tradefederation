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
package com.android.tradefed.targetprep.incremental;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.android.tradefed.device.ITestDevice;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ApkChangeDetectorTest {

    private ApkChangeDetector mApkChangeDetector;
    private ITestDevice mMockDevice;
    private File mMockFile1;
    private File mMockFile2;
    private File mMockFile3;
    private List<File> mMockTestApps;

    @Before
    public void setUp() throws Exception {
        mApkChangeDetector = spy(new ApkChangeDetector());
        mMockDevice = mock(ITestDevice.class);
        mMockFile1 = mock(File.class);
        mMockFile2 = mock(File.class);
        mMockFile3 = mock(File.class);

        mMockTestApps = new ArrayList<>();
        mMockTestApps.add(mMockFile1);
        mMockTestApps.add(mMockFile2);
        mMockTestApps.add(mMockFile3);

        List<String> apkInstallPaths = new ArrayList<>();
        apkInstallPaths.add("/a.b.c.package.installPath/file1.apk");
        apkInstallPaths.add("/a.b.c.package.installPath/file2.apk");
        apkInstallPaths.add("/a.b.c.package.installPath/file3.apk");

        doReturn(apkInstallPaths)
            .when(mApkChangeDetector)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        Set<String> sha256SumsOnDevice = new HashSet<>();
        sha256SumsOnDevice.add("sha256sum1");
        sha256SumsOnDevice.add("sha256sum2");
        sha256SumsOnDevice.add("sha256sum3");
        doReturn(sha256SumsOnDevice)
            .when(mApkChangeDetector)
            .getSha256SumsOnDevice(Mockito.any(), Mockito.any());
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_noApkInstallPathFound() throws Exception {
        ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector());
        doReturn(new ArrayList<>()).when(apkChangeDetector)
            .getApkInstallPaths(Mockito.any(), Mockito.any());

        boolean shouldSkipInstallation =
            apkChangeDetector.handleTestAppsPreinstall("a.b.c.package", mMockTestApps, mMockDevice);

        assertThat(shouldSkipInstallation).isFalse();
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_hashesOnHostMismatchThoseOnDevice()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum4").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile2);
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);
        testApps.add(mMockFile2);

        boolean shouldSkipInstallation =
            mApkChangeDetector.handleTestAppsPreinstall("a.b.c.package", testApps, mMockDevice);

        assertThat(shouldSkipInstallation).isFalse();
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_hashesOnHostAreSubsetOfThoseOnDevice()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum2").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile2);
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);
        testApps.add(mMockFile2);

        boolean shouldSkipInstallation =
            mApkChangeDetector.handleTestAppsPreinstall("a.b.c.package", testApps, mMockDevice);

        assertThat(shouldSkipInstallation).isFalse();
    }

    @Test
    public void handleTestAppsPreinstall_skipInstallation_hashesMatchOnDeviceAndHost()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum2").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile2);
        doReturn("sha256sum3").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile3);

        boolean shouldSkipInstallation =
            mApkChangeDetector.handleTestAppsPreinstall(
                "a.b.c.package", mMockTestApps, mMockDevice);

        assertThat(shouldSkipInstallation).isTrue();
    }

    @Test
    public void handlePackageCleanup_forSingleUser_skipAppUninstallation() throws Exception {
        doReturn("Pseudo success message")
            .when(mMockDevice).executeShellCommand("am force-stop a.b.c.package");

        boolean shouldSkipAppUninstallation =
            mApkChangeDetector.handlePackageCleanup(
                "a.b.c.package", mMockDevice, /* userId= */ 12345, /* forAllUsers= */ false);

        assertThat(shouldSkipAppUninstallation).isTrue();
    }

    @Test
    public void handlePackageCleanup_forAllUsers_skipAppUninstallation() throws Exception {
        doReturn("Pseudo success message")
            .when(mMockDevice).executeShellCommand("am force-stop a.b.c.package");

        boolean shouldSkipAppUninstallation =
            mApkChangeDetector.handlePackageCleanup(
                "a.b.c.package", mMockDevice, /* userId= */ null, /* forAllUsers= */ true);

        assertThat(shouldSkipAppUninstallation).isTrue();
    }
}
