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
 * limitations under the License.
 */
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.host.IHostOptions.PermitLimitType;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A target preparer that flashes the device with android generic system image. Please see
 * https://source.android.com/setup/build/gsi for details.
 */
@OptionClass(alias = "gsi-device-flash-preparer")
public class GsiDeviceFlashPreparer extends BaseTargetPreparer implements ILabPreparer {

    private static final int DYNAMIC_PARTITION_API_LEVEL = 29;
    // Wait time for device state to stablize in millisecond
    private static final int STATE_STABLIZATION_WAIT_TIME_MLLISECS = 60000;
    private static final String PVMFW_IMG_FILE_NAME = "pvmfw.img";

    @Option(
            name = "device-boot-time",
            description = "max time to wait for device to boot. Set as 5 minutes by default",
            isTimeVal = true)
    private long mDeviceBootTime = 5 * 60 * 1000;

    @Option(
            name = "system-image-zip-name",
            description = "The name of the zip file containing the system image in BuildInfo.")
    private String mSystemImageZipName = "gsi_system.img";

    @Option(
            name = "system-image-file-name",
            description =
                    "The system image file name to search for if provided system image "
                            + "is in a zip file or directory.")
    private String mSystemImageFileName = "system.img";

    @Option(
            name = "flash-pvmfw",
            description = "Fastboot flash pvmfw.img in the file system-image-zip-name.")
    private boolean mFlashPvmfw = false;

    @Option(
            name = "vbmeta-image-zip-name",
            description = "The name of the zip file containing the system image in BuildInfo.")
    private String mVbmetaImageZipName = "gsi_vbmeta.img";

    @Option(
            name = "vbmeta-image-file-name",
            description =
                    "The vbmeta image file name to search for if provided vbmeta image is "
                            + "in a zip file or directory.")
    private String mVbmetaImageFileName = "vbmeta.img";

    @Option(
            name = "boot-image-zip-name",
            description = "The name of the zip file containing the boot image in BuildInfo.")
    private String mBootImageZipName = "gki_boot.img";

    @Option(
            name = "boot-image-file-name",
            description =
                    "The boot image file name to search for if boot image is is in a zip "
                            + "file or directory, for example boot-5.4.img. The first file"
                            + "match the provided name string will be used.")
    private String mBootImageFileName = "boot(.*).img";

    @Option(
            name = "erase-product-partition",
            description = "Whether to erase product partion before flashing GSI.")
    private boolean mShouldEraseProductPartition = true;

    @Option(
            name = "post-reboot-device-into-user-space",
            description = "whether to boot the device in user space after flash.")
    private boolean mPostRebootDeviceIntoUserSpace = true;

    private File mSystemImg = null;
    private File mPvmfwImg = null;
    private File mVbmetaImg = null;
    private File mBootImg = null;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();

        File tmpDir = null;
        try {
            tmpDir = FileUtil.createTempDir("gsi_preparer");
            validateGsiImg(device, buildInfo, tmpDir);
            flashGsi(device, buildInfo);
        } catch (IOException ioe) {
            throw new TargetSetupError(ioe.getMessage(), ioe, device.getDeviceDescriptor());
        } finally {
            FileUtil.recursiveDelete(tmpDir);
        }

        if (!mPostRebootDeviceIntoUserSpace) {
            return;
        }

        // Wait some time after flashing the image.
        getRunUtil().sleep(STATE_STABLIZATION_WAIT_TIME_MLLISECS);
        device.rebootUntilOnline();
        if (device.enableAdbRoot()) {
            device.setDate(null);
        }
        try {
            device.setRecoveryMode(RecoveryMode.AVAILABLE);
            device.waitForDeviceAvailable(mDeviceBootTime);
        } catch (DeviceUnresponsiveException e) {
            // Assume this is a build problem
            throw new DeviceFailedToBootError(
                    String.format(
                            "Device %s did not become available after flashing GSI. Exception: %s",
                            device.getSerialNumber(), e),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.ERROR_AFTER_FLASHING);
        }
        device.postBootSetup();
        CLog.i("Device update completed on %s", device.getDeviceDescriptor());
    }

    /**
     * Get a reference to the {@link IHostOptions}
     *
     * @return the {@link IHostOptions} to use
     */
    @VisibleForTesting
    protected IHostOptions getHostOptions() {
        return GlobalConfiguration.getInstance().getHostOptions();
    }

    /**
     * Get the {@link IRunUtil} instance to use.
     *
     * @return the {@link IRunUtil} to use
     */
    @VisibleForTesting
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Flash GSI images.
     *
     * @param device the {@link ITestDevice}
     * @param buildInfo the {@link IBuildInfo} the build info
     * @throws TargetSetupError, DeviceNotAvailableException, IOException
     */
    private void flashGsi(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        device.waitForDeviceOnline();
        // After Android 10, system parition and product partion are moved to dynamic partitions
        // https://source.android.com/devices/tech/ota/dynamic_partitions/implement?hl=en
        boolean shouldUseFastbootd = true;
        if (device.getApiLevel() < DYNAMIC_PARTITION_API_LEVEL) {
            shouldUseFastbootd = false;
        }
        device.rebootIntoBootloader();
        long start = System.currentTimeMillis();
        getHostOptions().takePermit(PermitLimitType.CONCURRENT_FLASHER);
        CLog.v(
                "Flashing permit obtained after %ds",
                TimeUnit.MILLISECONDS.toSeconds((System.currentTimeMillis() - start)));
        // Don't allow interruptions during flashing operations.
        getRunUtil().allowInterrupt(false);
        try {
            executeFastbootCmd(device, "-w");
            if (mVbmetaImg != null) {
                executeFastbootCmd(
                        device,
                        "--disable-verification",
                        "flash",
                        "vbmeta",
                        mVbmetaImg.getAbsolutePath());
            }

            if (mSystemImg != null) {
                String currSlot = getCurrentSlot(device);
                if (currSlot == null) {
                    currSlot = "";
                } else {
                    currSlot = "_" + currSlot;
                }
                if (shouldUseFastbootd) {
                    device.rebootIntoFastbootd();
                    if (mShouldEraseProductPartition) {
                        device.executeLongFastbootCommand(
                                "delete-logical-partition", "product" + currSlot);
                    }
                }
                executeFastbootCmd(device, "erase", "system" + currSlot);
                executeFastbootCmd(device, "flash", "system", mSystemImg.getAbsolutePath());
            }
            if (mBootImg != null || mPvmfwImg != null) {
                device.rebootIntoBootloader();
            }
            if (mBootImg != null) {
                executeFastbootCmd(device, "flash", "boot", mBootImg.getAbsolutePath());
            }
            if (mPvmfwImg != null) {
                executeFastbootCmd(device, "flash", "pvmfw", mPvmfwImg.getAbsolutePath());
            }
        } finally {
            getHostOptions().returnPermit(PermitLimitType.CONCURRENT_FLASHER);
            // Allow interruption at the end no matter what.
            getRunUtil().allowInterrupt(true);
            CLog.v(
                    "Flashing permit returned after %ds",
                    TimeUnit.MILLISECONDS.toSeconds((System.currentTimeMillis() - start)));
        }
    }

    /**
     * Validate GSI image is expected. Throw exception if there is no valid GSI image.
     *
     * @param device the {@link ITestDevice}
     * @param buildInfo the {@link IBuildInfo} the build info
     * @param tmpDir the temporary directory {@link File}
     * @throws TargetSetupError if there is no valid gki boot.img
     */
    private void validateGsiImg(ITestDevice device, IBuildInfo buildInfo, File tmpDir)
            throws TargetSetupError {
        if (buildInfo.getFile(mSystemImageZipName) == null) {
            throw new TargetSetupError(
                    String.format("BuildInfo doesn't contain file key %s.", mSystemImageZipName),
                    device.getDeviceDescriptor());
        }
        mSystemImg =
                getRequestedFile(
                        device,
                        mSystemImageFileName,
                        buildInfo.getFile(mSystemImageZipName),
                        tmpDir);
        if (mFlashPvmfw) {
            mPvmfwImg =
                    getRequestedFile(
                            device,
                            PVMFW_IMG_FILE_NAME,
                            buildInfo.getFile(mSystemImageZipName),
                            tmpDir);
        }
        if (buildInfo.getFile(mVbmetaImageZipName) != null) {
            mVbmetaImg =
                    getRequestedFile(
                            device,
                            mVbmetaImageFileName,
                            buildInfo.getFile(mVbmetaImageZipName),
                            tmpDir);
        }
        if (buildInfo.getFile(mBootImageZipName) != null && mBootImageFileName != null) {
            mBootImg =
                    getRequestedFile(
                            device,
                            mBootImageFileName,
                            buildInfo.getFile(mBootImageZipName),
                            tmpDir);
        }
    }

    /**
     * Get the current partitition slot.
     *
     * @param device the {@link ITestDevice}
     * @return the current slot "a" or "b"
     * @throws TargetSetupError, DeviceNotAvailableException
     */
    private String getCurrentSlot(ITestDevice device)
            throws TargetSetupError, DeviceNotAvailableException {
        Matcher matcher;
        String queryOutput = executeFastbootCmd(device, "getvar", "current-slot");
        Pattern outputPattern = Pattern.compile("^current-slot: _?([ab])");
        matcher = outputPattern.matcher(queryOutput);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }

    /**
     * Get the requested file from the source file (zip or folder) by requested file name.
     *
     * <p>The provided source file can be a zip file. The method will unzip it to tempary directory
     * and find the requested file by the provided file name.
     *
     * <p>The provided source file can be a file folder. The method will find the requestd file by
     * the provided file name.
     *
     * @param device the {@link ITestDevice}
     * @param requestedFileName the requeste file name String
     * @param sourceFile the source file
     * @return the file that is specified by the requested file name
     * @throws TargetSetupError
     */
    private File getRequestedFile(
            ITestDevice device, String requestedFileName, File sourceFile, File tmpDir)
            throws TargetSetupError {
        File requestedFile = null;
        if (sourceFile.getName().endsWith(".zip")) {
            try {
                File destDir =
                        FileUtil.createTempDir(FileUtil.getBaseName(sourceFile.getName()), tmpDir);
                ZipUtil2.extractZip(sourceFile, destDir);
                requestedFile = FileUtil.findFile(destDir, requestedFileName);
            } catch (IOException e) {
                throw new TargetSetupError(
                        String.format("Fail to get %s from %s", requestedFileName, sourceFile),
                        e,
                        device.getDeviceDescriptor());
            }
        } else if (sourceFile.isDirectory()) {
            requestedFile = FileUtil.findFile(sourceFile, requestedFileName);
        } else {
            requestedFile = sourceFile;
        }
        if (requestedFile == null || !requestedFile.exists()) {
            throw new TargetSetupError(
                    String.format(
                            "Requested file with file_name %s does not exist in provided %s.",
                            requestedFileName, sourceFile),
                    device.getDeviceDescriptor());
        }
        return requestedFile;
    }

    /**
     * Helper method to execute a fastboot command.
     *
     * @param device the {@link ITestDevice} to execute command on
     * @param cmdArgs the arguments to provide to fastboot
     * @return String the stderr output from command if non-empty. Otherwise returns the stdout Some
     *     fastboot commands are weird in that they dump output to stderr on success case
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    private String executeFastbootCmd(ITestDevice device, String... cmdArgs)
            throws DeviceNotAvailableException, TargetSetupError {
        CLog.i(
                "Execute fastboot command %s on %s",
                Arrays.toString(cmdArgs), device.getSerialNumber());
        CommandResult result = device.executeLongFastbootCommand(cmdArgs);
        CLog.v("fastboot stdout: " + result.getStdout());
        CLog.v("fastboot stderr: " + result.getStderr());
        CommandStatus cmdStatus = result.getStatus();
        // fastboot command line output is in stderr even for successful run
        if (result.getStderr().contains("FAILED")) {
            // If output contains "FAILED", just override to failure
            cmdStatus = CommandStatus.FAILED;
        }
        if (cmdStatus != CommandStatus.SUCCESS) {
            throw new TargetSetupError(
                    String.format(
                            "fastboot command %s failed in device %s. stdout: %s, stderr: %s",
                            Arrays.toString(cmdArgs),
                            device.getSerialNumber(),
                            result.getStdout(),
                            result.getStderr()),
                    device.getDeviceDescriptor());
        }
        if (!result.getStderr().isEmpty()) {
            return result.getStderr();
        } else {
            return result.getStdout();
        }
    }
}
