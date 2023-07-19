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
import static org.junit.Assert.fail;

import com.android.tradefed.device.DeviceNotAvailableException;
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
import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Basic test to start iterating on device incremental image. */
@RunWith(DeviceJUnit4ClassRunner.class)
public class IncrementalImageFuncTest extends BaseHostJUnit4Test {

    public static final Set<String> PARTITIONS_TO_DIFF =
            ImmutableSet.of(
                    "product.img",
                    "system.img",
                    "system_dlkm.img",
                    "system_ext.img",
                    "vendor.img",
                    "vendor_dlkm.img");

    public static class TrackResults {
        public String imageMd5;
        public String mountedBlock;

        @Override
        public String toString() {
            return "TrackResults [imageMd5=" + imageMd5 + ", mountedBlock=" + mountedBlock + "]";
        }
    }

    private Map<String, TrackResults> partitionToInfo = new ConcurrentHashMap<>();

    @Test
    public void testBlockCompareUpdate() throws Exception {
        String originalBuildId = getDevice().getBuildId();
        CLog.d("Original build id: %s", originalBuildId);

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
                    TrackResults newRes = new TrackResults();
                    newRes.imageMd5 = FileUtil.calculateMd5(possibleTarget);
                    partitionToInfo.put(FileUtil.getBaseName(partition), newRes);
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
                    CLog.e("Push successful.: %s. %s->%s", success, f, "/data/ndb/" + f.getName());
                    assertTrue(success);
                }
            }

            CommandResult mapOutput =
                    getDevice().executeShellV2Command("snapshotctl map-snapshots /data/ndb/");
            CLog.e("stdout: %s, stderr: %s", mapOutput.getStdout(), mapOutput.getStderr());
            if (!CommandStatus.SUCCESS.equals(mapOutput.getStatus())) {
                fail("Failed to map the snapshots.");
            }

            getDevice().reboot();
            // Do Validation
            getDevice().enableAdbRoot();
            CommandResult psOutput = getDevice().executeShellV2Command("ps -ef | grep snapuserd");
            CLog.d("stdout: %s, stderr: %s", psOutput.getStdout(), psOutput.getStderr());

            listMappingAndCompare(partitionToInfo);

            String afterMountBuildId = getDevice().getBuildId();
            CLog.d(
                    "Original build id: %s. after mount build id: %s",
                    originalBuildId, afterMountBuildId);
        } finally {
            FileUtil.recursiveDelete(workDir);
            FileUtil.recursiveDelete(srcDirectory);
            FileUtil.recursiveDelete(targetDirectory);
            revertToPreviousBuild();
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

    private void inspectCowPatches(File workDir) throws IOException {
        File inspectZip = getBuild().getFile("inspect_cow.zip");
        if (inspectZip == null) {
            return;
        }
        File destDir = ZipUtil2.extractZipToTemp(inspectZip, "inspect_cow_unzip");
        File inspect = FileUtil.findFile(destDir, "inspect_cow");
        FileUtil.chmodGroupRWX(inspect);
        IRunUtil runUtil = new RunUtil();
        long sizeOfPatches = 0L;
        try (CloseableTraceScope ignored = new CloseableTraceScope("inspect_cow")) {
            for (File f : workDir.listFiles()) {
                CommandResult result =
                        runUtil.runTimedCmd(0L, inspect.getAbsolutePath(), f.getAbsolutePath());
                CLog.d("Status: %s", result.getStatus());
                CLog.d("Stdout: %s", result.getStdout());
                CLog.d("Stderr: %s", result.getStderr());
                CLog.d("Patch size: %s", f.length());
                sizeOfPatches += f.length();
            }
            CLog.d("Total size of patches: %s", sizeOfPatches);
        } finally {
            FileUtil.recursiveDelete(destDir);
        }
    }

    private void listMappingAndCompare(Map<String, TrackResults> partitionToInfo)
            throws DeviceNotAvailableException {
        CommandResult lsOutput = getDevice().executeShellV2Command("ls -l /dev/block/mapper/");
        CLog.d("stdout: %s, stderr: %s", lsOutput.getStdout(), lsOutput.getStderr());

        for (String lines : lsOutput.getStdout().split("\n")) {
            if (!lines.contains("->")) {
                continue;
            }
            String[] pieces = lines.split(" ");
            String partition = pieces[8].substring(0, pieces[8].length() - 2);
            CLog.d("Partition extracted: %s", partition);
            if (partitionToInfo.containsKey(partition)) {
                partitionToInfo.get(partition).mountedBlock = pieces[10];
            }
        }
        CLog.d("Infos: %s", partitionToInfo);

        for (Entry<String, TrackResults> res : partitionToInfo.entrySet()) {
            if (res.getValue().mountedBlock == null) {
                CLog.e("No partition found in mapping for %s", res);
                continue;
            }
            TrackResults result = res.getValue();
            CommandResult md5Output =
                    getDevice().executeShellV2Command("md5sum " + result.mountedBlock);
            CLog.d("stdout: %s, stderr: %s", md5Output.getStdout(), md5Output.getStderr());
            if (!CommandStatus.SUCCESS.equals(md5Output.getStatus())) {
                fail("Fail to get md5sum from " + result.mountedBlock);
            }
            String md5device = md5Output.getStdout().trim().split("\\s+")[0];
            Truth.assertThat(result.imageMd5).isEqualTo(md5device);
        }
    }

    private void revertToPreviousBuild() throws DeviceNotAvailableException {
        getDevice().executeShellV2Command("rm -f /metadata/ota/snapshot-boot");
        getDevice().reboot();
    }
}
