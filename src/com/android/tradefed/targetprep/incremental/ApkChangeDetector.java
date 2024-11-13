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
        String packageName, List<File> testApps, ITestDevice device) {
        List<String> apkInstallPaths;
        try {
            apkInstallPaths = getApkInstallPaths(packageName, device);
        } catch (DeviceNotAvailableException ex) {
            CLog.d(
                "Exception occurred when getting the APK install paths of package '%s'. "
                    + "Install the APKs. Error message: %s",
                packageName, ex);
            return false;
        }

        if (apkInstallPaths.size() != testApps.size()) {
            CLog.d(
                    "The file count of APKs to be installed is not equal to the number of APKs on "
                        + "the device for the package '%s'. Install the APKs.", packageName);
            return false;
        }

        Set<String> sha256SetOnDevice;
        try {
            sha256SetOnDevice = getSha256SumsOnDevice(apkInstallPaths, device);
        } catch (DeviceNotAvailableException ex) {
            CLog.d(
                "Exception occurred when getting the SHA256Sums of APKs on the device for the "
                    + "package '%s'. Install the APKs. Error message: %s",
                packageName, ex);
            return false;
        }
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
        String packageName, ITestDevice device, Integer userId, boolean forAllUsers) {
        throw new UnsupportedOperationException("This method is not implemented yet.");
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
}
