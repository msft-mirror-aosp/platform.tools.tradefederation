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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import javax.annotation.Nullable;

/**
 * This class detects whether the APKs to be installed are different from those on the device, in
 * order to decide whether to skip app installation and uninstallation during {@link
 * TestAppInstallSetup}'s setUp and tearDown.
 */
public class ApkChangeDetector {

    private static final long MIN_FREE_DISK_SPACE_THRESHOLD_IN_BYTES = 10000000L;
    private static final double DISK_SPACE_TO_USE_ESTIMATE_FACTOR = 1.5;
    @VisibleForTesting
    static final String PACKAGE_INSTALLED_FILE_PATH =
        "/sdcard/.tradefed_package_installation_cache";

    @VisibleForTesting
    final Set<String> mPackagesHandledInCurrentTestRun = new HashSet<>();

    private Set<String> mPackagesHandledInPreviousTestRuns;
    private Boolean incrementalSetupSupportEnsureResult;

    /**
     * Handle app pre-install process.
     *
     * @param packageName The name of the package.
     * @param testApps Indicate all APK files in the package with the name {@link packageName}.
     * @param device Indicates the device on which the test is running.
     * @return Whether the APKs in {@link packageName} are fully handled under local incremental
     *     setup. Default to false, which does not oblige to re-install the package APKs.
     */
    public boolean handleTestAppsPreinstall(
        String packageName, List<File> testApps, ITestDevice device)
        throws DeviceNotAvailableException {
        if (!ensureIncrementalSetupSupported(device)) {
            return false;
        }
        if (!cleanupAppsIfNecessary(device, testApps)) {
            return false;
        }
        updateInstalledPackageCache(device, packageName);

        List<String> apkInstallPaths = getApkInstallPaths(packageName, device);
        if (apkInstallPaths.size() != testApps.size()) {
            CLog.d(
                "The file count of APKs to be installed is not equal to the number of APKs on "
                    + "the device for the package '%s'. Install the APKs.", packageName);
            return false;
        }

        Set<String> sha256SetOnDevice = getSha256SumsOnDevice(apkInstallPaths, device);
        CLog.d("The SHA256Sums on device contains: ");
        sha256SetOnDevice.forEach(sha256 -> {
            CLog.d("%s", sha256);
        });

        try {
            Set<String> sha256SumsOnHost = new HashSet<>();
            for (File testApp : testApps) {
                sha256SumsOnHost.add(calculateSHA256OnHost(testApp));
            }
            return sha256SetOnDevice.equals(sha256SumsOnHost);
        } catch (IOException ex) {
            CLog.d(
                "Exception occurred when calculating the SHA256Sums of APKs to be installed. "
                    + "Install the APKs. Error message: %s", ex);
            return false;
        }
    }

    /**
     * Handle package cleanup process.
     *
     * @param packageName the name of package to be cleaned up.
     * @param device Indicates the device on which the test is running.
     * @param userId The current user ID.
     * @param forAllUsers Indicates whether the cleanup should be done for all users.
     * @return Whether the cleanup of an indicated package is done. Default to false, which
     *     indicates that the cleanup is not done.
     */
    public boolean handlePackageCleanup(
        String packageName, ITestDevice device, Integer userId, boolean forAllUsers)
        throws DeviceNotAvailableException {
        if (!mPackagesHandledInCurrentTestRun.contains(packageName)) {
            // In case incremental setup is not supported for the package, skip package cleanup of
            // this detector.
            return false;
        }
        // For the current implementation, we stop the app process. If successful, skip the app
        // uninstallation.
        String commandToRun = String.format("am force-stop %s", packageName);
        device.executeShellCommand(commandToRun);
        return true;
    }

    /** The receiver class for SHA256Sum outputs. */
    private static class Sha256SumCommandLineReceiver extends MultiLineReceiver {

        private Set<String> mSha256Sums = new HashSet<>();

        /** Return the calculated SHA256Sums of parsed APK files.*/
        Set<String> getSha256Sums() {
            return mSha256Sums;
        }

        /** {@inheritDoc} */
        @Override
        public boolean isCancelled() {
            return false;
        }

        /** {@inheritDoc} */
        @Override
        public void processNewLines(String[] lines) {
            for (String line : lines) {
                StringTokenizer tokenizer = new StringTokenizer(line);
                if (tokenizer.hasMoreTokens()) {
                    mSha256Sums.add(tokenizer.nextToken());
                }
            }
        }
    }

    /** Obtain the APK install paths of the package with {@code packageName}. */
    @VisibleForTesting
    @Nullable
    List<String> getApkInstallPaths(String packageName, ITestDevice device)
        throws DeviceNotAvailableException {
        String commandToRun = String.format("pm path %s", packageName);
        Splitter splitter = Splitter.on('\n').trimResults().omitEmptyStrings();
        return splitter.splitToList(device.executeShellCommand(commandToRun))
                .stream()
                .filter(line -> line.startsWith("package:"))
                .map(line -> line.substring("package:".length()))
                .collect(toImmutableList());
    }

    /** Collect the SHA256Sums of all APK files under {@code apkInstallPaths}. */
    @VisibleForTesting
    Set<String> getSha256SumsOnDevice(List<String> apkInstallPaths, ITestDevice device)
        throws DeviceNotAvailableException {
        Set<String> packageInstallPaths = new HashSet<>();
        apkInstallPaths.forEach(apkInstallPath -> {
            packageInstallPaths.add(Paths.get(apkInstallPath).getParent().toString());
        });

        Set<String> sha256Sums = new HashSet<>();
        for (String packageInstallPath : packageInstallPaths) {
            Sha256SumCommandLineReceiver receiver = new Sha256SumCommandLineReceiver();
            String commandToRun =
                String.format("find %s -name \"*.apk\" -exec sha256sum {} \\;", packageInstallPath);
            device.executeShellCommand(commandToRun, receiver);
            sha256Sums.addAll(receiver.getSha256Sums());
        }
        return sha256Sums;
    }

    @VisibleForTesting
    String calculateSHA256OnHost(File file) throws IOException {
        byte[] byteArray = new byte[(int) file.length()];
        try (FileInputStream inputStream = new FileInputStream(file)) {
            inputStream.read(byteArray);
        }
        return Hashing.sha256().hashBytes(byteArray).toString();
    }

    /**
     * Returns if the processes of checking free disk space and app cleanup are successful.
     *
     * Note that this method only returns {@code false} if any issue happens. Upon no needing to
     * clean up, this method returns {@code true}.
     */
    private boolean cleanupAppsIfNecessary(ITestDevice device, List<File> testApps)
        throws DeviceNotAvailableException {
        long freeDiskSpace;
        try {
            freeDiskSpace = getFreeDiskSpaceForAppInstallation(device);
        } catch (IllegalArgumentException illegalArgumentEx) {
            CLog.d(
                "Not able to obtain free disk space: %s. App cleanup not successful.",
                illegalArgumentEx);
            return false;
        }
        long totalAppSize = testApps.stream().mapToLong(File::length).sum();
        if (freeDiskSpace - totalAppSize * DISK_SPACE_TO_USE_ESTIMATE_FACTOR
                < MIN_FREE_DISK_SPACE_THRESHOLD_IN_BYTES) {
            // First, get the list of packages to be uninstalled.
            Set<String> packagesToBeUninstalled =
                Sets.difference(
                    loadPackagesHandledInPreviousTestRuns(device),
                    mPackagesHandledInCurrentTestRun);

            // Then, uninstall the packages.
            boolean anyUninstallationFailed = false;
            for (String packageName : packagesToBeUninstalled) {
                if (device.uninstallPackage(packageName) != null) {
                    anyUninstallationFailed = true;
                }
            }

            // Finally, remove the file indicating the packages to be uninstalled if there is no
            // uninstallation failure; otherwise, return false to indicate the cleanup is not
            // successful.
            if (anyUninstallationFailed) {
                return false;
            }
            device.deleteFile(PACKAGE_INSTALLED_FILE_PATH);
            mPackagesHandledInPreviousTestRuns = new HashSet<>();
        }
        return true;
    }

    /** Get the free disk space in bytes of the folder "/data" of {@code device}. */
    @VisibleForTesting
    long getFreeDiskSpaceForAppInstallation(ITestDevice device)
        throws DeviceNotAvailableException {
        String commandToRun = "df /data";
        return getFreeDiskSpaceFromDfCommandLine(device.executeShellCommand(commandToRun));
    }

    private long getFreeDiskSpaceFromDfCommandLine(String output) {
        if (output == null) {
            throw new IllegalArgumentException(
                "No output available for obtaining the device's free disk space.");
        }
        // The format of the output of `df /data` is as follows:
        // Filesystem        1K-blocks    Used Available Use% Mounted on
        // [PATH_FS]         [TOTAL]    [USED] [FREE]    [FREE_PCT] [PATH_MOUNTED_ON]
        // Thus we need to skip the first line and take token 3 of the second line.
        final long bytesInKiloBytes = 1024L;
        Splitter splitter = Splitter.on('\n').trimResults().omitEmptyStrings();
        List<String> outputLines = splitter.splitToList(output);
        if (outputLines.size() < 2) {
            throw new IllegalArgumentException("No free disk space info was emitted.");
        }
        String[] tokens = outputLines.get(1).split("\\s+");
        if (tokens.length < 4) {
            throw new IllegalArgumentException(
                "Free disk space info under /data was malformatted.");
        }
        return Long.parseLong(tokens[3]) * bytesInKiloBytes;
    }

    /**
     * Get the set of packages installed on the device and handled by the APK change detector in
     * previous test runs.
     */
    @VisibleForTesting
    Set<String> loadPackagesHandledInPreviousTestRuns(ITestDevice device)
        throws DeviceNotAvailableException {
        if (mPackagesHandledInPreviousTestRuns != null) {
            return mPackagesHandledInPreviousTestRuns;
        }

        String fileContents = device.pullFileContents(PACKAGE_INSTALLED_FILE_PATH);
        if (fileContents != null) {
            Splitter splitter = Splitter.on('\n').trimResults().omitEmptyStrings();
            mPackagesHandledInPreviousTestRuns =
                Sets.newHashSet(splitter.split(fileContents));
        } else {
            mPackagesHandledInPreviousTestRuns = new HashSet<>();
        }
        return mPackagesHandledInPreviousTestRuns;
    }

    /**
     * Return the incremental setup is supported on {@code device}.
     *
     * Note that this method has the side effect of creating a cache file under "/sdcard/." if it
     * does not exist.
     */
    @VisibleForTesting
    boolean ensureIncrementalSetupSupported(ITestDevice device)
        throws DeviceNotAvailableException {
        if (incrementalSetupSupportEnsureResult != null) {
            return incrementalSetupSupportEnsureResult;
        }

        // Check if the device has sha256sum command installed.
        String sha256SumDryRunOutput = device.executeShellCommand("sha256sum --help");
        if (sha256SumDryRunOutput.contains("sha256sum: inaccessible or not found")) {
            incrementalSetupSupportEnsureResult = false;
            return false;
        }

        // Check if we have access to "/sdcard/.".
        if (device.doesFileExist(PACKAGE_INSTALLED_FILE_PATH)) {
            incrementalSetupSupportEnsureResult = true;
        } else {
            incrementalSetupSupportEnsureResult =
                device.pushString("", PACKAGE_INSTALLED_FILE_PATH);
        }
        return incrementalSetupSupportEnsureResult;
    }

    private void updateInstalledPackageCache(ITestDevice device, String packageName)
        throws DeviceNotAvailableException {
        mPackagesHandledInCurrentTestRun.add(packageName);
        Set<String> packagesHandledByIncrementalSetup =
            Sets.union(
                loadPackagesHandledInPreviousTestRuns(device),
                mPackagesHandledInCurrentTestRun);
        device.pushString(
            String.join("\n", packagesHandledByIncrementalSetup),
            PACKAGE_INSTALLED_FILE_PATH);
    }
}
