/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.device.connection;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;

/**
 * Default connection representation of a device, assumed to be a standard adb connection of the
 * device.
 */
public class AdbTcpConnection extends DefaultConnection {

    protected static final long RETRY_INTERVAL_MS = 5000;
    protected static final int MAX_RETRIES = 5;
    protected static final long DEFAULT_SHORT_CMD_TIMEOUT = 20 * 1000;
    protected static final long WAIT_FOR_ADB_CONNECT = 2 * 60 * 1000;
    private static final String ADB_SUCCESS_CONNECT_TAG = "connected to";
    private static final String ADB_ALREADY_CONNECTED_TAG = "already";
    private static final String ADB_CONN_REFUSED = "Connection refused";

    // TODO: See how to implement the logs
    private File mAdbConnectLogs = null;

    public AdbTcpConnection(ConnectionBuilder builder) {
        super(builder);
    }

    @Override
    public void tearDownConnection() {
        super.tearDownConnection();
        FileUtil.deleteFile(mAdbConnectLogs);
    }

    @Override
    public void reconnect(String serial) throws DeviceNotAvailableException {
        super.reconnect(serial);
        adbTcpConnect(getHostName(serial), getPortNum(serial));
        waitForAdbConnect(serial, WAIT_FOR_ADB_CONNECT);
    }

    /**
     * Helper method to adb connect to a given tcp ip Android device
     *
     * @param host the hostname/ip of a tcp/ip Android device
     * @param port the port number of a tcp/ip device
     * @return true if we successfully connected to the device, false otherwise.
     */
    private boolean adbTcpConnect(String host, String port) {
        for (int i = 0; i < MAX_RETRIES; i++) {
            CommandResult result = adbConnect(host, port);
            if (CommandStatus.SUCCESS.equals(result.getStatus())
                    && result.getStdout().contains(ADB_SUCCESS_CONNECT_TAG)) {
                CLog.d(
                        "adb connect output: status: %s stdout: %s",
                        result.getStatus(), result.getStdout());

                // It is possible to get a positive result without it being connected because of
                // the ssh bridge. Retrying to get confirmation, and expecting "already connected".
                if (confirmAdbTcpConnect(host, port)) {
                    return true;
                }
            } else if (CommandStatus.SUCCESS.equals(result.getStatus())
                    && result.getStdout().contains(ADB_CONN_REFUSED)) {
                // If we find "Connection Refused", we bail out directly as more connect won't help
                return false;
            }
            CLog.d(
                    "adb connect output: status: %s stdout: %s stderr: %s, retrying.",
                    result.getStatus(), result.getStdout(), result.getStderr());
            getRunUtil().sleep((i + 1) * RETRY_INTERVAL_MS);
        }
        return false;
    }

    /** Check if the adb connection is enabled. */
    protected void waitForAdbConnect(String serial, final long waitTime)
            throws DeviceNotAvailableException {
        CLog.i("Waiting %d ms for adb connection.", waitTime);
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < waitTime) {
            if (confirmAdbTcpConnect(getHostName(serial), getPortNum(serial))) {
                CLog.d("Adb connection confirmed.");
                return;
            }
            getRunUtil().sleep(RETRY_INTERVAL_MS);
        }
        throw new DeviceNotAvailableException(
                String.format("No adb connection after %sms.", waitTime),
                serial,
                DeviceErrorIdentifier.FAILED_TO_CONNECT_TO_GCE);
    }

    private boolean confirmAdbTcpConnect(String host, String port) {
        CommandResult resultConfirmation = adbConnect(host, port);
        if (CommandStatus.SUCCESS.equals(resultConfirmation.getStatus())
                && resultConfirmation.getStdout().contains(ADB_ALREADY_CONNECTED_TAG)) {
            CLog.d("adb connect confirmed:\nstdout: %s\n", resultConfirmation.getStdout());
            return true;
        } else {
            CLog.d(
                    "adb connect confirmation failed:\nstatus:%s\nstdout: %s\nsterr: %s",
                    resultConfirmation.getStatus(),
                    resultConfirmation.getStdout(),
                    resultConfirmation.getStderr());
        }
        return false;
    }

    /** Run adb connect. */
    private CommandResult adbConnect(String host, String port) {
        IRunUtil runUtil = getRunUtil();
        if (mAdbConnectLogs != null) {
            runUtil = new RunUtil();
            runUtil.setEnvVariable("ADB_TRACE", "1");
        }
        CommandResult result =
                runUtil.runTimedCmd(
                        DEFAULT_SHORT_CMD_TIMEOUT,
                        "adb",
                        "connect",
                        String.format("%s:%s", host, port));
        if (mAdbConnectLogs != null) {
            try {
                FileUtil.writeToFile(result.getStderr(), mAdbConnectLogs, true);
                FileUtil.writeToFile(
                        "\n======= SEPARATOR OF ATTEMPTS =====\n", mAdbConnectLogs, true);
            } catch (IOException e) {
                CLog.e(e);
            }
        }
        return result;
    }

    /** Return the hostname associated with the device. Extracted from the serial. */
    public String getHostName(String serial) {
        if (!RemoteAndroidDevice.checkSerialFormatValid(serial)) {
            throw new RuntimeException(
                    String.format(
                            "Serial Format is unexpected: %s "
                                    + "should look like <hostname>:<port>",
                            serial));
        }
        return serial.split(":")[0];
    }

    /** Return the port number asociated with the device. Extracted from the serial. */
    public String getPortNum(String serial) {
        if (!RemoteAndroidDevice.checkSerialFormatValid(serial)) {
            throw new RuntimeException(
                    String.format(
                            "Serial Format is unexpected: %s "
                                    + "should look like <hostname>:<port>",
                            serial));
        }
        return serial.split(":")[1];
    }
}
