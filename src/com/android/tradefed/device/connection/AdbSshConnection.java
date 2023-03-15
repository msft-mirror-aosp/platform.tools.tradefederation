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

import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.RemoteAvdIDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.TestDeviceOptions.InstanceType;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.CommonLogRemoteFileUtil;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.device.cloud.GceSshTunnelMonitor;
import com.android.tradefed.device.cloud.OxygenUtil;
import com.android.tradefed.host.IHostOptions.PermitLimitType;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Adb connection over an ssh bridge. */
public class AdbSshConnection extends AdbTcpConnection {

    private GceAvdInfo mGceAvd = null;
    private ITestLogger mTestLogger;

    private GceManager mGceHandler = null;
    private GceSshTunnelMonitor mGceSshMonitor;
    private DeviceNotAvailableException mTunnelInitFailed = null;

    private static final int WAIT_TIME_DIVISION = 4;
    private static final long WAIT_FOR_TUNNEL_OFFLINE = 5 * 1000;
    private static final long WAIT_FOR_TUNNEL_ONLINE = 2 * 60 * 1000;

    public AdbSshConnection(ConnectionBuilder builder) {
        super(builder);
    }

    @Override
    public void initializeConnection() throws DeviceNotAvailableException, TargetSetupError {
        mGceSshMonitor = null;
        mTunnelInitFailed = null;
        // We create a brand new GceManager each time to ensure clean state.
        mGceHandler =
                new GceManager(
                        getDevice().getDeviceDescriptor(),
                        getDevice().getOptions(),
                        getBuildInfo());

        long remainingTime = 0;
        // mGceAvd is null means the device hasn't been launched.
        if (mGceAvd != null) {
            CLog.d("skipped GCE launch because GceAvdInfo %s is already set", mGceAvd);
        } else {
            // Launch GCE helper script.
            long startTime = getCurrentTime();

            try {
                if (GlobalConfiguration.getInstance()
                                .getHostOptions()
                                .getConcurrentVirtualDeviceStartupLimit()
                        != null) {
                    GlobalConfiguration.getInstance()
                            .getHostOptions()
                            .takePermit(PermitLimitType.CONCURRENT_VIRTUAL_DEVICE_STARTUP);
                    long queueTime = System.currentTimeMillis() - startTime;
                    CLog.v(
                            "Fetch and launch CVD permit obtained after %ds",
                            TimeUnit.MILLISECONDS.toSeconds(queueTime));
                }
                launchGce(getBuildInfo(), getAttributes());
                remainingTime =
                        getDevice().getOptions().getGceCmdTimeout()
                                - (getCurrentTime() - startTime);
            } finally {
                if (GlobalConfiguration.getInstance()
                                .getHostOptions()
                                .getConcurrentVirtualDeviceStartupLimit()
                        != null) {
                    GlobalConfiguration.getInstance()
                            .getHostOptions()
                            .returnPermit(PermitLimitType.CONCURRENT_VIRTUAL_DEVICE_STARTUP);
                }
            }
            if (remainingTime < 0) {
                throw new DeviceNotAvailableException(
                        String.format(
                                "Failed to launch GCE after %sms",
                                getDevice().getOptions().getGceCmdTimeout()),
                        getDevice().getSerialNumber(),
                        DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE);
            }
            CLog.d("%sms left before timeout after GCE launch returned", remainingTime);
        }
        // Wait for device to be ready.
        RecoveryMode previousMode = getDevice().getRecoveryMode();
        getDevice().setRecoveryMode(RecoveryMode.NONE);
        boolean unresponsive = true;
        try {
            for (int i = 0; i < WAIT_TIME_DIVISION; i++) {
                // We don't have a way to bail out of waitForDeviceAvailable if the Gce Avd
                // boot up and then fail some other setup so we check to make sure the monitor
                // thread is alive and we have an opportunity to abort and avoid wasting time.
                if (((IManagedTestDevice) getDevice())
                                .getMonitor()
                                .waitForDeviceAvailable(remainingTime / WAIT_TIME_DIVISION)
                        != null) {
                    unresponsive = false;
                    break;
                }
                waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
                waitForAdbConnect(getDevice().getSerialNumber(), WAIT_FOR_ADB_CONNECT);
            }
        } finally {
            getDevice().setRecoveryMode(previousMode);
        }
        if (!DeviceState.ONLINE.equals(getDevice().getIDevice().getState()) || unresponsive) {
            if (mGceAvd != null && GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                // Update status to reflect that we were not able to connect to it.
                mGceAvd.setStatus(GceStatus.DEVICE_OFFLINE);
            }
            if (unresponsive) {
                throw new DeviceUnresponsiveException(
                        "AVD device booted to online but is unresponsive.",
                        getDevice().getSerialNumber(),
                        DeviceErrorIdentifier.DEVICE_UNRESPONSIVE);
            }
            throw new DeviceNotAvailableException(
                    String.format(
                            "AVD device booted but was in %s state",
                            getDevice().getIDevice().getState()),
                    getDevice().getSerialNumber(),
                    DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE);
        }
        getDevice().enableAdbRoot();
        // For virtual device we only start logcat collection after we are sure it's online.
        if (getDevice().getOptions().isLogcatCaptureEnabled()) {
            getDevice().startLogcat();
        }
    }

    @Override
    public void reconnect(String serial) throws DeviceNotAvailableException {
        if (!getGceSshMonitor().isTunnelAlive()) {
            getGceSshMonitor().closeConnection();
            getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
            waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
        }
        super.reconnect(serial);
    }

    @Override
    public void tearDownConnection() {
        try {
            CLog.i("Invocation tear down for device %s", getDevice().getSerialNumber());
            // Just clear the logcat, we don't need the teardown logcat
            getDevice().clearLogcat();
            getDevice().stopLogcat();
            // Terminate SSH tunnel process.
            if (getGceSshMonitor() != null) {
                getGceSshMonitor().logSshTunnelLogs(mTestLogger);
                getGceSshMonitor().shutdown();
                try {
                    getGceSshMonitor().joinMonitor();
                } catch (InterruptedException e1) {
                    CLog.i("Interrupted while waiting for GCE SSH monitor to shutdown.");
                }
                // We are done with the monitor, clean it to prevent re-entry.
                mGceSshMonitor = null;
            }
            if (!((IManagedTestDevice) getDevice())
                    .waitForDeviceNotAvailable(DEFAULT_SHORT_CMD_TIMEOUT)) {
                CLog.w("Device %s still available after timeout.", getDevice().getSerialNumber());
            }

            if (mGceAvd != null) {
                // Host and port can be null in case of acloud timeout
                if (mGceAvd.hostAndPort() != null) {
                    // attempt to get a bugreport if Gce Avd is a failure
                    if (!GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                        // Get a bugreport via ssh
                        getSshBugreport();
                    }
                    // Log the serial output of the instance.
                    getGceHandler().logSerialOutput(mGceAvd, mTestLogger);

                    // Test if an SSH connection can be established. If can't, skip all collection.
                    boolean isGceReachable =
                            CommonLogRemoteFileUtil.isRemoteGceReachableBySsh(
                                    mGceAvd, getDevice().getOptions(), getRunUtil());

                    if (isGceReachable) {
                        // Fetch remote files
                        CommonLogRemoteFileUtil.fetchCommonFiles(
                                mTestLogger, mGceAvd, getDevice().getOptions(), getRunUtil());

                        // Fetch all tombstones if any.
                        CommonLogRemoteFileUtil.fetchTombstones(
                                mTestLogger, mGceAvd, getDevice().getOptions(), getRunUtil());
                    } else {
                        CLog.e(
                                "Failed to establish ssh connect to remote file host, skipping"
                                        + " remote common file and tombstones collection.");
                    }

                    // Fetch host kernel log by running `dmesg` for Oxygen hosts
                    if (getDevice().getOptions().useOxygen()) {
                        CommonLogRemoteFileUtil.logRemoteCommandOutput(
                                mTestLogger,
                                mGceAvd,
                                getDevice().getOptions(),
                                getRunUtil(),
                                "host_kernel.log",
                                "toybox",
                                "dmesg");
                    }
                }
            }

            // Cleanup GCE first to make sure ssh tunnel has nowhere to go.
            if (!getDevice().getOptions().shouldSkipTearDown() && getGceHandler() != null) {
                getGceHandler().shutdownGce();
            }
            // We are done with the gce related information, clean it to prevent re-entry.
            mGceAvd = null;

            if (getInitialSerial() != null) {
                ((IManagedTestDevice) getDevice())
                        .setIDevice(
                                new RemoteAvdIDevice(
                                        getInitialSerial(),
                                        getInitialIp(),
                                        getInitialUser(),
                                        getInitialDeviceNumOffset()));
            }
            ((IManagedTestDevice) getDevice()).setFastbootEnabled(false);

            if (getGceHandler() != null) {
                getGceHandler().cleanUp();
            }
        } finally {
            super.tearDownConnection();
        }
    }

    /** Launch the actual gce device based on the build info. */
    protected void launchGce(IBuildInfo buildInfo, MultiMap<String, String> attributes)
            throws TargetSetupError {
        TargetSetupError exception = null;
        for (int attempt = 0; attempt < getDevice().getOptions().getGceMaxAttempt(); attempt++) {
            try {
                mGceAvd =
                        getGceHandler()
                                .startGce(
                                        getInitialIp(),
                                        getInitialUser(),
                                        getInitialDeviceNumOffset(),
                                        attributes,
                                        mTestLogger);
                if (mGceAvd != null) {
                    break;
                }
            } catch (TargetSetupError tse) {
                CLog.w(
                        "Failed to start Gce with attempt: %s out of %s. With Exception: %s",
                        attempt + 1, getDevice().getOptions().getGceMaxAttempt(), tse);
                exception = tse;

                if (getDevice().getOptions().useOxygen()) {
                    OxygenUtil util = new OxygenUtil();
                    util.downloadLaunchFailureLogs(tse, mTestLogger);
                }
            }
        }
        if (mGceAvd == null) {
            throw exception;
        } else {
            CLog.i("GCE AVD has been started: %s", mGceAvd);
            if (GceAvdInfo.GceStatus.BOOT_FAIL.equals(mGceAvd.getStatus())) {
                String errorMsg =
                        String.format(
                                "Device failed to boot. Error from Acloud: %s",
                                mGceAvd.getErrors());
                ErrorIdentifier errorIdentifier =
                        (mGceAvd.getErrorType() != null)
                                ? mGceAvd.getErrorType()
                                : DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE;
                throw new TargetSetupError(
                        errorMsg, getDevice().getDeviceDescriptor(), errorIdentifier);
            }
        }
        createGceSshMonitor(
                getDevice(), buildInfo, mGceAvd.hostAndPort(), getDevice().getOptions());
    }

    /** Create an ssh tunnel, connect to it, and keep the connection alive. */
    void createGceSshMonitor(
            ITestDevice device,
            IBuildInfo buildInfo,
            HostAndPort hostAndPort,
            TestDeviceOptions deviceOptions) {
        mGceSshMonitor = new GceSshTunnelMonitor(device, buildInfo, hostAndPort, deviceOptions);
        mGceSshMonitor.start();
    }

    /** Check if the tunnel monitor is running. */
    protected void waitForTunnelOnline(final long waitTime) throws DeviceNotAvailableException {
        CLog.i("Waiting %d ms for tunnel to be restarted", waitTime);
        long startTime = getCurrentTime();
        while (getCurrentTime() - startTime < waitTime) {
            if (getGceSshMonitor() == null) {
                CLog.e("Tunnel Thread terminated, something went wrong with the device.");
                break;
            }
            if (getGceSshMonitor().isTunnelAlive()) {
                CLog.d("Tunnel online again, resuming.");
                return;
            }
            getRunUtil().sleep(RETRY_INTERVAL_MS);
        }
        mTunnelInitFailed =
                new DeviceNotAvailableException(
                        String.format("Tunnel did not come back online after %sms", waitTime),
                        getDevice().getSerialNumber(),
                        DeviceErrorIdentifier.FAILED_TO_CONNECT_TO_GCE);
        throw mTunnelInitFailed;
    }

    /** Returns the {@link com.android.tradefed.device.cloud.GceSshTunnelMonitor} of the device. */
    public GceSshTunnelMonitor getGceSshMonitor() {
        return mGceSshMonitor;
    }

    /** Returns the current system time. Exposed for testing. */
    protected long getCurrentTime() {
        return System.currentTimeMillis();
    }

    /** Returns the instance of the {@link com.android.tradefed.device.cloud.GceManager}. */
    @VisibleForTesting
    GceManager getGceHandler() {
        return mGceHandler;
    }

    /** Capture a remote bugreport by ssh-ing into the device directly. */
    private void getSshBugreport() {
        InstanceType type = getDevice().getOptions().getInstanceType();
        File bugreportFile = null;
        try {
            if (InstanceType.GCE.equals(type) || InstanceType.REMOTE_AVD.equals(type)) {
                bugreportFile =
                        GceManager.getBugreportzWithSsh(
                                mGceAvd, getDevice().getOptions(), getRunUtil());
            } else {
                bugreportFile =
                        GceManager.getNestedDeviceSshBugreportz(
                                mGceAvd, getDevice().getOptions(), getRunUtil());
            }
            if (bugreportFile != null) {
                InputStreamSource bugreport = new FileInputStreamSource(bugreportFile);
                mTestLogger.testLog("bugreportz-ssh", LogDataType.BUGREPORTZ, bugreport);
                StreamUtil.cancel(bugreport);
            }
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(bugreportFile);
        }
    }
}
