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

package com.android.tradefed.targetprep;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An {@link ITargetPreparer} that creates a work profile in setup, and marks that tests should be
 * run in that user.
 *
 * <p>In teardown, the work profile is removed.
 *
 * <p>If a work profile already exists, it will be used rather than creating a new one, and it will
 * not be removed in teardown.
 *
 * <p>If the device does not have the managed_users feature, or does not have capacity to create a
 * new user when one is required, then the instrumentation argument skip-tests-reason will be set,
 * and the user will not be changed. Tests running on the device can read this argument to respond
 * to this state.
 */
@OptionClass(alias = "run-on-work-profile")
public class RunOnWorkProfileTargetPreparer extends BaseTargetPreparer
        implements IConfigurationReceiver {

    @VisibleForTesting static final String RUN_TESTS_AS_USER_KEY = "RUN_TESTS_AS_USER";

    @VisibleForTesting static final String TEST_PACKAGE_NAME_OPTION = "test-package-name";

    @VisibleForTesting static final String SKIP_TESTS_REASON_KEY = "skip-tests-reason";

    private IConfiguration mConfiguration;

    private int mUserIdToDelete = -1;
    private DeviceOwner mDeviceOwnerToSet = null;

    private static class DeviceOwner {
        final String componentName;
        final int userId;

        DeviceOwner(String componentName, int userId) {
            this.componentName = componentName;
            this.userId = userId;
        }
    }

    @Option(
            name = TEST_PACKAGE_NAME_OPTION,
            description =
                    "the name of a package to be installed on the work profile. "
                            + "This must already be installed on the device.",
            importance = Option.Importance.IF_UNSET)
    private List<String> mTestPackages = new ArrayList<>();

    @Override
    public void setConfiguration(IConfiguration configuration) {
        if (configuration == null) {
            throw new NullPointerException("configuration must not be null");
        }
        mConfiguration = configuration;
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        if (!requireFeatures(testInfo.getDevice(), "android.software.managed_users")) {
            return;
        }

        int workProfileId = getWorkProfileId(testInfo.getDevice());

        if (workProfileId == -1) {
            if (!assumeTrue(
                    canCreateAdditionalUsers(testInfo.getDevice(), 1),
                    "Device cannot support additional users",
                    testInfo.getDevice())) {
                return;
            }

            mDeviceOwnerToSet = getDeviceOwner(testInfo.getDevice());

            if (mDeviceOwnerToSet != null) {
                CLog.d(
                        "Work profiles cannot be created after device owner is set. Attempting to"
                                + " remove device owner");
                removeDeviceOwner(testInfo.getDevice(), mDeviceOwnerToSet);
            }

            workProfileId = createWorkProfile(testInfo.getDevice());
            mUserIdToDelete = workProfileId;
        }

        // The wait flag is only supported on Android 29+
        testInfo.getDevice()
                .startUser(workProfileId, /* waitFlag= */ testInfo.getDevice().getApiLevel() >= 29);

        for (String pkg : mTestPackages) {
            testInfo.getDevice()
                    .executeShellCommand("pm install-existing --user " + workProfileId + " " + pkg);
        }

        testInfo.properties().put(RUN_TESTS_AS_USER_KEY, Integer.toString(workProfileId));
    }

    /** Get the id of a work profile currently on the device. -1 if there is none */
    private static int getWorkProfileId(ITestDevice device) throws DeviceNotAvailableException {
        for (Map.Entry<Integer, UserInfo> userInfo : device.getUserInfos().entrySet()) {
            if (userInfo.getValue().isManagedProfile()) {
                return userInfo.getKey();
            }
        }
        return -1;
    }

    /** Creates a work profile and returns the new user ID. */
    private static int createWorkProfile(ITestDevice device) throws DeviceNotAvailableException {
        int parentProfile = device.getCurrentUser();
        String command = "pm create-user --profileOf " + parentProfile + " --managed work";
        final String createUserOutput = device.executeShellCommand(command);

        try {
            return Integer.parseInt(createUserOutput.split(" id ")[1].trim());
        } catch (RuntimeException e) {
            throwCommandError("Error creating work profile", command, createUserOutput, e);
            return -1; // Never reached as showCommandError throws an exception
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        testInfo.properties().remove(RUN_TESTS_AS_USER_KEY);
        if (mUserIdToDelete != -1) {
            testInfo.getDevice().removeUser(mUserIdToDelete);
        }

        if (mDeviceOwnerToSet != null) {
            testInfo.getDevice()
                    .setDeviceOwner(mDeviceOwnerToSet.componentName, mDeviceOwnerToSet.userId);
        }
    }

    private boolean requireFeatures(ITestDevice device, String... features)
            throws TargetSetupError, DeviceNotAvailableException {
        for (String feature : features) {
            if (!assumeTrue(
                    device.hasFeature(feature),
                    "Device does not have feature " + feature,
                    device)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Disable teardown and set the {@link #SKIP_TESTS_REASON_KEY} if {@code value} isn't true.
     *
     * <p>This will return {@code value} and, if it is not true, setup should be skipped.
     */
    private boolean assumeTrue(boolean value, String reason, ITestDevice device)
            throws TargetSetupError {
        if (!value) {
            setDisableTearDown(true);
            try {
                mConfiguration.injectOptionValue(
                        "instrumentation-arg", SKIP_TESTS_REASON_KEY, reason.replace(" ", "\\ "));
            } catch (ConfigurationException e) {
                throw new TargetSetupError(
                        "Error setting skip-tests-reason", e, device.getDeviceDescriptor());
            }
        }

        return value;
    }

    /** Checks whether it is possible to create the desired number of users. */
    protected boolean canCreateAdditionalUsers(ITestDevice device, int numberOfUsers)
            throws DeviceNotAvailableException {
        return device.listUsers().size() + numberOfUsers <= device.getMaxNumberOfUsersSupported();
    }

    private DeviceOwner getDeviceOwner(ITestDevice device) throws DeviceNotAvailableException {
        String command = "dumpsys device_policy";
        String dumpsysOutput = device.executeShellCommand(command);

        if (!dumpsysOutput.contains("Device Owner:")) {
            return null;
        }

        try {
            String deviceOwnerOnwards = dumpsysOutput.split("Device Owner:", 2)[1];
            String componentName =
                    deviceOwnerOnwards.split("ComponentInfo\\{", 2)[1].split("}", 2)[0];
            int userId =
                    Integer.parseInt(
                            deviceOwnerOnwards.split("User ID: ", 2)[1].split("\n", 2)[0].trim());
            return new DeviceOwner(componentName, userId);
        } catch (RuntimeException e) {
            throwCommandError("Error reading device owner information", command, dumpsysOutput, e);
            return null; // Never reached as showCommandError throws an exception
        }
    }

    private void removeDeviceOwner(ITestDevice device, DeviceOwner deviceOwner)
            throws DeviceNotAvailableException {
        String command =
                "dpm remove-active-admin --user "
                        + deviceOwner.userId
                        + " "
                        + deviceOwner.componentName;

        String commandOutput = device.executeShellCommand(command);
        if (!commandOutput.startsWith("Success")) {
            throwCommandError("Error removing device owner", command, commandOutput);
        }
    }

    private static void throwCommandError(String error, String command, String commandOutput) {
        throwCommandError(error, command, commandOutput, /* exception= */ null);
    }

    private static void throwCommandError(
            String error, String command, String commandOutput, Exception exception) {
        throw new IllegalStateException(
                error + ". Command was '" + command + "', output was '" + commandOutput + "'",
                exception);
    }
}
