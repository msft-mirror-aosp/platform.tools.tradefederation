/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tradefed.device;

import com.android.tradefed.config.Option;

/**
 * A simple implementation of a {@link IDeviceRecovery} that supports to recover the virtual device
 * to be online.
 */
public class RecoverVirtualDevice implements IDeviceRecovery {

    @Option(
            name = "recovered-device-by-cvd",
            description =
                    "Try to recover the device by cvd tool when the device is gone during test"
                            + " running.")
    protected boolean mRecoveredDeviceByCvd = false;

    @Override
    public void recoverDevice(IDeviceStateMonitor monitor, boolean recoverUntilOnline)
            throws DeviceNotAvailableException {
        throw new DeviceNotAvailableException("Not implemented");
    }

    @Override
    public void recoverDeviceRecovery(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        throw new DeviceNotAvailableException("Not implemented");
    }

    @Override
    public void recoverDeviceBootloader(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        throw new DeviceNotAvailableException("Not implemented");
    }

    @Override
    public void recoverDeviceFastbootd(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        throw new DeviceNotAvailableException("Not implemented");
    }
}
