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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A {@link ITargetPreparer} that allows to mix a kernel image with the device image. */
@OptionClass(alias = "mix-kernel-target-preparer")
public class MixKernelTargetPreparer extends BaseTargetPreparer {

    @Option(
            name = "device-label",
            description = "The label for the test device that stores device images.")
    private String mDeviceLabel = "device";

    @Option(
            name = "kernel-label",
            description = "The label for the null-device that stores the kernel images.")
    private String mKernelLabel = "kernel";

    @Option(
            name = "gki-label",
            description = "The label for the null-device that stores the GKI images.")
    private String mGkiLabel = "gki";

    /*
    To use mix kernel tool on your local file system, use flag --mix-kernel-tool-path
    For example: --mix-kernel-tool-path
    /the_path_to_internal_android_tree/vendor/google/tools/build_mixed_kernels

    A file directory path can also be used. The mixing tool will search the mix-kernel-tool-name in
    the specified file directory
    For example the tool path can be specified with flexible download as:
    --mix-kernel-tool-path ab://git_master/flame-userdebug/LATEST/.*flame-tests-.*.zip?unzip=true

    If mix-kernel-tool-path is not specified, testsdir of the device build will be used as the
    mix-kernel-tool-path.
    */
    @Option(
            name = "mix-kernel-tool-path",
            description =
                    "The file path of mix kernel tool. It can be the absolute path of the tool"
                        + " path, or the absolute directory path that contains the tool. It can be"
                        + " used with flexible download feature for example --mix-kernel-tool-path"
                        + " ab://git_master/flame-userdebug/LATEST/.*flame-tests-.*.zip?unzip=true")
    private File mMixKernelToolPath = null;

    @Option(
            name = "mix-kernel-tool-name",
            description =
                    "The mixing kernel tool file name, defaulted to build_mixed_kernels. "
                            + "If mix-kernel-tool-path is a directory, the mix-kernel-tool-name "
                            + "will be used to locate the tool in the directory of "
                            + "mix-kernel-tool-path.")
    private String mMixKernelToolName = "build_mixed_kernels_ramdisk";

    // TODO: With go/aog/1141660, the TimeVal is deprecated. Will need to convert TimeVal to
    // Duration as in b/142554890.
    @Option(
            name = "mix-kernel-script-wait-time",
            isTimeVal = true,
            description =
                    "The maximum wait time for mix kernel script. By default is 20 minutes. "
                            + "It can be specified with/without time unit such as 20m, 1h, "
                            + "or 2000 for 2000 milliseconds.")
    private long mMixingWaitTime = 1200000L;

    @Option(
            name = "mix-kernel-arg",
            description =
                    "Additional arguments to be passed to mix-kernel-script command, "
                            + "including leading dash, e.g. \"--nocompress\"")
    private Collection<String> mMixKernelArgs = new ArrayList<>();

    // The files needed for mix kernel script need to be with the original image name. These files
    // will be obtained through additional-files-filter in DeviceLaunchControlProvider as they will
    // have the original file name as FileKey in BuildInfo. All the files that are stored with
    // defined BuildInfoFileKey will not be used for kernel mixing purpose.
    private static final Set<String> IMG_NOT_TO_COPY =
            new HashSet<>(
                    Arrays.asList(
                            BuildInfoFileKey.BASEBAND_IMAGE.getFileKey(),
                            BuildInfoFileKey.BOOTLOADER_IMAGE.getFileKey(),
                            BuildInfoFileKey.DEVICE_IMAGE.getFileKey(),
                            BuildInfoFileKey.MKBOOTIMG_IMAGE.getFileKey(),
                            BuildInfoFileKey.RAMDISK_IMAGE.getFileKey(),
                            BuildInfoFileKey.TESTDIR_IMAGE.getFileKey(),
                            BuildInfoFileKey.USERDATA_IMAGE.getFileKey(),
                            BuildInfoFileKey.ROOT_DIRECTORY.getFileKey()));

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        IInvocationContext context = testInfo.getContext();
        ITestDevice device = context.getDevice(mDeviceLabel);
        IDeviceBuildInfo deviceBuildInfo = (IDeviceBuildInfo) context.getBuildInfo(mDeviceLabel);
        if (deviceBuildInfo == null) {
            throw new TargetSetupError(
                    String.format("Could not find device build from device '%s'", mDeviceLabel),
                    context.getDevice(mDeviceLabel).getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        if (deviceBuildInfo.getDeviceImageFile() == null
                || !deviceBuildInfo.getDeviceImageFile().exists()) {
            throw new TargetSetupError(
                    mDeviceLabel + " does not come with device image",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        IBuildInfo kernelBuildInfo = context.getBuildInfo(mKernelLabel);
        if (kernelBuildInfo == null) {
            throw new TargetSetupError(
                    String.format("Could not find kernel build from device '%s'", mKernelLabel),
                    context.getDevice(mKernelLabel).getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        IBuildInfo gkiBuildInfo = context.getBuildInfo(mGkiLabel);
        mixKernelWithTool(device, deviceBuildInfo, kernelBuildInfo, gkiBuildInfo);
    }

    /**
     * Create new device image with mixing kernel tool.
     *
     * @param device the test device
     * @param deviceBuildInfo the device build info
     * @param kernelBuildInfo the kernel build info
     * @param gkiBuildInfo the GKI kernel build info
     */
    private void mixKernelWithTool(
            ITestDevice device,
            IDeviceBuildInfo deviceBuildInfo,
            IBuildInfo kernelBuildInfo,
            IBuildInfo gkiBuildInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        File tmpDeviceDir = null;
        File tmpKernelDir = null;
        File tmpGkiDir = null;
        File tmpNewDeviceDir = null;
        try {
            CLog.d(
                    "Before mixing kernel, the device image %s is of size %d",
                    deviceBuildInfo.getDeviceImageFile().toString(),
                    deviceBuildInfo.getDeviceImageFile().length());

            // Find the kernel mixing tool
            findMixKernelTool(deviceBuildInfo);

            // Create temp dir for original device build, kernel build, and new device build
            tmpDeviceDir = FileUtil.createTempDir("device_dir");
            tmpKernelDir = FileUtil.createTempDir("kernel_dir");
            tmpNewDeviceDir = FileUtil.createTempDir("new_device_dir");

            // Copy device images to tmpDeviceDir
            copyFileFromBuildToDir(deviceBuildInfo, tmpDeviceDir);

            // Copy kernel images to tmpKernelDir
            copyFileFromBuildToDir(kernelBuildInfo, tmpKernelDir);

            if (gkiBuildInfo != null) {
                tmpGkiDir = FileUtil.createTempDir("gki_dir");
                // Copy Gki images to tmpGkiDir
                copyFileFromBuildToDir(gkiBuildInfo, tmpGkiDir);
            }
            // Run the mix kernel tool and generate new device image into tmpNewDeviceDir
            runMixKernelTool(device, tmpDeviceDir, tmpKernelDir, tmpGkiDir, tmpNewDeviceDir);
            // Find the new device image and copy it to device build info's device image file
            setNewDeviceImage(deviceBuildInfo, tmpNewDeviceDir);

        } catch (IOException e) {
            throw new TargetSetupError(
                    "Could not mix device and kernel images", e, device.getDeviceDescriptor());
        } finally {
            FileUtil.recursiveDelete(tmpDeviceDir);
            FileUtil.recursiveDelete(tmpKernelDir);
            FileUtil.recursiveDelete(tmpGkiDir);
            FileUtil.recursiveDelete(tmpNewDeviceDir);
        }
    }

    /**
     * Copy files from BuildInfo to the specified destination directory.
     *
     * @param buildInfo the device build info where the source file will be copied from
     * @param destDir the destination directory {@link File} that the files will be copied to
     * @throws IOException if hit IOException
     */
    private void copyFileFromBuildToDir(IBuildInfo buildInfo, File destDir) throws IOException {
        for (String fileKey : buildInfo.getVersionedFileKeys()) {
            File srcFile = buildInfo.getFile(fileKey);
            if (IMG_NOT_TO_COPY.contains(fileKey)) {
                CLog.d("Skip copying %s %s to %s", fileKey, srcFile.toString(), destDir.toString());
                continue;
            }
            if (srcFile.isFile()) {
                File dstFile = new File(destDir, fileKey);
                CLog.d("Copy %s %s to %s", fileKey, srcFile.toString(), dstFile.toString());
                FileUtil.copyFile(srcFile, dstFile);
            } else {
                if (buildInfo.getFile(BuildInfoFileKey.DEVICE_IMAGE).toString().contains(fileKey)) {
                    File dstFile = new File(destDir, fileKey);
                    CLog.d("Copy %s %s to %s", fileKey, srcFile.toString(), dstFile.toString());
                    FileUtil.copyFile(buildInfo.getFile(BuildInfoFileKey.DEVICE_IMAGE), dstFile);
                } else {
                    CLog.d(
                            "%s %s is not a file, skip copying to %s",
                            fileKey, srcFile.toString(), destDir.toString());
                }
            }
        }
    }

    /**
     * Find the mix kernel tool if mMixKernelToolPath does not exist. If mix-kernel-tool-path
     * provides a valid mixing tool, use it. Otherwise, try to find the mixing tool from the build
     * provider
     *
     * @param buildInfo the {@link IDeviceBuildInfo} where to search for mix kernel script
     * @throws TargetSetupError if flashing script missing or fails
     * @throws IOException if hit IOException
     */
    private void findMixKernelTool(IDeviceBuildInfo buildInfo)
            throws TargetSetupError, IOException {
        if (mMixKernelToolPath == null || !mMixKernelToolPath.exists()) {
            CLog.i(
                    "File mix-kernel-tool-path is not configured or does not exist. "
                            + "Use Devices's TestsDir and the mix kernel tool path.");
            mMixKernelToolPath = buildInfo.getTestsDir();
            if (mMixKernelToolPath == null || !mMixKernelToolPath.isDirectory()) {
                throw new TargetSetupError(
                        String.format(
                                "There is no testsDir for %s to search for mix kernel tool",
                                buildInfo.getBuildId()),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
        }
        if (mMixKernelToolPath.isDirectory()) {
            File mixKernelTool = FileUtil.findFile(mMixKernelToolPath, mMixKernelToolName);
            if (mixKernelTool == null || !mixKernelTool.exists()) {
                throw new TargetSetupError(
                        String.format(
                                "Could not find the mix kernel tool %s from %s",
                                mMixKernelToolName, mMixKernelToolPath),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            mMixKernelToolPath = mixKernelTool;
        }
        if (!mMixKernelToolPath.canExecute()) {
            FileUtil.chmodGroupRWX(mMixKernelToolPath);
        }
    }

    /**
     * Find the new device image and set it as device image for the device.
     *
     * @param deviceBuildInfo the device build info
     * @param newDeviceDir the directory {@link File} where to find the new device image
     * @throws TargetSetupError if fail to get the new device image
     * @throws IOException if hit IOException
     */
    @VisibleForTesting
    void setNewDeviceImage(IDeviceBuildInfo deviceBuildInfo, File newDeviceDir)
            throws TargetSetupError, IOException {
        File newDeviceImage =
                FileUtil.findFile(newDeviceDir, ".*-img-" + deviceBuildInfo.getBuildId() + ".zip$");
        if (newDeviceImage == null || !newDeviceImage.exists()) {
            throw new TargetSetupError(
                    "Failed to get a new device image after mixing",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        CLog.i(
                "Successfully generated new device image %s of size %d",
                newDeviceImage, newDeviceImage.length());
        String deviceImagePath = deviceBuildInfo.getDeviceImageFile().getAbsolutePath();
        deviceBuildInfo.getDeviceImageFile().delete();
        FileUtil.copyFile(newDeviceImage, new File(deviceImagePath));

        CLog.d(
                "After mixing kernel, the device image %s is of size %d",
                deviceBuildInfo.getDeviceImageFile().toString(),
                deviceBuildInfo.getDeviceImageFile().length());
    }

    /**
     * Run mix kernel tool to generate the new device build
     *
     * <p>Mixing tool Usage: build_mixed_kernels device_dir out_dir target flavor kernel_dir
     *
     * @param device the test device
     * @param oldDeviceDir the directory {@link File} contains old device images
     * @param kernelDir the directory {@link File} contains kernel images destination
     * @param gkiDir the directory {@link File} contains GKI kernel images destination
     * @param newDeviceDir the directory {@link File} where new device images will be generated to
     * @throws TargetSetupError if fails to run mix kernel tool
     * @throws IOException
     */
    protected void runMixKernelTool(
            ITestDevice device, File oldDeviceDir, File kernelDir, File gkiDir, File newDeviceDir)
            throws TargetSetupError, DeviceNotAvailableException {
        List<String> cmd = ArrayUtil.list(mMixKernelToolPath.getAbsolutePath());
        // Tool command line: $0 [<options>] --gki_dir gkiDir oldDeviceDir kernelDir newDeviceDir
        cmd.addAll(mMixKernelArgs);
        if (gkiDir != null) {
            cmd.add("--gki_dir");
            cmd.add(gkiDir.toString());
        }
        cmd.add(oldDeviceDir.toString());
        cmd.add(kernelDir.toString());
        cmd.add(newDeviceDir.toString());
        CLog.i("Run %s to mix kernel and device build", mMixKernelToolPath.toString());
        CommandResult result =
                RunUtil.getDefault()
                        .runTimedCmd(mMixingWaitTime, cmd.toArray(new String[cmd.size()]));
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.e(
                    "Failed to run mix kernel tool. Exit code: %s, stdout: %s, stderr: %s",
                    result.getStatus(), result.getStdout(), result.getStderr());
            throw new TargetSetupError(
                    "Failed to run mix kernel tool. Stderr: " + result.getStderr());
        }
        CLog.i(
                "Successfully mixed kernel to new device image in %s with files %s",
                newDeviceDir.toString(), Arrays.toString(newDeviceDir.list()));
    }
}
