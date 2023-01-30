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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Helper that helps syncing a new device image to the device. Future improvements: compute files
 * that need to be deleted and files that needs to be pushed instead of full folders.
 */
public class DeviceSyncHelper {

    private final ITestDevice mDevice;
    private final File mTargetFilesFolder;

    public DeviceSyncHelper(ITestDevice device, File targetFilesFolder) {
        mDevice = device;
        mTargetFilesFolder = targetFilesFolder;
    }

    public boolean sync() throws DeviceNotAvailableException {
        try {
            List<String> partitions = getPartitions(mTargetFilesFolder);
            lowerCaseDirectory(mTargetFilesFolder);
            syncFiles(mDevice, partitions);
            return true;
        } catch (IOException e) {
            CLog.e(e);
        }
        return false;
    }

    private List<String> getPartitions(File rootFolder) throws IOException {
        File abPartitions = new File(rootFolder, "META/ab_partitions.txt");
        String partitionString = FileUtil.readStringFromFile(abPartitions);
        return Arrays.asList(partitionString.split("\n"));
    }

    private void lowerCaseDirectory(File rootFolder) {
        for (File f : rootFolder.listFiles()) {
            if (f.isDirectory()) {
                File newName = new File(f.getParentFile(), f.getName().toLowerCase());
                f.renameTo(newName);
            }
        }
        CLog.d("Directory content: %s", Arrays.asList(rootFolder.listFiles()));
    }

    private void syncFiles(ITestDevice device, List<String> partitions)
            throws DeviceNotAvailableException, IOException {
        device.enableAdbRoot();
        device.remountSystemWritable();
        device.executeAdbCommand("shell", "stop");

        for (String partition : partitions) {
            File localToPush = new File(mTargetFilesFolder, partition);
            if (!localToPush.exists()) {
                CLog.w("%s is in the partition but doesn't exist", partition);
                continue;
            }
            String output =
                    device.executeAdbCommand(0L, "push", localToPush.getAbsolutePath(), "/");
            if (output == null) {
                throw new IOException("Failed to push " + localToPush);
            }
        }

        device.reboot();
    }
}
