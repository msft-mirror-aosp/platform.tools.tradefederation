/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tradefed.device.metric;

import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.CLANG;

import static com.google.common.base.Verify.verifyNotNull;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.util.AdbRootElevator;
import com.android.tradefed.util.ClangProfileIndexer;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.NativeCodeCoverageFlusher;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TarUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A {@link com.android.tradefed.device.metric.BaseDeviceMetricCollector} that will pull Clang
 * coverage measurements off of the device and log them as test artifacts.
 */
public final class ClangCodeCoverageCollector extends BaseDeviceMetricCollector
        implements IConfigurationReceiver {
    // Finds .profraw files and compresses those files only. Stores the full
    // path of the file on the device.
    private static final String ZIP_CLANG_FILES_COMMAND_FORMAT =
            "find %s -name '*.profraw' | tar -czf - -T - 2>/dev/null";

    // Deletes .profraw files in the directory.
    private static final String DELETE_COVERAGE_FILES_COMMAND_FORMAT =
            "find %s -name '*.profraw' -delete";

    private IConfiguration mConfiguration;
    private IRunUtil mRunUtil = RunUtil.getDefault();
    // Timeout for pulling coverage measurements from the device, in milliseconds.
    private long mTimeoutMilli = 20 * 60 * 1000;
    private File mLlvmProfileTool;

    private NativeCodeCoverageFlusher mFlusher;

    @Override
    public void extraInit(IInvocationContext context, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        super.extraInit(context, listener);
        setDisableReceiver(false);

        verifyNotNull(mConfiguration);
        setCoverageOptions(mConfiguration.getCoverageOptions());

        if (isClangCoverageEnabled()
                && mConfiguration.getCoverageOptions().shouldResetCoverageBeforeTest()) {
            for (ITestDevice device : getRealDevices()) {
                // Clear coverage measurements on the device.
                try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
                    getCoverageFlusher(device).deleteCoverageMeasurements();
                }
            }
        }
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public void rebootEnded(ITestDevice device) throws DeviceNotAvailableException {
        if (isClangCoverageEnabled()
                && mConfiguration.getCoverageOptions().shouldResetCoverageBeforeTest()) {
            getCoverageFlusher(device).deleteCoverageMeasurements();
        }
    }

    @VisibleForTesting
    public void setRunUtil(IRunUtil runUtil) {
        mRunUtil = runUtil;
        if (mFlusher != null) {
            mFlusher.setRunUtil(runUtil);
        }
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData, final Map<String, Metric> currentRunMetrics)
            throws DeviceNotAvailableException {
        if (!isClangCoverageEnabled()) {
            return;
        }

        for (ITestDevice device : getRealDevices()) {
            try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
                if (mConfiguration.getCoverageOptions().isCoverageFlushEnabled()) {
                    getCoverageFlusher(device).forceCoverageFlush();
                }
                logCoverageMeasurement(device, generateMeasurementFileName());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Generate the .profdata file prefix in format "$moduleName_MODULE_$runName". */
    private String generateMeasurementFileName() {
        String moduleName = Strings.nullToEmpty(getModuleName());
        if (moduleName.length() > 0) {
            moduleName += "_MODULE_";
        }
        return moduleName + getRunName().replace(' ', '_');
    }

    /**
     * Logs Clang coverage measurements from the device.
     *
     * @param runName name used in the log file
     * @throws DeviceNotAvailableException
     * @throws IOException
     */
    private void logCoverageMeasurement(ITestDevice device, String runName)
            throws DeviceNotAvailableException, IOException {
        Map<String, File> untarDirs = new HashMap<>();
        File profileTool = null;
        File indexedProfileFile = null;
        try {
            Set<String> rawProfileFiles = new HashSet<>();
            for (String devicePath : mConfiguration.getCoverageOptions().getDeviceCoveragePaths()) {
                File coverageTarGz = FileUtil.createTempFile("clang_coverage", ".tar.gz");

                try {
                    // Compress coverage measurements on the device before streaming to the host.
                    try (OutputStream out =
                            new BufferedOutputStream(new FileOutputStream(coverageTarGz))) {
                        device.executeShellV2Command(
                                String.format(
                                        ZIP_CLANG_FILES_COMMAND_FORMAT, devicePath), // Command
                                null, // File pipe as input
                                out, // OutputStream to write to
                                mTimeoutMilli, // Timeout in milliseconds
                                TimeUnit.MILLISECONDS, // Timeout units
                                1); // Retry count
                    }

                    File untarDir = TarUtil.extractTarGzipToTemp(coverageTarGz, "clang_coverage");
                    untarDirs.put(devicePath, untarDir);
                    rawProfileFiles.addAll(
                            FileUtil.findFiles(
                                    untarDir,
                                    mConfiguration.getCoverageOptions().getProfrawFilter()));
                } catch (IOException e) {
                    CLog.e("Failed to pull Clang coverage data from %s", devicePath);
                    CLog.e(e);
                } finally {
                    FileUtil.deleteFile(coverageTarGz);
                }
            }

            if (rawProfileFiles.isEmpty()) {
                CLog.i("No Clang code coverage measurements found.");
                return;
            }

            CLog.i("Received %d Clang code coverage measurements.", rawProfileFiles.size());

            ClangProfileIndexer indexer = new ClangProfileIndexer(getProfileTool(), mRunUtil);

            // Create the output file.
            indexedProfileFile =
                    FileUtil.createTempFile(runName + "_clang_runtime_coverage", ".profdata");
            indexer.index(rawProfileFiles, indexedProfileFile);

            try (FileInputStreamSource source =
                    new FileInputStreamSource(indexedProfileFile, true)) {
                testLog(runName + "_clang_runtime_coverage", LogDataType.CLANG_COVERAGE, source);
            }
        } finally {
            // Delete coverage files on the device.
            for (String devicePath : mConfiguration.getCoverageOptions().getDeviceCoveragePaths()) {
                device.executeShellCommand(
                        String.format(DELETE_COVERAGE_FILES_COMMAND_FORMAT, devicePath));
            }
            for (File untarDir : untarDirs.values()) {
                FileUtil.recursiveDelete(untarDir);
            }
            FileUtil.recursiveDelete(mLlvmProfileTool);
            FileUtil.deleteFile(indexedProfileFile);
        }
    }

    /**
     * Creates a {@link NativeCodeCoverageFlusher} if one does not already exist.
     *
     * @return a NativeCodeCoverageFlusher
     */
    private NativeCodeCoverageFlusher getCoverageFlusher(ITestDevice device) {
        if (mFlusher == null) {
            verifyNotNull(mConfiguration);
            mFlusher = new NativeCodeCoverageFlusher(device, mConfiguration.getCoverageOptions());
            mFlusher.setRunUtil(mRunUtil);
        }
        return mFlusher;
    }

    private boolean isClangCoverageEnabled() {
        return mConfiguration != null
                && mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration.getCoverageOptions().getCoverageToolchains().contains(CLANG);
    }

    /**
     * Retrieves the profile tool and dependencies from the build, and extracts them.
     *
     * @return the directory containing the profile tool and dependencies
     */
    private File getProfileTool() throws IOException {
        // If llvm-profdata-path was set in the Configuration, pass it through. Don't save the path
        // locally since the parent process is responsible for cleaning it up.
        File configurationTool = mConfiguration.getCoverageOptions().getLlvmProfdataPath();
        if (configurationTool != null) {
            return configurationTool;
        }
        if (mLlvmProfileTool != null && mLlvmProfileTool.exists()) {
            return mLlvmProfileTool;
        }

        // Otherwise, try to download llvm-profdata.zip from the build and cache it.
        File profileToolZip = null;
        for (IBuildInfo info : getBuildInfos()) {
            if (info.getFile("llvm-profdata.zip") != null) {
                profileToolZip = info.getFile("llvm-profdata.zip");
                mLlvmProfileTool = ZipUtil.extractZipToTemp(profileToolZip, "llvm-profdata");
                return mLlvmProfileTool;
            }
        }
        return mLlvmProfileTool;
    }

    private void setCoverageOptions(CoverageOptions coverageOptions) {
        mTimeoutMilli = coverageOptions.getPullTimeout();
    }
}
