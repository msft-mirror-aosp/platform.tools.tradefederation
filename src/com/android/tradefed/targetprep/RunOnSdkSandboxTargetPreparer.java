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
package com.android.tradefed.targetprep;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

/**
 * An {@link ITargetPreparer} to marks that tests should run in the sdk sandbox. See
 * https://developer.android.com/design-for-safety/privacy-sandbox/sdk-runtime
 */
@OptionClass(alias = "run-on-sdk-sandbox")
public class RunOnSdkSandboxTargetPreparer extends BaseTargetPreparer {

    public static final String RUN_TESTS_ON_SDK_SANDBOX = "RUN_TESTS_ON_SDK_SANDBOX";
    public static final String SDK_IN_SANDBOX_ACTIVITIES =
            "sdk_in_sandbox_tests_activities_enabled";
    public static final String ENABLE_TEST_ACTIVITIES_CMD =
            "device_config put adservices " + SDK_IN_SANDBOX_ACTIVITIES + " true";
    public static final String DISABLE_TEST_ACTIVITIES_CMD =
            "device_config put adservices " + SDK_IN_SANDBOX_ACTIVITIES + " false";

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        testInformation.properties().put(RUN_TESTS_ON_SDK_SANDBOX, Boolean.TRUE.toString());

        ITestDevice device = testInformation.getDevice();
        CommandResult result = device.executeShellV2Command(ENABLE_TEST_ACTIVITIES_CMD);
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to enable test activities. stdout: '%s'\nstderr: '%s'",
                            result.getStdout(), result.getStderr()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
        }
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        CommandResult result =
                testInformation.getDevice().executeShellV2Command(DISABLE_TEST_ACTIVITIES_CMD);
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.e(
                    String.format(
                            "Failed to disable test activities. stdout: '%s'\nstderr: '%s'",
                            result.getStdout(), result.getStderr()));
        }
    }
}
