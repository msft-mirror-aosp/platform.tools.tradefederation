/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tradefed.device.metric;

import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.GCOV_KERNEL;

import static com.google.common.base.Verify.verifyNotNull;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceRuntimeException;
import com.android.tradefed.device.INativeDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.util.AdbRootElevator;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import com.google.common.base.Strings;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A {@link com.android.tradefed.device.metric.BaseDeviceMetricCollector} that will pull gcov kernel
 * coverage measurements out of debugfs and off of the device and then finally logs them as test
 * artifacts.
 */
public final class GcovKernelCodeCoverageCollector extends BaseDeviceMetricCollector
        implements IConfigurationReceiver {

    public static final String DEBUGFS_PATH = "/sys/kernel/debug";
    public static final String CHECK_DEBUGFS_MNT_COMMAND =
            String.format("mountpoint -q %s", DEBUGFS_PATH);
    public static final String MOUNT_DEBUGFS_COMMAND =
            String.format("mount -t debugfs debugfs %s", DEBUGFS_PATH);
    public static final String UNMOUNT_DEBUGFS_COMMAND = String.format("umount %s", DEBUGFS_PATH);
    public static final String RESET_GCOV_COUNTS_COMMAND =
            String.format("echo 1 > %s/gcov/reset", DEBUGFS_PATH);
    public static final String MAKE_TEMP_DIR_COMMAND = "mktemp -d -p /data/local/tmp/";

    private IConfiguration mConfiguration;
    private boolean mTestRunStartFail;
    private int mTestCount;

    @Override
    public void setConfiguration(IConfiguration config) {
        mConfiguration = config;
    }

    private boolean isGcovKernelCoverageEnabled() {
        return mConfiguration != null
                && mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration
                        .getCoverageOptions()
                        .getCoverageToolchains()
                        .contains(GCOV_KERNEL);
    }

    @Override
    public void onTestRunStart(DeviceMetricData runData, int testCount)
            throws DeviceNotAvailableException {
        mTestCount = testCount;

        if (!isGcovKernelCoverageEnabled()) {
            return;
        }

        if (mTestCount == 0) {
            CLog.i("No tests in test run, not collecting coverage for %s.", getTarBasename());
            return;
        }

        try {
            for (ITestDevice device : getRealDevices()) {
                mountDebugfs(device);
                resetGcovCounts(device);
            }
        } catch (Throwable t) {
            mTestRunStartFail = true;
            throw t;
        }
        mTestRunStartFail = false;
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData, final Map<String, Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        if (!isGcovKernelCoverageEnabled() || mTestCount == 0) {
            return;
        }

        if (mTestRunStartFail) {
            CLog.e("onTestRunStart failed, not collecting coverage for %s.", getTarBasename());
            return;
        }

        for (ITestDevice device : getRealDevices()) {
            collectGcovDebugfsCoverage(device, getTarBasename());
            unmountDebugfs(device);
        }
    }

    /* Gets the name to be used for the collected coverage tar file.
     * Prefer the module name if available otherwise use the run name.
     */
    private String getTarBasename() {
        String collectionFilename = getModuleName();
        return Strings.isNullOrEmpty(collectionFilename) ? getRunName() : collectionFilename;
    }

    /** Check if debugfs is mounted. */
    private boolean isDebugfsMounted(INativeDevice device) throws DeviceNotAvailableException {
        try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
            return device.executeShellV2Command(CHECK_DEBUGFS_MNT_COMMAND).getStatus()
                    == CommandStatus.SUCCESS;
        }
    }

    /** Mount debugfs. */
    private void mountDebugfs(INativeDevice device) throws DeviceNotAvailableException {
        try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
            if (isDebugfsMounted(device)) {
                CLog.w("debugfs already mounted for %s.", getTarBasename());
                return;
            }

            CommandResult result = device.executeShellV2Command(MOUNT_DEBUGFS_COMMAND);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to mount debugfs. %s", result);
                throw new DeviceRuntimeException(
                        "'" + MOUNT_DEBUGFS_COMMAND + "' has failed: " + result,
                        DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
            }
        }
    }

    /** Unmount debugfs. */
    private void unmountDebugfs(ITestDevice device) throws DeviceNotAvailableException {
        try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
            if (!isDebugfsMounted(device)) {
                CLog.w("debugfs not mounted to unmount for %s.", getTarBasename());
                return;
            }

            CommandResult result = device.executeShellV2Command(UNMOUNT_DEBUGFS_COMMAND);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to unmount debugfs for %s. %s", getTarBasename(), result);
            }
        }
    }

    /** Reset gcov counts by writing to the gcov debugfs reset node. */
    private void resetGcovCounts(ITestDevice device) throws DeviceNotAvailableException {
        try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
            CommandResult result = device.executeShellV2Command(RESET_GCOV_COUNTS_COMMAND);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to reset gcov counts for %s. %s", getTarBasename(), result);
                throw new DeviceRuntimeException(
                        "'" + RESET_GCOV_COUNTS_COMMAND + "' has failed: " + result,
                        DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
            }
        }
    }

    /**
     * Gather overage data files off of the device. This logic is taken directly from the
     * `gather_on_test.sh` script detailed here:
     * https://www.kernel.org/doc/html/v4.15/dev-tools/gcov.html#appendix-b-gather-on-test-sh
     */
    private void collectGcovDebugfsCoverage(INativeDevice device, String name)
            throws DeviceNotAvailableException {
        try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
            if (!isDebugfsMounted(device)) {
                String errorMessage =
                        String.format("debugfs not mounted, unable to collect for %s.", name);
                CLog.e(errorMessage);
                throw new DeviceRuntimeException(
                        errorMessage, DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }

            CommandResult result = device.executeShellV2Command(MAKE_TEMP_DIR_COMMAND);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to create temp dir for %s. %s", name, result);
                throw new DeviceRuntimeException(
                        "'" + MAKE_TEMP_DIR_COMMAND + "' has failed: " + result,
                        DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
            }
            String tempDir = result.getStdout().strip();
            String tarName = String.format("%s.tar.gz", name);
            String tarFullPath = String.format("%s/%s", tempDir, tarName);
            String gcda = "/d/gcov";

            String gatherCommand =
                    String.format(
                            "find %s -type d -exec sh -c 'mkdir -p %s/$0' {} \\;; find %s -name"
                                + " '*.gcda' -exec sh -c 'cat < $0 > '%s'/$0' {} \\;; find %s -name"
                                + " '*.gcno' -exec sh -c 'cp -d $0 '%s'/$0' {} \\;; tar -czf %s -C"
                                + " %s %s",
                            gcda,
                            tempDir,
                            gcda,
                            tempDir,
                            gcda,
                            tempDir,
                            tarFullPath,
                            tempDir,
                            gcda.substring(1));

            result = device.executeShellV2Command(gatherCommand, 10, TimeUnit.MINUTES);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.e("Failed to collect coverage files for %s. %s", name, result);
                throw new DeviceRuntimeException(
                        "'" + gatherCommand + "' has failed: " + result,
                        DeviceErrorIdentifier.SHELL_COMMAND_ERROR);
            }

            // We specify the root's user id here, 0, because framework services may be stopped
            // which would cause the non-user id version of this method to fail when it attempts to
            // tget the current user id which isn't needed.
            File coverageTar = device.pullFile(tarFullPath, 0);
            verifyNotNull(
                    coverageTar,
                    "Failed to pull the native kernel coverage file %s for %s",
                    tarFullPath,
                    name);

            try (FileInputStreamSource source = new FileInputStreamSource(coverageTar, true)) {
                String fileName =
                        String.format("%s_%d_kernel_coverage", name, System.currentTimeMillis());
                testLog(fileName, LogDataType.TAR_GZ, source);
            } finally {
                FileUtil.deleteFile(coverageTar);
            }
        }
    }
}
