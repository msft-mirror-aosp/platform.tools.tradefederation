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
package com.android.tradefed.device.cloud;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.GCSFileDownloader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link OxygenUtil}. */
@RunWith(JUnit4.class)
public class OxygenUtilTest {

    /** Test downloadLaunchFailureLogs. */
    @Test
    public void testDownloadLaunchFailureLogs() throws Exception {
        ITestLogger logger = Mockito.mock(ITestLogger.class);
        GCSFileDownloader downloader = Mockito.mock(GCSFileDownloader.class);
        final String error =
                "Device launcher failed, check out logs for more details: \n"
                    + "some error:"
                    + " https://domain.name/storage/browser/bucket_name/instance_name?&project=project_name\n"
                    + "\tat leaseDevice\n"
                    + "\tat ";
        final String expectedUrl = "gs://bucket_name/instance_name";
        TargetSetupError setupError =
                new TargetSetupError(
                        "some error",
                        new Exception(error),
                        DeviceErrorIdentifier.FAILED_TO_LAUNCH_GCE);

        File tmpDir = null;
        try {
            tmpDir = FileUtil.createTempDir("oxygen");
            File file1 = FileUtil.createTempFile("kernel", ".log", tmpDir);
            File tmpDir2 = FileUtil.createTempDir("dir", tmpDir);
            File file2 = FileUtil.createTempFile("file2", ".txt", tmpDir2);
            when(downloader.downloadFile(expectedUrl)).thenReturn(tmpDir);

            OxygenUtil util = new OxygenUtil(downloader);
            util.downloadLaunchFailureLogs(setupError, logger);

            verify(logger, times(1)).testLog(Mockito.any(), eq(LogDataType.KERNEL_LOG), Mockito.any());
            verify(logger, times(1))
                .testLog(Mockito.any(), eq(LogDataType.CUTTLEFISH_LOG), Mockito.any());
        } finally {
            FileUtil.recursiveDelete(tmpDir);
        }
    }

    /** Test getDefaultLogType. */
    @Test
    public void testGetDefaultLogType() {
        assertEquals(OxygenUtil.getDefaultLogType("logcat_1234567.txt"), LogDataType.LOGCAT);
        assertEquals(OxygenUtil.getDefaultLogType("kernel.log_12345.txt"), LogDataType.KERNEL_LOG);
        assertEquals(
                OxygenUtil.getDefaultLogType("invocation_ended_bugreport_123456.zip"),
                LogDataType.BUGREPORTZ);
        assertEquals(
                OxygenUtil.getDefaultLogType("invocation_started_bugreport_123456.txt"),
                LogDataType.BUGREPORT);
    }
}
