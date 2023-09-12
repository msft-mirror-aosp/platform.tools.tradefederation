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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
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
    private final File mTargetImage;
    private final ITestDevice mDevice;
    private final File mCreateSnapshotBinary;

    private File mSourceDirectory;

    public IncrementalImageUtil(
            ITestDevice device, File srcImage, File targetImage, File createSnapshot) {
        mDevice = device;
        mSrcImage = srcImage;
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
        return false;
    }

    /** Updates the device using the snapshot logic. */
    public void updateDevice() throws DeviceNotAvailableException, TargetSetupError {
        if (!mDevice.enableAdbRoot()) {
            throw new TargetSetupError(
                    "Failed to obtain root, this is required for incremental update.",
                    InfraErrorIdentifier.INCREMENTAL_FLASHING_ERROR);
        }
        File srcDirectory = null;
        File targetDirectory = null;
        File workDir = null;

        try (CloseableTraceScope ignored = new CloseableTraceScope("unzip_device_images")) {
            srcDirectory = ZipUtil2.extractZipToTemp(mSrcImage, "incremental_src");
            targetDirectory = ZipUtil2.extractZipToTemp(mTargetImage, "incremental_target");
            workDir = FileUtil.createTempDir("block_compare_workdir");
        } catch (IOException e) {
            FileUtil.recursiveDelete(srcDirectory);
            FileUtil.recursiveDelete(targetDirectory);
            FileUtil.recursiveDelete(workDir);
            throw new TargetSetupError(e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }

        try (CloseableTraceScope ignored = new CloseableTraceScope("update_device")) {
            List<Callable<Boolean>> callableTasks = new ArrayList<>();
            for (String partition : srcDirectory.list()) {
                File possibleSrc = new File(srcDirectory, partition);
                File possibleTarget = new File(targetDirectory, partition);
                File workDirectory = workDir;
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
                throw new RuntimeException(executor.getErrors().get(0));
            }

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
            CommandResult revertOutput =
                    mDevice.executeShellV2Command("snapshotctl revert-snapshots");
            CLog.d("stdout: %s, stderr: %s", revertOutput.getStdout(), revertOutput.getStderr());
            if (mSourceDirectory != null) {
                flashStaticPartition(mSourceDirectory);
            }
            FileUtil.recursiveDelete(mSourceDirectory);
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
            CLog.e("%s", Arrays.asList(listFiles));
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
}
