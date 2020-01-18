/*
 * Copyright (C) 2017 The Android Open Source Project
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
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;

/** Target preparer that reboots the device. */
@OptionClass(alias = "reboot-preparer")
public final class RebootTargetPreparer extends BaseTargetPreparer {

    @Option(name = "pre-reboot", description = "Reboot the device during setUp.")
    private boolean mPreReboot = true;

    @Option(name = "post-reboot", description = "Reboot the device during tearDown.")
    private boolean mPostReboot = false;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        if (mPreReboot) {
            ITestDevice device = testInfo.getDevice();
            device.reboot();
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mPostReboot) {
            ITestDevice device = testInfo.getDevice();
            device.reboot();
        }
    }
}
