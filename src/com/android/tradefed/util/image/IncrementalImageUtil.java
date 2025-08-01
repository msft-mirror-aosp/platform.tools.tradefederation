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
package com.android.tradefed.util.image;

import static org.junit.Assert.assertTrue;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.SnapuserdWaitPhase;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationGroupMetricKey;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorService;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.FastbootDeviceFlasher;
import com.android.tradefed.targetprep.FlashingResourcesParser;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;
import com.android.tradefed.util.image.DeviceImageTracker.FileCacheTracker;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** A utility to leverage the incremental image and device update. */
public class IncrementalImageUtil {

    private static final AtomicInteger poolNumber = new AtomicInteger(1);

    public static final Set<String> DYNAMIC_PARTITIONS_TO_DIFF =
            ImmutableSet.of(
                    "product.img",
                    "system.img",
                    "system_dlkm.img",
                    "system_ext.img",
                    "vendor.img",
                    "vendor_dlkm.img");

    private final File mTargetImage;
    private final ITestDevice mDevice;
    private final File mCreateSnapshotBinary;
    private final boolean mApplySnapshot;
    private final boolean mWipeAfterApplySnapshot;
    private final boolean mUpdateBootloaderFromUserspace;
    private boolean mNewFlow;
    private final SnapuserdWaitPhase mWaitPhase;

    private boolean mAllowSameBuildFlashing = false;
    private boolean mAllowUnzipBaseline = false;
    private boolean mBootloaderNeedsFlashing = false;
    private boolean mBasebandNeedsFlashing = false;
    private boolean mUpdateWasCompleted = false;
    private File mSourceDirectory;
    private File mTargetDirectory;

    private ParallelPreparation mParallelSetup;
    private final IRunUtil mRunUtil;

    public static IncrementalImageUtil initialize(
            ITestDevice device,
            IDeviceBuildInfo build,
            File createSnapshot,
            boolean isIsolatedSetup,
            boolean allowTrackerlessUpdate,
            Set<String> allowedTransition,
            boolean newFlow,
            boolean updateBootloaderFromUserspace,
            SnapuserdWaitPhase waitPhase,
            boolean useMerkleTree)
            throws DeviceNotAvailableException {
        String serialNumber = device.getSerialNumber();
        FileCacheTracker tracker =
                DeviceImageTracker.getDefaultCache().getBaselineDeviceImage(serialNumber);
        boolean crossRelease = false;
        if (tracker == null) {
            CLog.d("Not tracking current baseline image for %s", serialNumber);
        } else {
            String deviceBuildId = device.getBuildId();
            if (!tracker.buildId.equals(deviceBuildId)) {
                CLog.d(
                        "On-device build (id = %s) isn't matching the cache (id = %s).",
                        deviceBuildId, tracker.buildId);
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.DEVICE_IMAGE_CACHE_MISMATCH, 1);
                return null;
            }
            if (tracker.branch.contains("release")) {
                CLog.d("Skipping incremental flashing for release builds origin.");
                return null;
            }
            if (!tracker.branch.equals(build.getBuildBranch())) {
                if (allowedTransition.contains(tracker.branch)
                        && allowedTransition.contains(build.getBuildBranch())) {
                    CLog.d(
                            "Allowing transition from %s => %s",
                            tracker.branch, build.getBuildBranch());
                } else {
                    CLog.d("Newer build is not on the same branch.");
                    return null;
                }
            }
            if (!tracker.flavor.equals(build.getBuildFlavor())) {
                CLog.d(
                        "Allowing cross-flavor update from '%s' to '%s'",
                        tracker.flavor, build.getBuildFlavor());
                crossRelease = true;
            }
        }
        if (!isSnapshotSupported(device, useMerkleTree)) {
            CLog.d("Incremental flashing not supported.");
            return null;
        }
        if (crossRelease) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_ACROSS_RELEASE_COUNT, 1);
        }

        if (tracker != null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_IMAGE_CACHE_ORIGIN,
                    String.format("%s:%s:%s", tracker.branch, tracker.buildId, tracker.flavor));
        } else {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_IMAGE_CACHE_ORIGIN, "no baseline");
        }
        File merkleTreeDir = null;
        if (useMerkleTree) {
            device.executeShellV2Command("mkdir -p /data/verity-hash/");
            CommandResult merkleDump =
                    device.executeShellV2Command("snapshotctl dump-verity-hash /data/verity-hash/");
            if (CommandStatus.SUCCESS.equals(merkleDump.getStatus())) {
                try {
                    merkleTreeDir =
                            FileUtil.createTempDir(
                                    "device-merkle-tree", CurrentInvocation.getWorkFolder());
                    boolean res = device.pullDir("/data/verity-hash/", merkleTreeDir);
                    if (!res) {
                        CLog.w("Failed to pull merkle tree");
                        FileUtil.recursiveDelete(merkleTreeDir);
                        merkleTreeDir = null;
                    }
                } catch (IOException e) {
                    CLog.e(e);
                    FileUtil.recursiveDelete(merkleTreeDir);
                    merkleTreeDir = null;
                }
            }
        }
        return new IncrementalImageUtil(
                device,
                build.getDeviceImageFile(),
                createSnapshot,
                newFlow,
                updateBootloaderFromUserspace,
                waitPhase,
                merkleTreeDir);
    }

    public IncrementalImageUtil(
            ITestDevice device,
            File targetImage,
            File createSnapshot,
            boolean newFlow,
            boolean updateBootloaderFromUserspace,
            SnapuserdWaitPhase waitPhase,
            File deviceMerkleTree) {
        mDevice = device;
        mApplySnapshot = true;
        mWipeAfterApplySnapshot = true;
        mNewFlow = newFlow;
        mUpdateBootloaderFromUserspace = updateBootloaderFromUserspace;
        mWaitPhase = waitPhase;

        mTargetImage = targetImage;
        mRunUtil = new RunUtil();
        // TODO: clean up when docker image is updated
        mRunUtil.setEnvVariable("LD_LIBRARY_PATH", "/tradefed/lib64");
        if (createSnapshot != null) {
            File snapshot = createSnapshot;
            try {
                if (createSnapshot.getName().endsWith(".zip")
                        && ZipUtil.isZipFileValid(createSnapshot, false)) {
                    File destDir = ZipUtil2.extractZipToTemp(createSnapshot, "create_snapshot");
                    snapshot = FileUtil.findFile(destDir, "create_snapshot");
                }
            } catch (IOException e) {
                CLog.e(e);
            }
            mCreateSnapshotBinary = snapshot;
            FileUtil.chmodGroupRWX(snapshot);
        } else {
            mCreateSnapshotBinary = null;
        }
        mParallelSetup =
                new ParallelPreparation(
                        Thread.currentThread().getThreadGroup(),
                        mTargetImage,
                        deviceMerkleTree);
        mParallelSetup.start();
    }

    private static File copyImage(File originalImage) throws IOException {
        if (originalImage.isDirectory()) {
            CLog.d("Baseline was already unzipped for %s", originalImage);
            File copy =
                    FileUtil.createTempDir(
                            FileUtil.getBaseName(originalImage.getName()),
                            CurrentInvocation.getWorkFolder());
            FileUtil.recursiveHardlink(originalImage, copy);
            return copy;
        } else {
            File copy =
                    FileUtil.createTempFile(
                            FileUtil.getBaseName(originalImage.getName()),
                            ".img",
                            CurrentInvocation.getWorkFolder());
            copy.delete();
            FileUtil.hardlinkFile(originalImage, copy);
            return copy;
        }
    }

    /** Returns whether or not we can use the snapshot logic to update the device */
    public static boolean isSnapshotSupported(ITestDevice device, boolean useMerkle)
            throws DeviceNotAvailableException {
        // Ensure snapshotctl exists
        CommandResult whichOutput = device.executeShellV2Command("which snapshotctl");
        CLog.d("stdout: %s, stderr: %s", whichOutput.getStdout(), whichOutput.getStderr());
        if (!whichOutput.getStdout().contains("/system/bin/snapshotctl")) {
            return false;
        }
        CommandResult helpOutput = device.executeShellV2Command("snapshotctl");
        CLog.d("stdout: %s, stderr: %s", helpOutput.getStdout(), helpOutput.getStderr());
        if (useMerkle) {
            if (!helpOutput.getStdout().contains("dump-verity-hash")
                    && !helpOutput.getStderr().contains("dump-verity-hash")) {
                return false;
            }
        }
        if (helpOutput.getStdout().contains("apply-update")
                || helpOutput.getStderr().contains("apply-update")) {
            return true;
        }
        return false;
    }

    public void notifyBootloaderNeedsRevert() {
        mBootloaderNeedsFlashing = true;
    }

    public void notifyBasebadNeedsRevert() {
        mBasebandNeedsFlashing = true;
    }

    public void allowSameBuildFlashing() {
        mAllowSameBuildFlashing = true;
    }

    public boolean isSameBuildFlashingAllowed() {
        return mAllowSameBuildFlashing;
    }

    public void allowUnzipBaseline() {
        mAllowUnzipBaseline = true;
    }

    public boolean useUpdatedFlow() {
        return mNewFlow;
    }

    /** Returns whether device is currently using snapshots or not. */
    public static boolean isSnapshotInUse(ITestDevice device) throws DeviceNotAvailableException {
        CommandResult dumpOutput = device.executeShellV2Command("snapshotctl dump");
        CLog.d("stdout: %s, stderr: %s", dumpOutput.getStdout(), dumpOutput.getStderr());
        if (dumpOutput.getStdout().contains("Using snapuserd: 0")) {
            return false;
        }
        return true;
    }

    public void updateDeviceWithNewFlow(File currentBootloader, File currentRadio)
            throws DeviceNotAvailableException, TargetSetupError {
        if (!mNewFlow || !mApplySnapshot || !mWipeAfterApplySnapshot) {
            mNewFlow = false;
            return;
        }
        // If device isn't online, we can't use the new flow
        if (!TestDeviceState.ONLINE.equals(mDevice.getDeviceState())) {
            mNewFlow = false;
            return;
        }
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.INCREMENTAL_NEW_FLOW, 1);
        // If enable, push the bootloader from userspace like OTA
        if (mUpdateBootloaderFromUserspace) {
            updateBootloaderFromUserspace(currentBootloader);
        }
        CLog.d("Updating with new incremental flow.");
        updateDevice(currentBootloader, currentRadio);
    }

    /** Updates the device using the snapshot logic. */
    public void updateDevice(File currentBootloader, File currentRadio)
            throws DeviceNotAvailableException, TargetSetupError {
        if (mDevice.isStateBootloaderOrFastbootd()) {
            mDevice.rebootUntilOnline();
        }
        if (!mDevice.enableAdbRoot()) {
            throw new TargetSetupError(
                    "Failed to obtain root, this is required for incremental update.",
                    InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
        }
        try {
            internalUpdateDevice(currentBootloader, currentRadio);
        } catch (DeviceNotAvailableException | TargetSetupError | RuntimeException e) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_FLASHING_UPDATE_FAILURE, 1);
            throw e;
        }
    }

    private void updateBootloaderFromUserspace(File currentBootloader)
            throws DeviceNotAvailableException, TargetSetupError {
        File bootloaderDir = null;
        try (CloseableTraceScope ignored = new CloseableTraceScope("update_bootloader_userspace")) {
            String listAbPartitions = mDevice.getProperty("ro.product.ab_ota_partitions");
            if (listAbPartitions == null) {
                throw new TargetSetupError(
                        "Couldn't query ab_ota_partitions",
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }
            String bootSuffix = mDevice.getProperty("ro.boot.slot_suffix");
            if (bootSuffix == null) {
                throw new TargetSetupError(
                        "Couldn't query ro.boot.slot_suffix",
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }
            if (bootSuffix.equals("_a")) {
                bootSuffix = "_b";
            } else if (bootSuffix.equals("_b")) {
                bootSuffix = "_a";
            } else {
                throw new TargetSetupError(
                        String.format("unexpected ro.boot.slot_suffix: %s", bootSuffix),
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }

            Set<String> partitions =
                    Arrays.asList(listAbPartitions.split(",")).stream()
                            .map(p -> p + ".img")
                            .collect(Collectors.toSet());
            CLog.d("Bootloader partitions to be considered: %s", partitions);
            try {
                bootloaderDir =
                        FileUtil.createTempDir("bootloader", CurrentInvocation.getWorkFolder());
                FastbootPack.unpack(currentBootloader, bootloaderDir, null, false);
            } catch (IOException e) {
                throw new TargetSetupError(
                        e.getMessage(), e, InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }
            Set<File> toBePushed = new LinkedHashSet<File>();
            for (File f : bootloaderDir.listFiles()) {
                if (partitions.contains(f.getName())) {
                    toBePushed.add(f);
                }
            }
            CLog.d("Bootloader partitions to be updated: %s", toBePushed);
            mDevice.executeShellV2Command("mkdir -p /data/bootloader");
            for (File push : toBePushed) {
                boolean success = mDevice.pushFile(push, "/data/bootloader/" + push.getName());
                if (!success) {
                    throw new TargetSetupError(
                            "Failed to push bootloader partition.",
                            InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
                }
            }
            for (File write : toBePushed) {
                CommandResult writeRes =
                        mDevice.executeShellV2Command(
                                String.format(
                                        "dd if=/data/bootloader/%s of=/dev/block/by-name/%s%s",
                                        write.getName(),
                                        FileUtil.getBaseName(write.getName()),
                                        bootSuffix));
                if (!CommandStatus.SUCCESS.equals(writeRes.getStatus())) {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to write bootloader partition: %s",
                                    writeRes.getStderr()),
                            InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
                }
            }
        } finally {
            FileUtil.recursiveDelete(bootloaderDir);
        }
    }

    private void internalUpdateDevice(File currentBootloader, File currentRadio)
            throws DeviceNotAvailableException, TargetSetupError {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.INCREMENTAL_FLASHING_ATTEMPT_COUNT, 1);
        // Join the unzip thread
        long startWait = System.currentTimeMillis();
        try {
            mParallelSetup.join();
        } catch (InterruptedException e) {
            mParallelSetup.cleanUpFiles();
            throw new RuntimeException(e);
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_FLASHING_WAIT_PARALLEL_SETUP,
                    System.currentTimeMillis() - startWait);
        }
        if (mParallelSetup.getError() != null) {
            mParallelSetup.cleanUpFiles();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_FALLBACK_REASON,
                    mParallelSetup.getError().getMessage());
            throw mParallelSetup.getError();
        }
        boolean bootComplete =
                mDevice.waitForBootComplete(mDevice.getOptions().getAvailableTimeout());
        if (!bootComplete) {
            mParallelSetup.cleanUpFiles();
            throw new TargetSetupError(
                    "Failed to boot within timeout.",
                    InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
        }
        // We need a few seconds after boot complete for update_engine to finish
        // TODO: we could improve by listening to some update_engine messages.
        if (!mNewFlow) {
            RunUtil.getDefault().sleep(5000L);
        }
        File srcDirectory = mParallelSetup.getSrcDirectory();
        File targetDirectory = mParallelSetup.getTargetDirectory();
        File workDir = mParallelSetup.getWorkDir();
        try (CloseableTraceScope ignored = new CloseableTraceScope("update_device")) {
            // Once block comparison is successful, log the information
            logTargetInformation(targetDirectory);
            long totalPatchSizes = logPatchesInformation(workDir);
            // if we have more than 2.5GB we will overflow super partition size to /data and we
            // can't use the feature
            if (totalPatchSizes > 2300000000L) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.INCREMENTAL_FALLBACK_REASON, "Patches too large.");
                throw new TargetSetupError(
                        String.format(
                                "Total patch size is %s bytes. Too large to use the feature."
                                        + " falling back",
                                totalPatchSizes),
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }

            mDevice.executeShellV2Command("mkdir -p /data/ndb");
            mDevice.executeShellV2Command("rm -rf /data/ndb/*.patch");

            mDevice.executeShellV2Command("snapshotctl unmap-snapshots");
            mDevice.executeShellV2Command("snapshotctl delete-snapshots");

            RecoveryMode mode = mDevice.getRecoveryMode();
            mDevice.setRecoveryMode(RecoveryMode.NONE);
            try {
                List<Callable<Boolean>> pushTasks = new ArrayList<>();
                for (File f : workDir.listFiles()) {
                    try (CloseableTraceScope push =
                            new CloseableTraceScope("push:" + f.getName())) {
                        pushTasks.add(
                                () -> {
                                    boolean success;
                                    if (f.isDirectory()) {
                                        success = mDevice.pushDir(f, "/data/ndb/");
                                    } else {
                                        success = mDevice.pushFile(f, "/data/ndb/" + f.getName());
                                    }
                                    CLog.d(
                                            "Push status: %s. %s->%s",
                                            success, f, "/data/ndb/" + f.getName());
                                    assertTrue(success);
                                    return true;
                                });
                    }
                }
                ParallelDeviceExecutor<Boolean> pushExec =
                        new ParallelDeviceExecutor<Boolean>(pushTasks.size());
                pushExec.invokeAll(pushTasks, 0, TimeUnit.MINUTES);
                if (pushExec.hasErrors()) {
                    for (Throwable err : pushExec.getErrors()) {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.INCREMENTAL_FALLBACK_REASON, err.getMessage());
                        if (err instanceof DeviceNotAvailableException) {
                            throw (DeviceNotAvailableException) err;
                        }
                    }
                    throw new TargetSetupError(
                            String.format("Failed to push patches."),
                            pushExec.getErrors().get(0),
                            InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
                }
            } finally {
                mDevice.setRecoveryMode(mode);
            }

            CommandResult listSnapshots = mDevice.executeShellV2Command("ls -l /data/ndb/");
            CLog.d("stdout: %s, stderr: %s", listSnapshots.getStdout(), listSnapshots.getStderr());
            mDevice.logOnDevice("Tradefed", LogLevel.DEBUG, "Running snapshotctl apply-update");
            String applyCommand = "snapshotctl apply-update /data/ndb/";
            if (mWipeAfterApplySnapshot) {
                applyCommand += " -w";
            }
            CommandResult mapOutput = mDevice.executeShellV2Command(applyCommand);
            CLog.d("stdout: %s, stderr: %s", mapOutput.getStdout(), mapOutput.getStderr());
            if (!CommandStatus.SUCCESS.equals(mapOutput.getStatus())) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.INCREMENTAL_FALLBACK_REASON, "Failed apply-update");
                // Clean state if apply-update fails
                mDevice.executeShellV2Command("snapshotctl unmap-snapshots");
                mDevice.executeShellV2Command("snapshotctl delete-snapshots");
                throw new TargetSetupError(
                        String.format(
                                "Failed to apply-update.\nstdout:%s\nstderr:%s",
                                mapOutput.getStdout(), mapOutput.getStderr()),
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }
            try {
                if (mNewFlow && mDevice instanceof TestDevice) {
                    ((TestDevice) mDevice).setFirstBootloaderReboot();
                }
                mDevice.rebootIntoBootloader();
            } catch (DeviceNotAvailableException e) {
                if (mNewFlow) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.INCREMENTAL_FIRST_BOOTLOADER_REBOOT_FAIL, 1);
                }
                throw e;
            }

            if (mApplySnapshot) {
                if (mWipeAfterApplySnapshot) {
                    CommandResult cancelResults =
                            mDevice.executeFastbootCommand("snapshot-update", "cancel");
                    CLog.d("Cancel status: %s", cancelResults.getStatus());
                    CLog.d("Cancel stdout: %s", cancelResults.getStdout());
                    CLog.d("Cancel stderr: %s", cancelResults.getStderr());
                    CommandResult wipeResults = mDevice.executeFastbootCommand("-w");
                    CLog.d("wipe status: %s", wipeResults.getStatus());
                    CLog.d("wipe stdout: %s", wipeResults.getStdout());
                    CLog.d("wipe stderr: %s", wipeResults.getStderr());
                }
                updateBootloaderAndBasebandIfNeeded(
                        targetDirectory, currentBootloader, currentRadio);
            }
            flashStaticPartition(targetDirectory);
            mSourceDirectory = srcDirectory;

            mDevice.enableAdbRoot();

            if (mApplySnapshot) {
                mDevice.notifySnapuserd(mWaitPhase);
                mDevice.waitForSnapuserd(SnapuserdWaitPhase.BLOCK_AFTER_UPDATE);
            } else {
                // If patches are mounted, just print snapuserd once
                CommandResult psOutput = mDevice.executeShellV2Command("ps -ef | grep snapuserd");
                CLog.d("stdout: %s, stderr: %s", psOutput.getStdout(), psOutput.getStderr());
            }
            mTargetDirectory = targetDirectory;
            mUpdateWasCompleted = true;
        } catch (DeviceNotAvailableException | RuntimeException e) {
            if (mSourceDirectory == null) {
                FileUtil.recursiveDelete(srcDirectory);
            }
            throw e;
        } finally {
            FileUtil.recursiveDelete(workDir);
        }
    }

    /** Returns whether update was completed or not. */
    public boolean updateCompleted() {
        return mUpdateWasCompleted;
    }

    public File getExtractedTargetDirectory() {
        return mTargetDirectory;
    }

    /** When doing some of the apply logic, we can clean up files right after setup. */
    public void cleanAfterSetup() {
        if (!mApplySnapshot) {
            return;
        }
        // Delete the copy we made to use the incremental update
        FileUtil.recursiveDelete(mSourceDirectory);
        FileUtil.recursiveDelete(mTargetDirectory);
        // In case of same build flashing, we should clean the setup operation
        if (mParallelSetup != null) {
            try {
                mParallelSetup.join();
            } catch (InterruptedException e) {
                CLog.e(e);
            }
            mParallelSetup.cleanUpFiles();
        }
    }

    /*
     * Returns the device to its original state.
     */
    public void teardownDevice(TestInformation testInfo) throws DeviceNotAvailableException {
        // Delete the copy we made to use the incremental update
        FileUtil.recursiveDelete(mSourceDirectory);
        FileUtil.recursiveDelete(mTargetDirectory);
        // In case of same build flashing, we should clean the setup operation
        if (mParallelSetup != null) {
            try {
                mParallelSetup.join();
            } catch (InterruptedException e) {
                CLog.e(e);
            }
            mParallelSetup.cleanUpFiles();
        }
    }

    private void updateBootloaderAndBasebandIfNeeded(
            File deviceImageUnzipped, File bootloader, File baseband)
            throws DeviceNotAvailableException, TargetSetupError {
        FlashingResourcesParser parser = new FlashingResourcesParser(deviceImageUnzipped);
        if (bootloader == null) {
            CLog.w("No bootloader file to flash.");
        } else {
            if (shouldFlashBootloader(mDevice, parser.getRequiredBootloaderVersion())) {
                CommandResult bootloaderFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "bootloader", bootloader.getAbsolutePath());
                CLog.d("Status: %s", bootloaderFlashTarget.getStatus());
                CLog.d("stdout: %s", bootloaderFlashTarget.getStdout());
                CLog.d("stderr: %s", bootloaderFlashTarget.getStderr());
                mDevice.rebootIntoBootloader();
            }
        }
        if (baseband == null) {
            CLog.w("No baseband file to flash");
        } else {
            if (shouldFlashBaseband(mDevice, parser.getRequiredBasebandVersion())) {
                CommandResult radioFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "radio", baseband.getAbsolutePath());
                CLog.d("Status: %s", radioFlashTarget.getStatus());
                CLog.d("stdout: %s", radioFlashTarget.getStdout());
                CLog.d("stderr: %s", radioFlashTarget.getStderr());
                mDevice.rebootIntoBootloader();
            }
        }
    }

    private void revertBootloaderAndBasebandifNeeded(File bootloader, File baseband)
            throws DeviceNotAvailableException {
        if (mBootloaderNeedsFlashing) {
            if (bootloader == null) {
                CLog.w("No bootloader file to flash.");
            } else {
                mDevice.rebootIntoBootloader();

                CommandResult bootloaderFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "bootloader", bootloader.getAbsolutePath());
                CLog.d("Status: %s", bootloaderFlashTarget.getStatus());
                CLog.d("stdout: %s", bootloaderFlashTarget.getStdout());
                CLog.d("stderr: %s", bootloaderFlashTarget.getStderr());
            }
        }
        if (mBasebandNeedsFlashing) {
            if (baseband == null) {
                CLog.w("No baseband file to flash");
            } else {
                mDevice.rebootIntoBootloader();

                CommandResult radioFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "radio", baseband.getAbsolutePath());
                CLog.d("Status: %s", radioFlashTarget.getStatus());
                CLog.d("stdout: %s", radioFlashTarget.getStdout());
                CLog.d("stderr: %s", radioFlashTarget.getStderr());
            }
        }
    }

    private void blockCompare(File srcImage, File srcMerkleTree, File targetImage, File workDir) {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("block_compare:" + srcImage.getName())) {
            mRunUtil.setWorkingDir(workDir);

            String createSnapshot = "create_snapshot"; // Expected to be on PATH
            if (mCreateSnapshotBinary != null && mCreateSnapshotBinary.exists()) {
                createSnapshot = mCreateSnapshotBinary.getAbsolutePath();
            }
            String[] command = null;
            if (srcMerkleTree.exists()) {
                command =
                        new String[] {
                            createSnapshot,
                            "--source=" + srcMerkleTree.getAbsolutePath(),
                            "--target=" + targetImage.getAbsolutePath(),
                            "--merkel_tree"
                        };
            } else {
                command =
                        new String[] {
                            createSnapshot,
                            "--source=" + srcImage.getAbsolutePath(),
                            "--target=" + targetImage.getAbsolutePath()
                        };
            }

            CommandResult result = mRunUtil.runTimedCmd(0L, command);
            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                throw new RuntimeException(
                        String.format("%s\n%s", result.getStdout(), result.getStderr()));
            }
            File[] listFiles = workDir.listFiles();
            CLog.d("%s", Arrays.asList(listFiles));
        }
    }

    private boolean flashStaticPartition(File imageDirectory)
            throws DeviceNotAvailableException, TargetSetupError {
        Map<String, String> envMap = new HashMap<>();
        envMap.put("ANDROID_PRODUCT_OUT", imageDirectory.getAbsolutePath());
        CommandResult fastbootResult =
                mDevice.executeLongFastbootCommand(
                        envMap,
                        "flashall",
                        "--exclude-dynamic-partitions",
                        "--disable-super-optimization");
        CLog.d("Status: %s", fastbootResult.getStatus());
        CLog.d("stdout: %s", fastbootResult.getStdout());
        CLog.d("stderr: %s", fastbootResult.getStderr());
        if (!CommandStatus.SUCCESS.equals(fastbootResult.getStatus())) {
            return false;
        }
        RecoveryMode recoveryMode = mDevice.getRecoveryMode();
        try {
            mDevice.setRecoveryMode(RecoveryMode.NONE);
            ((IManagedTestDevice) mDevice).getMonitor().attachFinalState(TestDeviceState.RECOVERY);
            boolean available = mDevice.waitForDeviceAvailable(5 * 60 * 1000L);
            if (!available) {
                if (TestDeviceState.RECOVERY.equals(mDevice.getDeviceState())) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.INCREMENTAL_RECOVERY_FALLBACK, 1);
                    // Go back to bootloader for fallback flashing
                    mDevice.rebootIntoBootloader();
                    CommandResult result = mDevice.executeFastbootCommand("-w");
                    CLog.d("wipe status: %s", result.getStatus());
                    CLog.d("wipe stdout: %s", result.getStdout());
                    CLog.d("wipe stderr: %s", result.getStderr());
                    throw new TargetSetupError(
                            "Device went to recovery unexpectedly",
                            DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
                }
            }
        } finally {
            mDevice.setRecoveryMode(recoveryMode);
        }
        return true;
    }

    private long logPatchesInformation(File patchesDirectory) {
        long totalPatchesSize = 0L;
        for (File patch : patchesDirectory.listFiles()) {
            if (patch == null) {
                CLog.w("Something went wrong listing %s", patchesDirectory);
                return 0L;
            }
            totalPatchesSize += patch.length();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationGroupMetricKey.INCREMENTAL_FLASHING_PATCHES_SIZE,
                    patch.getName(),
                    patch.length());
        }
        return totalPatchesSize;
    }

    private void logTargetInformation(File targetDirectory) {
        for (File patch : targetDirectory.listFiles()) {
            if (patch == null) {
                CLog.w("Something went wrong listing target %s", targetDirectory);
                return;
            }
            if (DYNAMIC_PARTITIONS_TO_DIFF.contains(patch.getName())) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationGroupMetricKey.INCREMENTAL_FLASHING_TARGET_SIZE,
                        patch.getName(),
                        patch.length());
            }
        }
    }

    private boolean shouldFlashBootloader(ITestDevice device, String bootloaderVersion)
            throws DeviceNotAvailableException, TargetSetupError {
        String currentBootloaderVersion =
                FastbootDeviceFlasher.fetchImageVersion(mRunUtil, device, "bootloader");
        if (bootloaderVersion != null && !bootloaderVersion.equals(currentBootloaderVersion)) {
            CLog.i("Flashing bootloader %s", bootloaderVersion);
            return true;
        } else {
            CLog.i("Bootloader is already version %s, skipping flashing", currentBootloaderVersion);
            return false;
        }
    }

    private boolean shouldFlashBaseband(ITestDevice device, String basebandVersion)
            throws DeviceNotAvailableException, TargetSetupError {
        String currentBaseBandVersion =
                FastbootDeviceFlasher.fetchImageVersion(mRunUtil, device, "baseband");
        if (basebandVersion != null && !basebandVersion.equals(currentBaseBandVersion)) {
            CLog.i("Flashing bootloader %s", basebandVersion);
            return true;
        } else {
            CLog.i("Bootloader is already version %s, skipping flashing", currentBaseBandVersion);
            return false;
        }
    }

    private static String getSplVersion(IBuildInfo build) {
        File buildProp = build.getFile("build.prop");
        if (buildProp == null) {
            CLog.d("No target build.prop found for comparison.");
            return null;
        }
        try {
            String props = FileUtil.readStringFromFile(buildProp);
            for (String line : props.split("\n")) {
                if (line.startsWith("ro.build.version.security_patch=")) {
                    return line.split("=")[1];
                }
            }
        } catch (IOException e) {
            CLog.e(e);
        }
        return null;
    }

    private class ParallelPreparation extends Thread {

        private final File mDeviceOriginMerkleTree;
        private final File mSetupTargetImage;

        private File mSrcDirectory;
        private File mTargetDirectory;
        private File mWorkDir;
        private TargetSetupError mError;

        public ParallelPreparation(
                ThreadGroup currentGroup, File targetImage, File deviceMerkleTree) {
            super(currentGroup, "incremental-flashing-preparation");
            setDaemon(true);
            this.mDeviceOriginMerkleTree = deviceMerkleTree;
            this.mSetupTargetImage = targetImage;
        }

        @Override
        public void run() {
            ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
            ThreadFactory factory =
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t =
                                    new Thread(
                                            currentGroup,
                                            r,
                                            "unzip-pool-task-" + poolNumber.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        }
                    };
            try (CloseableTraceScope ignored = new CloseableTraceScope("unzip_device_images")) {
                mSrcDirectory = mDeviceOriginMerkleTree;
                mTargetDirectory = FileUtil.createTempDir("incremental_target");
                Future<Boolean> futureTargetDir =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    if (mSetupTargetImage.isDirectory()) {
                                        try (CloseableTraceScope unzipTarget =
                                                new CloseableTraceScope("hardlink_target")) {
                                            FileUtil.recursiveHardlink(
                                                    mSetupTargetImage, mTargetDirectory);
                                            return true;
                                        } catch (IOException ioe) {
                                            throw new RuntimeException(ioe);
                                        }
                                    }
                                    try (CloseableTraceScope unzipTarget =
                                            new CloseableTraceScope("unzip_target")) {
                                        ZipUtil2.extractZip(mSetupTargetImage, mTargetDirectory);
                                        return true;
                                    } catch (IOException ioe) {
                                        throw new RuntimeException(ioe);
                                    }
                                },
                                TracePropagatingExecutorService.create(
                                        Executors.newFixedThreadPool(1, factory)));
                // Join the unzipping
                futureTargetDir.get();
            } catch (InterruptedException | IOException | ExecutionException e) {
                FileUtil.recursiveDelete(mSrcDirectory);
                FileUtil.recursiveDelete(mTargetDirectory);
                mSrcDirectory = null;
                mTargetDirectory = null;
                mError =
                        new TargetSetupError(
                                e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
                return;
            }

            try {
                mWorkDir = FileUtil.createTempDir("block_compare_workdir");
            } catch (IOException e) {
                FileUtil.recursiveDelete(mWorkDir);
                FileUtil.recursiveDelete(mSrcDirectory);
                FileUtil.recursiveDelete(mTargetDirectory);
                mSrcDirectory = null;
                mTargetDirectory = null;
                mError =
                        new TargetSetupError(
                                e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
                return;
            }

            List<Callable<Boolean>> callableTasks = new ArrayList<>();
            for (String partition : mSrcDirectory.list()) {
                String merklePartition = partition.replaceAll(".img", ".pb");
                partition = partition.replaceAll(".pb", ".img");

                File possibleSrc = new File(mSrcDirectory, partition);
                File sourceMerkleTree = new File(mDeviceOriginMerkleTree, merklePartition);
                File possibleTarget = new File(mTargetDirectory, partition);
                File workDirectory = mWorkDir;
                if ((possibleSrc.exists() || sourceMerkleTree.exists())
                        && possibleTarget.exists()) {
                    if (DYNAMIC_PARTITIONS_TO_DIFF.contains(partition)) {
                        callableTasks.add(
                                () -> {
                                    blockCompare(
                                            possibleSrc,
                                            sourceMerkleTree,
                                            possibleTarget,
                                            workDirectory);
                                    return true;
                                });
                    }
                } else {
                    CLog.e("Skipping %s no src or target", partition);
                }
            }
            ParallelDeviceExecutor<Boolean> executor =
                    new ParallelDeviceExecutor<Boolean>(callableTasks.size());
            executor.invokeAll(callableTasks, 0, TimeUnit.MINUTES);
            if (executor.hasErrors()) {
                mError =
                        new TargetSetupError(
                                executor.getErrors().get(0).getMessage(),
                                executor.getErrors().get(0),
                                InfraErrorIdentifier.BLOCK_COMPARE_ERROR);
            }
        }

        public File getSrcDirectory() {
            return mSrcDirectory;
        }

        public File getTargetDirectory() {
            return mTargetDirectory;
        }

        public File getWorkDir() {
            return mWorkDir;
        }

        public TargetSetupError getError() {
            return mError;
        }

        public void cleanUpFiles() {
            FileUtil.recursiveDelete(mSrcDirectory);
            FileUtil.recursiveDelete(mTargetDirectory);
            FileUtil.recursiveDelete(mWorkDir);
        }
    }
}
