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

package com.android.tradefed.device.metric;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.proto.TfMetricProtoUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.MultiMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link GcovKernelCodeCoverageCollector}. */
@RunWith(JUnit4.class)
public class GcovKernelCodeCoverageCollectorTest {

    @Rule public TestName name = new TestName();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Mock IConfiguration mMockConfiguration;
    @Mock IInvocationContext mMockContext;
    @Mock ITestDevice mMockDevice;

    LogFileReader mFakeListener = new LogFileReader();
    MultiMap<String, String> mContextAttributes = new MultiMap<>();

    /** Options for coverage. */
    CoverageOptions mCoverageOptions = null;

    OptionSetter mCoverageOptionsSetter = null;

    /** Object under test. */
    GcovKernelCodeCoverageCollector mKernelCodeCoverageListener;

    final CommandResult mSuccessResult;
    final CommandResult mFailedResult;

    public GcovKernelCodeCoverageCollectorTest() {
        mSuccessResult = new CommandResult(CommandStatus.SUCCESS);
        mSuccessResult.setStdout("ffffffffffff\n");
        mSuccessResult.setExitCode(0);

        mFailedResult = new CommandResult(CommandStatus.FAILED);
        mFailedResult.setStdout("");
        mFailedResult.setExitCode(-1);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCoverageOptions = new CoverageOptions();
        mCoverageOptionsSetter = new OptionSetter(mCoverageOptions);

        doReturn(mCoverageOptions).when(mMockConfiguration).getCoverageOptions();
        doReturn(ImmutableList.of(mMockDevice)).when(mMockContext).getDevices();
        doReturn(mContextAttributes).when(mMockContext).getAttributes();

        // Mock various device `adb` calls embedded in collector methods.
        //

        // isDebugfsMounted() happy path: fail, success, success
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.CHECK_DEBUGFS_MNT_COMMAND))
                .thenReturn(mFailedResult) // Inside mountDebugfs() expects it not mounted
                .thenReturn(mSuccessResult) // Inside collectGcovDebugfsCoverage()
                .thenReturn(mSuccessResult); // Inside unmountDebugfs()

        // mountDebugFs() happy path: success
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.MOUNT_DEBUGFS_COMMAND))
                .thenReturn(mSuccessResult);

        // unmountDebugFs() happy path: success
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.UNMOUNT_DEBUGFS_COMMAND))
                .thenReturn(mSuccessResult);

        // resetGcovCounts() happy path: success
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.RESET_GCOV_COUNTS_COMMAND))
                .thenReturn(mSuccessResult);

        // collectGcovDebugfsCoverage() make temp dir happy path:
        //     success with dirctory string stdout
        CommandResult successResultWithDir = new CommandResult(CommandStatus.SUCCESS);
        successResultWithDir.setStdout("/\n");
        successResultWithDir.setExitCode(0);
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.MAKE_TEMP_DIR_COMMAND))
                .thenReturn(successResultWithDir);

        // collectGcovDebugfsCoverage() gather coverage happy path: success
        when(mMockDevice.executeShellV2Command(
                        startsWith("find /d/gcov"), anyLong(), any(TimeUnit.class)))
                .thenReturn(mSuccessResult);

        // device.pullFile() happy path: always return a file with the given name
        doAnswer(
                        i ->
                                createTar(
                                        (String) i.getArguments()[0],
                                        ImmutableMap.of(
                                                "path/to/coverage.gcda",
                                                ByteString.copyFromUtf8("coverage.gcda"))))
                .when(mMockDevice)
                .pullFile(anyString(), anyInt());

        when(mMockDevice.isAdbRoot()).thenReturn(true);

        mKernelCodeCoverageListener = new GcovKernelCodeCoverageCollector();
        mKernelCodeCoverageListener.setConfiguration(mMockConfiguration);
    }

    private List<String> configuredRun(
            List<String> modules, int testRunsPerModule, boolean simulateTestReboot)
            throws Exception {

        List<String> testRunNames = new ArrayList<String>();

        enableGcovKernelCoverage();
        mKernelCodeCoverageListener.init(mMockContext, mFakeListener);
        for (String module : modules) {
            setModuleName(module);

            mKernelCodeCoverageListener.onTestModuleStarted();

            for (int i = 0; i < testRunsPerModule; i++) {
                var randomRunName = UUID.randomUUID().toString();
                mKernelCodeCoverageListener.testRunStarted(randomRunName, 1);

                if (simulateTestReboot) {
                    mMockDevice.reboot();
                }

                mKernelCodeCoverageListener.testRunEnded(
                        1000, TfMetricProtoUtil.upgradeConvert(new HashMap<String, String>()));
                testRunNames.add(randomRunName);
            }

            mKernelCodeCoverageListener.onTestModuleEnded();
            clearModuleName();
        }
        return testRunNames;
    }

    @Test
    public void singleModuleSingleTestRun_returnTestRunNamedTar() throws Exception {
        // If a module name isn't specified the created tar file will fall back to using
        // the test run name.
        var testRunNames = configuredRun(List.of(""), 1, false);

        assertThat(mFakeListener.getLogs()).hasSize(1);
        assertThat(mFakeListener.getLogFilenames().get(0)).startsWith(testRunNames.get(0));
    }

    @Test
    public void singleModuleSingleTestRun_returnModuleNamedTar() throws Exception {
        var moduleName = name.getMethodName();
        configuredRun(List.of(moduleName), 1, false);

        assertThat(mFakeListener.getLogs()).hasSize(1);
        assertThat(mFakeListener.getLogFilenames().get(0)).startsWith(moduleName);
    }

    @Test
    public void singleModuleMultiTestRun_returnModuleNamedTar() throws Exception {
        var moduleName = name.getMethodName();
        var testRunNames = configuredRun(List.of(moduleName), 3, false);

        assertThat(testRunNames).hasSize(3);
        assertThat(mFakeListener.getLogs()).hasSize(3);
        assertThat(mFakeListener.getLogFilenames().get(0)).startsWith(moduleName);
    }

    @Test
    public void multipleModuleRun_returnMultipleModuleNamedTars() throws Exception {
        var moduleName1 = name.getMethodName() + "_1";
        var moduleName2 = name.getMethodName() + "_2";
        var moduleName3 = name.getMethodName() + "_3";
        configuredRun(List.of(moduleName1, moduleName2, moduleName3), 1, false);

        var logFileNames = mFakeListener.getLogFilenames();
        assertThat(mFakeListener.getLogs()).hasSize(3);
        assertTrue(logFileNames.removeIf(x -> x.startsWith(moduleName1)));
        assertTrue(logFileNames.removeIf(x -> x.startsWith(moduleName2)));
        assertTrue(logFileNames.removeIf(x -> x.startsWith(moduleName3)));
    }

    @Test
    public void mountDebugfsFailure_noTar() throws Exception {
        var moduleName = name.getMethodName();

        // Set mount command to fail
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.MOUNT_DEBUGFS_COMMAND))
                .thenReturn(mFailedResult);

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    @Test
    public void resetGcovCountsFail_noTar() throws Exception {
        var moduleName = name.getMethodName();

        // Set mount command to fail
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.RESET_GCOV_COUNTS_COMMAND))
                .thenReturn(mFailedResult);

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    @Test
    public void makeTempDirFail_noTar() throws Exception {
        var moduleName = name.getMethodName();

        // Set mount command to fail
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.MAKE_TEMP_DIR_COMMAND))
                .thenReturn(mFailedResult);

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    @Test
    public void gatherCoverageFail_noTar() throws Exception {
        var moduleName = name.getMethodName();

        // Set mount command to fail
        when(mMockDevice.executeShellV2Command(
                        startsWith("find /d/gcov"), anyLong(), any(TimeUnit.class)))
                .thenReturn(mFailedResult);

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    @Test
    public void debugfsMountGoneBeforeGather_noTar() throws Exception {
        var moduleName = name.getMethodName();

        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.CHECK_DEBUGFS_MNT_COMMAND))
                .thenReturn(mFailedResult) // Inside mountDebugfs() expects it not mounted
                .thenReturn(mFailedResult); // Inside collectGcovDebugfsCoverage()

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    @Test
    public void debugfsMountAlreadyExists_justWarn() throws Exception {
        var moduleName = name.getMethodName();

        // All cases return success
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.CHECK_DEBUGFS_MNT_COMMAND))
                .thenReturn(mSuccessResult);

        configuredRun(List.of(moduleName), 1, false);

        assertThat(mFakeListener.getLogs()).hasSize(1);
        assertThat(mFakeListener.getLogFilenames().get(0)).startsWith(moduleName);
    }

    @Test
    public void nullFilePulledFromDevice_noTar() throws Exception {
        var moduleName = name.getMethodName();

        // Return null on pullFile request
        doAnswer(i -> null).when(mMockDevice).pullFile(anyString(), anyInt());

        configuredRun(List.of(moduleName), 1, false);
        assertThat(mFakeListener.getLogs()).hasSize(0);
    }

    private void setModuleName(String name) {
        mContextAttributes.put(ModuleDefinition.MODULE_NAME, name);
    }

    private void clearModuleName() {
        mContextAttributes.remove(ModuleDefinition.MODULE_NAME);
    }

    private void failMountDebugfs() throws Exception {
        CommandResult result = new CommandResult(CommandStatus.FAILED);
        result.setExitCode(-1);
        when(mMockDevice.executeShellV2Command(startsWith("mount -t debugfs debugfs")))
                .thenReturn(result);
    }

    private void failDebugfsMountCheck() throws Exception {
        CommandResult result = new CommandResult(CommandStatus.FAILED);
        result.setExitCode(-1);
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.CHECK_DEBUGFS_MNT_COMMAND))
                .thenReturn(result);
    }

    private void passDebugfsMountCheck() throws Exception {
        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setExitCode(0);
        when(mMockDevice.executeShellV2Command(
                        GcovKernelCodeCoverageCollector.CHECK_DEBUGFS_MNT_COMMAND))
                .thenReturn(result);
    }

    /** An {@link ITestInvocationListener} which reads test log data streams for verification. */
    private static class LogFileReader implements ITestInvocationListener {
        private Map<String, ByteString> mLogs = new HashMap<String, ByteString>();

        /** Reads the contents of the {@code dataStream} and saves it in the logs. */
        @Override
        public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
            try (InputStream input = dataStream.createInputStream()) {
                mLogs.put(dataName, ByteString.readFrom(input));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Map<String, ByteString> getLogs() {
            return new HashMap<String, ByteString>(mLogs);
        }

        List<String> getLogFilenames() {
            List<String> fileNames = new ArrayList<String>();
            for (Map.Entry<String, ByteString> entry : mLogs.entrySet()) {
                fileNames.add(entry.getKey());
            }
            return fileNames;
        }
    }

    /** Utility method to create .tar files. */
    private File createTar(String fullFileName, Map<String, ByteString> fileContents)
            throws IOException {
        var fileName = Paths.get(fullFileName).getFileName().toString();
        var tarFile = folder.newFile(fileName);
        try (TarArchiveOutputStream out =
                new TarArchiveOutputStream(new FileOutputStream(tarFile))) {
            for (Map.Entry<String, ByteString> file : fileContents.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().size());

                out.putArchiveEntry(entry);
                file.getValue().writeTo(out);
                out.closeArchiveEntry();
            }
        }
        return tarFile;
    }

    private void enableGcovKernelCoverage() throws ConfigurationException {
        mCoverageOptionsSetter.setOptionValue("coverage", "true");
        mCoverageOptionsSetter.setOptionValue("coverage-toolchain", "GCOV_KERNEL");
    }
}