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
import com.android.tradefed.util.KernelModuleUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A target preparer that install kernel modules. Please see
 * https://source.android.com/docs/core/architecture/kernel/modules for details.
 */
@OptionClass(alias = "install-kernel-module-preparer")
public class InstallKernelModulePreparer extends BaseTargetPreparer implements ILabPreparer {

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
            KernelModuleUtils.removeModuleWithDependency(
                    device, KernelModuleUtils.getDisplayedModuleName(modulePath));
        }

        for (String modulePath : mModulePaths) {
            KernelModuleUtils.installModule(
                    device, modulePath, String.join(" ", mInstallArgs), mInstallModuleTimeout);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        List<String> reversedModulePaths = new ArrayList<>(mModulePaths);
        Collections.reverse(reversedModulePaths);
        for (String modulePath : reversedModulePaths) {
            KernelModuleUtils.removeModuleWithDependency(
                    device, KernelModuleUtils.getDisplayedModuleName(modulePath));
        }
        if (!mPreExistingAdbRootState) {
            device.disableAdbRoot();
        }
    }
}
