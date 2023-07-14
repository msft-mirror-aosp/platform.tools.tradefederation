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
package com.android.tradefed.targetprep.sync;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil2;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Set;

/** Basic test to start iterating on device incremental image. */
@RunWith(DeviceJUnit4ClassRunner.class)
public class IncrementalImageFuncTest extends BaseHostJUnit4Test {

    public static final Set<String> PARTITIONS_TO_DIFF =
            ImmutableSet.of(
                    "product.img",
                    "system.img",
                    "system_dlkm.img",
                    "system_other.img",
                    "system_ext.img",
                    "vendor.img",
                    "vendor_dlkm.img");

    @Test
    public void testBlockCompareUpdate() throws Exception {
        File blockCompare = getBuild().getFile("block-compare");
        FileUtil.chmodGroupRWX(blockCompare);
        File srcImage = getBuild().getFile("src-image");
        File srcDirectory = ZipUtil2.extractZipToTemp(srcImage, "incremental_src");
        File targetImage = getBuild().getFile("target-image");
        File targetDirectory = ZipUtil2.extractZipToTemp(targetImage, "incremental_target");

        File workDir = FileUtil.createTempDir("block_compare_workdir");
        try {
            for (String partition : PARTITIONS_TO_DIFF) {
                File possibleSrc = new File(srcDirectory, partition);
                File possibleTarget = new File(targetDirectory, partition);
                if (possibleSrc.exists() && possibleTarget.exists()) {
                    blockCompare(blockCompare, possibleSrc, possibleTarget, workDir);
                } else {
                    CLog.e("Skipping %s no src or target", partition);
                }
            }
            inspectCowPatches(workDir);

            getDevice().executeShellV2Command("mkdir -p /data/ndb");
            getDevice().executeShellV2Command("rm -rf /data/ndb/*.patch");

            // Ensure snapshotctl exists
            CommandResult whichOutput = getDevice().executeShellV2Command("which snapshotctl");
            CLog.e("stdout: %s, stderr: %s", whichOutput.getStdout(), whichOutput.getStderr());

            getDevice().executeShellV2Command("snapshotctl unmap-snapshots");
            getDevice().executeShellV2Command("snapshotctl delete-snapshots");

            for (File f : workDir.listFiles()) {
                try (CloseableTraceScope ignored = new CloseableTraceScope("push:" + f.getName())) {
                    boolean success;
                    if (f.isDirectory()) {
                        success = getDevice().pushDir(f, "/data/ndb/");
                    } else {
                        success = getDevice().pushFile(f, "/data/ndb/" + f.getName());
                    }
                    CLog.e("Push successful: %s", success);
                    assertTrue(success);
                }
            }

            CommandResult mapOutput =
                    getDevice().executeShellV2Command("snapshotctl map-snapshots /data/ndb/");
            CLog.e("stdout: %s, stderr: %s", mapOutput.getStdout(), mapOutput.getStderr());
            if (CommandStatus.SUCCESS.equals(mapOutput.getStatus())) {
                getDevice().reboot();
            }

            // Do Validation
        } finally {
            FileUtil.recursiveDelete(workDir);
            FileUtil.recursiveDelete(srcDirectory);
            FileUtil.recursiveDelete(targetDirectory);
        }
    }

    private void blockCompare(File blockCompare, File srcImage, File targetImage, File workDir) {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("block_compare:" + srcImage.getName())) {
            IRunUtil runUtil = new RunUtil();
            runUtil.setWorkingDir(workDir);

            CommandResult result =
                    runUtil.runTimedCmd(
                            0L,
                            blockCompare.getAbsolutePath(),
                            srcImage.getAbsolutePath(),
                            targetImage.getAbsolutePath());
            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                throw new RuntimeException(
                        String.format("%s\n%s", result.getStdout(), result.getStderr()));
            }
            File[] listFiles = workDir.listFiles();
            CLog.e("%s", Arrays.asList(listFiles));
        }
    }

    private void inspectCowPatches(File workDir) {
        File inspect = getBuild().getFile("inspect_cow");
        if (inspect == null) {
            return;
        }
        FileUtil.chmodGroupRWX(inspect);
        IRunUtil runUtil = new RunUtil();
        try (CloseableTraceScope ignored = new CloseableTraceScope("inspect_cow")) {
            for (File f : workDir.listFiles()) {
                CommandResult result =
                        runUtil.runTimedCmd(0L, inspect.getAbsolutePath(), f.getAbsolutePath());
                CLog.e("Status: %s", result.getStatus());
                CLog.e("Stdout: %s", result.getStdout());
                CLog.e("Stderr: %s", result.getStderr());
            }
        }
    }
}
