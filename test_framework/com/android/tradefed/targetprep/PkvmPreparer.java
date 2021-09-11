/*
 * Copyright (C) 2021 Google Inc.
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
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

/**
 * Target preparer that tried to enable pKVM on the device.
 *
 * <p>If pKVM is not found to be enabled, this preparer assumes the device's bootloader supports an
 * oem fastboot command to control whether pKVM is enabled on boot.
 *
 * <ul>
 *   <li>fastboot oem pkvm enable -- will force-enable pKVM
 *   <li>fastboot oem pkvm disable -- will force-disable pKVM
 *   <li>fastboot oem pkvm auto -- will reset to the device's default
 * </ul>
 *
 * <p>If this command fails or is not supported, the tests will run without pKVM enabled so they may
 * wish to skip themselves under this condition.
 */
public final class PkvmPreparer extends BaseTargetPreparer {
    private boolean mPkvmAlreadyEnabled = false;
    private boolean mPkvmEnabledInSetUp = false;

    @Override
    public void setUp(TestInformation testInformation)
            throws BuildError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        mPkvmAlreadyEnabled = isPkvmEnabled(device);
        if (!mPkvmAlreadyEnabled) {
            CLog.i("pKVM was not already enabled");
            runFastbootOemPkvmCommand(device, "enable");
            mPkvmEnabledInSetUp = isPkvmEnabled(device);
            if (!mPkvmEnabledInSetUp) {
                CLog.w("pKVM could not be enabled");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        if (mPkvmEnabledInSetUp) {
            runFastbootOemPkvmCommand(testInformation.getDevice(), "auto");
        }
    }

    private boolean isPkvmEnabled(ITestDevice device) throws DeviceNotAvailableException {
        // Existence of /dev/kvm is the best indication we have at the moment.
        return device.doesFileExist("/dev/kvm");
    }

    private void runFastbootOemPkvmCommand(ITestDevice device, String mode)
            throws DeviceNotAvailableException {
        device.rebootIntoBootloader();
        device.executeFastbootCommand("oem", "pkvm", mode);
        device.reboot();
    }
}
