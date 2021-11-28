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

/** A common setter to handle device baseline settings via ITestDevice.setSetting. */
public class SettingsBaselineSetter extends DeviceBaselineSetter {

    private final String name;
    private final String namespace;
    private final String key;
    private final String value;

    public SettingsBaselineSetter(String name, String namespace, String key, String value) {
        this.name = name;
        this.namespace = namespace;
        this.key = key;
        this.value = value;
    }

    @Override
    public void setBaseline(ITestDevice mDevice) throws DeviceNotAvailableException {
        mDevice.setSetting(namespace, key, value);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }
}
