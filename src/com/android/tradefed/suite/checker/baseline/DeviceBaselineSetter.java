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

package com.android.tradefed.suite.checker.baseline;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

/** Abstract class used to create a device baseline setting. */
public abstract class DeviceBaselineSetter {

    /** Gets the unique name of the setter. */
    public abstract String getName();

    /** Sets the baseline setting for the device. */
    public abstract void setBaseline(ITestDevice mDevice) throws DeviceNotAvailableException;

    /**
     * Whether the baseline setting is under experiment stage. It is used for the rollout of a new
     * setting. Only the settings under experiment can be optionally enabled via the option
     * enable-experimental-device-baseline-setters. Other non experimental settings are force
     * applied unless the option enable-device-baseline-settings is set to false.
     */
    public boolean isExperimental() {
        return false;
    }
}
