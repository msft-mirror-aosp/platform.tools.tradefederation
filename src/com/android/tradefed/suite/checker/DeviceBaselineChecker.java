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
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Set device baseline settings before each module. */
public class DeviceBaselineChecker implements ISystemStatusChecker {

    private static final String DEVICE_BASELINE_CONFIG_FILE = "/config/checker/baseline_config.json";
    // Thread pool size to set device baselines.
    private static final int N_THREAD = 8;
    private static final String SET_SUCCESS_MESSAGE = "SUCCESS";
    private List<DeviceBaselineSetter> mDeviceBaselineSetters;

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

    public static String getSetSuccessMessage() {
        return SET_SUCCESS_MESSAGE;
    }

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
                    // Create a setter according to the class name.
                    String className = objectValue.getString("class_name");
                    Class<? extends DeviceBaselineSetter> setterClass =
                            (Class<? extends DeviceBaselineSetter>) Class.forName(className);
                    Constructor<? extends DeviceBaselineSetter> constructor =
                            setterClass.getConstructor(JSONObject.class, String.class);
                    deviceBaselineSetters.add(constructor.newInstance(objectValue, name));
                }
            } catch (JSONException
                    | IOException
                    | ClassNotFoundException
                    | NoSuchMethodException
                    | InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        setDeviceBaselineSetters(deviceBaselineSetters);
    }

    /** {@inheritDoc} */
    @Override
    public StatusCheckerResult preExecutionCheck(ITestDevice device)
            throws DeviceNotAvailableException {
        if (mDeviceBaselineSetters == null) {
            initializeDeviceBaselineSetters();
        }
        StatusCheckerResult result = new StatusCheckerResult(CheckStatus.SUCCESS);
        StringBuilder errorMessage = new StringBuilder();
        ExecutorService pool = Executors.newFixedThreadPool(N_THREAD);
        List<SetterHelper> setterHelperList = new ArrayList<>();
        for (DeviceBaselineSetter setter : mDeviceBaselineSetters) {
            // Check if the device baseline setting should be skipped.
            if (setter.isExperimental()
                    && !mEnableExperimentDeviceBaselineSetters.contains(setter.getName())) {
                continue;
            }
            setterHelperList.add(new SetterHelper(setter, device));
        }
        try {
            // Set device baseline settings in parallel.
            List<Future<String>> setterResultList = pool.invokeAll(setterHelperList);
            for (Future<String> setterResult : setterResultList) {
                if (!SET_SUCCESS_MESSAGE.equals(setterResult.get())) {
                    result.setStatus(CheckStatus.FAILED);
                    errorMessage.append(setterResult.get());
                }
            }
        } catch (ExecutionException e) {
            result.setStatus(CheckStatus.FAILED);
            errorMessage.append(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setStatus(CheckStatus.FAILED);
            errorMessage.append(e.getMessage());
        } finally {
            pool.shutdown();
        }
        if (result.getStatus() == CheckStatus.FAILED) {
            result.setErrorMessage(errorMessage.toString());
            result.setBugreportNeeded(true);
        }
        return result;
    }
}

class SetterHelper implements Callable<String> {

    private final DeviceBaselineSetter mSetter;
    private final ITestDevice mDevice;

    SetterHelper(DeviceBaselineSetter setter, ITestDevice device) {
        mSetter = setter;
        mDevice = device;
    }

    @Override
    public String call() throws Exception {
        // Set device baseline settings.
        if (!mSetter.setBaseline(mDevice)) {
            return String.format("Failed to set baseline %s. ", mSetter.getName());
        }
        return DeviceBaselineChecker.getSetSuccessMessage();
    }
}
