/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.TimeoutException;
import com.android.helper.aoa.UsbDevice;
import com.android.helper.aoa.UsbException;
import com.android.helper.aoa.UsbHelper;
import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * A simple implementation of a {@link IDeviceRecovery} that waits for device to be online and
 * respond to simple commands.
 */
public class WaitDeviceRecovery implements IDeviceRecovery {

    /** the time in ms to wait before beginning recovery attempts */
    protected static final long INITIAL_PAUSE_TIME = 5 * 1000;

    private static final long WAIT_FOR_DEVICE_OFFLINE = 20 * 1000;

    /**
     * The number of attempts to check if device is in bootloader.
     * <p/>
     * Exposed for unit testing
     */
    public static final int BOOTLOADER_POLL_ATTEMPTS = 3;

    // TODO: add a separate configurable timeout per operation
    @Option(name="online-wait-time",
            description="maximum time in ms to wait for device to come online.")
    protected long mOnlineWaitTime = 60 * 1000;
    @Option(name="device-wait-time",
            description="maximum time in ms to wait for a single device recovery command.")
    protected long mWaitTime = 4 * 60 * 1000;

    @Option(name="bootloader-wait-time",
            description="maximum time in ms to wait for device to be in fastboot.")
    protected long mBootloaderWaitTime = 30 * 1000;

    @Option(name="shell-wait-time",
            description="maximum time in ms to wait for device shell to be responsive.")
    protected long mShellWaitTime = 30 * 1000;

    @Option(name="fastboot-wait-time",
            description="maximum time in ms to wait for a fastboot command result.")
    protected long mFastbootWaitTime = 30 * 1000;

    @Option(name = "min-battery-after-recovery",
            description = "require a min battery level after successful recovery, " +
                          "default to 0 for ignoring.")
    protected int mRequiredMinBattery = 0;

    @Option(name = "disable-unresponsive-reboot",
            description = "If this is set, we will not attempt to reboot an unresponsive device" +
            "that is in userspace.  Note that this will have no effect if the device is in " +
            "fastboot or is expected to be in fastboot.")
    protected boolean mDisableUnresponsiveReboot = false;

    @Option(
            name = "disable-usb-reset",
            description = "Do not attempt reset via USB in order to recover devices.")
    protected boolean mDisableUsbReset = false;

    private String mFastbootPath = "fastboot";

    /**
     * Get the {@link RunUtil} instance to use.
     * <p/>
     * Exposed for unit testing.
     */
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Sets the maximum time in ms to wait for a single device recovery command.
     */
    void setWaitTime(long waitTime) {
        mWaitTime = waitTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFastbootPath(String fastbootPath) {
        mFastbootPath = fastbootPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recoverDevice(IDeviceStateMonitor monitor, boolean recoverUntilOnline)
            throws DeviceNotAvailableException {
        // device may have just gone offline
        // sleep a small amount to give ddms state a chance to settle
        // TODO - see if there is better way to handle this
        CLog.i("Pausing for %d for %s to recover", INITIAL_PAUSE_TIME, monitor.getSerialNumber());
        getRunUtil().sleep(INITIAL_PAUSE_TIME);

        // ensure bootloader state is updated
        monitor.waitForDeviceBootloaderStateUpdate();

        TestDeviceState state = monitor.getDeviceState();
        if (TestDeviceState.FASTBOOT.equals(state) || TestDeviceState.FASTBOOTD.equals(state)) {
            CLog.i(
                    "Found device %s in %s but expected online. Rebooting...",
                    monitor.getSerialNumber(), state);
            // TODO: retry if failed
            getRunUtil()
                    .runTimedCmd(
                            mFastbootWaitTime,
                            mFastbootPath,
                            "-s",
                            monitor.getFastbootSerialNumber(),
                            "reboot");
        }

        // wait for device online
        IDevice device = monitor.waitForDeviceOnline(mOnlineWaitTime);
        if (device == null) {
            handleDeviceNotAvailable(monitor, recoverUntilOnline);
            // function returning implies that recovery is successful, check battery level here
            checkMinBatteryLevel(getDeviceAfterRecovery(monitor));
            return;
        }
        // occasionally device is erroneously reported as online - double check that we can shell
        // into device
        if (!monitor.waitForDeviceShell(mShellWaitTime)) {
            // treat this as a not available device
            handleDeviceNotAvailable(monitor, recoverUntilOnline);
            checkMinBatteryLevel(getDeviceAfterRecovery(monitor));
            return;
        }

        if (!recoverUntilOnline) {
            if (monitor.waitForDeviceAvailableInRecoverPath(mWaitTime) == null) {
                // device is online but not responsive
                handleDeviceUnresponsive(device, monitor);
            }
        }
        // do a final check here when all previous if blocks are skipped or the last
        // handleDeviceUnresponsive was successful
        checkMinBatteryLevel(getDeviceAfterRecovery(monitor));
    }

    private IDevice getDeviceAfterRecovery(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        IDevice device = monitor.waitForDeviceOnline(mOnlineWaitTime);
        if (device == null) {
            throw new DeviceNotAvailableException(
                    "Device still not online after successful recovery",
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
        return device;
    }

    /**
     * Checks if device battery level meets min requirement
     * @param device
     * @throws DeviceNotAvailableException if battery level cannot be read or lower than min
     */
    protected void checkMinBatteryLevel(IDevice device) throws DeviceNotAvailableException {
        if (mRequiredMinBattery <= 0) {
            // don't do anything if check is not required
            return;
        }
        try {
            Integer level = device.getBattery().get();
            if (level == null) {
                // can't read battery level but we are requiring a min, reject
                // device
                throw new DeviceNotAvailableException(
                        "Cannot read battery level but a min is required",
                        device.getSerialNumber(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            } else if (level < mRequiredMinBattery) {
                throw new DeviceNotAvailableException(String.format(
                        "After recovery, device battery level %d is lower than required minimum %d",
                        level, mRequiredMinBattery), device.getSerialNumber());
            }
            return;
        } catch (InterruptedException | ExecutionException e) {
            throw new DeviceNotAvailableException(
                    "exception while reading battery level",
                    e,
                    device.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
    }

    /**
     * Handle situation where device is online but unresponsive.
     * @param monitor
     * @throws DeviceNotAvailableException
     */
    protected void handleDeviceUnresponsive(IDevice device, IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        if (!mDisableUnresponsiveReboot) {
            CLog.i("Device %s unresponsive. Rebooting...", monitor.getSerialNumber());
            rebootDevice(device, null);
            IDevice newdevice = monitor.waitForDeviceOnline(mOnlineWaitTime);
            if (newdevice == null) {
                handleDeviceNotAvailable(monitor, false);
                return;
            }
            if (monitor.waitForDeviceAvailable(mWaitTime) != null) {
                return;
            }
        }
        // If no reboot was done, waitForDeviceAvailable has already been checked.
        throw new DeviceUnresponsiveException(
                String.format("Device %s is online but unresponsive", monitor.getSerialNumber()),
                monitor.getSerialNumber(),
                DeviceErrorIdentifier.DEVICE_UNRESPONSIVE);
    }

    /**
     * Handle situation where device is not available.
     *
     * @param monitor the {@link IDeviceStateMonitor}
     * @param recoverTillOnline if true this method should return if device is online, and not
     * check for responsiveness
     * @throws DeviceNotAvailableException
     */
    protected void handleDeviceNotAvailable(IDeviceStateMonitor monitor, boolean recoverTillOnline)
            throws DeviceNotAvailableException {
        if (attemptDeviceUnavailableRecovery(monitor, recoverTillOnline)) {
            return;
        }
        String serial = monitor.getSerialNumber();
        throw new DeviceNotAvailableException(
                String.format("Could not find device %s", serial),
                serial,
                DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recoverDeviceBootloader(final IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        // device may have just gone offline
        // wait a small amount to give device state a chance to settle
        // TODO - see if there is better way to handle this
        CLog.i("Pausing for %d for %s to recover", INITIAL_PAUSE_TIME, monitor.getSerialNumber());
        getRunUtil().sleep(INITIAL_PAUSE_TIME);

        // poll and wait for device to return to valid state
        long pollTime = mBootloaderWaitTime / BOOTLOADER_POLL_ATTEMPTS;
        for (int i=0; i < BOOTLOADER_POLL_ATTEMPTS; i++) {
            if (monitor.waitForDeviceBootloader(pollTime)) {
                handleDeviceBootloaderUnresponsive(monitor);
                // passed above check, abort
                return;
            } else if (monitor.getDeviceState() == TestDeviceState.ONLINE) {
                handleDeviceOnlineExpectedBootloader(monitor);
                return;
            }
        }
        handleDeviceBootloaderOrFastbootNotAvailable(monitor, "bootloader");
    }

    /** {@inheritDoc} */
    @Override
    public void recoverDeviceFastbootd(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        // device may have just gone offline
        // wait a small amount to give device state a chance to settle
        // TODO - see if there is better way to handle this
        CLog.i("Pausing for %d for %s to recover", INITIAL_PAUSE_TIME, monitor.getSerialNumber());
        getRunUtil().sleep(INITIAL_PAUSE_TIME);

        // poll and wait for device to return to valid state
        long pollTime = mBootloaderWaitTime / BOOTLOADER_POLL_ATTEMPTS;
        for (int i = 0; i < BOOTLOADER_POLL_ATTEMPTS; i++) {
            if (monitor.waitForDeviceFastbootd(mFastbootPath, pollTime)) {
                handleDeviceFastbootdUnresponsive(monitor);
                // passed above check, abort
                return;
            } else if (monitor.getDeviceState() == TestDeviceState.ONLINE) {
                handleDeviceOnlineExpectedFasbootd(monitor);
                return;
            }
        }
        handleDeviceBootloaderOrFastbootNotAvailable(monitor, "fastbootd");
    }

    /**
     * Handle condition where device is online, but should be in bootloader state.
     *
     * <p>If this method
     *
     * @param monitor
     * @throws DeviceNotAvailableException
     */
    private void handleDeviceOnlineExpectedBootloader(final IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        CLog.i("Found device %s online but expected bootloader.", monitor.getSerialNumber());
        // call waitForDeviceOnline to get handle to IDevice
        IDevice device = monitor.waitForDeviceOnline(mOnlineWaitTime);
        if (device == null) {
            handleDeviceBootloaderOrFastbootNotAvailable(monitor, "bootloader");
            return;
        }
        rebootDevice(device, "bootloader");
        if (!monitor.waitForDeviceBootloader(mBootloaderWaitTime)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s not in bootloader after reboot", monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
    }

    private void handleDeviceOnlineExpectedFasbootd(final IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        CLog.i("Found device %s online but expected fastbootd.", monitor.getSerialNumber());
        // call waitForDeviceOnline to get handle to IDevice
        IDevice device = monitor.waitForDeviceOnline(mOnlineWaitTime);
        if (device == null) {
            handleDeviceBootloaderOrFastbootNotAvailable(monitor, "fastbootd");
            return;
        }
        rebootDevice(device, "fastboot");
        if (!monitor.waitForDeviceFastbootd(mFastbootPath, mBootloaderWaitTime)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s not in fastbootd after reboot", monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
    }

    private void handleDeviceFastbootdUnresponsive(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        CLog.i(
                "Found device %s in fastbootd but potentially unresponsive.",
                monitor.getSerialNumber());
        // TODO: retry reboot
        getRunUtil()
                .runTimedCmd(
                        mFastbootWaitTime,
                        mFastbootPath,
                        "-s",
                        monitor.getSerialNumber(),
                        "reboot",
                        "fastboot");
        // wait for device to reboot
        monitor.waitForDeviceNotAvailable(WAIT_FOR_DEVICE_OFFLINE);
        if (!monitor.waitForDeviceFastbootd(mFastbootPath, mBootloaderWaitTime)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s not in fastbootd after reboot", monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
        // running a meaningless command just to see whether the device is responsive.
        CommandResult result =
                getRunUtil()
                        .runTimedCmd(
                                mFastbootWaitTime,
                                mFastbootPath,
                                "-s",
                                monitor.getSerialNumber(),
                                "getvar",
                                "product");
        if (result.getStatus().equals(CommandStatus.TIMED_OUT)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s is in fastbootd but unresponsive",
                            monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNRESPONSIVE);
        }
    }

    /**
     * @param monitor
     * @throws DeviceNotAvailableException
     */
    private void handleDeviceBootloaderUnresponsive(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        CLog.i("Found device %s in fastboot but potentially unresponsive.",
                monitor.getSerialNumber());
        // TODO: retry reboot
        getRunUtil().runTimedCmd(mFastbootWaitTime, mFastbootPath, "-s", monitor.getSerialNumber(),
                "reboot-bootloader");
        // wait for device to reboot
        monitor.waitForDeviceNotAvailable(20*1000);
        if (!monitor.waitForDeviceBootloader(mBootloaderWaitTime)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s not in bootloader after reboot", monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
        }
        // running a meaningless command just to see whether the device is responsive.
        CommandResult result = getRunUtil().runTimedCmd(mFastbootWaitTime, mFastbootPath, "-s",
                monitor.getSerialNumber(), "getvar", "product");
        if (result.getStatus().equals(CommandStatus.TIMED_OUT)) {
            throw new DeviceNotAvailableException(
                    String.format(
                            "Device %s is in fastboot but unresponsive", monitor.getSerialNumber()),
                    monitor.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNRESPONSIVE);
        }
    }

    /**
     * Reboot device into given mode.
     *
     * @param device the {@link IDevice} to reboot.
     * @param mode The mode into which to reboot the device. null being regular reboot.
     */
    private void rebootDevice(IDevice device, String mode) throws DeviceNotAvailableException {
        try {
            device.reboot(mode);
        } catch (IOException e) {
            CLog.w(
                    "%s: failed to reboot %s: %s",
                    e.getClass().getSimpleName(), device.getSerialNumber(), e.getMessage());
        } catch (TimeoutException e) {
            CLog.w("failed to reboot %s: timeout", device.getSerialNumber());
        } catch (AdbCommandRejectedException e) {
            CLog.w(
                    "%s: failed to reboot %s: %s",
                    e.getClass().getSimpleName(), device.getSerialNumber(), e.getMessage());
            if (e.isDeviceOffline() || e.wasErrorDuringDeviceSelection()) {
                // If reboot is not attempted, then fail right away
                throw new DeviceNotAvailableException(
                        e.getMessage(),
                        e,
                        device.getSerialNumber(),
                        DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
            }
        }
    }

    /**
     * Handle situation where device is not available when expected to be in bootloader.
     *
     * @param monitor the {@link IDeviceStateMonitor}
     * @throws DeviceNotAvailableException
     */
    private void handleDeviceBootloaderOrFastbootNotAvailable(
            final IDeviceStateMonitor monitor, String mode) throws DeviceNotAvailableException {
        throw new DeviceNotAvailableException(
                String.format("Could not find device %s in %s", monitor.getSerialNumber(), mode),
                monitor.getSerialNumber(),
                DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recoverDeviceRecovery(IDeviceStateMonitor monitor)
            throws DeviceNotAvailableException {
        // TODO(b/305735893): Root and capture logs
        throw new DeviceNotAvailableException(
                "device unexpectedly went into recovery mode.",
                monitor.getSerialNumber(),
                DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
    }

    /** Recovery routine for device unavailable errors. */
    private boolean attemptDeviceUnavailableRecovery(
            IDeviceStateMonitor monitor, boolean recoverTillOnline)
            throws DeviceNotAvailableException {
        TestDeviceState state = monitor.getDeviceState();
        if (TestDeviceState.RECOVERY.equals(state)) {
            CLog.d("Device is in '%s' state skipping USB reset attempt.", state);
            recoverDeviceRecovery(monitor);
            return false;
        }
        if (TestDeviceState.FASTBOOT.equals(state) || TestDeviceState.FASTBOOTD.equals(state)) {
            CLog.d("Device is in '%s' state skipping USB reset attempt.", state);
            return false;
        }
        if (monitor.isAdbTcp()) {
            CLog.d("Device is connected via TCP, skipping USB reset attempt.");
            return false;
        }
        boolean recoveryAttempted = false;
        if (!mDisableUsbReset) {
            // First try to do a USB reset to get the device back
            try (UsbHelper usb = getUsbHelper()) {
                String serial = monitor.getSerialNumber();
                try (UsbDevice usbDevice = usb.getDevice(serial)) {
                    if (usbDevice != null) {
                        CLog.d("Resetting USB port for device '%s'", serial);
                        usbDevice.reset();
                        recoveryAttempted = true;
                        if (waitForDevice(monitor, recoverTillOnline)) {
                            // Success
                            CLog.d("Device recovered from USB reset and is online.");
                            InvocationMetricLogger.addInvocationMetrics(
                                    InvocationMetricKey.DEVICE_RECOVERY, 1);
                            return true;
                        }
                    }
                }
            } catch (LinkageError e) {
                CLog.w("Problem initializing USB helper, skipping USB reset and disabling it.");
                CLog.w(e);
                mDisableUsbReset = true;
            } catch (UsbException e) {
                CLog.w("Problem initializing USB helper, skipping USB reset.");
                CLog.w(e);
            }
        }
        if (recoveryAttempted) {
            // Sometimes device come back visible but in recovery
            if (TestDeviceState.RECOVERY.equals(monitor.getDeviceState())) {
                IDevice device = monitor.waitForDeviceInRecovery();
                if (device != null) {
                    CLog.d("Device came back in 'RECOVERY' mode when we expected 'ONLINE'");
                    rebootDevice(
                            device, null
                            /** regular mode */
                            );
                    if (waitForDevice(monitor, recoverTillOnline)) {
                        // Success
                        CLog.d("Device recovered from recovery mode and is online.");
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.DEVICE_RECOVERY, 1);
                        // Individually track this too
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.DEVICE_RECOVERY_FROM_RECOVERY, 1);
                        return true;
                    }
                }
            }
            // Track the failure
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_RECOVERY_FAIL, 1);
            CLog.w("USB reset recovery was unsuccessful");
        }
        return false;
    }

    private boolean waitForDevice(IDeviceStateMonitor monitor, boolean recoverTillOnline) {
        if (recoverTillOnline) {
            if (monitor.waitForDeviceOnline() != null) {
                // Success
                return true;
            }
        } else if (monitor.waitForDeviceAvailable() != null) {
            // Success
            return true;
        }
        return false;
    }

    @VisibleForTesting
    UsbHelper getUsbHelper() {
        return new UsbHelper();
    }
}
