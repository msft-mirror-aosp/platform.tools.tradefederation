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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.suite.checker.StatusCheckerResult.CheckStatus;
import com.android.tradefed.suite.checker.baseline.DeviceBaselineSetter;
import com.android.tradefed.suite.checker.baseline.SettingsBaselineSetter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link DeviceBaselineChecker}. */
@RunWith(JUnit4.class)
public class DeviceBaselineCheckerTest {

    private DeviceBaselineChecker mChecker;
    private ITestDevice mMockDevice;
    private OptionSetter mOptionSetter;

    @Before
    public void setup() throws Exception {
        mMockDevice = mock(ITestDevice.class);
        mChecker = new DeviceBaselineChecker();
        mOptionSetter = new OptionSetter(mChecker);
    }

    /** Test that the baseline setting is set when it is included in the experiment list. */
    @Test
    public void testSetBaselineSettings_inExperimentList() throws Exception {
        mOptionSetter.setOptionValue("enable-device-baseline-settings", "true");
        mOptionSetter.setOptionValue("enable-experimental-device-baseline-setters", "test");
        DeviceBaselineSetter setter_exp =
                new SettingsBaselineSetter("test", "namespace", "key", "value") {
                    @Override
                    public boolean isExperimental() {
                        return true;
                    }
                };
        DeviceBaselineSetter setter_notExp =
                new SettingsBaselineSetter("test", "namespace", "key", "value") {
                    @Override
                    public boolean isExperimental() {
                        return false;
                    }
                };
        List<DeviceBaselineSetter> deviceBaselineSetters = new ArrayList<>();
        deviceBaselineSetters.add(setter_exp);
        deviceBaselineSetters.add(setter_notExp);
        mChecker.setDeviceBaselineSetters(deviceBaselineSetters);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        verify(mMockDevice, times(2)).setSetting("namespace", "key", "value");
    }

    /** Test that the baseline setting is skipped when it is not included in the experiment list. */
    @Test
    public void testSetBaselineSettings_notInExperimentList() throws Exception {
        mOptionSetter.setOptionValue("enable-device-baseline-settings", "true");
        DeviceBaselineSetter setter =
                new SettingsBaselineSetter("test", "namespace", "key", "value") {
                    @Override
                    public boolean isExperimental() {
                        return true;
                    }
                };
        List<DeviceBaselineSetter> deviceBaselineSetters = new ArrayList<>();
        deviceBaselineSetters.add(setter);
        mChecker.setDeviceBaselineSetters(deviceBaselineSetters);
        assertEquals(CheckStatus.SUCCESS, mChecker.preExecutionCheck(mMockDevice).getStatus());
        verify(mMockDevice, times(0)).setSetting("namespace", "key", "value");
    }

    /** Test that the status is set to failed when an exception occurs. */
    @Test
    public void testFailToSetBaselineSettings() throws Exception {
        mOptionSetter.setOptionValue("enable-device-baseline-settings", "true");
        DeviceBaselineSetter setter =
                new SettingsBaselineSetter("test", "namespace", "key", "value") {
                    @Override
                    public boolean isExperimental() {
                        return false;
                    }
                };
        List<DeviceBaselineSetter> deviceBaselineSetters = new ArrayList<>();
        deviceBaselineSetters.add(setter);
        mChecker.setDeviceBaselineSetters(deviceBaselineSetters);
        doThrow(new RuntimeException()).when(mMockDevice).setSetting("namespace", "key", "value");
        assertEquals(CheckStatus.FAILED, mChecker.preExecutionCheck(mMockDevice).getStatus());
        assertEquals(
                "Failed to set baseline test. ",
                mChecker.preExecutionCheck(mMockDevice).getErrorMessage());
    }
}
