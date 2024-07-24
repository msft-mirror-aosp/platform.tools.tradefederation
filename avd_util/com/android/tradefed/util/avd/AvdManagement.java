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

package com.android.tradefed.util.avd;

import android.virtualdevice.proto.LeaseAvdDeviceRequest;
import android.virtualdevice.proto.LeaseAvdDeviceResponse;
import android.virtualdevice.proto.ReleaseAvdDeviceRequest;
import android.virtualdevice.proto.ReleaseAvdDeviceResponse;

import com.android.tradefed.util.IRunUtil;

/** A utility for AVD management */
public class AvdManagement {
    private IRunUtil mRunUtil;

    IRunUtil getRunUtil() {
        return mRunUtil;
    }

    /**
     * Helper method to lease AVD device
     *
     * @param request detailed info about the device to lease
     * @return {@link LeaseAvdDeviceResponse} device leased
     */
    public LeaseAvdDeviceResponse leaseDevice(LeaseAvdDeviceRequest request) {
        // TODO(dshi): Implement the method
        return null;
    }

    /**
     * Helper method to release AVD device
     *
     * @param request detailed info about the device to release
     * @return {@link ReleaseAvdDeviceResponse} result about the release device action.
     */
    public ReleaseAvdDeviceResponse releaseDevice(ReleaseAvdDeviceRequest request) {
        // TODO(dshi): Implement the method
        return null;
    }
}
