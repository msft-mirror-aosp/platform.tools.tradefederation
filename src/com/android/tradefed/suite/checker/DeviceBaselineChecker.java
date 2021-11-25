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

package com.android.tradefed.suite.checker;

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.suite.checker.StatusCheckerResult.CheckStatus;
import com.android.tradefed.suite.checker.baseline.DeviceBaselineSetter;
import com.android.tradefed.suite.checker.baseline.SettingsBaselineSetter;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Set device baseline settings before each module. */
public class DeviceBaselineChecker implements ISystemStatusChecker {

    private static final String DEVICE_BASELINE_CONFIG_FILE =
            "/config/checker/baseline_config.json";
    private List<DeviceBaselineSetter> mDeviceBaselineSetters = null;

    @Option(
            name = "enable-device-baseline-settings",
            description =
                    "Whether or not to apply device baseline settings before each test module. ")
    private boolean mEnableDeviceBaselineSettings = true;

    @Option(
            name = "enable-experimental-device-baseline-setters",
            description =
                    "Set of experimental baseline setters to be enabled. "
                            + "Each value is the setter’s name")
    private Set<String> mEnableExperimentDeviceBaselineSetters = new HashSet<>();

    @VisibleForTesting
    void setDeviceBaselineSetters(List<DeviceBaselineSetter> deviceBaselineSetters) {
        mDeviceBaselineSetters = deviceBaselineSetters;
    }

    private void initializeDeviceBaselineSetters() {
        List<DeviceBaselineSetter> deviceBaselineSetters = new ArrayList<>();
        if (mEnableDeviceBaselineSettings) {
            // Generate device baseline setters by parsing the config file.
            try {
                InputStream configStream =
                        ITestSuite.class.getResourceAsStream(DEVICE_BASELINE_CONFIG_FILE);
                String jsonStr = StreamUtil.getStringFromStream(configStream);
                JSONObject jsonObject = new JSONObject(jsonStr);
                JSONArray names = jsonObject.names();
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    JSONObject objectValue = jsonObject.getJSONObject(name);
                    DeviceBaselineSetter deviceBaselineSetter =
                            new SettingsBaselineSetter(
                                    name,
                                    objectValue.getString("namespace"),
                                    objectValue.getString("key"),
                                    objectValue.getString("value"));
                    deviceBaselineSetters.add(deviceBaselineSetter);
                }
            } catch (JSONException | IOException e) {
                throw new RuntimeException(e);
            }
        }
        setDeviceBaselineSetters(deviceBaselineSetters);
    }

    /** {@inheritDoc} */
    @Override
    public StatusCheckerResult preExecutionCheck(ITestDevice mDevice)
            throws DeviceNotAvailableException {
        if (mDeviceBaselineSetters == null) {
            initializeDeviceBaselineSetters();
        }
        StatusCheckerResult result = new StatusCheckerResult(CheckStatus.SUCCESS);
        StringBuilder errorMessage = new StringBuilder();
        for (DeviceBaselineSetter setter : mDeviceBaselineSetters) {
            // Check if the device baseline setting should be skipped.
            if (setter.isExperimental()
                    && !mEnableExperimentDeviceBaselineSetters.contains(setter.getName())) {
                continue;
            }
            try {
                setter.setBaseline(mDevice);
            } catch (RuntimeException e) {
                result.setStatus(CheckStatus.FAILED);
                errorMessage.append(String.format("Failed to set baseline %s. ", setter.getName()));
            }
        }
        if (result.getStatus() == CheckStatus.FAILED) {
            result.setErrorMessage(errorMessage.toString());
        }
        return result;
    }
}
