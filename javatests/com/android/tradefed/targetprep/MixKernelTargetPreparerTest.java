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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.FileUtil;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;

/** Unit tests for {@link MixKernelTargetPreparer} */
@RunWith(JUnit4.class)
public class MixKernelTargetPreparerTest {
    private IInvocationContext mContext;
    private TestInformation mTestInfo;

    @Before
    public void setUp() {
        mContext = new InvocationContext();
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();
    }

    @Test
    public void testFailsOnMissingDeviceLabel() throws Exception {
        MixKernelTargetPreparer mk = new MixKernelTargetPreparer();
        ITestDevice mockDevice = Mockito.mock(ITestDevice.class);
        try {
            mContext.addDeviceBuildInfo("random_invalid_device_label", new DeviceBuildInfo());
            mContext.addDeviceBuildInfo("kernel", new DeviceBuildInfo());
            mContext.addAllocatedDevice("device", mockDevice);
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        }
    }

    @Test
    public void testFailsOnMissingKernelLabel() throws Exception {
        MixKernelTargetPreparer mk = new MixKernelTargetPreparer();
        ITestDevice mockDevice = Mockito.mock(ITestDevice.class);
        try {
            mContext.addDeviceBuildInfo("device", new DeviceBuildInfo());
            mContext.addDeviceBuildInfo("invalid_kernel", new DeviceBuildInfo());
            mContext.addAllocatedDevice("kernel", mockDevice);
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        }
    }

    @Test
    public void testFailsOnMissingDeviceImage() throws Exception {
        MixKernelTargetPreparer mk = new MixKernelTargetPreparer();
        try {
            mContext.addDeviceBuildInfo("device", new DeviceBuildInfo());
            mContext.addDeviceBuildInfo("kernel", new DeviceBuildInfo());
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        }
    }

    @Test
    public void testFailsOnMissingKernelMixingTool() throws Exception {
        MixKernelTargetPreparer mk = new MixKernelTargetPreparer();
        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        DeviceBuildInfo deviceBuild = createDeviceBuildInfo("device_flavor", "12345", deviceImage);
        try {
            mContext.addDeviceBuildInfo("device", deviceBuild);
            mContext.addDeviceBuildInfo("kernel", new DeviceBuildInfo("fake_id", "fake_target"));
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(deviceImage);
        }
    }

    @Test
    public void testFailsOnRunningKernelMixingTool() throws Exception {
        MixKernelTargetPreparer mk =
                new MixKernelTargetPreparer() {
                    @Override
                    protected void runMixKernelTool(
                            ITestDevice device,
                            File oldDeviceDir,
                            File kernelDir,
                            File gkiDir,
                            File newDeviceDir)
                            throws TargetSetupError, DeviceNotAvailableException {
                        throw new TargetSetupError("Failed to run mixing tool");
                    }
                };
        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        File kernelImage = FileUtil.createTempFile("dtbo", "img");
        File testsDir = FileUtil.createTempDir("testsdir");
        OptionSetter setter = new OptionSetter(mk);
        setter.setOptionValue("mix-kernel-tool-name", "build_mixed_kernels_ramdisk");
        File mixKernelTool = FileUtil.createTempFile("build_mixed_kernels_ramdisk", null, testsDir);
        mixKernelTool.renameTo(new File(testsDir, "build_mixed_kernels_ramdisk"));
        DeviceBuildInfo deviceBuild = createDeviceBuildInfo("device_flavor", "12345", deviceImage);
        DeviceBuildInfo kernelBuild =
                createDeviceBuildInfo("kernel_flavor", "kernel_build_id", null, kernelImage);
        deviceBuild.setTestsDir(testsDir, "12345");
        try {
            mContext.addDeviceBuildInfo("device", deviceBuild);
            mContext.addDeviceBuildInfo("kernel", kernelBuild);
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(deviceImage);
            FileUtil.recursiveDelete(kernelImage);
            FileUtil.recursiveDelete(testsDir);
        }
    }

    @Test
    public void testFailsOnNoNewDeviceImage() throws Exception {
        MixKernelTargetPreparer mk =
                new MixKernelTargetPreparer() {
                    @Override
                    protected void runMixKernelTool(
                            ITestDevice device,
                            File oldDeviceDir,
                            File kernelDir,
                            File gkiDir,
                            File newDeviceDir)
                            throws TargetSetupError, DeviceNotAvailableException {
                        return;
                    }
                };

        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        File kernelImage = FileUtil.createTempFile("dtbo", "img");
        OptionSetter setter = new OptionSetter(mk);
        setter.setOptionValue("mix-kernel-tool-name", "build_mixed_kernels_ramdisk");
        File testsDir = FileUtil.createTempDir("testsdir");
        File mixKernelTool = FileUtil.createTempFile("build_mixed_kernels_ramdisk", null, testsDir);
        mixKernelTool.renameTo(new File(testsDir, "build_mixed_kernels_ramdisk"));
        DeviceBuildInfo deviceBuild = createDeviceBuildInfo("device_flavor", "12345", deviceImage);
        DeviceBuildInfo kernelBuild =
                createDeviceBuildInfo("kernel_flavor", "kernel_build_id", null, kernelImage);
        deviceBuild.setTestsDir(testsDir, "12345");
        try {
            mContext.addDeviceBuildInfo("device", deviceBuild);
            mContext.addDeviceBuildInfo("kernel", kernelBuild);
            mk.setUp(mTestInfo);
            Assert.fail("Expected TargetSetupError");
        } catch (TargetSetupError e) {
            // expected.
        } finally {
            FileUtil.recursiveDelete(deviceImage);
            FileUtil.recursiveDelete(kernelImage);
            FileUtil.recursiveDelete(testsDir);
        }
    }

    @Test
    public void testSuccessfulMixKernel() throws Exception {
        MixKernelTargetPreparer mk =
                new MixKernelTargetPreparer() {
                    @Override
                    protected void runMixKernelTool(
                            ITestDevice device,
                            File oldDeviceDir,
                            File kernelDir,
                            File gkiDir,
                            File newDeviceDir)
                            throws TargetSetupError, DeviceNotAvailableException {
                        try {
                            File newFile =
                                    FileUtil.createTempFile(
                                            "new-device-img-12345", "zip", newDeviceDir);
                            newFile.renameTo(new File(newDeviceDir, "device-img-12345.zip"));
                        } catch (IOException e) {
                            throw new TargetSetupError(
                                    "Could not create file in " + newDeviceDir.toString());
                        }
                    }
                };

        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        File kernelImage = FileUtil.createTempFile("dtbo", "img");
        File testsDir = FileUtil.createTempDir("testsdir");
        OptionSetter setter = new OptionSetter(mk);
        setter.setOptionValue("mix-kernel-tool-name", "my_tool_12345");
        File mixKernelTool = FileUtil.createTempFile("my_tool_12345", null, testsDir);
        mixKernelTool.renameTo(new File(testsDir, "my_tool_12345"));
        DeviceBuildInfo deviceBuild = createDeviceBuildInfo("device_flavor", "12345", deviceImage);
        DeviceBuildInfo kernelBuild =
                createDeviceBuildInfo("kernel_flavor", "kernel_build_id", null, kernelImage);
        deviceBuild.setTestsDir(testsDir, "12345");
        try {
            mContext.addDeviceBuildInfo("device", deviceBuild);
            mContext.addDeviceBuildInfo("kernel", kernelBuild);
            mk.setUp(mTestInfo);
        } finally {
            FileUtil.recursiveDelete(deviceImage);
            FileUtil.recursiveDelete(kernelImage);
            FileUtil.recursiveDelete(testsDir);
        }
    }

    @Test
    public void testSuccessfulMixKernelWithLocalToolPath() throws Exception {
        MixKernelTargetPreparer mk =
                new MixKernelTargetPreparer() {
                    @Override
                    protected void runMixKernelTool(
                            ITestDevice device,
                            File oldDeviceDir,
                            File kernelDir,
                            File gkiDir,
                            File newDeviceDir)
                            throws TargetSetupError, DeviceNotAvailableException {
                        try {
                            File newFile =
                                    FileUtil.createTempFile(
                                            "new-device-img-12345", "zip", newDeviceDir);
                            newFile.renameTo(new File(newDeviceDir, "device-img-12345.zip"));
                        } catch (IOException e) {
                            throw new TargetSetupError(
                                    "Could not create file in " + newDeviceDir.toString());
                        }
                    }
                };

        File deviceImage = FileUtil.createTempFile("device-img-12345", "zip");
        File kernelImage = FileUtil.createTempFile("dtbo", "img");
        File toolDir = FileUtil.createTempDir("tooldir");
        File mixKernelTool = FileUtil.createTempFile("my_tool_12345", null, toolDir);
        DeviceBuildInfo deviceBuild = createDeviceBuildInfo("device_flavor", "12345", deviceImage);
        DeviceBuildInfo kernelBuild =
                createDeviceBuildInfo("kernel_flavor", "kernel_build_id", null, kernelImage);
        OptionSetter setter = new OptionSetter(mk);
        setter.setOptionValue("mix-kernel-tool-path", mixKernelTool.getAbsolutePath());
        try {
            mContext.addDeviceBuildInfo("device", deviceBuild);
            mContext.addDeviceBuildInfo("kernel", kernelBuild);
            mk.setUp(mTestInfo);
        } finally {
            FileUtil.recursiveDelete(deviceImage);
            FileUtil.recursiveDelete(kernelImage);
            FileUtil.recursiveDelete(toolDir);
        }
    }

    @Test
    public void testSetNewDeviceImage() throws Exception {
        MixKernelTargetPreparer mk = new MixKernelTargetPreparer();
        // Create a device image in lc cache
        File deviceImageInLcCache = FileUtil.createTempFile("device-img-12345", ".zip");
        FileUtil.writeToFile("old_image_12345", deviceImageInLcCache);
        // Create the device image with hardlink to device image in lc cache
        File tmpImg = FileUtil.createTempFile("device-img-hard-link", ".zip");
        tmpImg.delete();
        FileUtil.hardlinkFile(deviceImageInLcCache, tmpImg);
        // Add the device image with hardlink to device build info
        DeviceBuildInfo deviceBuildInfo = createDeviceBuildInfo("device_flavor", "12345", tmpImg);
        // Create a new device image in the device_dir
        File newImageDir = FileUtil.createTempDir("device_dir");
        File deviceImage = new File(newImageDir, "device-img-12345.zip");
        FileUtil.writeToFile("new_image", deviceImage);
        try {
            if (deviceImageInLcCache.length() != deviceBuildInfo.getDeviceImageFile().length()) {
                Assert.fail(
                        "Device image in lc cache is not of the same size as device image"
                                + " in device build info before calling setNewDeviceImage");
            }
            mk.setNewDeviceImage(deviceBuildInfo, newImageDir);
            if (deviceBuildInfo.getDeviceImageFile() == null
                    || !deviceBuildInfo.getDeviceImageFile().exists()) {
                Assert.fail("New device image is not set");
            }
            if (deviceImageInLcCache == null || !deviceImageInLcCache.exists()) {
                Assert.fail("Device image in lc cache is gone");
            }
            if (deviceImageInLcCache.length() == deviceBuildInfo.getDeviceImageFile().length()) {
                Assert.fail(
                        "Device image in lc cache is of the same size as device image "
                                + "in device build info after calling setNewDeviceImage");
            }
        } finally {
            FileUtil.recursiveDelete(deviceImageInLcCache);
            FileUtil.recursiveDelete(tmpImg);
            FileUtil.recursiveDelete(newImageDir);
        }
    }

    private DeviceBuildInfo createDeviceBuildInfo(
            String buildFlavor, String buildId, File deviceImage, File... images) {
        DeviceBuildInfo buildInfo = new DeviceBuildInfo();
        buildInfo.setBuildFlavor(buildFlavor);
        buildInfo.setBuildId(buildId);
        if (deviceImage != null) {
            buildInfo.setDeviceImageFile(deviceImage, buildId);
        }
        for (File image : images) {
            buildInfo.setFile(image.getName(), image, buildId);
        }
        return buildInfo;
    }
}
