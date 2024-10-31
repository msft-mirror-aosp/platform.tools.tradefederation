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

import com.android.tradefed.device.ITestDevice;
import java.io.File;
import java.util.List;

/**
 * This class indicates the strategy to decide whether to skip app installation and uninstallation
 * during {@link TestAppInstallSetup}'s setUp and tearDown.
 */
public class TestAppInstallSetupIncrementalHelper {

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
        throw new UnsupportedOperationException("This method is not implemented yet.");
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
}
