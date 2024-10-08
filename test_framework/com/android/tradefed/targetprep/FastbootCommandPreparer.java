/*
 * Copyright (C) 2018 Google Inc.
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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Target preparer that triggers fastboot and sends fastboot commands.
 *
 * <p>TODO(b/122592575): Add tests for this preparer.
 */
@OptionClass(alias = "fastboot-command-preparer")
public final class FastbootCommandPreparer extends BaseTargetPreparer {

    /** Placeholder to be replaced with real file path in commands */
    private static final Pattern EXTRA_FILE_PATTERSTRING =
            Pattern.compile("\\$EXTRA_FILE\\(([^()]+)\\)");

    private enum FastbootMode {
        BOOTLOADER,
        FASTBOOTD,
    }

    @Option(
            name = "fastboot-mode",
            description = "True to boot the device into bootloader mode, false for fastbootd mode.")
    private FastbootMode mFastbootMode = FastbootMode.BOOTLOADER;

    @Option(
            name = "stay-fastboot",
            description =
                    "True to keep the device in bootloader or fastbootd mode after the commands"
                            + " executed.")
    private boolean mStayFastboot = false;

    @Option(
            name = "command",
            description =
                    "Fastboot commands to run in setup. Device will be rebooted after the commands"
                            + " executed.")
    private List<String> mFastbootCommands = new ArrayList<String>();

    @Option(
            name = "teardown-command",
            description =
                    "Fastboot commands to run in teardown. Device will be rebooted after the"
                            + " commands executed.")
    private List<String> mFastbootTearDownCommands = new ArrayList<String>();

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        if (!mFastbootCommands.isEmpty()) {
            final IBuildInfo buildInfo = testInformation.getBuildInfo();
            final ITestDevice device = testInformation.getDevice();
            enterFastboot(device);
            replaceExtraFile(mFastbootCommands, buildInfo);
            for (String cmd : mFastbootCommands) {
                final CommandResult result = device.executeFastbootCommand(cmd.split("\\s+"));
                if (result.getExitCode() != 0) {
                    throw new TargetSetupError(
                            String.format(
                                    "Command %s failed, stdout = [%s], stderr = [%s].",
                                    cmd, result.getStdout(), result.getStderr()),
                            device.getDeviceDescriptor(),
                            InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                }
            }
            exitFastboot(device);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        if (!mFastbootTearDownCommands.isEmpty()) {
            final ITestDevice device = testInformation.getDevice();
            final IBuildInfo buildInfo = testInformation.getBuildInfo();
            enterFastboot(device);
            replaceExtraFile(mFastbootTearDownCommands, buildInfo);
            for (String cmd : mFastbootTearDownCommands) {
                device.executeFastbootCommand(cmd.split("\\s+"));
            }
            exitFastboot(device);
        }
    }

    private void enterFastboot(ITestDevice device) throws DeviceNotAvailableException {
        if (mFastbootMode == FastbootMode.BOOTLOADER) {
            device.rebootIntoBootloader();
        } else {
            device.rebootIntoFastbootd();
        }
    }

    private void exitFastboot(ITestDevice device) throws DeviceNotAvailableException {
        if (!mStayFastboot) {
            device.reboot();
        }
    }

    /**
     * For each command in the list, replace placeholder (if any) with the file name indicated in
     * the build information
     *
     * @param commands list of host commands
     * @param buildInfo build artifact information
     */
    private void replaceExtraFile(final List<String> commands, IBuildInfo buildInfo) {
        for (int i = 0; i < commands.size(); i++) {
            Matcher matcher = EXTRA_FILE_PATTERSTRING.matcher(commands.get(i));
            StringBuffer command = new StringBuffer();

            while (matcher.find()) {
                String fileName = matcher.group(1);
                File file = buildInfo.getFile(fileName);
                if (file == null || !file.exists()) {
                    continue;
                }
                matcher.appendReplacement(command, file.getPath());
            }
            matcher.appendTail(command);
            commands.set(i, command.toString());
        }
    }
}