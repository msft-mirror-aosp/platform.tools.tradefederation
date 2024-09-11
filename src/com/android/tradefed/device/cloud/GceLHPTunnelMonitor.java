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
package com.android.tradefed.device.cloud;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.RemoteAvdIDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.connection.AbstractConnection;
import com.android.tradefed.device.connection.AdbTcpConnection;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.avd.OxygenClient;
import com.android.tradefed.util.avd.OxygenClient.LHPTunnelMode;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Thread Monitor for the Gce lab host proxy tunnel used for oxygenation. */
public class GceLHPTunnelMonitor extends AbstractTunnelMonitor {
    private static final long WAIT_AFTER_REBOOT = 60 * 1000;
    private static final int WAIT_FOR_FIRST_CONNECT = 10 * 1000;
    private static final long DEFAULT_SHORT_CMD_TIMEOUT = 20 * 1000;
    private ITestDevice mDevice;
    private TestDeviceOptions mDeviceOptions;
    private IBuildInfo mBuildInfo;
    private boolean mQuit = false;
    private String mInstanceName = null;
    private String mSessionId = null;
    private String mDeviceId = null;
    private String mServerUrl = null;
    private Process mAdbLHPTunnelProcess;
    private HostAndPort mLocalHostAndPort;
    private boolean mAdbRebootCalled = false;
    private File mAdbConnectionLog = null;
    private File mAdbLHPTunnelLog = null;
    private Integer mPortNumber = null;

    /**
     * Constructor
     *
     * @param device {@link ITestDevice} the TF device to associate the remote GCE AVD with.
     * @param buildInfo {@link ITestDevice} the TF device to associate the remote GCE AVD with.
     * @param sessionId {@link ITestDevice} the TF device to associate the remote GCE AVD with.
     * @param deviceId {@link ITestDevice} the TF device to associate the remote GCE AVD with.
     * @param serverUrl {@link ITestDevice} the TF device to associate the remote GCE AVD with.
     * @param deviceOptions {@link HostAndPort} of the remote GCE AVD.
     */
    public GceLHPTunnelMonitor(
            ITestDevice device,
            IBuildInfo buildInfo,
            String sessionId,
            String deviceId,
            String serverUrl,
            TestDeviceOptions deviceOptions) {
        super(
                String.format(
                        "GceLHPTunnelMonitor-%s-%s-%s-%s-%s-%s",
                        buildInfo.getBuildBranch(),
                        buildInfo.getBuildFlavor(),
                        buildInfo.getBuildId(),
                        sessionId,
                        deviceId,
                        serverUrl));
        setDaemon(true);
        mDevice = device;
        mBuildInfo = buildInfo;
        mDeviceOptions = deviceOptions;
        mSessionId = sessionId;
        mServerUrl = serverUrl;
        mDeviceId = deviceId;
    }

    /** Returns the instance of {@link IRunUtil}. */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    @Override
    public void run() {
        FileOutputStream adbLHPTunnel = null;
        while (!mQuit) {
            if (mAdbConnectionLog == null) {
                try {
                    mAdbConnectionLog = FileUtil.createTempFile("adb-connection", ".txt");
                } catch (IOException e) {
                    FileUtil.deleteFile(mAdbConnectionLog);
                    CLog.e(e);
                }
            }
            if (mAdbConnectionLog != null) {
                if (mDevice.getConnection() instanceof AdbTcpConnection) {
                    ((AdbTcpConnection) mDevice.getConnection()).setAdbLogFile(mAdbConnectionLog);
                }
            }
            if (mAdbLHPTunnelLog == null || !mAdbLHPTunnelLog.exists()) {
                try {
                    mAdbLHPTunnelLog = FileUtil.createTempFile("lhp-adb-connection", ".txt");
                    adbLHPTunnel = new FileOutputStream(mAdbLHPTunnelLog, true);
                } catch (IOException e) {
                    FileUtil.deleteFile(mAdbLHPTunnelLog);
                    CLog.e(e);
                }
            }

            // Establish the adb connection through LHP, and monitor it.
            OxygenClient oxygenClient =
                    OxygenUtil.createOxygenClient(mDeviceOptions.getAvdDriverBinary());
            if (mPortNumber == null) {
                mPortNumber = oxygenClient.createServerSocket();
            }
            // Create a new RemoteAvdIDevice with serial number to replace with gce-device.
            String serial = String.format("localhost:%d", mPortNumber);
            CLog.d("Setting device %s serial to %s", mDevice.getSerialNumber(), serial);
            ((IManagedTestDevice) mDevice).setIDevice(new RemoteAvdIDevice(serial));
            // Do not call setDeviceSerial to keep track of it consistently with the placeholder
            // serial
            mBuildInfo.addBuildAttribute("virtual-device-serial", serial);
            mAdbLHPTunnelProcess =
                    oxygenClient.createTunnelViaLHP(
                            LHPTunnelMode.ADB,
                            Integer.toString(mPortNumber),
                            mSessionId,
                            mServerUrl,
                            OxygenUtil.getTargetRegion(mDeviceOptions),
                            mDeviceOptions.getOxygenAccountingUser(),
                            mDeviceId,
                            mDeviceOptions.getExtraOxygenArgs(),
                            adbLHPTunnel);

            if (mAdbLHPTunnelProcess == null) {
                CLog.e("Failed creating the adb over LHP tunnel for oxygenation.");
                return;
            }

            // Device serial should contain tunnel host and port number.
            getRunUtil().sleep(WAIT_FOR_FIRST_CONNECT);
            // Checking if it is actually running.
            if (isTunnelAlive()) {
                mLocalHostAndPort = HostAndPort.fromString(mDevice.getSerialNumber());
                AbstractConnection conn = mDevice.getConnection();
                if (conn instanceof AdbTcpConnection) {
                    if (!((AdbTcpConnection) conn)
                            .adbTcpConnect(
                                    mLocalHostAndPort.getHost(),
                                    Integer.toString(mLocalHostAndPort.getPort()))) {
                        CLog.e("Adb connect failed, re-init GCE connection.");
                        closeConnection();
                        continue;
                    }
                }
                try {
                    mAdbLHPTunnelProcess.waitFor();
                } catch (InterruptedException e) {
                    CLog.d("adb tunnel connected through LHP terminated %s", e.getMessage());
                }
                CLog.d("Reached end of loop, tunnel is going to re-init.");
                if (mAdbRebootCalled) {
                    mAdbRebootCalled = false;
                    CLog.d(
                            "Tunnel reached end of loop due to adbReboot, "
                                    + "waiting a little for device to come online");
                    getRunUtil().sleep(WAIT_AFTER_REBOOT);
                }
            } else {
                CLog.e(
                        "adb tunnel connected through LHP isn't alive after starting it. It must "
                                + "have returned, closing the tunnel...");
                oxygenClient.closeLHPConnection(mAdbLHPTunnelProcess);
            }
        }
    }

    /** Returns True if the {@link GceLHPTunnelMonitor} is still alive, false otherwise. */
    @Override
    public boolean isTunnelAlive() {
        if (mAdbLHPTunnelProcess != null) {
            return mAdbLHPTunnelProcess.isAlive();
        }
        return false;
    }

    /** Set True when an adb reboot is about to be called to make sure the monitor expect it. */
    @Override
    public void isAdbRebootCalled(boolean isCalled) {
        mAdbRebootCalled = isCalled;
    }

    /** Close the adb connection from the monitor. */
    @Override
    public void closeConnection() {
        // shutdown adb connection first, if we reached where there could be a connection
        CLog.d("closeConnection is triggered.");
        if (mLocalHostAndPort != null) {
            AbstractConnection conn = mDevice.getConnection();
            if (conn instanceof AdbTcpConnection) {
                if (!((AdbTcpConnection) conn)
                        .adbTcpDisconnect(
                                mLocalHostAndPort.getHost(),
                                Integer.toString(mLocalHostAndPort.getPort()))) {
                    CLog.d("Failed to disconnect from local host %s", mLocalHostAndPort.toString());
                }
            }
        }
        if (mAdbLHPTunnelProcess != null) {
            mAdbLHPTunnelProcess.destroy();
            try {
                boolean res =
                        mAdbLHPTunnelProcess.waitFor(
                                DEFAULT_SHORT_CMD_TIMEOUT, TimeUnit.MILLISECONDS);
                if (!res) {
                    CLog.e("adb tunnel connected through LHP may not have properly terminated.");
                }
            } catch (InterruptedException e) {
                CLog.e(
                        "adb tunnel connected through LHP interrupted during shutdown: %s",
                        e.getMessage());
            }
        }
    }

    /** Log all the interesting log files generated from the adb tunnel connected through LHP. */
    @Override
    public void logSshTunnelLogs(ITestLogger logger) {
        if (mDevice.getConnection() instanceof AdbTcpConnection) {
            ((AdbTcpConnection) mDevice.getConnection()).setAdbLogFile(null);
        }
        if (mAdbConnectionLog != null) {
            try (InputStreamSource adbBridge = new FileInputStreamSource(mAdbConnectionLog, true)) {
                logger.testLog("adb-connect-logs", LogDataType.TEXT, adbBridge);
            }
        }
        if (mAdbLHPTunnelLog != null) {
            try (InputStreamSource lhpBridge = new FileInputStreamSource(mAdbLHPTunnelLog, true)) {
                logger.testLog("lhp-bridge-logs", LogDataType.TEXT, lhpBridge);
            }
        }
    }

    /** Terminate the tunnel monitor */
    @Override
    public void shutdown() {
        mQuit = true;
        closeConnection();
        FileUtil.deleteFile(mAdbLHPTunnelLog);
        getRunUtil().allowInterrupt(true);
        getRunUtil().interrupt(this, "shutting down the monitor thread.", null);
        interrupt();
    }
}
