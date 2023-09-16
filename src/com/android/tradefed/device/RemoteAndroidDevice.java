/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.ddmlib.IDevice;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.connection.DefaultConnection;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of a {@link ITestDevice} for a full stack android device connected via
 * adb connect.
 * Assume the device serial will be in the format <hostname>:<portnumber> in adb.
 */
public class RemoteAndroidDevice extends TestDevice {
    public static final long WAIT_FOR_ADB_CONNECT = 2 * 60 * 1000;

    protected static final long RETRY_INTERVAL_MS = 5000;
    protected static final int MAX_RETRIES = 5;
    protected static final long DEFAULT_SHORT_CMD_TIMEOUT = 20 * 1000;

    private static final Pattern IP_PATTERN =
            Pattern.compile(ManagedTestDeviceFactory.IPADDRESS_PATTERN);

    /**
     * Creates a {@link RemoteAndroidDevice}.
     *
     * @param device the associated {@link IDevice}
     * @param stateMonitor the {@link IDeviceStateMonitor} mechanism to use
     * @param allocationMonitor the {@link IDeviceMonitor} to inform of allocation state changes.
     */
    public RemoteAndroidDevice(IDevice device, IDeviceStateMonitor stateMonitor,
            IDeviceMonitor allocationMonitor) {
        super(device, stateMonitor, allocationMonitor);
    }

    /**
     * Check if the format of the serial is as expected <hostname>:port
     *
     * @return true if the format is valid, false otherwise.
     */
    public static boolean checkSerialFormatValid(String serialString) {
        String[] serial = serialString.split(":");
        if (serial.length == 2) {
            // Check first part is an IP
            Matcher match = IP_PATTERN.matcher(serial[0]);
            if (!match.find()) {
                return false;
            }
            // Check second part if a port
            try {
                Integer.parseInt(serial[1]);
                return true;
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEncryptionSupported() {
        // Prevent device from being encrypted since we won't have a way to decrypt on Remote
        // devices since fastboot cannot be use remotely
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMacAddress() {
        return null;
    }

    @Override
    public String getFastbootSerialNumber() {
        return "tcp:" + getSerialNumber();
    }

    @Override
    public boolean connectToWifiNetwork(Map<String, String> wifiSsidToPsk, boolean scanSsid)
            throws DeviceNotAvailableException {
        if (!getOptions().useCmdWifiCommands() || !enableAdbRoot()) {
            return super.connectToWifiNetwork(wifiSsidToPsk, scanSsid);
        }
        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            CommandResult enableOutput =
                    executeShellV2Command(String.format("cmd -w wifi set-wifi-enabled enabled"));
            if (!CommandStatus.SUCCESS.equals(enableOutput.getStatus())) {
                CLog.w(
                        "Failed to enable wifi. stdout: %s\nstderr:%s",
                        enableOutput.getStdout(), enableOutput.getStderr());
                return false;
            }
            for (Entry<String, String> wifiPair : wifiSsidToPsk.entrySet()) {
                CommandResult connectOutput =
                        executeShellV2Command(
                                String.format(
                                        "cmd wifi connect-network %s %s open",
                                        wifiPair.getKey(), wifiPair.getValue()));
                if (CommandStatus.SUCCESS.equals(connectOutput.getStatus())) {
                    CLog.i("Successfully connected to wifi network %s", wifiPair.getKey());
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.WIFI_AP_NAME, wifiPair.getKey());
                    success = true;
                    break;
                } else {
                    CLog.w(
                            "Failed to connect to wifi wifi. stdout: %s\nstderr:%s",
                            enableOutput.getStdout(), enableOutput.getStderr());
                }
            }
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.WIFI_CONNECT_TIME, System.currentTimeMillis() - startTime);
            InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.WIFI_CONNECT_COUNT, 1);
        }
        return success;
    }

    @Override
    public DeviceDescriptor getDeviceDescriptor(boolean shortDescriptor) {
        DeviceDescriptor descriptor = super.getDeviceDescriptor(shortDescriptor);
        if (getConnection() instanceof DefaultConnection) {
            String initialSerial = ((DefaultConnection) getConnection()).getInitialSerial();
            String initialIp = ((DefaultConnection) getConnection()).getInitialIp();
            Integer initialOffset =
                    ((DefaultConnection) getConnection()).getInitialDeviceNumOffset();
            if (initialIp != null) {
                // Specify ip/offset.
                descriptor = new DeviceDescriptor(descriptor, initialIp, initialOffset);
            }
        }
        return descriptor;
    }
}
