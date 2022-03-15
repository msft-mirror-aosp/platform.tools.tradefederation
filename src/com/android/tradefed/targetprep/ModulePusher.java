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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessException;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.util.AaptParser;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMultimap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModulePusher {
    private static final String APEX_SUFFIX = ".apex";
    private static final String APK_SUFFIX = ".apk";
    private static final String APEX_DIR = "/system/apex/";
    private static final String DISABLE_VERITY = "disable-verity";
    private static final String ENABLE_TESTHARNESS = "cmd testharness enable";
    private static final String GET_APEX_PACKAGE_VERSION =
            "cmd package list packages --apex-only --show-versioncode| grep ";
    private static final String GET_APK_PACKAGE_VERSION =
            "cmd package list packages --show-versioncode| grep ";
    private static final String REMOUNT_COMMAND = "remount";
    private static final int HEAD_LENGTH = "package:".length();
    private static final String GET_GOOGLE_MODULES = "pm get-moduleinfo | grep 'com.google'";

    private final long mWaitTimeMs;
    private final long mDelayWaitingTimeMs;
    private final ITestDevice mDevice;

    /** Fatal error during Mainline module push. */
    public static class ModulePushError extends HarnessException {

        public ModulePushError(String message, Throwable cause, ErrorIdentifier errorId) {
            super(message, cause, errorId);
        }

        public ModulePushError(String message, ErrorIdentifier errorId) {
            super(message, errorId);
        }
    }

    /** A simple struct class to store information about a module */
    static final class ModuleInfo {
        private final String packageName;
        private final String versionCode;
        private final boolean isApk;

        public String packageName() {
            return packageName;
        }

        public String versionCode() {
            return versionCode;
        }

        public boolean isApk() {
            return isApk;
        }

        ModuleInfo(String packageName, String versionCode, boolean isApk) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.isApk = isApk;
        }

        public static ModuleInfo create(String packageName, String versionCode, boolean isApk) {
            return new ModuleInfo(packageName, versionCode, isApk);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ModuleInfo) {
                ModuleInfo moduleInfo = (ModuleInfo) other;
                return packageName.equals(moduleInfo.packageName)
                        && versionCode.equals(moduleInfo.versionCode)
                        && isApk == moduleInfo.isApk;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return String.format(
                    "{packageName: %s, versionCode: %s, isApk: %b}",
                    packageName, versionCode, isApk);
        }
    }

    public ModulePusher(ITestDevice device, long waitTimeMs, long delayWaitingTimeMs) {
        this.mDevice = device;
        this.mWaitTimeMs = waitTimeMs;
        this.mDelayWaitingTimeMs = delayWaitingTimeMs;
    }

    /**
     * Installs {@code moduleFiles} to the device by adb push.
     *
     * @param moduleFiles a multimap from package names to the package files. In split case, the
     *     base package should be the first in iteration order.
     * @param factory_reset if reload via factory reset.
     */
    public void installModules(ImmutableMultimap<String, File> moduleFiles, boolean factory_reset)
            throws TargetSetupError, DeviceNotAvailableException, ModulePushError {
        ITestDevice device = mDevice;
        setupDevice(device);
        checkPreloadModules(device);
        List<ModuleInfo> pushedModules = pushModulesToDevice(device, moduleFiles);
        reloadAllModules(device, factory_reset);
        waitForDeviceToBeResponsive(mWaitTimeMs);
        checkApexActivated(device, pushedModules);
        LogUtil.CLog.i("Check pushed module version code after device reboot");
        checkModuleVersions(device, pushedModules);
    }

    /**
     * adb root and remount device before push files under /system
     *
     * @throws ModulePushError if device cannot be remounted.
     * @throws DeviceNotAvailableException if device unavailable.
     */
    @VisibleForTesting
    protected void setupDevice(ITestDevice device)
            throws DeviceNotAvailableException, ModulePushError {
        device.enableAdbRoot();
        String disableVerity = device.executeAdbCommand(DISABLE_VERITY);
        LogUtil.CLog.i("disable-verity status: %s", disableVerity);

        if (disableVerity.contains("disabled")) {
            LogUtil.CLog.d("disable-verity status: %s", disableVerity);
        } else {
            throw new ModulePushError(
                    String.format(
                            "Failed to disable verity on device %s", device.getSerialNumber()),
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
        }

        device.reboot();
        remountDevice(device);
        device.reboot();
        remountDevice(device);
    }

    /** Remounts files of the {@code device}. */
    static void remountDevice(ITestDevice device) throws ModulePushError {
        String remount;
        try {
            device.enableAdbRoot();
            remount = device.executeAdbCommand(REMOUNT_COMMAND);
        } catch (DeviceNotAvailableException e) {
            throw new ModulePushError(
                    String.format(
                            "Failed to execute remount command to %s", device.getSerialNumber()),
                    e,
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_REMOUNT);
        }

        LogUtil.CLog.i("adb remount status: %s", remount);
        if (remount.contains("remount succeed")) {
            LogUtil.CLog.i("Remount Success, output is %s", remount);
        } else {
            throw new ModulePushError(
                    String.format(
                            "Failed to remount device on %s. Error log: '%s'",
                            device.getSerialNumber(), remount),
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_REMOUNT);
        }
    }

    /**
     * Check preload modules info. It only shows the info in log.
     *
     * @param device under test
     * @throws ModulePushError if failed to get preload modules
     * @throws DeviceNotAvailableException if device unavailable
     */
    static void checkPreloadModules(ITestDevice device)
            throws DeviceNotAvailableException, ModulePushError {
        Set<ITestDevice.ApexInfo> activatedApexes = device.getActiveApexes();
        LogUtil.CLog.i("Activated apex packages list before module push:");
        for (ITestDevice.ApexInfo info : activatedApexes) {
            LogUtil.CLog.i("Activated apex: %s", info.toString());
        }
        LogUtil.CLog.i("Preloaded modules:");
        String out = device.executeShellV2Command(GET_GOOGLE_MODULES).getStdout();
        if (out != null) {
            LogUtil.CLog.i("Preload modules are as below: \n %s", out);
        } else {
            throw new ModulePushError(
                    "no modules preloaded", DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
    }

    /**
     * Pushes modules atomically.
     *
     * @param device under test
     * @param moduleFiles a multi map from package names to package files.
     * @return list of info of pushed modules.
     */
    List<ModuleInfo> pushModulesToDevice(
            ITestDevice device, ImmutableMultimap<String, File> moduleFiles)
            throws DeviceNotAvailableException, ModulePushError {
        List<ModuleInfo> pushedModules = new ArrayList<>();
        for (String packageName : moduleFiles.keySet()) {
            File[] toPush = moduleFiles.get(packageName).toArray(new File[0]);
            pushedModules.add(pushFile(toPush, device, packageName));
        }
        return pushedModules;
    }

    /**
     * Push files of one package to the {@code device} directly.
     *
     * <p>The files should require no further unzip before pushing. Push files to /system/apex/ for
     * apex or /system/** for apk
     *
     * @param moduleFiles an array of module files for one package
     * @param device under test
     * @param packageName of the mainline package
     * @return info of the module to push
     * @throws ModulePushError if expected behavior during push
     * @throws DeviceNotAvailableException if device not available
     */
    ModuleInfo pushFile(File[] moduleFiles, ITestDevice device, String packageName)
            throws DeviceNotAvailableException, ModulePushError {
        if (moduleFiles.length == 0) {
            throw new ModulePushError("No file to push.", DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }

        boolean isAPK = hasExtension(APK_SUFFIX, moduleFiles[0]);
        String preloadVersion = getPackageVersioncodeOnDevice(device, packageName, isAPK);
        ModuleInfo moduleInfo = retrieveModuleInfo(moduleFiles[0]);
        LogUtil.CLog.i(
                "To update module %s from version %s to %s",
                packageName, preloadVersion, moduleInfo.versionCode());

        Path[] preloadPaths = getPreloadPaths(device, moduleFiles, packageName);
        Path packagePath = preloadPaths[0];
        Path toRename;
        boolean isDir = preloadPaths.length > 1;
        if (isDir) { // Split case
            // delete the whole package dir on device.
            device.deleteFile(packagePath.toString());
            toRename = moduleFiles[0].toPath().getParent();
        } else {
            toRename = moduleFiles[0].toPath();
        }

        Path target = toRename.getParent().resolve(packagePath.getFileName());
        Path toPush;
        try {
            toPush = Files.move(toRename, target);
            LogUtil.CLog.i("Local file name %s changed to the preload name %s", toRename, target);
        } catch (IOException e) {
            throw new ModulePushError(
                    String.format(
                            "Failed to rename File '%s' to the name of '%s'", toRename, target),
                    e,
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }

        pushPackageToDevice(device, toPush.toFile(), packagePath.toString(), isDir);
        // Add a wait time to collect module info after module push
        waitForDeviceToBeResponsive(mDelayWaitingTimeMs);
        return moduleInfo;
    }

    /**
     * Check the version of installed package on device.
     *
     * @param device under test.
     * @param packageName pushed package name
     * @return the package version.
     * @throws DeviceNotAvailableException throws exception if device not found.
     */
    @VisibleForTesting
    protected String getPackageVersioncodeOnDevice(
            ITestDevice device, String packageName, boolean isAPK)
            throws DeviceNotAvailableException, ModulePushError {
        String outputs =
                isAPK
                        ? device.executeShellV2Command(GET_APK_PACKAGE_VERSION + packageName)
                                .getStdout()
                        : device.executeShellV2Command(GET_APEX_PACKAGE_VERSION + packageName)
                                .getStdout();

        // TODO(liuyg@): add wait-time to get output info and try to fix flakiness
        waitForDeviceToBeResponsive(mDelayWaitingTimeMs);
        LogUtil.CLog.i("Output string is %s", outputs);
        String removeHead = outputs.split(String.format("package:%s versionCode:", packageName))[1];
        Pattern pattern = Pattern.compile("\\w+");
        Matcher matcher = pattern.matcher(removeHead);
        String packageVersion;
        if (matcher.lookingAt()) {
            packageVersion = matcher.group();
            LogUtil.CLog.i("Package '%s' version code is %s", packageName, packageVersion);
        } else {
            throw new ModulePushError(
                    String.format("The output %s doesn't match the version code pattern.", outputs),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
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
            throws DeviceNotAvailableException, ModulePushError {
        String[] paths = getPathsOnDevice(device, packageName);
        if (paths.length > 1) {
            // Split case.
            List<Path> res = new ArrayList<>();
            // In the split case all apk files should be contained in a package dir.
            Path parentDir = Paths.get(paths[0]).getParent();
            if (!isPackageDir(device, parentDir, paths.length)) {
                throw new ModulePushError(
                        String.format(
                                "The parent folder %s contains files not related to the package %s",
                                parentDir, packageName),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            res.add(parentDir);
            for (String filePath : paths) {
                res.add(Paths.get(filePath));
            }
            return res.toArray(new Path[0]);
        } else if (hasExtension(APK_SUFFIX, moduleFiles[0])) {
            return new Path[] {Paths.get(paths[0])};
        } else { // apex
            // The Q build may not show apex path.
            if (paths.length == 0) {
                return new Path[] {Paths.get(APEX_DIR, packageName + APEX_SUFFIX)};
            }
            // There is an issue that some system apex are provided as decompressed file under
            // /data/apex/decompressed. We assume that the apex under /system/apex will
            // get decompressed and overwrite the decompressed variant in reload.
            // Log the cases for debugging.
            if (!paths[0].startsWith(APEX_DIR)) {
                LogUtil.CLog.w(
                        "The path of the system apex is not /system/apex. Actual paths are: %s",
                        Arrays.toString(paths));
            }
            return new Path[] {Paths.get(paths[0])};
        }
    }

    /** Check if the dir is a package dir by comparing the file numbers. */
    boolean isPackageDir(ITestDevice device, Path dirPathOnDevice, int packageFileNum)
            throws DeviceNotAvailableException, ModulePushError {
        CommandResult lsResult =
                device.executeShellV2Command(String.format("ls %s", dirPathOnDevice));
        if (!CommandStatus.SUCCESS.equals(lsResult.getStatus())) {
            throw new ModulePushError(
                    String.format("Failed to ls files in %s", dirPathOnDevice),
                    DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
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
     */
    @VisibleForTesting
    protected String[] getPathsOnDevice(ITestDevice device, String packageName)
            throws DeviceNotAvailableException, ModulePushError {
        String output = device.executeShellV2Command("pm path " + packageName).getStdout();
        String[] lines = output.split("\n");
        if (!output.contains("package") || lines.length == 0) {
            throw new ModulePushError(
                    String.format(
                            "Failed to find file paths of %s on the device %s. Error log\n '%s'",
                            packageName, device.getSerialNumber(), output),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
        List<String> paths = new ArrayList<>();
        for (String line : lines) {
            paths.add(line.substring(HEAD_LENGTH).trim());
        }
        return paths.toArray(new String[0]);
    }

    void pushPackageToDevice(
            ITestDevice device, File localFile, String filePathOnDevice, boolean isDir)
            throws DeviceNotAvailableException, ModulePushError {
        boolean success =
                isDir
                        ? device.pushDir(localFile, filePathOnDevice)
                        : device.pushFile(localFile, filePathOnDevice);

        if (success) {
            LogUtil.CLog.i(
                    "Local file %s got pushed to the preload path '%s",
                    localFile.getName(), filePathOnDevice);
        } else {
            throw new ModulePushError(
                    String.format(
                            "Failed to push File '%s' to '%s' on device %s.",
                            localFile, filePathOnDevice, device.getSerialNumber()),
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }
    }

    @VisibleForTesting
    protected void waitForDeviceToBeResponsive(long waitTime) {
        // Wait for device to be responsive.
        RunUtil.getDefault().sleep(waitTime);
    }

    /**
     * Reload all modules.
     *
     * @param device under test.
     * @param factory_reset or not.
     */
    void reloadAllModules(ITestDevice device, boolean factory_reset)
            throws DeviceNotAvailableException, TargetSetupError {
        if (factory_reset) {
            CommandResult cr = device.executeShellV2Command(ENABLE_TESTHARNESS);
            if (!CommandStatus.SUCCESS.equals(cr.getStatus())) {
                LogUtil.CLog.e("Failed to enable test harness mode: %s", cr.toString());
                throw new TargetSetupError(cr.toString(), device.getDeviceDescriptor());
            }
        } else {
            device.reboot();
        }
        device.waitForDeviceAvailable(mWaitTimeMs);
    }

    /**
     * Check if all apexes are activated.
     *
     * @param device under test.
     * @throws ModulePushError if activation failed.
     */
    protected void checkApexActivated(ITestDevice device, List<ModuleInfo> modules)
            throws DeviceNotAvailableException, ModulePushError {
        Set<ITestDevice.ApexInfo> activatedApexes;
        activatedApexes = device.getActiveApexes();

        if (activatedApexes.isEmpty()) {
            throw new ModulePushError(
                    String.format(
                            "Failed to retrieve activated apex on device %s. Empty set returned.",
                            device.getSerialNumber()),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        } else {
            LogUtil.CLog.i("Activated apex packages list after module/train installation:");
            for (ITestDevice.ApexInfo info : activatedApexes) {
                LogUtil.CLog.i("Activated apex: %s", info.toString());
            }
        }

        List<ModuleInfo> failToActivateApex = getModulesFailToActivate(modules, activatedApexes);

        if (!failToActivateApex.isEmpty()) {
            throw new ModulePushError(
                    String.format(
                            "Failed to activate %s on device %s.",
                            failToActivateApex, device.getSerialNumber()),
                    DeviceErrorIdentifier.FAIL_ACTIVATE_APEX);
        }
        LogUtil.CLog.i("Train activation succeed.");
    }

    /**
     * Get modules that failed to be activated.
     *
     * @param activatedApexes The set of the active apexes on device
     * @return a list containing the apexinfo of the input apex modules that failed to be activated.
     */
    protected List<ModuleInfo> getModulesFailToActivate(
            List<ModuleInfo> toInstall, Set<ITestDevice.ApexInfo> activatedApexes) {
        List<ModuleInfo> failToActivateApex = new ArrayList<>();
        HashMap<String, ITestDevice.ApexInfo> activatedApexInfo = new HashMap<>();
        for (ITestDevice.ApexInfo info : activatedApexes) {
            activatedApexInfo.put(info.name, info);
        }
        for (ModuleInfo moduleInfo : toInstall) {
            if (moduleInfo.isApk()) {
                continue;
            }
            if (!activatedApexInfo.containsKey(moduleInfo.packageName())) {
                failToActivateApex.add(moduleInfo);
            } else if (activatedApexInfo.get(moduleInfo.packageName()).versionCode
                    != Long.parseLong(moduleInfo.versionCode())) {
                failToActivateApex.add(moduleInfo);
            }
        }
        return failToActivateApex;
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
    void checkModuleVersions(ITestDevice device, List<ModuleInfo> pushedModules)
            throws DeviceNotAvailableException, ModulePushError {
        String newVersionCode;
        for (ModuleInfo moduleInfo : pushedModules) {
            newVersionCode =
                    getPackageVersioncodeOnDevice(
                            device, moduleInfo.packageName(), moduleInfo.isApk());
            if (newVersionCode.isEmpty() || !newVersionCode.equals(moduleInfo.versionCode())) {
                throw new ModulePushError(
                        "Failed to install package " + moduleInfo.packageName(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            LogUtil.CLog.i(
                    "Packages %s pushed! The expected version code is: %s, the actual is: %s",
                    moduleInfo.packageName(), moduleInfo.versionCode(), newVersionCode);
        }
    }

    static boolean hasExtension(String extension, File fileName) {
        return fileName.getName().endsWith(extension);
    }

    @VisibleForTesting
    ModuleInfo retrieveModuleInfo(File packageFile) throws ModulePushError {
        AaptParser parser = AaptParser.parse(packageFile);
        if (parser == null) {
            throw new ModulePushError(
                    String.format("Failed to parse package file %s", packageFile),
                    DeviceErrorIdentifier.AAPT_PARSER_FAILED);
        }
        return ModuleInfo.create(
                parser.getPackageName(),
                parser.getVersionCode(),
                packageFile.getName().endsWith(APK_SUFFIX));
    }
}
