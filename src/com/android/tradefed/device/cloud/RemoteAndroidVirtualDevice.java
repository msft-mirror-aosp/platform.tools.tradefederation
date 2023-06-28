/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceStateMonitor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.device.RemoteAvdIDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.TestDeviceOptions.InstanceType;
import com.android.tradefed.device.cloud.GceAvdInfo.GceStatus;
import com.android.tradefed.host.IHostOptions.PermitLimitType;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Extends {@link RemoteAndroidDevice} behavior for a full stack android device running in the
 * Google Compute Engine (Gce). Assume the device serial will be in the format
 * <hostname>:<portnumber> in adb.
 */
public class RemoteAndroidVirtualDevice extends RemoteAndroidDevice {

    private GceAvdInfo mGceAvd = null;

    private GceManager mGceHandler = null;
    private GceSshTunnelMonitor mGceSshMonitor;
    private DeviceNotAvailableException mTunnelInitFailed = null;

    private static final long CHECK_WAIT_DEVICE_AVAIL_MS = 30 * 1000;
    private static final long WAIT_FOR_TUNNEL_ONLINE = 2 * 60 * 1000;
    private static final long WAIT_FOR_TUNNEL_OFFLINE = 5 * 1000;
    private static final int WAIT_TIME_DIVISION = 4;

    private static final long FETCH_TOMBSTONES_TIMEOUT_MS = 5 * 60 * 1000;

    /**
     * Creates a {@link RemoteAndroidVirtualDevice}.
     *
     * @param device the associated {@link IDevice}
     * @param stateMonitor the {@link IDeviceStateMonitor} mechanism to use
     * @param allocationMonitor the {@link IDeviceMonitor} to inform of allocation state changes.
     */
    public RemoteAndroidVirtualDevice(
            IDevice device, IDeviceStateMonitor stateMonitor, IDeviceMonitor allocationMonitor) {
        super(device, stateMonitor, allocationMonitor);
    }

    /** {@inheritDoc} */
    @Override
    public void preInvocationSetup(IBuildInfo info, MultiMap<String, String> attributes)
            throws TargetSetupError, DeviceNotAvailableException {
        super.preInvocationSetup(info, attributes);
        if (getOptions().shouldUseConnection()) {
            // Connection should be initialized at this point
            return;
        }
        try {
            mGceSshMonitor = null;
            mTunnelInitFailed = null;
            // We create a brand new GceManager each time to ensure clean state.
            mGceHandler = new GceManager(getDeviceDescriptor(), getOptions(), info);
            setFastbootEnabled(false);

            long remainingTime = getOptions().getGceCmdTimeout();
            // mGceAvd is null means the device hasn't been launched.
            if (mGceAvd != null) {
                CLog.d("skipped GCE launch because GceAvdInfo %s is already set", mGceAvd);
                createGceSshMonitor(this, info, mGceAvd.hostAndPort(), this.getOptions());
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
                    launchGce(info, attributes);
                    remainingTime = remainingTime - (getCurrentTime() - startTime);
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
                if (remainingTime <= 0) {
                    throw new DeviceNotAvailableException(
                            String.format(
                                    "Failed to launch GCE after %sms",
                                    getOptions().getGceCmdTimeout()),
                            getSerialNumber(),
                            DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE);
                }
                CLog.d("%sms left before timeout after GCE launch returned", remainingTime);
            }
            // Wait for device to be ready.
            RecoveryMode previousMode = getRecoveryMode();
            setRecoveryMode(RecoveryMode.NONE);
            boolean unresponsive = true;
            try {
                for (int i = 0; i < WAIT_TIME_DIVISION; i++) {
                    // We don't have a way to bail out of waitForDeviceAvailable if the Gce Avd
                    // boot up and then fail some other setup so we check to make sure the monitor
                    // thread is alive and we have an opportunity to abort and avoid wasting time.
                    if (getMonitor().waitForDeviceAvailable(remainingTime / WAIT_TIME_DIVISION)
                            != null) {
                        unresponsive = false;
                        break;
                    }
                    waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
                    waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
                }
            } finally {
                setRecoveryMode(previousMode);
            }
            if (!DeviceState.ONLINE.equals(getIDevice().getState()) || unresponsive) {
                if (mGceAvd != null && GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                    // Update status to reflect that we were not able to connect to it.
                    mGceAvd.setStatus(GceStatus.DEVICE_OFFLINE);
                }
                if (unresponsive) {
                    throw new DeviceUnresponsiveException(
                            "AVD device booted to online but is unresponsive.",
                            getSerialNumber(),
                            DeviceErrorIdentifier.DEVICE_UNRESPONSIVE);
                }
                throw new DeviceNotAvailableException(
                        String.format(
                                "AVD device booted but was in %s state", getIDevice().getState()),
                        getSerialNumber(),
                        DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE);
            }
            enableAdbRoot();
        } catch (DeviceNotAvailableException | TargetSetupError e) {
            throw e;
        }
        // make sure we start logcat directly, device is up.
        setLogStartDelay(0);
        // For virtual device we only start logcat collection after we are sure it's online.
        if (getOptions().isLogcatCaptureEnabled()) {
            startLogcat();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void postInvocationTearDown(Throwable exception) {
        if (getOptions().shouldUseConnection()) {
            // Ensure parent postInvocationTearDown is always called.
            super.postInvocationTearDown(exception);
            return;
        }
        try {
            CLog.i("Invocation tear down for device %s", getSerialNumber());
            // Just clear the logcat, we don't need the teardown logcat
            clearLogcat();
            stopLogcat();
            // Terminate SSH tunnel process.
            if (getGceSshMonitor() != null) {
                getGceSshMonitor().logSshTunnelLogs(getLogger());
                getGceSshMonitor().shutdown();
                try {
                    getGceSshMonitor().joinMonitor();
                } catch (InterruptedException e1) {
                    CLog.i("Interrupted while waiting for GCE SSH monitor to shutdown.");
                }
                // We are done with the monitor, clean it to prevent re-entry.
                mGceSshMonitor = null;
            }
            if (!waitForDeviceNotAvailable(DEFAULT_SHORT_CMD_TIMEOUT)) {
                CLog.w("Device %s still available after timeout.", getSerialNumber());
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
                    getGceHandler().logSerialOutput(mGceAvd, getLogger());

                    // Test if an SSH connection can be established. If can't, skip all collection.
                    boolean isGceReachable =
                            CommonLogRemoteFileUtil.isRemoteGceReachableBySsh(
                                    mGceAvd, getOptions(), getRunUtil());

                    if (isGceReachable) {
                        // Fetch remote files
                        CommonLogRemoteFileUtil.fetchCommonFiles(
                                getLogger(), mGceAvd, getOptions(), getRunUtil());

                        // Fetch all tombstones if any.
                        CommonLogRemoteFileUtil.fetchTombstones(
                                getLogger(), mGceAvd, getOptions(), getRunUtil());
                    } else {
                        CLog.e(
                                "Failed to establish ssh connect to remote file host, skipping"
                                        + " remote common file and tombstones collection.");
                    }

                    // Fetch host kernel log by running `dmesg` for Oxygen hosts
                    if (getOptions().useOxygen()) {
                        CommonLogRemoteFileUtil.logRemoteCommandOutput(
                                getLogger(),
                                mGceAvd,
                                getOptions(),
                                getRunUtil(),
                                "host_kernel.log",
                                "toybox",
                                "dmesg");
                    }
                }
            }

            // Cleanup GCE first to make sure ssh tunnel has nowhere to go.
            if (!getOptions().shouldSkipTearDown() && getGceHandler() != null) {
                getGceHandler().shutdownGce();
            }
            // We are done with the gce related information, clean it to prevent re-entry.
            mGceAvd = null;

            if (getInitialSerial() != null) {
                setIDevice(
                        new RemoteAvdIDevice(
                                getInitialSerial(),
                                getInitialIp(),
                                getInitialUser(),
                                getInitialDeviceNumOffset()));
            }
            setFastbootEnabled(false);

            if (getGceHandler() != null) {
                getGceHandler().cleanUp();
            }
        } finally {
            // Ensure parent postInvocationTearDown is always called.
            super.postInvocationTearDown(exception);
        }
    }

    /** Capture a remote bugreport by ssh-ing into the device directly. */
    private void getSshBugreport() {
        InstanceType type = getOptions().getInstanceType();
        File bugreportFile = null;
        try {
            if (InstanceType.GCE.equals(type) || InstanceType.REMOTE_AVD.equals(type)) {
                bugreportFile =
                        GceManager.getBugreportzWithSsh(mGceAvd, getOptions(), getRunUtil());
            } else {
                bugreportFile =
                        GceManager.getNestedDeviceSshBugreportz(
                                mGceAvd, getOptions(), getRunUtil());
            }
            if (bugreportFile != null) {
                InputStreamSource bugreport = new FileInputStreamSource(bugreportFile);
                getLogger().testLog("bugreportz-ssh", LogDataType.BUGREPORTZ, bugreport);
                StreamUtil.cancel(bugreport);
            }
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(bugreportFile);
        }
    }

    /** Launch the actual gce device based on the build info. */
    protected void launchGce(IBuildInfo buildInfo, MultiMap<String, String> attributes)
            throws TargetSetupError {
        TargetSetupError exception = null;
        for (int attempt = 0; attempt < getOptions().getGceMaxAttempt(); attempt++) {
            try {
                // Clear exception before each attempt.
                exception = null;
                mGceAvd =
                        getGceHandler()
                                .startGce(
                                        getInitialIp(),
                                        getInitialUser(),
                                        getInitialDeviceNumOffset(),
                                        attributes,
                                        getLogger());
                if (mGceAvd != null) {
                    if (GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
                        break;
                    }
                    CLog.w(
                            "Failed to start AVD with attempt: %s out of %s, error: %s",
                            attempt + 1, getOptions().getGceMaxAttempt(), mGceAvd.getErrors());
                }
            } catch (TargetSetupError tse) {
                CLog.w(
                        "Failed to start Gce with attempt: %s out of %s. With Exception: %s",
                        attempt + 1, getOptions().getGceMaxAttempt(), tse);
                exception = tse;

                if (getOptions().useOxygen()) {
                    OxygenUtil util = new OxygenUtil();
                    util.downloadLaunchFailureLogs(tse, getLogger());
                }
            }
        }
        if (exception != null) {
            throw exception;
        } else {
            CLog.i("GCE AVD has been started: %s", mGceAvd);
            ErrorIdentifier errorIdentifier =
                    (mGceAvd.getErrorType() != null)
                            ? mGceAvd.getErrorType()
                            : DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE;
            if (GceAvdInfo.GceStatus.BOOT_FAIL.equals(mGceAvd.getStatus())) {
                String errorMsg =
                        String.format(
                                "Device failed to boot. Error from Acloud: %s",
                                mGceAvd.getErrors());
                throw new TargetSetupError(errorMsg, getDeviceDescriptor(), errorIdentifier);
            } else if (GceAvdInfo.GceStatus.FAIL.equals(mGceAvd.getStatus())) {
                throw new TargetSetupError(
                        mGceAvd.getErrors(), getDeviceDescriptor(), errorIdentifier);
            }
        }
        createGceSshMonitor(this, buildInfo, mGceAvd.hostAndPort(), this.getOptions());
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

    /** {@inherit} */
    @Override
    public void postBootSetup() throws DeviceNotAvailableException {
        if (!getOptions().shouldDisableReboot()) {
            if (!getOptions().shouldUseConnection()) {
                CLog.v("Performing post boot setup for GCE AVD %s", getSerialNumber());
                // Should already be connected at this point, but if something is
                // missing, restart the tunnel
                if (!getGceSshMonitor().isTunnelAlive()) {
                    getGceSshMonitor().closeConnection();
                    getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
                    waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
                }
                waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
            }
        }
        super.postBootSetup();
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
                        getSerialNumber(),
                        DeviceErrorIdentifier.FAILED_TO_CONNECT_TO_GCE);
        throw mTunnelInitFailed;
    }

    @Override
    public boolean recoverDevice() throws DeviceNotAvailableException {
        if (!getOptions().shouldUseConnection()) {
            if (getGceSshMonitor() == null) {
                if (mTunnelInitFailed != null) {
                    // We threw before but was not reported, so throw the root cause here.
                    throw mTunnelInitFailed;
                }
                waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
            }
            // Check that shell is available before resetting the bridge
            if (!waitForDeviceShell(CHECK_WAIT_DEVICE_AVAIL_MS)) {
                long startTime = System.currentTimeMillis();
                try {
                    // Re-init tunnel when attempting recovery
                    CLog.i("Attempting recovery on GCE AVD %s", getSerialNumber());
                    getGceSshMonitor().closeConnection();
                    getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
                    waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
                    waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
                } catch (Exception e) {
                    // Log the entrance in recovery here to avoid double counting with
                    // super.recoverDevice.
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.RECOVERY_ROUTINE_COUNT, 1);
                    throw e;
                } finally {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.RECOVERY_TIME,
                            System.currentTimeMillis() - startTime);
                }
            }
        }
        // Then attempt regular recovery
        return super.recoverDevice();
    }

    @Override
    protected void doAdbReboot(RebootMode rebootMode, @Nullable final String reason)
            throws DeviceNotAvailableException {
        // We catch that adb reboot is called to expect it from the tunnel.
        if (getGceSshMonitor() != null) {
            getGceSshMonitor().isAdbRebootCalled(true);
        }
        super.doAdbReboot(rebootMode, reason);
    }

    @Override
    protected void postAdbReboot() throws DeviceNotAvailableException {
        if (!getOptions().shouldUseConnection()) {
            // After the reboot we wait for tunnel to be online and device to be reconnected
            getRunUtil().sleep(WAIT_FOR_TUNNEL_OFFLINE);
            waitForTunnelOnline(WAIT_FOR_TUNNEL_ONLINE);
            waitForAdbConnect(WAIT_FOR_ADB_CONNECT);
        } else {
            super.postAdbReboot();
        }
    }

    /**
     * Cuttlefish has a special feature that brings the tombstones to the remote host where we can
     * get them directly.
     */
    @Override
    public List<File> getTombstones() throws DeviceNotAvailableException {
        InstanceType type = getOptions().getInstanceType();
        if (InstanceType.CUTTLEFISH.equals(type) || InstanceType.REMOTE_NESTED_AVD.equals(type)) {
            List<File> tombs = new ArrayList<>();
            String remoteRuntimePath =
                    String.format(
                                    CommonLogRemoteFileUtil.NESTED_REMOTE_LOG_DIR,
                                    getOptions().getInstanceUser())
                            + "tombstones/*";
            File localDir = null;
            try {
                localDir = FileUtil.createTempDir("tombstones");
            } catch (IOException e) {
                CLog.e(e);
                return tombs;
            }
            if (!fetchRemoteDir(localDir, remoteRuntimePath)) {
                CLog.e("Failed to pull %s", remoteRuntimePath);
                FileUtil.recursiveDelete(localDir);
            } else {
                tombs.addAll(Arrays.asList(localDir.listFiles()));
                localDir.deleteOnExit();
            }
            return tombs;
        }
        // If it's not Cuttlefish, use the standard call.
        return super.getTombstones();
    }

    /** Set the {@link GceAvdInfo} for launched device. */
    public void setAvdInfo(GceAvdInfo gceAvdInfo) throws TargetSetupError {
        if (mGceAvd == null) {
            mGceAvd = gceAvdInfo;
            setConnectionAvdInfo(gceAvdInfo);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "The GceAvdInfo of the device %s is already set, override is not"
                                    + " permitted. Current GceAvdInfo: %s",
                            getSerialNumber(), mGceAvd),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
    }

    /**
     * Returns the {@link GceAvdInfo} from the created remote VM. Returns null if the bring up was
     * not successful.
     */
    public @Nullable GceAvdInfo getAvdInfo() {
        if (mGceAvd == null) {
            CLog.w("Requested getAvdInfo() but GceAvdInfo is null.");
            return null;
        }
        if (!GceStatus.SUCCESS.equals(mGceAvd.getStatus())) {
            CLog.w("Requested getAvdInfo() but the bring up was not successful, returning null.");
            return null;
        }
        return mGceAvd;
    }

    /**
     * Returns the {@link GceAvdInfo} from the created remote VM. Returns regardless of the status
     * so we can inspect the info.
     */
    public @Nullable GceAvdInfo getAvdInfoAnyState() {
        if (mGceAvd == null) {
            CLog.w("Requested getAvdInfo() but GceAvdInfo is null.");
            return null;
        }
        return mGceAvd;
    }

    @VisibleForTesting
    boolean fetchRemoteDir(File localDir, String remotePath) {
        return RemoteFileUtil.fetchRemoteDir(
                mGceAvd,
                getOptions(),
                getRunUtil(),
                FETCH_TOMBSTONES_TIMEOUT_MS,
                remotePath,
                localDir);
    }

    /**
     * Returns the {@link com.android.tradefed.device.cloud.GceSshTunnelMonitor} of the device.
     */
    public GceSshTunnelMonitor getGceSshMonitor() {
        return mGceSshMonitor;
    }

    /**
     * Override the internal {@link com.android.tradefed.device.cloud.GceSshTunnelMonitor} of the
     * device.
     */
    // TODO(b/190657509): Remove this API once boot test is refactored to use
    // preInvocationSetup and postInvocationTeardown.
    public void setGceSshMonitor(GceSshTunnelMonitor gceSshMonitor) {
        CLog.i("Overriding internal GCE SSH monitor.");
        mGceSshMonitor = gceSshMonitor;
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

    @Override
    public DeviceDescriptor getDeviceDescriptor() {
        DeviceDescriptor descriptor = super.getDeviceDescriptor();
        if (!getInitialSerial().equals(descriptor.getSerial())) {
            // Alter the display for the console.
            descriptor =
                    new DeviceDescriptor(
                            descriptor,
                            getInitialSerial(),
                            getInitialSerial() + "[" + descriptor.getSerial() + "]");
        }
        return descriptor;
    }

    /**
     * Attempt to powerwash a GCE instance
     *
     * @return returns CommandResult of the powerwash attempts
     * @throws TargetSetupError @Deprecated Use {@link #powerwash()} instead
     */
    @Deprecated
    public boolean powerwashGce() throws TargetSetupError {
        return CommandStatus.SUCCESS.equals(powerwash().getStatus());
    }

    /**
     * Attempt to powerwash a GCE instance
     *
     * @return returns CommandResult of the powerwash attempts
     * @throws TargetSetupError
     */
    public CommandResult powerwash() throws TargetSetupError {
        return powerwashGce(null, null);
    }

    /**
     * Attempt to powerwash a GCE instance
     *
     * @param user the host running user of AVD, <code>null</code> if not applicable.
     * @param offset the device num offset of the AVD in the host, <code>null</code> if not
     *     applicable
     * @return returns CommandResult of the powerwash attempts
     * @throws TargetSetupError
     */
    public CommandResult powerwashGce(String user, Integer offset) throws TargetSetupError {
        long startTime = System.currentTimeMillis();

        if (mGceAvd == null) {
            String errorMsg = String.format("Can not get GCE AVD Info. launch GCE first?");
            throw new TargetSetupError(
                    errorMsg, getDeviceDescriptor(), DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
        // Get the user from options instance-user if user is null.
        if (user == null) {
            user = this.getOptions().getInstanceUser();
        }

        String powerwashCommand = String.format("/home/%s/bin/powerwash_cvd", user);

        if (offset != null) {
            powerwashCommand =
                    String.format(
                            "HOME=/home/%s/acloud_cf_%d acloud_cf_%d/bin/powerwash_cvd"
                                    + " -instance_num %d",
                            user, offset + 1, offset + 1, offset + 1);
        }

        if (this.getOptions().useOxygen()) {
            // TODO(dshi): Simplify the logic after Oxygen creates symlink of the tmp dir.
            CommandResult result =
                    GceManager.remoteSshCommandExecution(
                            mGceAvd,
                            this.getOptions(),
                            getRunUtil(),
                            10000L,
                            "toybox find /tmp -name powerwash_cvd".split(" "));
            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                CLog.e("Failed to locate powerwash_cvd: %s", result.getStderr());
                return result;
            }
            String powerwashPath = result.getStdout();
            // Remove tailing `/bin/powerwash_cvd`
            String tmpDir = powerwashPath.substring(0, powerwashPath.length() - 18);
            powerwashCommand = String.format("HOME=%s %s", tmpDir, powerwashPath);
        }
        CommandResult powerwashRes =
                GceManager.remoteSshCommandExecution(
                        mGceAvd,
                        this.getOptions(),
                        getRunUtil(),
                        Math.max(300000L, this.getOptions().getGceCmdTimeout()),
                        powerwashCommand.split(" "));

        // Time taken for powerwash this invocation
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.POWERWASH_TIME,
                Long.toString(System.currentTimeMillis() - startTime));

        if (CommandStatus.SUCCESS.equals(powerwashRes.getStatus())) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.POWERWASH_SUCCESS_COUNT, 1);
        } else {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.POWERWASH_FAILURE_COUNT, 1);
            CLog.e("%s", powerwashRes.getStderr());
            // Log 'adb devices' to confirm device is gone
            CommandResult printAdbDevices = getRunUtil().runTimedCmd(60000L, "adb", "devices");
            CLog.e("%s\n%s", printAdbDevices.getStdout(), printAdbDevices.getStderr());
            // Proceed here, device could have been already gone.
            return powerwashRes;
        }

        getMonitor().waitForDeviceAvailable();
        resetContentProviderSetup();
        return powerwashRes;
    }
}
