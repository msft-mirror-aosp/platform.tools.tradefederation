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

package com.android.tradefed.suite.checker.baseline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CommandBaselineSetter}. */
@RunWith(JUnit4.class)
public final class CommandBaselineSetterTest {

    private ITestDevice mMockDevice;
    private CommandBaselineSetter mSetter;
    private JSONObject mJsonObject;
    private static final String SETTING_NAME = "test";
    private static final String SETTING_STRING = "{\"command\": \"input keyevent KEYCODE_HOME\"}";

    @Before
    public void setup() throws Exception {
        mMockDevice = mock(ITestDevice.class);
        mJsonObject = new JSONObject(SETTING_STRING);
        mSetter = new CommandBaselineSetter(mJsonObject, SETTING_NAME);
    }

    @Test
    public void commandDeviceBaselineSetter_noCommandField_throwsException() throws Exception {
        mJsonObject.remove("command");
        assertThrows(
                JSONException.class, () -> new CommandBaselineSetter(mJsonObject, SETTING_NAME));
    }

    /** Test that the setter returns true when the baseline is set successfully. */
    @Test
    public void setBaseline_setSucceeds_returnTrue() throws Exception {
        when(mMockDevice.executeShellV2Command("input keyevent KEYCODE_HOME"))
                .thenReturn(new CommandResult(CommandStatus.SUCCESS));
        assertTrue(mSetter.setBaseline(mMockDevice));
    }

    /** Test that the setter returns false when the baseline is failed to set. */
    @Test
    public void setBaseline_setFails_returnFalse() throws Exception {
        when(mMockDevice.executeShellV2Command("input keyevent KEYCODE_HOME"))
                .thenReturn(new CommandResult(CommandStatus.FAILED));
        assertFalse(mSetter.setBaseline(mMockDevice));
    }
}
