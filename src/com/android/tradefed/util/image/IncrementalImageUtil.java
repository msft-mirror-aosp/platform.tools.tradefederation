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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationGroupMetricKey;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;
import com.android.tradefed.util.image.DeviceImageTracker.FileCacheTracker;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** A utility to leverage the incremental image and device update. */
public class IncrementalImageUtil {

    public static final Set<String> DYNAMIC_PARTITIONS_TO_DIFF =
            ImmutableSet.of(
                    "product.img",
                    "system.img",
                    "system_dlkm.img",
                    "system_ext.img",
                    "vendor.img",
                    "vendor_dlkm.img");

    private final File mSrcImage;
    private final File mSrcBootloader;
    private final File mSrcBaseband;
    private final File mTargetImage;
    private final ITestDevice mDevice;
    private final File mCreateSnapshotBinary;

    private boolean mBootloaderNeedsRevert = false;
    private boolean mBasebandNeedsRevert = false;
    private File mSourceDirectory;

    private ParallelPreparation mParallelSetup;

    public static IncrementalImageUtil initialize(
            ITestDevice device,
            IDeviceBuildInfo build,
            File createSnapshot,
            boolean isIsolatedSetup)
            throws DeviceNotAvailableException {
        if (isIsolatedSetup) {
            CLog.d("test is configured with isolation grade, doesn't support incremental yet.");
            return null;
        }
        FileCacheTracker tracker =
                DeviceImageTracker.getDefaultCache()
                        .getBaselineDeviceImage(device.getSerialNumber());
        if (tracker == null) {
            CLog.d("Not tracking current baseline image.");
            return null;
        }
        if (!tracker.buildId.equals(device.getBuildId())) {
            CLog.d("On-device build isn't matching the cache.");
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_IMAGE_CACHE_MISMATCH, 1);
            return null;
        }
        if (!tracker.branch.equals(build.getBuildBranch())) {
            CLog.d("Newer build is not on the same branch.");
            return null;
        }
        if (!tracker.flavor.equals(build.getBuildFlavor())) {
            CLog.d("Newer build is not on the build flavor.");
            return null;
        }

        if (!isSnapshotSupported(device)) {
            CLog.d("Incremental flashing not supported.");
            return null;
        }

        String splTarget = getSplVersion(build);
        String splBaseline = device.getProperty("ro.build.version.security_patch");
        if (splTarget != null && !splBaseline.equals(splTarget)) {
            CLog.d("Target SPL is '%s', while baseline is '%s", splTarget, splBaseline);
            return null;
        }

        File deviceImage = null;
        File bootloader = null;
        File baseband = null;
        try {
            deviceImage = copyImage(tracker.zippedDeviceImage);
            bootloader = copyImage(tracker.zippedBootloaderImage);
            baseband = copyImage(tracker.zippedBasebandImage);
        } catch (IOException e) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_IMAGE_CACHE_MISMATCH, 1);
            CLog.e(e);
            FileUtil.deleteFile(deviceImage);
            FileUtil.deleteFile(bootloader);
            FileUtil.deleteFile(baseband);
            return null;
        }
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.DEVICE_IMAGE_CACHE_ORIGIN,
                String.format("%s:%s:%s", tracker.branch, tracker.buildId, tracker.flavor));
        return new IncrementalImageUtil(
                device,
                deviceImage,
                bootloader,
                baseband,
                build.getDeviceImageFile(),
                createSnapshot);
    }

    public IncrementalImageUtil(
            ITestDevice device,
            File deviceImage,
            File bootloader,
            File baseband,
            File targetImage,
            File createSnapshot) {
        mDevice = device;
        mSrcImage = deviceImage;
        mSrcBootloader = bootloader;
        mSrcBaseband = baseband;

        mTargetImage = targetImage;
        if (createSnapshot != null) {
            File snapshot = null;
            try {
                File destDir = ZipUtil2.extractZipToTemp(createSnapshot, "create_snapshot");
                snapshot = FileUtil.findFile(destDir, "create_snapshot");
                FileUtil.chmodGroupRWX(snapshot);
            } catch (IOException e) {
                CLog.e(e);
            }
            mCreateSnapshotBinary = snapshot;
        } else {
            mCreateSnapshotBinary = null;
        }
        mParallelSetup =
                new ParallelPreparation(
                        Thread.currentThread().getThreadGroup(), mSrcImage, mTargetImage);
        mParallelSetup.start();
    }

    private static File copyImage(File originalImage) throws IOException {
        File copy = FileUtil.createTempFile(FileUtil.getBaseName(originalImage.getName()), ".img");
        copy.delete();
        FileUtil.hardlinkFile(originalImage, copy);
        return copy;
    }

    /** Returns whether or not we can use the snapshot logic to update the device */
    public static boolean isSnapshotSupported(ITestDevice device)
            throws DeviceNotAvailableException {
        // Ensure snapshotctl exists
        CommandResult whichOutput = device.executeShellV2Command("which snapshotctl");
        CLog.d("stdout: %s, stderr: %s", whichOutput.getStdout(), whichOutput.getStderr());
        if (whichOutput.getStdout().contains("/system/bin/snapshotctl")) {
            return true;
        }
        CommandResult helpOutput = device.executeShellV2Command("snapshotctl");
        CLog.d("stdout: %s, stderr: %s", helpOutput.getStdout(), helpOutput.getStderr());
        if (helpOutput.getStdout().contains("map-snapshots")
                || helpOutput.getStderr().contains("map-snapshots")) {
            return true;
        }
        return false;
    }

    public void notifyBootloaderNeedsRevert() {
        mBootloaderNeedsRevert = true;
    }

    public void notifyBasebadNeedsRevert() {
        mBasebandNeedsRevert = true;
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

    /** Updates the device using the snapshot logic. */
    public void updateDevice() throws DeviceNotAvailableException, TargetSetupError {
        try {
            internalUpdateDevice();
        } catch (DeviceNotAvailableException | TargetSetupError | RuntimeException e) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_FLASHING_UPDATE_FAILURE, 1);
            throw e;
        }
    }

    private void internalUpdateDevice() throws DeviceNotAvailableException, TargetSetupError {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.INCREMENTAL_FLASHING_ATTEMPT_COUNT, 1);
        if (mDevice.isStateBootloaderOrFastbootd()) {
            mDevice.rebootUntilOnline();
        }
        if (!mDevice.enableAdbRoot()) {
            throw new TargetSetupError(
                    "Failed to obtain root, this is required for incremental update.",
                    InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
        }

        // Join the unzip thread
        try {
            mParallelSetup.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (mParallelSetup.getError() != null) {
            throw mParallelSetup.getError();
        }
        File srcDirectory = mParallelSetup.getSrcDirectory();
        File targetDirectory = mParallelSetup.getTargetDirectory();
        File workDir = mParallelSetup.getWorkDir();
        try (CloseableTraceScope ignored = new CloseableTraceScope("update_device")) {
            // Once block comparison is successful, log the information
            logTargetInformation(targetDirectory);
            logPatchesInformation(workDir);

            mDevice.executeShellV2Command("mkdir -p /data/ndb");
            mDevice.executeShellV2Command("rm -rf /data/ndb/*.patch");

            mDevice.executeShellV2Command("snapshotctl unmap-snapshots");
            mDevice.executeShellV2Command("snapshotctl delete-snapshots");

            List<Callable<Boolean>> pushTasks = new ArrayList<>();
            for (File f : workDir.listFiles()) {
                try (CloseableTraceScope push = new CloseableTraceScope("push:" + f.getName())) {
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
                throw new RuntimeException(pushExec.getErrors().get(0));
            }

            CommandResult mapOutput =
                    mDevice.executeShellV2Command("snapshotctl map-snapshots /data/ndb/");
            CLog.d("stdout: %s, stderr: %s", mapOutput.getStdout(), mapOutput.getStderr());
            if (!CommandStatus.SUCCESS.equals(mapOutput.getStatus())) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to map the snapshots.\nstdout:%s\nstderr:%s",
                                mapOutput.getStdout(), mapOutput.getStderr()),
                        InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
            }
            flashStaticPartition(targetDirectory);
            mSourceDirectory = srcDirectory;

            mDevice.enableAdbRoot();
            CommandResult psOutput = mDevice.executeShellV2Command("ps -ef | grep snapuserd");
            CLog.d("stdout: %s, stderr: %s", psOutput.getStdout(), psOutput.getStderr());
        } catch (DeviceNotAvailableException | RuntimeException e) {
            if (mSourceDirectory == null) {
                FileUtil.recursiveDelete(srcDirectory);
            }
            throw e;
        } finally {
            FileUtil.recursiveDelete(workDir);
            FileUtil.recursiveDelete(targetDirectory);
        }
    }

    /*
     * Returns the device to its original state.
     */
    public void teardownDevice() throws DeviceNotAvailableException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("teardownDevice")) {
            if (mBootloaderNeedsRevert) {
                mDevice.rebootIntoBootloader();

                CommandResult bootloaderFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "bootloader", mSrcBootloader.getAbsolutePath());
                CLog.d("Status: %s", bootloaderFlashTarget.getStatus());
                CLog.d("stdout: %s", bootloaderFlashTarget.getStdout());
                CLog.d("stderr: %s", bootloaderFlashTarget.getStderr());
            }
            if (mBasebandNeedsRevert) {
                mDevice.rebootIntoBootloader();

                CommandResult radioFlashTarget =
                        mDevice.executeFastbootCommand(
                                "flash", "radio", mSrcBaseband.getAbsolutePath());
                CLog.d("Status: %s", radioFlashTarget.getStatus());
                CLog.d("stdout: %s", radioFlashTarget.getStdout());
                CLog.d("stderr: %s", radioFlashTarget.getStderr());
            }
            if (mDevice.isStateBootloaderOrFastbootd()) {
                mDevice.reboot();
            }
            mDevice.enableAdbRoot();
            CommandResult revertOutput =
                    mDevice.executeShellV2Command("snapshotctl revert-snapshots");
            if (!CommandStatus.SUCCESS.equals(revertOutput.getStatus())) {
                CLog.d(
                        "Failed revert-snapshots. stdout: %s, stderr: %s",
                        revertOutput.getStdout(), revertOutput.getStderr());
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.INCREMENTAL_FLASHING_TEARDOWN_FAILURE, 1);
            }
            if (mSourceDirectory != null) {
                flashStaticPartition(mSourceDirectory);
            }
        } catch (DeviceNotAvailableException e) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INCREMENTAL_FLASHING_TEARDOWN_FAILURE, 1);
            throw e;
        } finally {
            // Delete the copy we made to use the incremental update
            FileUtil.recursiveDelete(mSourceDirectory);
            FileUtil.deleteFile(mSrcImage);
            FileUtil.deleteFile(mSrcBootloader);
            FileUtil.deleteFile(mSrcBaseband);
        }
    }

    private void blockCompare(File srcImage, File targetImage, File workDir) {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("block_compare:" + srcImage.getName())) {
            IRunUtil runUtil = new RunUtil();
            runUtil.setWorkingDir(workDir);

            String createSnapshot = "create_snapshot"; // Expected to be on PATH
            if (mCreateSnapshotBinary != null && mCreateSnapshotBinary.exists()) {
                createSnapshot = mCreateSnapshotBinary.getAbsolutePath();
            }
            CommandResult result =
                    runUtil.runTimedCmd(
                            0L,
                            createSnapshot,
                            "--source=" + srcImage.getAbsolutePath(),
                            "--target=" + targetImage.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                throw new RuntimeException(
                        String.format("%s\n%s", result.getStdout(), result.getStderr()));
            }
            File[] listFiles = workDir.listFiles();
            CLog.d("%s", Arrays.asList(listFiles));
        }
    }

    private boolean flashStaticPartition(File imageDirectory) throws DeviceNotAvailableException {
        // flash all static partition in bootloader
        mDevice.rebootIntoBootloader();
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
        mDevice.waitForDeviceAvailable(5 * 60 * 1000L);
        return true;
    }

    private void logPatchesInformation(File patchesDirectory) {
        for (File patch : patchesDirectory.listFiles()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationGroupMetricKey.INCREMENTAL_FLASHING_PATCHES_SIZE,
                    patch.getName(),
                    patch.length());
        }
    }

    private void logTargetInformation(File targetDirectory) {
        for (File patch : targetDirectory.listFiles()) {
            if (DYNAMIC_PARTITIONS_TO_DIFF.contains(patch.getName())) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationGroupMetricKey.INCREMENTAL_FLASHING_TARGET_SIZE,
                        patch.getName(),
                        patch.length());
            }
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

        private final File mSetupSrcImage;
        private final File mSetupTargetImage;

        private File mSrcDirectory;
        private File mTargetDirectory;
        private File mWorkDir;
        private TargetSetupError mError;

        public ParallelPreparation(ThreadGroup currentGroup, File srcImage, File targetImage) {
            super(currentGroup, "incremental-flashing-preparation");
            setDaemon(true);
            this.mSetupSrcImage = srcImage;
            this.mSetupTargetImage = targetImage;
        }

        @Override
        public void run() {
            try (CloseableTraceScope ignored = new CloseableTraceScope("unzip_device_images")) {
                Future<File> futureSrcDir =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return ZipUtil2.extractZipToTemp(
                                                mSetupSrcImage, "incremental_src");
                                    } catch (IOException ioe) {
                                        throw new RuntimeException(ioe);
                                    }
                                });
                Future<File> futureTargetDir =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return ZipUtil2.extractZipToTemp(
                                                mSetupTargetImage, "incremental_target");
                                    } catch (IOException ioe) {
                                        throw new RuntimeException(ioe);
                                    }
                                });

                mSrcDirectory = futureSrcDir.get();
                mTargetDirectory = futureTargetDir.get();
            } catch (InterruptedException | ExecutionException e) {
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
                File possibleSrc = new File(mSrcDirectory, partition);
                File possibleTarget = new File(mTargetDirectory, partition);
                File workDirectory = mWorkDir;
                if (possibleSrc.exists() && possibleTarget.exists()) {
                    if (DYNAMIC_PARTITIONS_TO_DIFF.contains(partition)) {
                        callableTasks.add(
                                () -> {
                                    blockCompare(possibleSrc, possibleTarget, workDirectory);
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
    }
}
