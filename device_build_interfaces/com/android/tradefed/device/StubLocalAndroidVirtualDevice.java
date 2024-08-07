/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.ddmlib.IDevice;

/**
 * A placeholder {@link IDevice} used by {@link DeviceManager} to allocate when {@link
 * DeviceSelectionOptions#localVirtualDeviceRequested()} is <code>true</code>
 */
public class StubLocalAndroidVirtualDevice extends StubDevice implements IConfigurableVirtualDevice {

    private String mKnownDeviceIp = null;
    private int mDeviceNumOffset;

    public StubLocalAndroidVirtualDevice(String serial) {
        super(serial, false);
    }

    public StubLocalAndroidVirtualDevice(String serial, String knownDeviceIp) {
        super(serial, false);
        mKnownDeviceIp = knownDeviceIp;
    }

    public StubLocalAndroidVirtualDevice(String serial, int offset) {
        super(serial);
        mDeviceNumOffset = offset;
    }

    /** {@inheritDoc} */
    @Override
    public String getKnownDeviceIp() {
        return mKnownDeviceIp;
    }

    @Override
    public Integer getDeviceNumOffset() {
        return mDeviceNumOffset;
    }
}
