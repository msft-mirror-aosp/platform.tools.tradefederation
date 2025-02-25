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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.ITestDevice;
import com.google.common.collect.Sets;
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
    private ApkChangeDetector mApkChangeDetectorLessDiskSpace;
    private ApkChangeDetector mApkChangeDetectorDiskSpaceNotObtained;
    private ApkChangeDetector mApkChangeDetectorFileNotAccessible;
    private ApkChangeDetector mApkChangeDetectorFileUninstallFailed;
    private ITestDevice mMockDevice;
    private ITestDevice mMockDeviceFileUninstallFailed;
    private File mMockFile1;
    private File mMockFile2;
    private File mMockFile3;
    private List<File> mMockTestApps;

    @Before
    public void setUp() throws Exception {
        mApkChangeDetector = spy(new ApkChangeDetector(new HashSet<>()));
        mApkChangeDetectorLessDiskSpace = spy(new ApkChangeDetector());
        mApkChangeDetectorDiskSpaceNotObtained =
            spy(
                new ApkChangeDetector(
                    Sets.newHashSet(
                        "prev.handled1", "prev.handled2", "prev.handled3", "a.b.c.package")));
        mApkChangeDetectorFileNotAccessible = spy(new ApkChangeDetector());
        mApkChangeDetectorFileUninstallFailed =
            spy(
                new ApkChangeDetector(
                    Sets.newHashSet(
                        "prev.handled1", "prev.handled2", "prev.handled3", "a.b.c.package")));
        mMockDevice = mock(ITestDevice.class);
        mMockDeviceFileUninstallFailed = mock(ITestDevice.class);
        doReturn(null).when(mMockDevice).uninstallPackage(Mockito.any());
        doReturn("Pseudo error code").when(mMockDeviceFileUninstallFailed)
            .uninstallPackage(Mockito.any());
        doNothing().when(mMockDevice).deleteFile(Mockito.any());
        doReturn(true).when(mMockDevice).pushString(Mockito.any(), Mockito.any());

        mMockFile1 = mock(File.class);
        mMockFile2 = mock(File.class);
        mMockFile3 = mock(File.class);

        mMockTestApps = new ArrayList<>();
        mMockTestApps.add(mMockFile1);
        mMockTestApps.add(mMockFile2);
        mMockTestApps.add(mMockFile3);
        doReturn(1000000L).when(mMockFile1).length();
        doReturn(2000000L).when(mMockFile2).length();
        doReturn(3000000L).when(mMockFile3).length();

        List<String> apkInstallPaths = new ArrayList<>();
        apkInstallPaths.add("/a.b.c.package.installPath/file1.apk");
        apkInstallPaths.add("/a.b.c.package.installPath/file2.apk");
        apkInstallPaths.add("/a.b.c.package.installPath/file3.apk");

        doReturn(apkInstallPaths)
            .when(mApkChangeDetector)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        doReturn(apkInstallPaths)
            .when(mApkChangeDetectorLessDiskSpace)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        doReturn(apkInstallPaths)
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        doReturn(apkInstallPaths)
            .when(mApkChangeDetectorFileNotAccessible)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        doReturn(2000000000L)
            .when(mApkChangeDetector)
            .getFreeDiskSpaceForAppInstallation(Mockito.any());
        doReturn(15000000L)
            .when(mApkChangeDetectorLessDiskSpace)
            .getFreeDiskSpaceForAppInstallation(Mockito.any());
        doReturn(15000000L)
            .when(mApkChangeDetectorFileUninstallFailed)
            .getFreeDiskSpaceForAppInstallation(Mockito.any());
        doThrow(IllegalArgumentException.class)
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .getFreeDiskSpaceForAppInstallation(Mockito.any());
        doReturn(true)
            .when(mApkChangeDetector)
            .ensureIncrementalSetupSupported(Mockito.any());
        doReturn(true)
            .when(mApkChangeDetectorLessDiskSpace)
            .ensureIncrementalSetupSupported(Mockito.any());
        doReturn(true)
            .when(mApkChangeDetectorFileUninstallFailed)
            .ensureIncrementalSetupSupported(Mockito.any());
        doReturn(true)
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .ensureIncrementalSetupSupported(Mockito.any());
        doReturn(false)
            .when(mApkChangeDetectorFileNotAccessible)
            .ensureIncrementalSetupSupported(Mockito.any());
        Set<String> sha256SumsOnDevice = new HashSet<>();
        sha256SumsOnDevice.add("sha256sum1");
        sha256SumsOnDevice.add("sha256sum2");
        sha256SumsOnDevice.add("sha256sum3");
        doReturn(sha256SumsOnDevice)
            .when(mApkChangeDetector)
            .getSha256SumsOnDevice(Mockito.any(), Mockito.any());
        doReturn(sha256SumsOnDevice)
            .when(mApkChangeDetectorLessDiskSpace)
            .getSha256SumsOnDevice(Mockito.any(), Mockito.any());
        doReturn(sha256SumsOnDevice)
            .when(mApkChangeDetectorFileUninstallFailed)
            .getSha256SumsOnDevice(Mockito.any(), Mockito.any());
        doReturn(sha256SumsOnDevice)
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .getSha256SumsOnDevice(Mockito.any(), Mockito.any());
        doNothing()
            .when(mApkChangeDetector)
            .loadPackagesHandledInPreviousTestRuns(mMockDevice);
        doNothing()
            .when(mApkChangeDetectorLessDiskSpace)
            .loadPackagesHandledInPreviousTestRuns(mMockDevice);
        doNothing()
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .loadPackagesHandledInPreviousTestRuns(mMockDevice);
        doNothing()
            .when(mApkChangeDetectorFileNotAccessible)
            .loadPackagesHandledInPreviousTestRuns(mMockDevice);
        doNothing()
            .when(mApkChangeDetectorFileUninstallFailed)
            .loadPackagesHandledInPreviousTestRuns(mMockDevice);
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_noApkInstallPathFound() throws Exception {
    ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector(new HashSet<>()));
        doReturn(new ArrayList<>()).when(apkChangeDetector)
            .getApkInstallPaths(Mockito.any(), Mockito.any());
        doReturn(2000000000L)
            .when(apkChangeDetector)
            .getFreeDiskSpaceForAppInstallation(Mockito.any());
        doReturn(true)
            .when(apkChangeDetector)
            .ensureIncrementalSetupSupported(Mockito.any());
        doNothing().when(apkChangeDetector).loadPackagesHandledInPreviousTestRuns(mMockDevice);

        boolean shouldSkipInstallation =
            apkChangeDetector.handleTestAppsPreinstall(
                "a.b.c.package", mMockTestApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

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
            mApkChangeDetector.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

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
            mApkChangeDetector.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(shouldSkipInstallation).isFalse();
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_userNotOwner()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum2").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile2);
        doReturn("sha256sum3").when(mApkChangeDetector).calculateSHA256OnHost(mMockFile3);

        boolean shouldSkipInstallation =
            mApkChangeDetector.handleTestAppsPreinstall(
                "a.b.c.package", mMockTestApps, mMockDevice, /* userId= */ 12345,
                /* forAllUsers= */ false);

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
                "a.b.c.package", mMockTestApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(shouldSkipInstallation).isTrue();
    }

    @Test
    public void handlePackageCleanup_forSingleUser_doAppUninstallation() throws Exception {
        doReturn("Pseudo success message")
            .when(mMockDevice).executeShellCommand("am force-stop a.b.c.package");

        boolean shouldSkipAppUninstallation =
            mApkChangeDetector.handlePackageCleanup(
                "a.b.c.package", mMockDevice, /* userId= */ 12345, /* forAllUsers= */ false);

        assertThat(shouldSkipAppUninstallation).isFalse();
    }

    @Test
    public void handlePackageCleanup_forSingleUser_skipAppUninstallation() throws Exception {
        doReturn("Pseudo success message")
            .when(mMockDevice).executeShellCommand("am force-stop a.b.c.package");
        mApkChangeDetector.mPackagesHandledInCurrentTestRun.add("a.b.c.package");

        boolean shouldSkipAppUninstallation =
            mApkChangeDetector.handlePackageCleanup(
                "a.b.c.package", mMockDevice, /* userId= */ 12345, /* forAllUsers= */ false);

        assertThat(shouldSkipAppUninstallation).isTrue();
    }

    @Test
    public void handlePackageCleanup_forAllUsers_skipAppUninstallation() throws Exception {
        doReturn("Pseudo success message")
            .when(mMockDevice).executeShellCommand("am force-stop a.b.c.package");
        mApkChangeDetector.mPackagesHandledInCurrentTestRun.add("a.b.c.package");

        boolean shouldSkipAppUninstallation =
            mApkChangeDetector.handlePackageCleanup(
                "a.b.c.package", mMockDevice, /* userId= */ null, /* forAllUsers= */ true);

        assertThat(shouldSkipAppUninstallation).isTrue();
    }

    @Test
    public void handleTestAppsPreinstall_doAppCleanup_appNeedsInstallationAndDiskSpaceNotEnough()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetectorLessDiskSpace)
            .calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum3").when(mApkChangeDetectorLessDiskSpace)
            .calculateSHA256OnHost(mMockFile3);
        doReturn(
                Sets.newHashSet("prev.handled1", "prev.handled2", "prev.handled3", "a.b.c.package"),
                new HashSet<String>())
            .when(mApkChangeDetectorLessDiskSpace)
            .getPackagesHandledInPreviousTestRuns(mMockDevice);
        mApkChangeDetectorLessDiskSpace.mPackagesHandledInCurrentTestRun.addAll(
            Sets.newHashSet("prev.handled1", "cur.handled1", "cur.handled2"));
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);
        testApps.add(mMockFile3);

        // The free disk space before installation is 15,000,000 bytes while the two APKs' sizes
        // are 1,000,000 bytes and 3,000,000 bytes, respectively. Thus the estimated free space
        // after installation is 15,000,000 - 1.5 * (1,000,000 + 3,000,000) = 9,000,000, which is
        // less than the threshold 10,000,000 bytes.
        boolean shouldSkipAppUninstallation =
            mApkChangeDetectorLessDiskSpace.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(shouldSkipAppUninstallation).isFalse();
        verify(mMockDevice, times(0)).uninstallPackage("prev.handled1");
        verify(mMockDevice).uninstallPackage("prev.handled2");
        verify(mMockDevice).uninstallPackage("prev.handled3");
        verify(mMockDevice).uninstallPackage("a.b.c.package");
        verify(mMockDevice).deleteFile(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
    }

    @Test
    public void handleTestAppsPreinstall_doInstallation_diskSpaceNotEnoughAndFileUninstallFailed()
        throws Exception {
        doReturn("sha256sum1").when(mApkChangeDetectorFileUninstallFailed)
            .calculateSHA256OnHost(mMockFile1);
        doReturn("sha256sum3")
            .when(mApkChangeDetectorFileUninstallFailed)
            .calculateSHA256OnHost(mMockFile3);
        mApkChangeDetectorFileUninstallFailed.mPackagesHandledInCurrentTestRun.addAll(
            Sets.newHashSet("prev.handled1", "cur.handled1", "cur.handled2"));
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);
        testApps.add(mMockFile3);

        // The free disk space before installation is 15,000,000 bytes while the two APKs' sizes
        // are 1,000,000 bytes and 3,000,000 bytes, respectively. Thus the estimated free space
        // after installation is 15,000,000 - 1.5 * (1,000,000 + 3,000,000) = 9,000,000, which is
        // less than the threshold 10,000,000 bytes.
        boolean shouldSkipAppUninstallation =
            mApkChangeDetectorFileUninstallFailed.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDeviceFileUninstallFailed, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(shouldSkipAppUninstallation).isFalse();
        verify(mMockDeviceFileUninstallFailed, times(0)).uninstallPackage("prev.handled1");
        verify(mMockDeviceFileUninstallFailed).uninstallPackage("prev.handled2");
        verify(mMockDeviceFileUninstallFailed).uninstallPackage("prev.handled3");
        verify(mMockDeviceFileUninstallFailed).uninstallPackage("a.b.c.package");
        // The file is not deleted because the deletion of the previous three files failed.
        verify(mMockDeviceFileUninstallFailed, times(0))
            .deleteFile(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
    }

    @Test
    public void handleTestAppsPreinstall_incrementalSetupNotSupported()
        throws Exception {
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);

        boolean incrementalSetupSupported =
            mApkChangeDetectorFileNotAccessible.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(incrementalSetupSupported).isFalse();
    }

    @Test
    public void handleTestAppsPreinstall_incrementalSetupNotSupported_diskSpaceNotObtained()
        throws Exception {
        doReturn("sha256sum1")
            .when(mApkChangeDetectorDiskSpaceNotObtained)
            .calculateSHA256OnHost(mMockFile1);
        mApkChangeDetectorDiskSpaceNotObtained.mPackagesHandledInCurrentTestRun.addAll(
            Sets.newHashSet("prev.handled1", "cur.handled1", "cur.handled2"));
        List<File> testApps = new ArrayList<>();
        testApps.add(mMockFile1);

        boolean incrementalSetupSupported =
            mApkChangeDetectorDiskSpaceNotObtained.handleTestAppsPreinstall(
                "a.b.c.package", testApps, mMockDevice, /* userId= */ null,
                /* forAllUsers= */ true);

        assertThat(incrementalSetupSupported).isFalse();
        verify(mMockDevice, times(0)).uninstallPackage("prev.handled1");
        verify(mMockDevice, times(0)).uninstallPackage("prev.handled2");
        verify(mMockDevice, times(0)).uninstallPackage("prev.handled3");
        verify(mMockDevice, times(0)).uninstallPackage("a.b.c.package");
        verify(mMockDevice, times(0))
            .deleteFile(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
    }

    @Test
    public void ensureIncrementalSetupSupported_returnsFalse_sha256NotInstalled()
        throws Exception {
        ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector());
        doReturn("sh: sha256sum: inaccessible or not found").when(mMockDevice)
            .executeShellCommand("sha256sum --help");
        doNothing().when(apkChangeDetector).loadPackagesHandledInPreviousTestRuns(mMockDevice);

        boolean incrementalSetupSupported =
            apkChangeDetector.ensureIncrementalSetupSupported(mMockDevice);

        assertThat(incrementalSetupSupported).isFalse();
    }

    @Test
    public void ensureIncrementalSetupSupported_returnsFalse_noAccessToSdCardDirectory()
        throws Exception {
        ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector());
        doReturn("").when(mMockDevice).executeShellCommand("sha256sum --help");
        doReturn(false).when(mMockDevice)
            .doesFileExist(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
        doReturn(false).when(mMockDevice)
            .pushString("", ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
        doNothing().when(apkChangeDetector).loadPackagesHandledInPreviousTestRuns(mMockDevice);

        boolean incrementalSetupSupported =
            apkChangeDetector.ensureIncrementalSetupSupported(mMockDevice);

        assertThat(incrementalSetupSupported).isFalse();
    }

    @Test
    public void ensureIncrementalSetupSupported_returnsTrue_installCacheFileExists()
        throws Exception {
        ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector());
        doReturn("").when(mMockDevice).executeShellCommand("sha256sum --help");
        doReturn(true).when(mMockDevice)
            .doesFileExist(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
        doNothing().when(apkChangeDetector).loadPackagesHandledInPreviousTestRuns(mMockDevice);

        boolean incrementalSetupSupported =
            apkChangeDetector.ensureIncrementalSetupSupported(mMockDevice);

        assertThat(incrementalSetupSupported).isTrue();
    }

    @Test
    public void ensureIncrementalSetupSupported_returnsTrue_canCreateInstallCacheFile()
        throws Exception {
        ApkChangeDetector apkChangeDetector = spy(new ApkChangeDetector());
        doReturn("").when(mMockDevice).executeShellCommand("sha256sum --help");
        doReturn(false).when(mMockDevice)
            .doesFileExist(ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
        doReturn(true).when(mMockDevice)
            .pushString("", ApkChangeDetector.PACKAGE_INSTALLED_FILE_PATH);
        doNothing().when(apkChangeDetector).loadPackagesHandledInPreviousTestRuns(mMockDevice);

        boolean incrementalSetupSupported =
            apkChangeDetector.ensureIncrementalSetupSupported(mMockDevice);

        assertThat(incrementalSetupSupported).isTrue();
    }
}
