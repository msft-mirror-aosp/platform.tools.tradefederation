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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates the DeviceConfig (feature flags tuned by a remote service) using values from files.
 *
 * <p>This can be used to reproduce the state of a device (by dumping all flag values to a file
 * using `adb shell device_config list`) or to bulk enable/disable flags (all-on/all-off testing).
 *
 * <p>The "flag-file" option can take one or more files with flag values to apply in
 * namespace/name=[value] format. For all-on / all-off testing, pass 1 flag file with all-on or
 * all-off flag values. For reversibility testing, pass all-on flag file then pass all-off flag
 * file.
 *
 * <p>Should be used in combination with {@link DeviceSetup} to disable DeviceConfig syncing during
 * the test which could overwrite the changes made by this preparer.
 */
@OptionClass(alias = "feature-flags")
public class FeatureFlagTargetPreparer extends BaseTargetPreparer {

    // Expected flag format (same as output by "device_config list"):
    // namespace/name=[value]
    // Note value is optional.
    private static final Pattern FLAG_PATTERN =
            Pattern.compile("^(?<namespace>[^\\s/=]+)/(?<name>[^\\s/=]+)=(?<value>.*)$");

    @Option(
            name = "flag-file",
            description = "File containing flag values to apply. Can be repeated.")
    private List<File> mFlagFiles = new ArrayList<>();

    private final Map<String, Map<String, String>> mFlagsToRestore = new HashMap<>();

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInformation.getDevice();
        validateFlagFiles(device);

        // Keep track of the initial flags to diff against input flag files.
        Map<String, Map<String, String>> initialFlags = null;

        for (File mFlagFile : mFlagFiles) {
            // Determine current and target flag values.
            Map<String, Map<String, String>> currentFlags = listFlags(device);
            if (initialFlags == null) {
                initialFlags = currentFlags;
            }
            Map<String, Map<String, String>> targetFlags = parseFlags(device, mFlagFile);

            for (String namespace : targetFlags.keySet()) {
                // Ignore unchanged flag values.
                Map<String, String> currentValues = currentFlags.getOrDefault(namespace, Map.of());
                Map<String, String> targetValues = targetFlags.get(namespace);
                // target flags - current flags = new flags to apply
                targetValues.entrySet().removeAll(currentValues.entrySet());
                // new flags to apply - initial flags = new flags to restore
                updateFlagsToRestore(
                        namespace, initialFlags.getOrDefault(namespace, Map.of()), targetValues);
            }

            if (targetFlags.values().stream().allMatch(Map::isEmpty)) {
                continue; // No flags to update.
            }
            updateFlags(device, targetFlags);
            device.reboot();
        }
    }

    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        if (e instanceof DeviceNotAvailableException
                || mFlagsToRestore.isEmpty()
                || mFlagsToRestore.values().stream().allMatch(Map::isEmpty)) {
            return;
        }
        try {
            ITestDevice device = testInformation.getDevice();
            updateFlags(device, mFlagsToRestore);
            device.reboot();
        } catch (TargetSetupError tse) {
            CLog.e("Failed to restore flags: %s", tse);
        }
    }

    private void validateFlagFiles(ITestDevice device) throws TargetSetupError {
        if (mFlagFiles.isEmpty()) {
            throw new TargetSetupError(
                    String.format(
                            "Flag file is required but not found. Make sure flag file(s) are"
                                    + " specified in the flag-file option."),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
        }
        for (File mFlagFile : mFlagFiles) {
            if (mFlagFile == null || !mFlagFile.isFile()) {
                throw new TargetSetupError(
                        String.format("Flag file '%s' not found", mFlagFile),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
            }
        }
    }

    private Map<String, Map<String, String>> listFlags(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        String values = runCommand(device, "device_config list");
        try (ByteArrayInputStream stream = new ByteArrayInputStream(values.getBytes())) {
            return parseFlags(stream);
        } catch (IOException ioe) {
            throw new TargetSetupError(
                    "Failed to parse device flags",
                    ioe,
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
    }

    private Map<String, Map<String, String>> parseFlags(ITestDevice device, File flagFile)
            throws TargetSetupError {
        try (FileInputStream stream = new FileInputStream(flagFile)) {
            return parseFlags(stream);
        } catch (IOException ioe) {
            throw new TargetSetupError(
                    "Failed to parse flag file",
                    ioe,
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.LAB_HOST_FILESYSTEM_ERROR);
        }
    }

    private Map<String, Map<String, String>> parseFlags(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            Map<String, Map<String, String>> flags = new HashMap<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                Matcher match = FLAG_PATTERN.matcher(line);
                if (!match.matches()) {
                    CLog.w("Skipping invalid flag data: %s", line);
                    continue;
                }
                String namespace = match.group("namespace");
                String name = match.group("name");
                String value = match.group("value");
                // In teardown(), null values in mFlagsToRestore means to delete the flag.
                // Set value to an empty String so that it can be persisted.
                value = value == null ? "" : value;
                flags.computeIfAbsent(namespace, ns -> new HashMap<>()).put(name, value);
            }
            return flags;
        }
    }

    private void updateFlagsToRestore(
            String namespace,
            Map<String, String> initialValues,
            Map<String, String> updatedValues) {
        // Keep track of flag values to restore.
        for (String name : updatedValues.keySet()) {
            String initialValue = initialValues.get(name);
            String updatedValue = updatedValues.get(name);
            if (Objects.equals(updatedValue, initialValue)) {
                // When the first file updates a default flag value and the second file sets the
                // flag back to its default, there is no need to restore this flag.
                mFlagsToRestore.getOrDefault(namespace, Map.of()).remove(name);
            } else {
                mFlagsToRestore
                        .computeIfAbsent(namespace, ns -> new HashMap<>())
                        .put(name, initialValue);
            }
        }
    }

    private void updateFlags(ITestDevice device, Map<String, Map<String, String>> flags)
            throws DeviceNotAvailableException, TargetSetupError {
        for (String namespace : flags.keySet()) {
            for (Map.Entry<String, String> entry : flags.get(namespace).entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                updateFlag(device, namespace, name, value);
            }
        }
    }

    private void updateFlag(ITestDevice device, String namespace, String name, String value)
            throws DeviceNotAvailableException, TargetSetupError {
        if (value == null) {
            runCommand(device, String.format("device_config delete '%s' '%s'", namespace, name));
        } else {
            runCommand(
                    device,
                    String.format("device_config put '%s' '%s' '%s'", namespace, name, value));
        }
    }

    private String runCommand(ITestDevice device, String command)
            throws DeviceNotAvailableException, TargetSetupError {
        CommandResult result = device.executeShellV2Command(command);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            throw new TargetSetupError(
                    String.format(
                            "Command %s failed, stdout = [%s], stderr = [%s]",
                            command, result.getStdout(), result.getStderr()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
        return result.getStdout();
    }
}
