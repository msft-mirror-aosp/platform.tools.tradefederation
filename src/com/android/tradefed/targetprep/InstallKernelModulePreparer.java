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
package com.android.tradefed.targetprep;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A target preparer that flash the device with android common kernel generic image. Please see
 * https://source.android.com/devices/architecture/kernel/android-common for details.
 */
@OptionClass(alias = "install-kernel-module-preparer")
public class InstallKernelModulePreparer extends BaseTargetPreparer implements ILabPreparer {

    // Wait time for device state to stablize in millisecond
    private static final int STATE_STABLIZATION_WAIT_TIME = 60000;

    @Option(
            name = "module-path",
            description = "the filesystem path of the module to install. Can be repeated.",
            importance = Importance.IF_UNSET)
    private Collection<String> mModulePaths = new ArrayList<String>();

    @Option(
            name = "install-arg",
            description = "Additional arguments to be passed to kernel module install command")
    private Collection<String> mInstallArgs = new ArrayList<String>();

    @Option(
            name = "install-module-timeout",
            isTimeVal = true,
            description = "Timeout applied to each module installation.")
    private long mInstallModuleTimeout = 5 * 60 * 1000L;

    private boolean mPreExistingAdbRootState;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();

        mPreExistingAdbRootState = device.isAdbRoot();

        for (String modulePath : mModulePaths) {
            installModule(device, modulePath, String.join(" ", mInstallArgs));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        List<String> reversedModulePaths = new ArrayList<>(mModulePaths);
        Collections.reverse(reversedModulePaths);
        for (String modulePath : reversedModulePaths) {
            removeModule(device, modulePath);
        }
        if (!mPreExistingAdbRootState) {
            device.disableAdbRoot();
        }
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
    private String getDisplayedModuleName(String fullPath) {

        // Extract filename from full path
        int sepPos = fullPath.lastIndexOf('/');
        String moduleName = sepPos == -1 ? fullPath : fullPath.substring(sepPos + 1);
        if (moduleName.isEmpty()) {
            throw new IllegalArgumentException("input should not end with \"/\"");
        }

        // Remove `.ko` extension if present
        moduleName =
                moduleName.endsWith(".ko")
                        ? moduleName.substring(0, moduleName.length() - 3)
                        : moduleName;

        // Replace all '-' with '_'
        return moduleName.replace('-', '_');
    }

    private void installModule(ITestDevice device, String modulePath, String arg)
            throws TargetSetupError, DeviceNotAvailableException {

        String kernelModule = getDisplayedModuleName(modulePath);
        String command = String.format("rmmod %s", kernelModule);

        // Unload module before hand in case it's already loaded for some reason
        CommandResult result = device.executeShellV2Command(command);

        if (CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.w("Module '%s' unexpectedly still loaded, it has been unloaded.", kernelModule);
        }

        command = String.format("insmod %s %s", modulePath, arg);
        CLog.i("Installing %s on %s", modulePath, device.getSerialNumber());
        result =
                device.executeShellV2Command(command, mInstallModuleTimeout, TimeUnit.MILLISECONDS);
        if (result == null) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to get return from command '%s' from %s",
                            command, device.getSerialNumber()),
                    DeviceErrorIdentifier.KERNEL_MODULE_INSTALLATION_FAILED);
        }
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            String errorMessage =
                    String.format(
                            "shell command %s failed with exit code: %d, stderr: %s, stdout:"
                                    + " %s",
                            command, result.getExitCode(), result.getStderr(), result.getStdout());
            CLog.e("Unable to install module '%s'. Error message: %s", kernelModule, errorMessage);
            throw new TargetSetupError(
                    String.format(
                            "Failed to install %s on %s. Error message: '%s'",
                            kernelModule, device.getSerialNumber(), errorMessage),
                    DeviceErrorIdentifier.KERNEL_MODULE_INSTALLATION_FAILED);
        }
    }

    private void removeModule(ITestDevice device, String modulePath)
            throws DeviceNotAvailableException {

        String kernelModule = getDisplayedModuleName(modulePath);

        CLog.i("Remove kernel module %s from %s", kernelModule, device.getSerialNumber());

        // Clean up, unload module.
        CommandResult result =
                device.executeShellV2Command(String.format("rmmod %s", kernelModule));

        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            String errorMessage =
                    String.format(
                            "remove module returned non-zero. Exit code: %d, stderr: %s, stdout:"
                                    + " %s",
                            result.getExitCode(), result.getStderr(), result.getStdout());
            CLog.e("Unable to unload module '%s'. %s", kernelModule, errorMessage);
        } else {
            CLog.w("Successfully removed module '%s' from device.", kernelModule);
        }
    }
}
