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

package com.android.tradefed.targetprep;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.host.IHostOptions.PermitLimitType;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.FuseUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.image.DeviceImageTracker;
import com.android.tradefed.util.image.DeviceImageTracker.FileCacheTracker;
import com.android.tradefed.util.image.IncrementalImageUtil;

import com.google.common.collect.ImmutableSet;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A class that relies on fastboot to flash an image on physical Android hardware. */
public class FastbootDeviceFlasher implements IDeviceFlasher {
    public static final String BASEBAND_IMAGE_NAME = "radio";

    private static final String FASTBOOT_VERSION = "fastboot_version";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_SLEEP = 2 * 1000; // 2s sleep between retries

    private static final String SLOT_PROP = "ro.boot.slot_suffix";
    private static final String SLOT_VAR = "current-slot";
    private static final String SKIP_REBOOT_PARAM = "--skip-reboot";
    private static final ImmutableSet<String> DISK_SPACE_ERRORS =
        ImmutableSet.of("No space left on device", "failed to create temporary file");

    private long mWipeTimeout = 4 * 60 * 1000;

    private UserDataFlashOption mUserDataFlashOption = UserDataFlashOption.FLASH;

    private IFlashingResourcesRetriever mResourceRetriever;

    private ITestsZipInstaller mTestsZipInstaller = null;

    private Collection<String> mFlashOptions = new ArrayList<>();

    private Collection<String> mDataWipeSkipList = null;

    private boolean mForceSystemFlash;

    private CommandStatus mFbCmdStatus;

    private CommandStatus mSystemFlashStatus;

    private boolean mShouldFlashRamdisk = false;

    private String mRamdiskPartition = "root";

    private String mSystemBuildId = null;
    private String mSystemBuildFlavor = null;

    private IncrementalImageUtil mIncrementalFlashing = null;
    private ITestLogger mTestLogger = null;

    @VisibleForTesting
    protected FuseUtil getFuseUtil() {
        return new FuseUtil();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFlashingResourcesRetriever(IFlashingResourcesRetriever retriever) {
        mResourceRetriever = retriever;
    }

    protected IFlashingResourcesRetriever getFlashingResourcesRetriever() {
        return mResourceRetriever;
    }

    void setTestLogger(ITestLogger logger) {
        mTestLogger = logger;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUserDataFlashOption(UserDataFlashOption flashOption) {
        mUserDataFlashOption = flashOption;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UserDataFlashOption getUserDataFlashOption() {
        return mUserDataFlashOption;
    }

    void setTestsZipInstaller(ITestsZipInstaller testsZipInstaller) {
        mTestsZipInstaller = testsZipInstaller;
    }

    ITestsZipInstaller getTestsZipInstaller() {
        // Lazily initialize the TestZipInstaller.
        if (mTestsZipInstaller == null) {
            if (mDataWipeSkipList == null) {
                mDataWipeSkipList = new ArrayList<String>();
            }
            if (mDataWipeSkipList.isEmpty()) {
                // To maintain backwards compatibility. Keep media by default.
                // TODO: deprecate and remove this.
                mDataWipeSkipList.add("media");
            }
            mTestsZipInstaller = new DefaultTestsZipInstaller(mDataWipeSkipList);
        }
        return mTestsZipInstaller;
    }

    /**
     * Sets a list of options to pass with flash/update commands.
     *
     * @param flashOptions
     */
    public void setFlashOptions(Collection<String> flashOptions) {
        // HACK: To workaround TF's command line parsing, options starting with a dash
        // needs to be prepended with a whitespace and trimmed before they are used.
        mFlashOptions = flashOptions.stream().map(String::trim).collect(Collectors.toList());
    }

    public void setIncrementalFlashing(IncrementalImageUtil incrementalUtil) {
        mIncrementalFlashing = incrementalUtil;
    }

    /** {@inheritDoc} */
    @Override
    public void preFlashOperations(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        boolean initialStateFastbootD =
                supportsFlashingInFastbootD() &&
                TestDeviceState.FASTBOOTD.equals(device.getDeviceState());
        if (initialStateFastbootD) {
            CLog.i("Using flashing from fastbootd");
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.FLASHING_FROM_FASTBOOTD, 1);
        }

        CLog.i("Flashing device %s with build %s", device.getSerialNumber(),
                deviceBuild.getDeviceBuildId());

        // Get system build id and build flavor before booting into fastboot
        if (TestDeviceState.ONLINE.equals(device.getDeviceState())) {
            setSystemBuildInfo(device.getBuildId(), device.getBuildFlavor());
        }
        downloadFlashingResources(device, deviceBuild);
        if (device instanceof IManagedTestDevice) {
            String fastbootVersion = ((IManagedTestDevice) device).getFastbootVersion();
            if (fastbootVersion != null) {
                deviceBuild.addBuildAttribute(FASTBOOT_VERSION, fastbootVersion);
            }
        }

        if (mIncrementalFlashing != null
                && mIncrementalFlashing.useUpdatedFlow()
                && shouldFlashSystem(mSystemBuildId, mSystemBuildFlavor, deviceBuild)) {
            try {
                mIncrementalFlashing.updateDeviceWithNewFlow(
                        deviceBuild.getBootloaderImageFile(), deviceBuild.getBasebandImageFile());
            } catch (TargetSetupError e) {
                mIncrementalFlashing = null;
                // In case of TargetSetupError for incremental flashing,
                // fallback to full flashing.
                CLog.e(e);
                DeviceImageTracker.getDefaultCache().invalidateTracking(device.getSerialNumber());
                if (mTestLogger != null) {
                    try (InputStreamSource source = device.getLogcatDump()) {
                        mTestLogger.testLog(
                                "apply-update-logcat-failure", LogDataType.LOGCAT, source);
                    }
                }
                if (TestDeviceState.ONLINE.equals(device.getDeviceState())) {
                    device.rebootIntoBootloader();
                }
            }
        } else {
            if (!initialStateFastbootD) {
                device.rebootIntoBootloader();
            }
        }
        preFlashSetup(device, deviceBuild);
    }

    /** {@inheritDoc} */
    @Override
    public void flash(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        if (mIncrementalFlashing != null && mIncrementalFlashing.updateCompleted()) {
            return;
        }
        handleUserDataFlashing(device, deviceBuild);
        checkAndFlashBootloader(device, deviceBuild);
        checkAndFlashBaseband(device, deviceBuild);
        flashExtraImages(device, deviceBuild);
        checkAndFlashSystem(device, mSystemBuildId, mSystemBuildFlavor, deviceBuild);
    }

    private String[] buildFastbootCommand(String action, boolean skipReboot, String... args) {
        List<String> cmdArgs = new ArrayList<>();
        if ("flash".equals(action) || "update".equals(action) || "flashall".equals(action)) {
            if (skipReboot) {
                // need to skip reboot if flashing root ramdisk, because this will be typically
                // used together with flashing of user build, and
                cmdArgs.add(SKIP_REBOOT_PARAM);
            }
            cmdArgs.addAll(mFlashOptions);
        }
        cmdArgs.add(action);
        cmdArgs.addAll(Arrays.asList(args));
        return cmdArgs.toArray(new String[cmdArgs.size()]);
    }

    /**
     * Perform any additional pre-flashing setup required. No-op unless overridden.
     *
     * @param device the {@link ITestDevice} to prepare
     * @param deviceBuild the {@link IDeviceBuildInfo} containing the build files
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    protected void preFlashSetup(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {}

    /**
     * Handle flashing of userdata/cache partition
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the files to flash
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    protected void handleUserDataFlashing(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        if (UserDataFlashOption.FORCE_WIPE.equals(mUserDataFlashOption) ||
                UserDataFlashOption.WIPE.equals(mUserDataFlashOption)) {
            CommandResult result = device.executeFastbootCommand(mWipeTimeout, "-w");
            handleFastbootResult(device, result, "-w");
        } else {
            flashUserData(device, deviceBuild);
            wipeCache(device);
        }
    }

    /**
     * Flash an individual partition of a device
     *
     * @param device the {@link ITestDevice} to flash
     * @param imgFile a {@link File} pointing to the image to be flashed
     * @param partition the name of the partition to be flashed
     */
    protected void flashPartition(ITestDevice device, File imgFile, String partition)
            throws DeviceNotAvailableException, TargetSetupError {
        CLog.d(
                "fastboot flash %s %s [size=%d]",
                partition, imgFile.getAbsolutePath(), imgFile.length());
        executeLongFastbootCmd(
                device,
                buildFastbootCommand(
                        "flash", mShouldFlashRamdisk, partition, imgFile.getAbsolutePath()));
    }

    /**
     * Wipe the specified partition with `fastboot erase &lt;name&gt;`
     *
     * @param device the {@link ITestDevice} to operate on
     * @param partition the name of the partition to be wiped
     */
    protected void wipePartition(ITestDevice device, String partition)
            throws DeviceNotAvailableException, TargetSetupError {
        String wipeMethod = device.getUseFastbootErase() ? "erase" : "format";
        CLog.d("fastboot %s %s", wipeMethod, partition);
        CommandResult result = device.fastbootWipePartition(partition);
        handleFastbootResult(device, result, wipeMethod, partition);
    }

    /**
     * Checks with the bootloader if the specified partition exists or not
     *
     * @param device the {@link ITestDevice} to operate on
     * @param partition the name of the partition to be checked
     */
    protected boolean hasPartition(ITestDevice device, String partition)
            throws DeviceNotAvailableException {
        String partitionType = String.format("partition-type:%s", partition);
        CommandResult result = device.executeFastbootCommand("getvar", partitionType);
        if (!CommandStatus.SUCCESS.equals(result.getStatus())
                || result.getStderr().contains("FAILED")) {
            return false;
        }
        Pattern regex = Pattern.compile(String.format("^%s:\\s*\\S+$", partitionType),
                Pattern.MULTILINE);
        return regex.matcher(result.getStderr()).find();
    }

    /**
     * Downloads extra flashing image files needed
     *
     * @param device the {@link ITestDevice} to download resources for
     * @param localBuild the {@link IDeviceBuildInfo} to populate. Assumes device image file is
     *     already set
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to retrieve resources
     */
    protected void downloadFlashingResources(ITestDevice device, IDeviceBuildInfo localBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        IFlashingResourcesParser resourceParser = createFlashingResourcesParser(localBuild,
                device.getDeviceDescriptor());

        if (resourceParser.getRequiredBoards() == null) {
            throw new TargetSetupError(String.format("Build %s is missing required board info.",
                    localBuild.getDeviceBuildId()), device.getDeviceDescriptor());
        }
        String deviceProductType = device.getProductType();
        if (deviceProductType == null) {
            // treat this as a fatal device error
            throw new DeviceNotAvailableException(
                    String.format(
                            "Could not determine product type for device %s",
                            device.getSerialNumber()),
                    device.getSerialNumber(),
                    DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
        }
        verifyRequiredBoards(device, resourceParser, deviceProductType);

        String bootloaderVersion = resourceParser.getRequiredBootloaderVersion();
        // only set bootloader image if this build doesn't have one already
        // TODO: move this logic to the BuildProvider step
        if (bootloaderVersion != null && localBuild.getBootloaderImageFile() == null) {
            CLog.v("Bootloader image was not included in the build artifacts (%s, %s), "
                + "fetching from blob service instead.",
                localBuild.getDeviceBuildId(), localBuild.getDeviceBuildFlavor());
            localBuild.setBootloaderImageFile(
                    getFlashingResourcesRetriever()
                            .retrieveFile(getBootloaderFilePrefix(device), bootloaderVersion),
                    bootloaderVersion);
        }
        String basebandVersion = resourceParser.getRequiredBasebandVersion();
        // only set baseband image if this build doesn't have one already
        if (basebandVersion != null && localBuild.getBasebandImageFile() == null) {
            CLog.v("Baseband image was not included in the build artifacts (%s, %s), "
                + "fetching from blob service instead.",
                localBuild.getDeviceBuildId(), localBuild.getDeviceBuildFlavor());
            localBuild.setBasebandImage(getFlashingResourcesRetriever().retrieveFile(
                    BASEBAND_IMAGE_NAME, basebandVersion), basebandVersion);
        }
        downloadExtraImageFiles(resourceParser, getFlashingResourcesRetriever(), localBuild);
    }

    /**
     * Verify that the device's product type supports the build-to-be-flashed.
     *
     * <p>The base implementation will verify that the deviceProductType is included in the {@link
     * IFlashingResourcesParser#getRequiredBoards()} collection. Subclasses may override as desired.
     *
     * @param device the {@link ITestDevice} to be flashed
     * @param resourceParser the {@link IFlashingResourcesParser}
     * @param deviceProductType the <var>device</var>'s product type
     * @throws TargetSetupError if the build's required board info did not match the device
     */
    protected void verifyRequiredBoards(
            ITestDevice device, IFlashingResourcesParser resourceParser, String deviceProductType)
            throws TargetSetupError {
        if (!containsIgnoreCase(resourceParser.getRequiredBoards(), deviceProductType)) {
            throw new TargetSetupError(
                    String.format(
                            "Device %s is %s. Expected %s",
                            device.getSerialNumber(),
                            deviceProductType,
                            resourceParser.getRequiredBoards()),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.UNEXPECTED_DEVICE_CONFIGURED);
        }
    }

    private static boolean containsIgnoreCase(Collection<String> stringList, String anotherString) {
        for (String aString : stringList) {
            if (aString != null && aString.equalsIgnoreCase(anotherString)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hook to allow subclasses to download extra custom image files if needed.
     *
     * @param resourceParser the {@link IFlashingResourcesParser}
     * @param retriever the {@link IFlashingResourcesRetriever}
     * @param localBuild the {@link IDeviceBuildInfo}
     * @throws TargetSetupError
     */
    protected void downloadExtraImageFiles(IFlashingResourcesParser resourceParser,
            IFlashingResourcesRetriever retriever, IDeviceBuildInfo localBuild)
            throws TargetSetupError {
    }

    /**
     * Factory method for creating a {@link IFlashingResourcesParser}.
     * <p/>
     * Exposed for unit testing.
     *
     * @param localBuild the {@link IDeviceBuildInfo} to parse
     * @param descriptor the descriptor of the device being flashed.
     * @return a {@link IFlashingResourcesParser} created by the factory method.
     * @throws TargetSetupError
     */
    protected IFlashingResourcesParser createFlashingResourcesParser(IDeviceBuildInfo localBuild,
            DeviceDescriptor descriptor) throws TargetSetupError {
        try {
            return new FlashingResourcesParser(localBuild.getDeviceImageFile());
        } catch (TargetSetupError e) {
            // Rethrow with descriptor since FlashingResourceParser doesn't have it.
            throw new TargetSetupError(e.getMessage(), e, descriptor);
        }
    }

    /**
     * If needed, flash the bootloader image on device.
     *
     * <p>Will only flash bootloader if current version on device != required version.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the bootloader image to flash
     * @return <code>true</code> if bootloader was flashed, <code>false</code> if it was skipped
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash bootloader
     */
    protected boolean checkAndFlashBootloader(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        String currentBootloaderVersion = getImageVersion(device, "bootloader");
        if (deviceBuild.getBootloaderVersion() != null &&
                !deviceBuild.getBootloaderVersion().equals(currentBootloaderVersion)) {
            CLog.i("Flashing bootloader %s", deviceBuild.getBootloaderVersion());
            flashBootloader(device, deviceBuild.getBootloaderImageFile());
            if (mIncrementalFlashing != null) {
                mIncrementalFlashing.notifyBootloaderNeedsRevert();
            }
            return true;
        } else {
            CLog.i("Bootloader is already version %s, skipping flashing", currentBootloaderVersion);
            return false;
        }
    }

    /**
     * Flashes the given bootloader image and reboots back into bootloader
     *
     * @param device the {@link ITestDevice} to flash
     * @param bootloaderImageFile the bootloader image {@link File}
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash
     */
    protected void flashBootloader(ITestDevice device, File bootloaderImageFile)
            throws DeviceNotAvailableException, TargetSetupError {
        // bootloader images are small, and flash quickly. so use the 'normal' timeout
        executeFastbootCmd(
                device,
                buildFastbootCommand(
                        "flash",
                        mShouldFlashRamdisk,
                        getBootPartitionName(),
                        bootloaderImageFile.getAbsolutePath()));
        device.rebootIntoBootloader();
    }

    /**
     * Get the boot partition name for this device flasher.
     *
     * <p>Defaults to 'bootloader'. Subclasses should override if necessary.
     */
    protected String getBootPartitionName() {
        return "bootloader";
    }

    /**
     * Get the bootloader file prefix.
     * <p/>
     * Defaults to {@link #getBootPartitionName()}. Subclasses should override if necessary.
     *
     * @param device the {@link ITestDevice} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to get prefix
     */
    protected String getBootloaderFilePrefix(ITestDevice device) throws TargetSetupError,
            DeviceNotAvailableException {
        return getBootPartitionName();
    }

    /**
     * If needed, flash the baseband image on device. Will only flash baseband if current version on
     * device != required version
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the baseband image to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash baseband
     */
    protected void checkAndFlashBaseband(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        if (checkShouldFlashBaseband(device, deviceBuild)) {
            CLog.i("Flashing baseband %s", deviceBuild.getBasebandVersion());
            flashBaseband(device, deviceBuild.getBasebandImageFile());
            if (mIncrementalFlashing != null) {
                mIncrementalFlashing.notifyBasebadNeedsRevert();
            }
        }
    }

    /**
     * Check if the baseband on the provided device needs to be flashed.
     *
     * @param device the {@link ITestDevice} to check
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the baseband image to check
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash baseband
     */
    protected boolean checkShouldFlashBaseband(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        String currentBasebandVersion = getImageVersion(device, "baseband");
        boolean shouldFlash =
                (deviceBuild.getBasebandVersion() != null
                        && !deviceBuild.getBasebandVersion().equals(currentBasebandVersion));
        if (!shouldFlash) {
            CLog.i("Baseband is already version %s, skipping flashing", currentBasebandVersion);
        }
        return shouldFlash;
    }

    /**
     * Flashes the given baseband image and reboot back into bootloader
     *
     * @param device the {@link ITestDevice} to flash
     * @param basebandImageFile the baseband image {@link File}
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash baseband
     */
    protected void flashBaseband(ITestDevice device, File basebandImageFile)
            throws DeviceNotAvailableException, TargetSetupError {
        flashPartition(device, basebandImageFile, BASEBAND_IMAGE_NAME);
        device.rebootIntoBootloader();
    }

    /**
     * Wipe the cache partition on device.
     *
     * @param device the {@link ITestDevice} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash cache
     */
    protected void wipeCache(ITestDevice device) throws DeviceNotAvailableException,
            TargetSetupError {
        // only wipe cache if user data is being wiped
        if (!mUserDataFlashOption.equals(UserDataFlashOption.RETAIN)) {
            CLog.i("Wiping cache on %s", device.getSerialNumber());
            String partition = "cache";
            if (hasPartition(device, partition)) {
                wipePartition(device, partition);
            }
        } else {
            CLog.d("Skipping cache wipe on %s", device.getSerialNumber());
        }
    }

    /**
     * Flash userdata partition on device.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the files to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash user data
     */
    protected void flashUserData(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        switch (mUserDataFlashOption) {
            case FLASH:
                CLog.i("Flashing %s with userdata %s", device.getSerialNumber(),
                        deviceBuild.getUserDataImageFile().getAbsolutePath());
                flashPartition(device, deviceBuild.getUserDataImageFile(), "userdata");
                break;
            case FLASH_IMG_ZIP:
                flashUserDataFromDeviceImageFile(device, deviceBuild);
                break;
            case FORCE_WIPE: // intentional fallthrough
            case WIPE:
                CLog.i("Wiping userdata %s", device.getSerialNumber());
                wipePartition(device, "userdata");
                break;

            case TESTS_ZIP:
                device.rebootUntilOnline(); // required to install tests
                if (device.isEncryptionSupported() && device.isDeviceEncrypted()) {
                    device.unlockDevice();
                }
                getTestsZipInstaller().pushTestsZipOntoData(device, deviceBuild);
                // Reboot into bootloader to continue the flashing process
                device.rebootIntoBootloader();
                break;

            case WIPE_RM:
                device.rebootUntilOnline(); // required to install tests
                getTestsZipInstaller().deleteData(device);
                // Reboot into bootloader to continue the flashing process
                device.rebootIntoBootloader();
                break;

            default:
                CLog.d("Skipping userdata flash for %s", device.getSerialNumber());
        }
    }

    /**
     * Extracts the userdata.img from device image file and flashes it onto device
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the files to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to extract or flash user data
     */
    protected void flashUserDataFromDeviceImageFile(
            ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        File userdataImg = null;
        try {
            try (ZipFile zip = new ZipFile(deviceBuild.getDeviceImageFile())) {
                userdataImg = ZipUtil2.extractFileFromZip(zip, "userdata.img");
            } catch (IOException ioe) {
                throw new TargetSetupError("failed to extract userdata.img from image file", ioe,
                        device.getDeviceDescriptor());
            }
            CLog.i("Flashing %s with userdata %s", device.getSerialNumber(), userdataImg);
            flashPartition(device, userdataImg, "userdata");
        } finally {
            FileUtil.deleteFile(userdataImg);
        }
    }

    /**
     * Flash any device specific partitions before flashing system and rebooting. No-op unless
     * overridden.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} containing the build files
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    protected void flashExtraImages(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {}

    /**
     * If needed, flash the system image on device.
     *
     * <p>Please look at {@link #shouldFlashSystem(String, String, IDeviceBuildInfo)}
     *
     * <p>Regardless of path chosen, after method execution device should be booting into userspace.
     *
     * @param device the {@link ITestDevice} to flash
     * @param systemBuildId the current build id running on the device
     * @param systemBuildFlavor the current build flavor running on the device
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the system image to flash
     * @return <code>true</code> if system was flashed, <code>false</code> if it was skipped
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if failed to flash bootloader
     */
    protected boolean checkAndFlashSystem(
            ITestDevice device,
            String systemBuildId,
            String systemBuildFlavor,
            IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        if (shouldFlashSystem(systemBuildId, systemBuildFlavor, deviceBuild)) {
            CLog.i("Flashing system %s", deviceBuild.getDeviceBuildId());
            flashSystem(device, deviceBuild);
            return true;
        }
        CLog.i(
                "System is already version %s and build flavor %s, skipping flashing",
                systemBuildId, systemBuildFlavor);
        if (mShouldFlashRamdisk) {
            // even if we don't flash system, still flash ramdisk just in case: because the fact
            // that the system had a different ramdisk won't be captured by a simple build check
            flashRamdiskIfNeeded(device, deviceBuild);
            CLog.i("Flashed ramdisk anyways per flasher settings.");
        }
        // reboot
        device.rebootUntilOnline();
        return false;
    }

    /**
     * Helper method used to determine if we need to flash the system image.
     *
     * @param systemBuildId the current build id running on the device
     * @param systemBuildFlavor the current build flavor running on the device
     * @param deviceBuild the {@link IDeviceBuildInfo} that contains the system image to flash
     * @return <code>true</code> if we should flash the system, <code>false</code> otherwise.
     */
    boolean shouldFlashSystem(String systemBuildId, String systemBuildFlavor,
            IDeviceBuildInfo deviceBuild) {
        if (mForceSystemFlash) {
            // Flag overrides all logic.
            return true;
        }
        // Err on the side of caution, if we failed to get the build id or build flavor, force a
        // flash of the system.
        if (systemBuildFlavor == null || systemBuildId == null) {
            return true;
        }
        // If we have the same build id and build flavor we don't need to flash it.
        if (systemBuildId.equals(deviceBuild.getDeviceBuildId())) {
            FileCacheTracker tracker =
                    DeviceImageTracker.getDefaultCache()
                            .getBaselineDeviceImage(deviceBuild.getDeviceSerial());
            if (tracker != null
                    && tracker.buildId.equals(systemBuildId)
                    && tracker.flavor.equals(deviceBuild.getBuildFlavor())) {
                if (mIncrementalFlashing != null
                        && mIncrementalFlashing.isSameBuildFlashingAllowed()) {
                    CLog.d("Same build incremental flashing is allowed");
                    return true;
                }
                return false;
            }
            if (systemBuildFlavor.equalsIgnoreCase(deviceBuild.getBuildFlavor())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Flash the system image on device.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    protected void flashSystem(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        CLog.i(
                "Flashing device %s with image %s",
                device.getSerialNumber(), deviceBuild.getDeviceImageFile().getAbsolutePath());
        // give extra time to the update cmd
        boolean tookPermit = false;
        try (CloseableTraceScope ignored = new CloseableTraceScope("flash_system")) {
            boolean shouldFlash = true;
            if (mIncrementalFlashing != null) {
                try {
                    mIncrementalFlashing.updateDevice(
                            deviceBuild.getBootloaderImageFile(),
                            deviceBuild.getBasebandImageFile());
                    shouldFlash = false;
                } catch (TargetSetupError e) {
                    // In case of TargetSetupError for incremental flashing,
                    // fallback to full flashing.
                    CLog.e(e);
                    DeviceImageTracker.getDefaultCache()
                            .invalidateTracking(device.getSerialNumber());
                    if (TestDeviceState.ONLINE.equals(device.getDeviceState())) {
                        device.rebootIntoBootloader();
                    }
                }
            }
            long startWait = System.currentTimeMillis();
            if (shouldFlash && mIncrementalFlashing != null) {
                // Take the permit in case of fallback from incremental
                try (CloseableTraceScope waitFor =
                        new CloseableTraceScope("wait_for_flashing_permit")) {
                    // Only #flash is included in the critical section
                    getHostOptions().takePermit(PermitLimitType.CONCURRENT_FLASHER);
                    tookPermit = true;
                    long queueTime = System.currentTimeMillis() - startWait;
                    CLog.v(
                            "Flashing permit obtained after %ds",
                            TimeUnit.MILLISECONDS.toSeconds(queueTime));
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.FLASHING_PERMIT_LATENCY, queueTime);
                }
            }
            if (shouldFlash) {
                if (deviceBuild.getDeviceImageFile().isDirectory()) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.FLASHING_METHOD,
                            FlashingMethod.FASTBOOT_FLASH_ALL.toString());
                    flashWithAll(device, deviceBuild);
                } else if (getHostOptions().shouldFlashWithFuseZip()
                        && getFuseUtil().canMountZip()) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.FLASHING_METHOD,
                            FlashingMethod.FASTBOOT_FLASH_ALL_FUSE_ZIP.toString());
                    flashWithFuseZip(device, deviceBuild);
                } else {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.FLASHING_METHOD,
                            FlashingMethod.FASTBOOT_UPDATE.toString());
                    flashWithUpdateCommand(device, deviceBuild);
                }
            }
            flashRamdiskIfNeeded(device, deviceBuild);
            // only transfer last fastboot command status over to system flash status after having
            // flashing the system partitions
            mSystemFlashStatus = mFbCmdStatus;
        } finally {
            // if system flash status is still null here, an exception has happened
            if (mSystemFlashStatus == null) {
                mSystemFlashStatus = CommandStatus.EXCEPTION;
            }
            if (tookPermit) {
                getHostOptions().returnPermit(PermitLimitType.CONCURRENT_FLASHER);
            }
        }
    }

    /**
     * Flash the system image on device by using an image directory with fastboot flashall command.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    private void flashWithAll(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        try {
            Map<String, String> systemVarMap = new HashMap<>();
            systemVarMap.put(
                    "ANDROID_PRODUCT_OUT", deviceBuild.getDeviceImageFile().getAbsolutePath());
            String[] fastbootArgs = buildFastbootCommand("flashall", mShouldFlashRamdisk);
            executeLongFastbootCmd(device, systemVarMap, fastbootArgs);
        } catch (DeviceNotAvailableException e) {
            // We wrap the exception from recovery if it fails to provide a clear message
            throw new DeviceNotAvailableException(
                    "Device became unavailable during fastboot 'flashall'. Please verify that "
                            + "the image you are flashing can boot properly.",
                    e,
                    device.getSerialNumber());
        }
    }

    /**
     * Flash the system image on device by using fuse-zip mounting with fastboot flashall command.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    private void flashWithFuseZip(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        FuseUtil fuseUtil = getFuseUtil();
        File mountPoint = null;
        Throwable exception = null;
        try {
            mountPoint = FileUtil.createTempDir("FlashAllMountPoint");
            fuseUtil.mountZip(deviceBuild.getDeviceImageFile().getAbsoluteFile(), mountPoint);
            Map<String, String> systemVarMap = new HashMap<>();
            systemVarMap.put("ANDROID_PRODUCT_OUT", mountPoint.getAbsolutePath());
            String[] fastbootArgs = buildFastbootCommand("flashall", mShouldFlashRamdisk);
            executeLongFastbootCmd(device, systemVarMap, fastbootArgs);
        } catch (DeviceNotAvailableException e) {
            // We wrap the exception from recovery if it fails to provide a clear message
            exception = e;
            throw new DeviceNotAvailableException(
                    "Device became unavailable during fastboot 'flashall'. Please verify that "
                            + "the image you are flashing can boot properly.",
                    e,
                    device.getSerialNumber());
        } catch (IOException e) {
            exception = e;
            throw new TargetSetupError(
                    String.format(
                            "Unable to create a temp dir for fuse zip to mount on, error: %s",
                            e.getMessage()),
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        } finally {
            if (mountPoint != null) {
                fuseUtil.unmountZip(mountPoint);
                FileUtil.recursiveDelete(mountPoint);
            }
            // In case the unmount operation fails, deleting the mount point will fail as well.
            if (mountPoint.exists()) {
                String mountErrorMsg =
                        String.format(
                                "Failed to delete mount point %s, unmount operation might failed.",
                                mountPoint);
                if (exception != null) {
                    // If a previous exception happened, surface the previous exception only
                    CLog.e(mountErrorMsg);
                } else {
                    throw new HarnessRuntimeException(
                            mountErrorMsg, InfraErrorIdentifier.LAB_HOST_FILESYSTEM_ERROR);
                }
            }
        }
    }

    /**
     * Flash the system image on device by using fastboot update command.
     *
     * @param device the {@link ITestDevice} to flash
     * @param deviceBuild the {@link IDeviceBuildInfo} to flash
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    private void flashWithUpdateCommand(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws DeviceNotAvailableException, TargetSetupError {
        try {
            executeLongFastbootCmd(
                    device,
                    buildFastbootCommand(
                            "update",
                            mShouldFlashRamdisk,
                            deviceBuild.getDeviceImageFile().getAbsolutePath()));
        } catch (DeviceNotAvailableException e) {
            // We wrap the exception from recovery if it fails to provide a clear message
            throw new DeviceNotAvailableException(
                    "Device became unavailable during fastboot 'update'. Please verify that "
                            + "the image you are flashing can boot properly.",
                    e,
                    device.getSerialNumber());
        }
    }

    protected String getImageVersion(ITestDevice device, String imageName)
            throws DeviceNotAvailableException, TargetSetupError {
        return fetchImageVersion(getRunUtil(), device, imageName);
    }

    /**
     * Helper method to get the current image version on device.
     *
     * @param device the {@link ITestDevice} to execute command on
     * @param imageName the name of image to get.
     * @return String the stdout output from command
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails or version could not be determined
     */
    public static String fetchImageVersion(IRunUtil runUtil, ITestDevice device, String imageName)
            throws DeviceNotAvailableException, TargetSetupError {
        int attempts = 0;
        String versionQuery = String.format("version-%s", imageName);
        String patternString = String.format("%s:\\s(.*)\\s", versionQuery);
        Pattern versionOutputPattern = Pattern.compile(patternString);

        while (attempts < MAX_RETRY_ATTEMPTS) {
            CLog.v("Executing short fastboot command 'getvar %s'", versionQuery);
            CommandResult result = device.executeFastbootCommand("getvar", versionQuery);
            String queryOutput =
                    internalHandleFastbootResult(device, result, "getvar", versionQuery);
            Matcher matcher = versionOutputPattern.matcher(queryOutput);
            if (matcher.find()) {
                return matcher.group(1);
            } else {
                attempts++;
                CLog.w(
                        "Could not find version for '%s'. Output '%s', retrying.",
                        imageName, queryOutput);
                runUtil.sleep(
                        RETRY_SLEEP * (attempts - 1)
                                + new Random(System.currentTimeMillis()).nextInt(RETRY_SLEEP));
                continue;
            }
        }
        throw new TargetSetupError(String.format(
                "Could not find version for '%s' after %d retry attempts", imageName, attempts),
                device.getDeviceDescriptor());
    }

    /**
     * Helper method to retrieve the current slot (for A/B capable devices).
     *
     * @param device the {@link ITestDevice} to execute command on.
     * @return "a", "b" or null (if device is not A/B capable)
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    protected String getCurrentSlot(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        Matcher matcher;
        if (device.getDeviceState().equals(TestDeviceState.FASTBOOT)) {
            String queryOutput = executeFastbootCmd(device, "getvar", SLOT_VAR);
            Pattern outputPattern = Pattern.compile(String.format("^%s: _?([ab])", SLOT_VAR));
            matcher = outputPattern.matcher(queryOutput);
        } else {
            String queryOutput = device.executeShellCommand(String.format("getprop %s", SLOT_PROP));
            Pattern outputPattern =
                    Pattern.compile(String.format("^\\[%s\\]: \\[_?([ab])\\]", SLOT_PROP));
            matcher = outputPattern.matcher(queryOutput);
        }
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }

    /** Exposed for testing. */
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Helper method to execute fastboot command.
     *
     * @param device the {@link ITestDevice} to execute command on
     * @param cmdArgs the arguments to provide to fastboot
     * @return String the stderr output from command if non-empty. Otherwise returns the stdout Some
     *     fastboot commands are weird in that they dump output to stderr on success case
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    protected String executeFastbootCmd(ITestDevice device, String... cmdArgs)
            throws DeviceNotAvailableException, TargetSetupError {
        CLog.v("Executing short fastboot command %s", java.util.Arrays.toString(cmdArgs));
        CommandResult result = device.executeFastbootCommand(cmdArgs);
        return handleFastbootResult(device, result, cmdArgs);
    }

    /**
     * Helper method to execute a long-running fastboot command.
     *
     * <p>Note: Most fastboot commands normally execute within the timeout allowed by {@link
     * ITestDevice#executeFastbootCommand(String...)}. However, when multiple devices are flashing
     * devices at once, fastboot commands can take much longer than normal.
     *
     * @param device the {@link ITestDevice} to execute command on
     * @param cmdArgs the arguments to provide to fastboot
     * @return String the stderr output from command if non-empty. Otherwise returns the stdout Some
     *     fastboot commands are weird in that they dump output to stderr on success case
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    protected String executeLongFastbootCmd(ITestDevice device, String... cmdArgs)
            throws DeviceNotAvailableException, TargetSetupError {
        return executeLongFastbootCmd(device, new HashMap<>(), cmdArgs);
    }

    /**
     * Helper method to execute a long-running fastboot command with environment variables.
     *
     * <p>Note: Most fastboot commands normally execute within the timeout allowed by {@link
     * ITestDevice#executeFastbootCommand(String...)}. However, when multiple devices are flashing
     * devices at once, fastboot commands can take much longer than normal.
     *
     * @param device the {@link ITestDevice} to execute command on
     * @param envVarMap the map which carries environment variables which need to be set before
     *     running the fastboot command
     * @param cmdArgs the arguments to provide to fastboot
     * @return String the stderr output from command if non-empty. Otherwise returns the stdout Some
     *     fastboot commands are weird in that they dump output to stderr on success case
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if fastboot command fails
     */
    protected String executeLongFastbootCmd(
            ITestDevice device, Map<String, String> envVarMap, String... cmdArgs)
            throws DeviceNotAvailableException, TargetSetupError {
        CommandResult result = device.executeLongFastbootCommand(envVarMap, cmdArgs);
        return handleFastbootResult(device, result, cmdArgs);
    }

    /**
     * Interpret the result of a fastboot command
     *
     * @param device
     * @param result
     * @param cmdArgs
     * @return the stderr output from command if non-empty. Otherwise returns the stdout
     * @throws TargetSetupError
     */
    @VisibleForTesting
    String handleFastbootResult(ITestDevice device, CommandResult result, String... cmdArgs)
            throws TargetSetupError {
        try {
            String res = internalHandleFastbootResult(device, result, cmdArgs);
            mFbCmdStatus = CommandStatus.SUCCESS;
            return res;
        } catch (TargetSetupError e) {
            mFbCmdStatus = CommandStatus.FAILED;
            throw e;
        }
    }

    private static String internalHandleFastbootResult(
            ITestDevice device, CommandResult result, String... cmdArgs) throws TargetSetupError {
        CLog.v("fastboot stdout: " + result.getStdout());
        CLog.v("fastboot stderr: " + result.getStderr());
        CommandStatus status = result.getStatus();
        ErrorIdentifier errorIdentifier = null;
        boolean diskErrorIdentified = false;
        for (String diskError : DISK_SPACE_ERRORS) {
            if (result.getStderr().contains(diskError)) {
                errorIdentifier = InfraErrorIdentifier.NO_DISK_SPACE;
                status = CommandStatus.FAILED;
                diskErrorIdentified = true;
                break;
            }
        }

        if (!diskErrorIdentified && result.getStderr().contains("FAILED")) {
            // if output contains "FAILED", just override to failure
            status = CommandStatus.FAILED;
        }
        if (status != CommandStatus.SUCCESS) {
            if (errorIdentifier == null) {
                errorIdentifier = DeviceErrorIdentifier.ERROR_AFTER_FLASHING;
            }
            throw new TargetSetupError(
                    String.format(
                            "fastboot command %s failed in device %s. stdout: %s, stderr: %s",
                            cmdArgs[0],
                            device.getSerialNumber(),
                            result.getStdout(),
                            result.getStderr()),
                    device.getDeviceDescriptor(),
                    errorIdentifier);
        }
        if (result.getStderr().length() > 0) {
            return result.getStderr();
        } else {
            return result.getStdout();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void overrideDeviceOptions(ITestDevice device) {
        // ignore
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setForceSystemFlash(boolean forceSystemFlash) {
        mForceSystemFlash = forceSystemFlash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDataWipeSkipList(Collection<String> dataWipeSkipList) {
        if (dataWipeSkipList == null) {
            dataWipeSkipList = new ArrayList<String>();
        }
        if (dataWipeSkipList.isEmpty()) {
            // To maintain backwards compatibility.
            // TODO: deprecate and remove.
            dataWipeSkipList.add("media");
        }
        mDataWipeSkipList = dataWipeSkipList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWipeTimeout(long timeout) {
        mWipeTimeout = timeout;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandStatus getSystemFlashingStatus() {
        return mSystemFlashStatus;
    }

    /** {@inheritDoc} */
    @Override
    public void setShouldFlashRamdisk(boolean shouldFlashRamdisk) {
        mShouldFlashRamdisk = shouldFlashRamdisk;
    }

    /** {@inheritDoc} */
    @Override
    public void setRamdiskPartition(String ramdiskPartition) {
        mRamdiskPartition = ramdiskPartition;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldFlashRamdisk() {
        return mShouldFlashRamdisk;
    }

    protected void flashRamdiskIfNeeded(ITestDevice device, IDeviceBuildInfo deviceBuild)
            throws TargetSetupError, DeviceNotAvailableException {
        if (mShouldFlashRamdisk) {
            // Flash ramdisk in bootloader
            device.rebootIntoBootloader();
            executeLongFastbootCmd(
                    device,
                    "flash",
                    mRamdiskPartition,
                    deviceBuild.getRamdiskFile().getAbsolutePath());
            device.reboot();
        }
    }

    protected void setSystemBuildInfo(String systemBuildId, String systemBuildFlavor) {
        mSystemBuildId = systemBuildId;
        mSystemBuildFlavor = systemBuildFlavor;
    }

    /** Gets the {@link IHostOptions} instance to use. */
    @VisibleForTesting
    IHostOptions getHostOptions() {
        return GlobalConfiguration.getInstance().getHostOptions();
    }
}
