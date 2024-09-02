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
package com.android.tradefed.util;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KernelModuleUtils {

    /** Remove `.ko` extension if present */
    public static String removeKoExtension(String s) {
        return s.endsWith(".ko") ? s.substring(0, s.length() - 3) : s;
    }

    /**
     * Return module name as it's displayed after loading.
     *
     * <p>For example, see the difference between the file name and that returned by `lsmod`:
     *
     * <pre>{@code
     * $ insmod kunit.ko
     * $ lsmod | grep kunit
     * kunit 20480 0
     * }</pre>
     */
    public static String getDisplayedModuleName(String fullPath) {

        // Extract filename from full path
        int sepPos = fullPath.lastIndexOf('/');
        String moduleName = sepPos == -1 ? fullPath : fullPath.substring(sepPos + 1);
        if (moduleName.isEmpty()) {
            throw new IllegalArgumentException("input should not end with \"/\"");
        }

        // Remove `.ko` extension if present
        moduleName = removeKoExtension(moduleName);

        // Replace all '-' with '_'
        return moduleName.replace('-', '_');
    }

    /**
     * Return the names of the modules that the given module depends on.
     *
     * <p>For example, if the given module is `kunit`, and the `lsmod` output is:
     *
     * <pre>{@code
     * $ lsmod
     * Module        Size    Used by
     * kunit_test    663552  0
     * time_test     663558  0
     * kunit         57344   15 kunit_test,time_test
     * }</pre>
     *
     * Then this method will return an array containing `kunit_test` and `time_test`.
     */
    public static String[] getDependentModules(String modName, String lsmodOutput) {

        Pattern pattern =
                Pattern.compile(
                        String.format("^%s\\s+\\d+\\s+\\d+\\s+(\\S*)$", modName),
                        Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(lsmodOutput);
        if (matcher.find()) {
            String dependModNames = matcher.group(1);
            CLog.i("%s has depending modules: %s", modName, dependModNames);
            return dependModNames.split(",");
        } else {
            return new String[0];
        }
    }

    /**
     * Install a kernel module on the given device.
     *
     * @param device the device to install the module on
     * @param modulePath the path to the module to install
     * @param arg the argument to pass to the install command
     * @param timeoutMs the timeout in milliseconds
     * @throws TargetSetupError if the module cannot be installed
     * @throws DeviceNotAvailableException if the device is not available
     */
    public static CommandResult installModule(
            ITestDevice device, String modulePath, String arg, long timeoutMs)
            throws TargetSetupError, DeviceNotAvailableException {

        String command = String.format("insmod %s %s", modulePath, arg);
        CLog.i("Installing %s on %s", modulePath, device.getSerialNumber());
        CommandResult result =
                device.executeShellV2Command(command, timeoutMs, TimeUnit.MILLISECONDS);
        if (result == null) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to get return from command '%s' from %s",
                            command, device.getSerialNumber()),
                    DeviceErrorIdentifier.KERNEL_MODULE_INSTALLATION_FAILED);
        }
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            String moduleName = getDisplayedModuleName(modulePath);
            String errorMessage =
                    String.format(
                            "shell command %s failed with exit code: %d, stderr: %s, stdout:"
                                    + " %s",
                            command, result.getExitCode(), result.getStderr(), result.getStdout());
            CLog.e("Unable to install module '%s'. Error message: %s", moduleName, errorMessage);
            throw new TargetSetupError(
                    String.format(
                            "Failed to install %s on %s. Error message: '%s'",
                            moduleName, device.getSerialNumber(), errorMessage),
                    DeviceErrorIdentifier.KERNEL_MODULE_INSTALLATION_FAILED);
        }
        return result;
    }

    /**
     * Remove a kernel module from the given device.
     *
     * <p>This method attempts to remove the target kernel module from the device. No dependent
     * modules will be removed.
     *
     * @param device the device to remove the module from
     * @param moduleName the name to the module to remove
     * @throws DeviceNotAvailableException if the device is not available
     */
    public static CommandResult removeSingleModule(ITestDevice device, String moduleName)
            throws DeviceNotAvailableException {

        String command = String.format("rmmod %s", moduleName);
        CommandResult result = device.executeShellV2Command(command);
        if (result != null) {
            CLog.i("'%s' returned %s.", command, result.getStdout());
        }
        return result;
    }

    /**
     * Remove a kernel module and dependent modules from the given device.
     *
     * <p>This method attempts to remove the target kernel module from the device. If the module has
     * any dependencies, those dependent modules will be removed before the target module with best
     * effort.
     *
     * @param device the device to remove the module from
     * @param moduleName the name to the module to remove
     * @throws DeviceNotAvailableException if the device is not available
     */
    public static CommandResult removeModuleWithDependency(ITestDevice device, String moduleName)
            throws DeviceNotAvailableException {

        String output = device.executeShellCommand("lsmod");
        CLog.d("lsmod output: %s from %s", output, device.getSerialNumber());
        for (String modName : getDependentModules(moduleName, output)) {
            String trimmedName = modName.trim();
            removeSingleModule(device, trimmedName);
        }

        // Clean up, unload module with best effort
        return removeSingleModule(device, moduleName);
    }
}
