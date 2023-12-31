/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.tradefed.targetprep.multi;

import com.android.loganalysis.util.config.OptionClass;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.Sl4aBluetoothUtil;
import com.android.tradefed.util.Sl4aBluetoothUtil.BluetoothAccessLevel;
import com.android.tradefed.util.Sl4aBluetoothUtil.BluetoothPriorityLevel;
import com.android.tradefed.util.Sl4aBluetoothUtil.BluetoothProfile;

import com.google.common.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A multi-target preparer helps make Bluetooth pairing (and connection) between two devices. */
@OptionClass(alias = "bluetooth-multi-target-pairing")
public class PairingMultiTargetPreparer extends BaseMultiTargetPreparer {

    @Option(
            name = "bt-connection-primary-device",
            description = "The target name of the primary device during BT pairing.",
            mandatory = true)
    private String mPrimaryDeviceName;

    @Option(
            name = "with-connection",
            description =
                    "Connect the profiles once the devices are paired."
                            + " If true, given bluetooth profiles will be connected."
                            + " If false, the connection status is non-deterministic."
                            + " Devices will be paired but connection will not be done explicitly."
                            + " The connection status depends on the type of device,"
                            + " i.e. some devices will automatically connect, but some won't.")
    private boolean mConnectDevices = true;

    @Option(
            name = "bt-profile",
            description =
                    "A set of Bluetooth profiles that will be connected if connection is needed."
                            + " They should be specified as Bluetooth profile name defined in"
                            + " android.bluetooth.BluetoothProfile")
    private Set<BluetoothProfile> mProfiles = new HashSet<>();

    @Option(
            name = "bt-pairing-timeout",
            description = "Set the timeout (default in ms) to wait for two devices to be paired")
    private Duration mPairingTimeout = Duration.ofMinutes(1).plusSeconds(30);

    @VisibleForTesting
    void setBluetoothUtil(Sl4aBluetoothUtil util) {
        mUtil = util;
    }

    private ITestDevice mPrimaryDevice;
    private ITestDevice mCompanionDevice;
    private Sl4aBluetoothUtil mUtil = new Sl4aBluetoothUtil();

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        setDeviceInfos(testInformation.getContext().getDeviceBuildMap());
        try {
            CLog.d("Enabling bluetooth on %s", mPrimaryDevice.getDeviceDescriptor());
            if (!mUtil.enable(mPrimaryDevice)) {
                throw new TargetSetupError(
                        "Failed to enable Bluetooth",
                        mPrimaryDevice.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            CLog.d("Enabling bluetooth on %s", mCompanionDevice.getDeviceDescriptor());
            if (!mUtil.enable(mCompanionDevice)) {
                throw new TargetSetupError(
                        "Failed to enable Bluetooth",
                        mCompanionDevice.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            mUtil.setBtPairTimeout(mPairingTimeout);
            CLog.d("Starting pairing bluetooth on %s", mPrimaryDevice.getDeviceDescriptor());
            if (!mUtil.pair(mPrimaryDevice, mCompanionDevice)) {
                throw new TargetSetupError(
                        "Bluetooth pairing failed.",
                        mPrimaryDevice.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_FAILED_BLUETOOTH_PAIRING);
            }
            // Always enable PBAP between primary and companion devices in case it's not enabled
            // For now, assume PBAP client profile is always on primary device, and enable PBAP on
            // companion device.
            CLog.d("Enabling PBAP on %s", mCompanionDevice.getDeviceDescriptor());
            if (!mUtil.changeProfileAccessPermission(
                    mCompanionDevice,
                    mPrimaryDevice,
                    BluetoothProfile.PBAP,
                    BluetoothAccessLevel.ACCESS_ALLOWED)) {
                throw new TargetSetupError(
                        "Failed to allow PBAP access",
                        mCompanionDevice.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            CLog.d("Enabling PBAP_CLIENT on %s", mPrimaryDevice.getDeviceDescriptor());
            if (!mUtil.setProfilePriority(
                    mPrimaryDevice,
                    mCompanionDevice,
                    Collections.singleton(BluetoothProfile.PBAP_CLIENT),
                    BluetoothPriorityLevel.PRIORITY_ON)) {
                throw new TargetSetupError(
                        "Failed to turn on PBAP client priority",
                        mPrimaryDevice.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }
            CLog.d("Connecting to profiles");
            if (mConnectDevices && mProfiles.size() > 0) {
                if (!mUtil.connect(mPrimaryDevice, mCompanionDevice, mProfiles)) {
                    throw new TargetSetupError(
                            "Failed to connect bluetooth profiles",
                            mPrimaryDevice.getDeviceDescriptor(),
                            DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
                }
            }
        } finally {
            mUtil.stopSl4a();
        }
    }

    private void setDeviceInfos(Map<ITestDevice, IBuildInfo> deviceInfos)
            throws TargetSetupError, DeviceNotAvailableException {
        List<ITestDevice> devices = new ArrayList<>(deviceInfos.keySet());
        if (devices.size() != 2) {
            throw new TargetSetupError(
                    "The preparer assumes 2 devices only",
                    devices.get(0).getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        int primaryIdx = mPrimaryDeviceName.equals(devices.get(0).getProductType()) ? 0 : 1;
        mPrimaryDevice = devices.get(primaryIdx);
        mCompanionDevice = devices.get(1 - primaryIdx);
    }
}
