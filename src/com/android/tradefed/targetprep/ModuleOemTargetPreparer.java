/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/*
 * A {@link TargetPreparer} that attempts to install mainline modules via adb push
 * and verify push success.
 */
@OptionClass(alias = "mainline-oem-installer")
public class ModuleOemTargetPreparer extends InstallApexModuleTargetPreparer {

    private static final String APEX_DIR = "/system/apex/";
    private static final String DISABLE_VERITY = "disable-verity";
    private static final String ENABLE_TESTHARNESS = "cmd testharness enable";
    private static final String GET_APEX_PACKAGE_VERSION =
            "cmd package list packages --apex-only --show-versioncode| grep ";
    private static final String GET_APK_PACKAGE_VERSION =
            "cmd package list packages --show-versioncode| grep ";
    private static final String REMOUNT_COMMAND = "remount";
    private static final long WAIT_TIME = 1000 * 60 * 10;
    private static final long DELAY_WAITING_TIME = 2000;
    private static final int HEAD_LENGTH = "package:".length();
    private static final String GET_GOOGLE_MODULES = "pm get-moduleinfo | grep 'com.google'";

    /** A simple struct class to store information about a module */
    public static class ModuleInfo {
        public final String packageName;
        public final String versionCode;
        public final boolean isApk;

        public ModuleInfo(String packageName, String versionCode, boolean isApk) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.isApk = isApk;
        }
    }

    /**
     * Perform the target setup for testing, push modules to replace the preload ones
     *
     * @param testInfo The {@link TestInformation} of the invocation.
     * @throws TargetSetupError if fatal error occurred setting up environment
     * @throws BuildError If an error occurs due to the build being prepared
     * @throws DeviceNotAvailableException if device became unresponsive
     */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        setTestInformation(testInfo);
        ITestDevice device = testInfo.getDevice();

        final int apiLevel = device.getApiLevel();
        if (apiLevel < 29 /* Build.VERSION_CODES.Q */) {
            CLog.i(
                    "Skip the target preparer because the api level is %i and"
                            + "the build doesn't have mainline modules",
                    apiLevel);
            return;
        }

        setupDevice(device);

        if (mTrainFolderPath != null) {
            addApksToTestFiles();
        }

        List<File> testAppFiles = getModulesToInstall(testInfo);
        if (testAppFiles.isEmpty()) {
            CLog.i("No modules to install.");
            return;
        }

        checkPreloadModules(testInfo, device.getDeviceDescriptor());
        List<ModuleInfo> pushedModules = pushModulesToDevice(testInfo, testAppFiles);
        reloadAllModules(device);
        waitForDeviceToBeResponsive(WAIT_TIME);
        checkApexActivation(device);
        CLog.i("Check pushed module version code after device reboot");
        checkModulesAfterPush(device, pushedModules);
    }

    /**
     * adb root and remount device before push files under /system
     *
     * @throws TargetSetupError if device cannot be remounted.
     */
    @VisibleForTesting
    protected void setupDevice(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        device.enableAdbRoot();
        String disableVerity = device.executeAdbCommand(DISABLE_VERITY);
        CLog.i("disable-verity status: %s", disableVerity);

        if (disableVerity.contains("disabled")) {
            CLog.d("disable-verity status: %s", disableVerity);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to disable verity on device %s", device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
        }

        device.reboot();
        remountDevice(device);
        device.reboot();
        remountDevice(device);
    }

    /**
     * Check package version after pushed module given package name.
     *
     * @param device under test.
     * @param packageName pushed package name
     * @throws DeviceNotAvailableException throws exception if device not found.
     * @return the package version.
     */
    @VisibleForTesting
    protected String getPackageVersioncode(ITestDevice device, String packageName, boolean isAPK)
            throws DeviceNotAvailableException {
        String packageVersion;
        String outputs;
        if (isAPK) {
            outputs =
                    device.executeShellV2Command(GET_APK_PACKAGE_VERSION + packageName).getStdout();
        } else {
            outputs =
                    device.executeShellV2Command(GET_APEX_PACKAGE_VERSION + packageName)
                            .getStdout();
        }
        // TODO(liuyg@): add wait-time to get output info and try to fix flakiness
        waitForDeviceToBeResponsive(DELAY_WAITING_TIME);
        CLog.i("Output string is %s", outputs);
        String[] splits = outputs.split(":", -1);
        packageVersion = splits[splits.length - 1].replaceAll("[\\n]", "");
        CLog.i("Package '%s' version code is %s", packageName, packageVersion);
        return packageVersion;
    }

    /**
     * Get the paths of the preload package on the device.
     *
     * <p>For split packages, return the path of the package dir followed by the paths of files. As
     * a result, the size of return is always > 1 in this case. For non-split packages, simply
     * return the path of the preload installation file.
     *
     * @param device under test
     * @param moduleFiles local modules files to install
     * @param packageName of the module
     * @return the paths of the preload files.
     */
    @VisibleForTesting
    protected Path[] getPreloadPaths(ITestDevice device, File[] moduleFiles, String packageName)
            throws TargetSetupError, DeviceNotAvailableException {
        String[] paths = getPathsOnDevice(device, packageName);
        if (paths.length > 1) {
            // Split case.
            List<Path> res = new ArrayList<>();
            // In the split case all apk files should be contained in a package dir.
            Path parentDir = Paths.get(paths[0]).getParent();
            if (!isPackageDir(device, parentDir, paths.length)) {
                throw new TargetSetupError(
                        String.format(
                                "The parent folder %s contains files not related to the package %s",
                                parentDir, packageName),
                        device.getDeviceDescriptor());
            }
            res.add(parentDir);
            for (String filePath : paths) {
                res.add(Paths.get(filePath));
            }
            return res.toArray(new Path[0]);
        } else if (hasExtension(APK_SUFFIX, moduleFiles[0])) {
            return new Path[] {Paths.get(paths[0])};
        } else { // apex
            // There is an issue that some system apex are provided as decompressed file under
            // /data/apex/decompressed. We assume that the apex under /system/apex will
            // get decompressed and overwrite the decompressed variant in reload.
            // Log the cases for debugging.
            if (!paths[0].startsWith(APEX_DIR)) {
                CLog.w(
                        "The path of the system apex is not /system/apex. Actual paths are: %s",
                        Arrays.toString(paths));
            }
            return new Path[] {Paths.get(APEX_DIR, packageName + APEX_SUFFIX)};
        }
    }

    private boolean isPackageDir(ITestDevice device, Path dirPathOnDevice, int packageFileNum)
            throws DeviceNotAvailableException, TargetSetupError {
        CommandResult lsResult =
                device.executeShellV2Command(String.format("ls %s", dirPathOnDevice));
        if (!CommandStatus.SUCCESS.equals(lsResult.getStatus())) {
            throw new TargetSetupError(
                    String.format("Failed to ls files in %s", dirPathOnDevice),
                    device.getDeviceDescriptor());
        }
        // All files in the dir should be the package files.
        return packageFileNum == lsResult.getStdout().split("\n").length;
    }

    /**
     * Get the paths of the installation files of the package on the device.
     *
     * @param device under test
     * @param packageName of the module
     * @return paths of all files of the package
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if cannot find the path of the package
     */
    @VisibleForTesting
    protected String[] getPathsOnDevice(ITestDevice device, String packageName)
            throws TargetSetupError, DeviceNotAvailableException {
        String output = device.executeShellV2Command("pm path " + packageName).getStdout();
        String[] lines = output.split("\n");
        if (!output.contains("package") || lines.length == 0) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to find file paths of %s on the device %s. Error log\n '%s'",
                            packageName, device.getSerialNumber(), output),
                    device.getDeviceDescriptor());
        }
        List<String> paths = new ArrayList<>();
        for (String line : lines) {
            paths.add(line.substring(HEAD_LENGTH).trim());
        }
        return paths.toArray(new String[0]);
    }

    @VisibleForTesting
    protected void waitForDeviceToBeResponsive(long waitTime) {
        // Wait for device to be responsive.
        RunUtil.getDefault().sleep(waitTime);
    }

    /**
     * Check preload modules info. It only shows the info for debugging.
     *
     * @param testInfo test info
     * @throws DeviceNotAvailableException throws exception if devices no available
     * @throws TargetSetupError throws exception if no modules preloaded
     */
    private static void checkPreloadModules(
            TestInformation testInfo, DeviceDescriptor deviceDescriptor)
            throws DeviceNotAvailableException, TargetSetupError {
        ITestDevice device = testInfo.getDevice();

        Set<ApexInfo> activatedApexes = device.getActiveApexes();
        CLog.i("Activated apex packages list before module push:");
        for (ApexInfo info : activatedApexes) {
            CLog.i("Activated apex: %s", info.toString());
        }
        CLog.i("Preloaded modules:");
        String out = device.executeShellV2Command(GET_GOOGLE_MODULES).getStdout();
        if (out != null) {
            CLog.i("Preload modules are as below: \n %s", out);
        } else {
            throw new TargetSetupError("no modules preloaded", deviceDescriptor);
        }
    }

    /**
     * Pushes modules atomically.
     *
     * @param testInfo test info.
     * @param testAppFiles list of mainline modules to install.
     * @return list of info of pushed modules.
     * @throws TargetSetupError if any push fails.
     */
    private List<ModuleInfo> pushModulesToDevice(TestInformation testInfo, List<File> testAppFiles)
            throws TargetSetupError, DeviceNotAvailableException {
        List<ModuleInfo> pushedModules = new ArrayList<>();
        for (File moduleFile : testAppFiles) {
            File[] toPush = new File[] {moduleFile};
            if (hasExtension(SPLIT_APKS_SUFFIX, moduleFile)) {
                toPush = getSplitsForApks(testInfo, moduleFile).toArray(new File[0]);
            }
            pushedModules.add(pushFile(toPush, testInfo.getDevice()));
        }
        return pushedModules;
    }

    /**
     * Reload all modules via factory reset.
     *
     * @param device under test.
     */
    private static void reloadAllModules(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        // In most cases, a reboot will have system modules reloaded.
        // However, there are special cases that a factory reset is needed.
        // Since the API requirement for mainline tests are also Q+ and
        // the test harness mode is available for Q+ devices,
        // we use enable test harness to do factory reset.
        CommandResult cr = device.executeShellV2Command(ENABLE_TESTHARNESS);
        if (!CommandStatus.SUCCESS.equals(cr.getStatus())) {
            CLog.e("Failed to enable test harness mode: %s", cr.toString());
            throw new TargetSetupError(cr.toString(), device.getDeviceDescriptor());
        }
        device.waitForDeviceAvailable(WAIT_TIME);
    }

    /**
     * Check module name and version code after pushed for debugging.
     *
     * <p>Note that we don't require the versions to be changed after push because the packages
     * under dev all have the same versions.
     *
     * @param pushedModules List of modules pushed
     * @throws DeviceNotAvailableException throw exception if no device available
     */
    private void checkModulesAfterPush(ITestDevice device, List<ModuleInfo> pushedModules)
            throws DeviceNotAvailableException, TargetSetupError {
        String newVersionCode;
        for (ModuleInfo mi : pushedModules) {
            newVersionCode = getPackageVersioncode(device, mi.packageName, mi.isApk);
            if (newVersionCode.isEmpty()) {
                throw new TargetSetupError(
                        "Failed to install package " + mi.packageName,
                        device.getDeviceDescriptor());
            }
            CLog.i(
                    "Packages %s pushed! version code before is: %s, after is: %s",
                    mi.packageName, mi.versionCode, newVersionCode);
        }
    }

    /**
     * Push files to /system/apex/ for apex or /system/** for apk
     *
     * @param moduleFiles an array of module files
     * @param device the {@link TestInformation} for the invocation.
     * @throws TargetSetupError if cannot push file via adb
     * @throws DeviceNotAvailableException if device not available
     */
    private ModuleInfo pushFile(File[] moduleFiles, ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        if (moduleFiles.length == 0) {
            throw new TargetSetupError(
                    "No file to push.",
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }
        String packageName = parsePackageName(moduleFiles[0], device.getDeviceDescriptor());
        boolean isAPK = hasExtension(APK_SUFFIX, moduleFiles[0]);
        String preloadVersion = getPackageVersioncode(device, packageName, isAPK);

        Path[] preloadPaths = getPreloadPaths(device, moduleFiles, packageName);
        Path packagePath = preloadPaths[0];
        Path toRename;
        boolean isDir = preloadPaths.length > 1;
        if (isDir) { // Split case
            // delete the preloaded package dir on device.
            device.deleteFile(packagePath.toString());
            toRename = moduleFiles[0].toPath().getParent();
        } else {
            toRename = moduleFiles[0].toPath();
        }

        Path target = toRename.getParent().resolve(packagePath.getFileName());
        Path toPush;
        try {
            toPush = Files.move(toRename, target);
            CLog.i("Local file name %s changed to the preload name %s", toRename, target);
        } catch (IOException e) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to rename File '%s' to the name of '%s'", toRename, target),
                    e,
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }

        pushPackageToDevice(device, toPush.toFile(), packagePath.toString(), isDir);
        // Add a wait time to collect module info after module push
        waitForDeviceToBeResponsive(DELAY_WAITING_TIME);
        return new ModuleInfo(packageName, preloadVersion, isAPK);
    }

    private void pushPackageToDevice(
            ITestDevice device, File localFile, String filePathOnDevice, boolean isDir)
            throws DeviceNotAvailableException, TargetSetupError {
        boolean success =
                isDir
                        ? device.pushDir(localFile, filePathOnDevice)
                        : device.pushFile(localFile, filePathOnDevice);

        if (success) {
            CLog.i(
                    "Local file %s got pushed to the preload path '%s",
                    localFile.getName(), filePathOnDevice);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to push File '%s' to '%s' on device %s.",
                            localFile, filePathOnDevice, device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }
    }

    /** Remount device function. */
    private static void remountDevice(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        device.enableAdbRoot();

        String remount = device.executeAdbCommand(REMOUNT_COMMAND);
        CLog.i("adb remount status: %s", remount);

        if (remount.contains("remount succeed")) {
            CLog.i("Remount Success, output is %s", remount);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to remount device on %s. Error log: '%s'",
                            device.getSerialNumber(), remount),
                    device.getDeviceDescriptor());
        }
    }

    private static boolean hasExtension(String extension, File fileName) {
        return fileName.getName().endsWith(extension);
    }
}
