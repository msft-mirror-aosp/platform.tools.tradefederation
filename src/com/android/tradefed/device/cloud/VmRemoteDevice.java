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
package com.android.tradefed.device.cloud;

import com.android.tradefed.device.IConfigurableVirtualDevice;
import com.android.tradefed.device.StubDevice;

/** A Remote virtual device that we will manage from inside the Virtual Machine. */
public class VmRemoteDevice extends StubDevice implements IConfigurableVirtualDevice {

    private String mKnownDeviceIp = null;

    public VmRemoteDevice(String serial) {
        super(serial, false);
    }

    public VmRemoteDevice(String serial, String knownIp) {
        super(serial, false);
        mKnownDeviceIp = knownIp;
    }

    /** {@inheritDoc} */
    @Override
    public String getKnownDeviceIp() {
        return mKnownDeviceIp;
    }
}
