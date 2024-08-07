/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * DeviceSelectionOptions#gceDeviceRequested()} is <code>true</code>
 */
public class RemoteAvdIDevice extends StubDevice implements IConfigurableVirtualDevice {

    private String mKnownDeviceIp = null;
    protected String mUser = null;
    protected Integer mDeviceNumOffset = null;

    /** @param serial placeholder for the real serial */
    public RemoteAvdIDevice(String serial) {
        super(serial);
    }

    public RemoteAvdIDevice(String serial, String knownDeviceIp) {
        super(serial, false);
        this.mKnownDeviceIp = knownDeviceIp;
    }

    public RemoteAvdIDevice(String serial, String knownDeviceIp, String user, Integer offset) {
        super(serial, false);
        this.mKnownDeviceIp = knownDeviceIp;
        this.mUser = user;
        this.mDeviceNumOffset = offset;
    }

    /** {@inheritDoc} */
    @Override
    public String getKnownDeviceIp() {
        return mKnownDeviceIp;
    }

    /** {@inheritDoc} */
    @Override
    public String getKnownUser() {
        return mUser;
    }

    /** {@inheritDoc} */
    @Override
    public Integer getDeviceNumOffset() {
        return mDeviceNumOffset;
    }
}
