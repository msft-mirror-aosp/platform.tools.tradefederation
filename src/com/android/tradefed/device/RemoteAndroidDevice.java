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
import com.android.tradefed.util.FileUtil;

import java.io.File;
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

    private File mAdbConnectLogs = null;
    private String mInitialSerial;
    private String mInitialIpDevice;
    private String mInitialUser;
    private Integer mInitialDeviceNumOffset;

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
        if (getIDevice() instanceof IConfigurableVirtualDevice) {
            mInitialIpDevice = ((IConfigurableVirtualDevice) getIDevice()).getKnownDeviceIp();
            mInitialUser = ((IConfigurableVirtualDevice) getIDevice()).getKnownUser();
            mInitialDeviceNumOffset =
                    ((IConfigurableVirtualDevice) getIDevice()).getDeviceNumOffset();
        }
        mInitialSerial = getSerialNumber();
    }

    @Override
    public void postInvocationTearDown(Throwable exception) {
        super.postInvocationTearDown(exception);
        FileUtil.deleteFile(mAdbConnectLogs);
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
     * Give a receiver file where we can store all the adb connection logs for debugging purpose.
     */
    public void setAdbLogFile(File adbLogFile) {
        mAdbConnectLogs = adbLogFile;
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
    public DeviceDescriptor getDeviceDescriptor() {
        DeviceDescriptor descriptor = super.getDeviceDescriptor();
        if (getConnection() instanceof DefaultConnection) {
            String initialSerial = ((DefaultConnection) getConnection()).getInitialSerial();
            String initialIp = ((DefaultConnection) getConnection()).getInitialIp();
            if (initialIp != null) {
                // Alter the display for the console.
                descriptor =
                        new DeviceDescriptor(
                                descriptor, initialSerial, initialSerial + "[" + initialIp + "]");
            }
        }
        return descriptor;
    }

    /**
     * Returns the initial associated ip to the device if any. Returns null if no known initial ip.
     */
    public String getInitialIp() {
        return mInitialIpDevice;
    }

    /** Returns the initial known user if any. Returns null if no initial known user. */
    public String getInitialUser() {
        return mInitialUser;
    }

    /** Returns the known device num offset if any. Returns null if not available. */
    public Integer getInitialDeviceNumOffset() {
        return mInitialDeviceNumOffset;
    }

    /** Returns the initial serial name of the device. */
    public String getInitialSerial() {
        return mInitialSerial;
    }
}
