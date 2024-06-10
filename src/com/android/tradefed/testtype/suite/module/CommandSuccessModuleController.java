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
package com.android.tradefed.testtype.suite.module;

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.suite.module.IModuleController.RunStrategy;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Base class for a module controller to skip test module based on shell command failure. */
public class CommandSuccessModuleController extends BaseModuleController {
    @Option(name = "run-command", description = "adb shell command to run")
    private List<String> mCommands = new ArrayList<>();

    @Option(
            name = "command-timeout",
            description =
                    "timeout to fail the check if command is hanging. "
                            + "Default unit is millis but '12s34ms' format is accepted.")
    private Duration mTimeout = Duration.ZERO;

    @Override
    public RunStrategy shouldRun(IInvocationContext context) throws DeviceNotAvailableException {
        for (ITestDevice device : context.getDevices()) {

            for (String cmd : mCommands) {
                CommandResult result =
                        device.executeShellV2Command(
                                cmd, mTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                    LogUtil.CLog.d(
                            "Skipping module %s because shell command '%s' failed with exit code"
                                    + " %d, stderr '%s', status '%s'",
                            getModuleName(),
                            cmd,
                            result.getExitCode(),
                            result.getStderr(),
                            result.getStatus());
                    return RunStrategy.FULL_MODULE_BYPASS;
                }
            }
        }

        return RunStrategy.RUN;
    }
}
