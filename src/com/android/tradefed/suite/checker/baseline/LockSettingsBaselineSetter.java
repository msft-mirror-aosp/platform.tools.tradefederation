/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A setter to remove screen lock settings. */
public class LockSettingsBaselineSetter extends DeviceBaselineSetter {
    private final List<String> mClearPwdCommands;
    private static final String GET_LOCK_SCREEN_COMMAND = "locksettings get-disabled";
    private static final String LOCK_SCREEN_OFF_COMMAND = "locksettings set-disabled true";
    private static final String CLEAR_PWD_COMMAND = "locksettings clear --old %s";
    private static final String KEYCODE_MENU_COMMAND = "input keyevent KEYCODE_MENU";
    private static final String KEYCODE_HOME_COMMAND = "input keyevent KEYCODE_HOME";

    public LockSettingsBaselineSetter(JSONObject object, String name) throws JSONException {
        super(object, name);
        List<String> clearPwdCommands = new ArrayList<>();
        JSONArray pwds = object.getJSONArray("clear_pwds");
        for (int index = 0; index < pwds.length(); index++) {
            clearPwdCommands.add(String.format(CLEAR_PWD_COMMAND, pwds.getString(index)));
        }
        mClearPwdCommands = clearPwdCommands;
    }

    @Override
    public boolean setBaseline(ITestDevice mDevice) throws DeviceNotAvailableException {
        if (!isLockScreenDisabled(mDevice)) {
            // Clear old passwords.
            for (String command : mClearPwdCommands) {
                mDevice.executeShellV2Command(command);
            }
            // Turn off lock-screen option.
            mDevice.executeShellV2Command(LOCK_SCREEN_OFF_COMMAND);
        }
        if (!isLockScreenDisabled(mDevice)) {
            return false;
        }
        CommandResult menuResult = mDevice.executeShellV2Command(KEYCODE_MENU_COMMAND);
        CommandResult homeResult = mDevice.executeShellV2Command(KEYCODE_HOME_COMMAND);
        return CommandStatus.SUCCESS.equals(menuResult.getStatus())
                && CommandStatus.SUCCESS.equals(homeResult.getStatus());
    }

    private boolean isLockScreenDisabled(ITestDevice mDevice) throws DeviceNotAvailableException {
        CommandResult result = mDevice.executeShellV2Command(GET_LOCK_SCREEN_COMMAND);
        return CommandStatus.SUCCESS.equals(result.getStatus())
                && result.getStdout() != null
                && "true".equals(result.getStdout().trim());
    }
}
