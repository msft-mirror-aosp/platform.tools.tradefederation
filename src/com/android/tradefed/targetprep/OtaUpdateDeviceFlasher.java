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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A device flasher that triggers system/update_engine/scripts/update_device.py script with a full
 * or incremental OTA package to update the device image. To properly use this flasher, the device
 * build info must contain a file entry named 'update-device-script' which points at the
 * above-mentioned script.
 */
public class OtaUpdateDeviceFlasher implements IDeviceFlasher {

    protected static final String UPDATE_DEVICE_SCRIPT = "update-device-script";
    protected static final String OTA_DOWNGRADE_PROP = "ro.ota.allow_downgrade";
    private static final long APPLY_OTA_PACKAGE_TIMEOUT_MINS = 25;
    protected static final String IN_ZIP_SCRIPT_PATH =
            String.join(File.separator, "bin", "update_device");

    private UserDataFlashOption mUserDataFlashOptions = null;
    private File mUpdateDeviceScript = null;
    private File mOtaPackage = null;
    private CommandStatus mOtaCommandStatus = null;

    @Override
    public void overrideDeviceOptions(ITestDevice device) {
        // no-op
    }

    @Override
    public void setFlashingResourcesRetriever(IFlashingResourcesRetriever retriever) {
        // no-op
    }

    @Override
    public void setUserDataFlashOption(UserDataFlashOption flashOption) {
        if (!UserDataFlashOption.RETAIN.equals(flashOption)) {
            if (!UserDataFlashOption.WIPE.equals(flashOption)) {
                CLog.i(
                        "Userdata flash option %s ignored, will use %s instead",
                        flashOption, UserDataFlashOption.WIPE);
            }
            // if not RETAIN then it's always WIPE
            mUserDataFlashOptions = UserDataFlashOption.WIPE;
        } else {
            mUserDataFlashOptions = UserDataFlashOption.RETAIN;
        }
    }

    @Override
    public void setDataWipeSkipList(Collection<String> dataWipeSkipList) {
        // no-op
    }

    @Override
    public UserDataFlashOption getUserDataFlashOption() {
        return mUserDataFlashOptions;
    }

    @Override
    public void setWipeTimeout(long timeout) {
        // no-op
    }

    @Override
    public void setForceSystemFlash(boolean forceSystemFlash) {
        // no-op
    }

    @Override
    public void preFlashOperations(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        // check that the update_device script path is specified
        mUpdateDeviceScript =
                new File(deviceBuild.getFile(UPDATE_DEVICE_SCRIPT), IN_ZIP_SCRIPT_PATH);
        if (mUpdateDeviceScript == null) {
            throw new TargetSetupError(
                    String.format(
                            "Missing %s entry in build info which should point at the update_device"
                                    + " script",
                            UPDATE_DEVICE_SCRIPT),
                    device.getDeviceDescriptor());
        }
        if (!mUpdateDeviceScript.exists() || !mUpdateDeviceScript.isFile()) {
            throw new TargetSetupError(
                    String.format(
                            "Specified update_device script at %s does not exist or is not a"
                                    + " regular file",
                            mUpdateDeviceScript.getAbsolutePath()),
                    device.getDeviceDescriptor());
        }
        if (!mUpdateDeviceScript.setExecutable(true)) {
            throw new TargetSetupError(
                    "Failed to set executable for " + mUpdateDeviceScript.getAbsolutePath(),
                    device.getDeviceDescriptor());
        }
        // check that the OTA package is present
        mOtaPackage = deviceBuild.getOtaPackageFile();
        if (mOtaPackage == null) {
            throw new TargetSetupError(
                    "Device build info is missing OTA package.", device.getDeviceDescriptor());
        }
    }

    @Override
    public void flash(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.FLASHING_METHOD, FlashingMethod.USERSPACE_OTA.toString());
        device.enableAdbRoot();
        // allow OTA downgrade since it can't be assumed that incoming builds are always newer
        device.setProperty(OTA_DOWNGRADE_PROP, "1");
        // trigger the actual flashing
        List<String> cmd =
                Arrays.asList(
                                mUpdateDeviceScript.getAbsolutePath(), // the script
                                "-s",
                                device.getSerialNumber(),
                                UserDataFlashOption.WIPE.equals(mUserDataFlashOptions)
                                        ? "--wipe-user-data"
                                        // set to null if no wipe, which will be filtered
                                        // out via lambda
                                        : null,
                                mOtaPackage.getAbsolutePath() // the OTA package
                                )
                        .stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
        CommandResult result =
                getRunUtil()
                        .runTimedCmd(
                                TimeUnit.MINUTES.toMillis(APPLY_OTA_PACKAGE_TIMEOUT_MINS),
                                cmd.toArray(new String[] {}));
        mOtaCommandStatus = result.getStatus();
        CLog.v("OTA script stdout: " + result.getStdout());
        CLog.v("OTA script stderr: " + result.getStderr());
        if (!CommandStatus.SUCCESS.equals(mOtaCommandStatus)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to apply OTA update to device. Exit Code: %d, Command Status:"
                                    + " %s. See host log for details.",
                            result.getExitCode(), mOtaCommandStatus),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.ERROR_AFTER_FLASHING);
        }
        // reboot to apply OTA and ensures that device is online before returning
        device.rebootUntilOnline();
    }

    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    @Override
    public CommandStatus getSystemFlashingStatus() {
        return mOtaCommandStatus;
    }
}
