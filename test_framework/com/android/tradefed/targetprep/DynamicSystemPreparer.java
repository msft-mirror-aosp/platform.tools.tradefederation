/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.tradefed.util.SparseImageUtil.SparseInputStream;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.CollectingOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * An {@link ITargetPreparer} that sets up a system image on top of a device build with the Dynamic
 * System Update.
 */
@OptionClass(alias = "dynamic-system-update")
public class DynamicSystemPreparer extends BaseTargetPreparer {
    static final int DSU_MAX_WAIT_SEC = 10 * 60;

    private static final int ANDROID_API_R = 30;

    private static final String SYSTEM_IMAGE_NAME = "system.img";
    private static final String SYSTEM_EXT_IMAGE_NAME = "system_ext.img";
    private static final String PRODUCT_IMAGE_NAME = "product.img";

    private static final String DEST_PATH = "/sdcard/system.raw.gz";
    private static final String DEST_ZIP_PATH = "/sdcard/system.zip";

    private static final String DSU_COMMAND_TEMPLATE_Q =
            "am start-activity"
                    + " -n com.android.dynsystem/com.android.dynsystem.VerificationActivity"
                    + " -a android.os.image.action.START_INSTALL"
                    + " -d file://%s"
                    + " --el KEY_USERDATA_SIZE %d"
                    + " --el KEY_SYSTEM_SIZE %d"
                    + " --ez KEY_ENABLE_WHEN_COMPLETED true";
    private static final String DSU_COMMAND_TEMPLATE_R =
            "am start-activity"
                    + " -n com.android.dynsystem/com.android.dynsystem.VerificationActivity"
                    + " -a android.os.image.action.START_INSTALL"
                    + " -d file://%s"
                    + " --el KEY_USERDATA_SIZE %d"
                    + " --ez KEY_ENABLE_WHEN_COMPLETED true";

    @Option(
            name = "system-image-zip-name",
            description = "The name of the zip file containing system.img.")
    private String mSystemImageZipName = "system-img.zip";

    @Option(
            name = "user-data-size-in-gb",
            description = "Number of GB to be allocated for DSU user-data.")
    private long mUserDataSizeInGb = 16L; // 16GB

    @Option(
            name = "wait-for-device-online",
            description = "whether to wait for device online after install DSU.")
    private boolean mWaitForDeviceOnline = true;

    private boolean isDSURunning(ITestDevice device) throws DeviceNotAvailableException {
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        device.executeShellCommand("gsi_tool status", receiver);
        return receiver.getOutput().contains("running");
    }

    /**
     * Utility method to get a SparseInputStream to an image file.
     *
     * @return null if imageName is not found.
     */
    private SparseInputStream getUnsparsedImageStream(File imageZipFile, String imageName)
            throws IOException {
        InputStream is = null;
        try {
            long imageSize = 0;
            if (imageZipFile.isDirectory()) {
                File localImageFile = new File(imageZipFile, imageName);
                if (!localImageFile.isFile()) {
                    return null;
                }
                imageSize = localImageFile.length();
                is = new FileInputStream(localImageFile);
            } else {
                ZipFile zipFile = new ZipFile(imageZipFile);
                try {
                    ZipEntry entry = zipFile.getEntry(imageName);
                    if (entry == null) {
                        StreamUtil.close(zipFile);
                        return null;
                    }
                    imageSize = entry.getSize();
                    is =
                            new FilterInputStream(zipFile.getInputStream(entry)) {
                                @Override
                                public void close() throws IOException {
                                    StreamUtil.close(in);
                                    StreamUtil.close(zipFile);
                                }
                            };
                } catch (IOException | RuntimeException e) {
                    StreamUtil.close(zipFile);
                    throw e;
                }
            }
            return new SparseInputStream(new BufferedInputStream(is), imageSize);
        } catch (IOException | RuntimeException e) {
            StreamUtil.close(is);
            throw e;
        }
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        File systemImageZipFile = buildInfo.getFile(mSystemImageZipName);
        if (systemImageZipFile == null) {
            throw new BuildError(
                    "Cannot find " + mSystemImageZipName + " in build info.",
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
        }

        List<File> tempFiles = new ArrayList<File>();
        try {
            final long userdataSize = mUserDataSizeInGb << 30;
            File dsuPushSrc;
            String dsuPushDest;
            String command;
            try (SparseInputStream systemImageStream =
                    getUnsparsedImageStream(systemImageZipFile, SYSTEM_IMAGE_NAME)) {
                if (systemImageStream == null) {
                    throw new BuildError(
                            String.format(
                                    "Cannot find %s in %s.",
                                    SYSTEM_IMAGE_NAME, mSystemImageZipName),
                            device.getDeviceDescriptor(),
                            InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
                }
                if (device.getApiLevel() < ANDROID_API_R) {
                    // Android Q only support single system image gzip.
                    File systemImageGZ = FileUtil.createTempFile("system", ".raw.gz");
                    tempFiles.add(systemImageGZ);
                    try (FileOutputStream foStream = new FileOutputStream(systemImageGZ);
                            BufferedOutputStream boStream = new BufferedOutputStream(foStream);
                            GZIPOutputStream out = new GZIPOutputStream(boStream)) {
                        StreamUtil.copyStreams(systemImageStream, out);
                    }

                    dsuPushSrc = systemImageGZ;
                    dsuPushDest = DEST_PATH;

                    final long systemImageSize = systemImageStream.size();
                    command =
                            String.format(
                                    DSU_COMMAND_TEMPLATE_Q,
                                    dsuPushDest,
                                    userdataSize,
                                    systemImageSize);
                } else {
                    // Android R+, support DSU zip archive.
                    File dsuImageZip = FileUtil.createTempFile("system", ".zip");
                    tempFiles.add(dsuImageZip);
                    try (FileOutputStream foStream = new FileOutputStream(dsuImageZip);
                            BufferedOutputStream boStream = new BufferedOutputStream(foStream);
                            ZipOutputStream out = new ZipOutputStream(boStream)) {
                        out.putNextEntry(new ZipEntry(SYSTEM_IMAGE_NAME));
                        StreamUtil.copyStreams(systemImageStream, out);
                        out.closeEntry();
                        // Also look for any system-like partition images.
                        for (String imageName :
                                Arrays.asList(SYSTEM_EXT_IMAGE_NAME, PRODUCT_IMAGE_NAME)) {
                            try (SparseInputStream sis =
                                    getUnsparsedImageStream(systemImageZipFile, imageName)) {
                                if (sis != null) {
                                    out.putNextEntry(new ZipEntry(imageName));
                                    StreamUtil.copyStreams(sis, out);
                                    out.closeEntry();
                                }
                            }
                        }
                    }

                    dsuPushSrc = dsuImageZip;
                    dsuPushDest = DEST_ZIP_PATH;

                    command = String.format(DSU_COMMAND_TEMPLATE_R, dsuPushDest, userdataSize);
                }
            }

            CLog.i("Pushing %s to %s", dsuPushSrc.getAbsolutePath(), dsuPushDest);
            if (!device.pushFile(dsuPushSrc, dsuPushDest)) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to push %s to %s",
                                dsuPushSrc.getAbsolutePath(), dsuPushDest),
                        device.getDeviceDescriptor(),
                        DeviceErrorIdentifier.FAIL_PUSH_FILE);
            }

            device.setProperty("persist.sys.fflag.override.settings_dynamic_system", "true");
            device.executeShellCommand(command);
            // Check if device shows as unavailable (as expected after the activity finished).
            if (!device.waitForDeviceNotAvailable(DSU_MAX_WAIT_SEC * 1000)) {
                throw new TargetSetupError(
                        "Timed out waiting for DSU installation to complete and reboot",
                        device.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            }

            // If installing user build by DSU, the device will boot up without user debug mode.
            // The adb command can not be executed immediately after upgrade to a user build by DSU.
            if (!mWaitForDeviceOnline) {
                return;
            }

            try {
                device.waitForDeviceAvailable();
            } catch (DeviceNotAvailableException e) {
                throw new TargetSetupError(
                        "Timed out booting into DSU", e, device.getDeviceDescriptor());
            }
            if (!isDSURunning(device)) {
                throw new TargetSetupError("Failed to boot into DSU", device.getDeviceDescriptor());
            }
            CommandResult result = device.executeShellV2Command("gsi_tool enable");
            if (CommandStatus.SUCCESS.equals(result.getStatus())) {
                // success
                return;
            } else {
                throw new TargetSetupError("fail on gsi_tool enable", device.getDeviceDescriptor());
            }
        } catch (IOException e) {
            CLog.e(e);
            throw new TargetSetupError(
                    "fail to install the DynamicSystemUpdate", e, device.getDeviceDescriptor());
        } finally {
            for (File tempFile : tempFiles) {
                FileUtil.deleteFile(tempFile);
            }
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (e instanceof DeviceNotAvailableException) {
            CLog.e("skip tearDown on DeviceNotAvailableException");
            return;
        }
        ITestDevice device = testInfo.getDevice();
        // Disable the DynamicSystemUpdate installation
        device.executeShellV2Command("gsi_tool disable", 2, TimeUnit.MINUTES, 0);
        // Enable the one-shot mode when DynamicSystemUpdate is disabled
        device.executeShellV2Command("gsi_tool enable -s", 2, TimeUnit.MINUTES, 0);
        // Disable the DynamicSystemUpdate installation
        device.executeShellV2Command("gsi_tool disable", 2, TimeUnit.MINUTES, 0);
        // Reboot into the original system image
        device.reboot();
    }
}
