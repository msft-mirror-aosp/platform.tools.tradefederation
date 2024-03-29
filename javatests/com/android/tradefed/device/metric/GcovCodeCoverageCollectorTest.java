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

package com.android.tradefed.device.metric;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.android.tradefed.util.proto.TfMetricProtoUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** Unit tests for {@link GcovCodeCoverageCollector}. */
@RunWith(JUnit4.class)
public class GcovCodeCoverageCollectorTest {

    private static final String RUN_NAME = "SomeTest";
    private static final int TEST_COUNT = 5;
    private static final long ELAPSED_TIME = 1000;

    private static final String PS_OUTPUT =
            "USER       PID   PPID  VSZ   RSS   WCHAN       PC  S NAME\n"
                    + "shell       123  1366  123    456   SyS_epoll+   0  S adbd\n";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Mock IConfiguration mMockConfiguration;
    @Mock IInvocationContext mMockContext;
    @Mock ITestDevice mMockDevice;
    @Mock IRunUtil mMockRunUtil;

    LogFileReader mFakeListener = new LogFileReader();

    /** Options for coverage. */
    CoverageOptions mCoverageOptions = null;

    OptionSetter mCoverageOptionsSetter = null;

    /** Object under test. */
    GcovCodeCoverageCollector mCodeCoverageListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCoverageOptions = new CoverageOptions();
        mCoverageOptionsSetter = new OptionSetter(mCoverageOptions);

        doReturn(mCoverageOptions).when(mMockConfiguration).getCoverageOptions();
        doReturn(ImmutableList.of(mMockDevice)).when(mMockContext).getDevices();

        doReturn(PS_OUTPUT).when(mMockDevice).executeShellCommand("ps -e");

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setStdout("ffffffffffff\n");
        result.setExitCode(0);
        when(mMockDevice.executeShellV2Command(anyString())).thenReturn(result);

        mCodeCoverageListener = new GcovCodeCoverageCollector();
        mCodeCoverageListener.setConfiguration(mMockConfiguration);
        mCodeCoverageListener.setRunUtil(mMockRunUtil);
    }

    @Test
    public void test_logsCoverageZip() throws Exception {
        enableGcovCoverage();
        // Setup mocks to write the coverage measurement to the file.
        doReturn(true).when(mMockDevice).isAdbRoot();
        File tar =
                createTar(
                        ImmutableMap.of(
                                "path/to/coverage.gcda",
                                ByteString.copyFromUtf8("coverage.gcda"),
                                "path/to/.hidden/coverage2.gcda",
                                ByteString.copyFromUtf8("coverage2.gcda")));
        doReturn(tar).when(mMockDevice).pullFile(anyString());

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was called with the coverage file in a zip.
        List<ByteString> logs = mFakeListener.getLogs();
        assertThat(logs).hasSize(1);
        File outputZip = folder.newFile("coverage.zip");
        try (OutputStream out = new FileOutputStream(outputZip)) {
            logs.get(0).writeTo(out);
        }

        URI uri = URI.create(String.format("jar:file:%s", outputZip));
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, new HashMap<>())) {
            Path path1 = filesystem.getPath("/path/to/coverage.gcda");
            assertThat(ByteString.readFrom(Files.newInputStream(path1)))
                    .isEqualTo(ByteString.copyFromUtf8("coverage.gcda"));

            Path path2 = filesystem.getPath("/path/to/.hidden/coverage2.gcda");
            assertThat(ByteString.readFrom(Files.newInputStream(path2)))
                    .isEqualTo(ByteString.copyFromUtf8("coverage2.gcda"));
        }
    }

    @Test
    public void testNoCoverageFiles_logsEmptyZip() throws Exception {
        enableGcovCoverage();
        doReturn(true).when(mMockDevice).isAdbRoot();
        doReturn(createTar(ImmutableMap.of())).when(mMockDevice).pullFile(anyString());

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was called with an empty zip.
        List<ByteString> logs = mFakeListener.getLogs();
        assertThat(logs).hasSize(1);
        File outputZip = folder.newFile("empty_coverage.zip");
        try (OutputStream out = new FileOutputStream(outputZip)) {
            logs.get(0).writeTo(out);
        }

        try (ZipFile loggedZip = new ZipFile(outputZip)) {
            assertThat(loggedZip.size()).isEqualTo(0);
        }
    }

    @Test
    public void testCoverageFlushAllProcesses_flushAllCommandCalled() throws Exception {
        enableGcovCoverage();
        mCoverageOptionsSetter.setOptionValue("coverage-flush", "true");

        doReturn(true).when(mMockDevice).isAdbRoot();
        doReturn(createTar(ImmutableMap.of())).when(mMockDevice).pullFile(anyString());

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify the flush-coverage command was called twice - once on init(...) and once
        // on test run end.
        verify(mMockDevice, times(2)).executeShellCommand("kill -37 123");
    }

    @Test
    public void testCoverageFlushSpecificProcesses_flushCommandCalled() throws Exception {
        enableGcovCoverage();
        mCoverageOptionsSetter.setOptionValue("coverage-flush", "true");
        mCoverageOptionsSetter.setOptionValue("coverage-processes", "mediaserver");
        mCoverageOptionsSetter.setOptionValue("coverage-processes", "adbd");

        doReturn(true).when(mMockDevice).isAdbRoot();
        doReturn(createTar(ImmutableMap.of())).when(mMockDevice).pullFile(anyString());

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify the flush-coverage command was called with the specific pids twice - once on
        // init(...) and once on test run end.
        verify(mMockDevice, times(2)).executeShellCommand("kill -37 123");
    }

    @Test
    public void testFailure_unableToPullFile() throws Exception {
        enableGcovCoverage();
        // Setup mocks.
        doReturn(true).when(mMockDevice).isAdbRoot();

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);

        Map<String, String> metric = new HashMap<>();
            mCodeCoverageListener.testRunEnded(
                    ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify testLog(..) was not called.
        assertThat(mFakeListener.getLogs()).isEmpty();
    }

    @Test
    public void testNoCollectOnTestEnd_noCoverageMeasurements() throws Exception {
        enableGcovCoverage();
        mCodeCoverageListener.setCollectOnTestEnd(false);

        // Setup mocks.
        doReturn(true).when(mMockDevice).isAdbRoot();

        // Simulate a test run.
        mCodeCoverageListener.init(mMockContext, mFakeListener);
        mCodeCoverageListener.testRunStarted(RUN_NAME, TEST_COUNT);
        Map<String, String> metric = new HashMap<>();
        mCodeCoverageListener.testRunEnded(ELAPSED_TIME, TfMetricProtoUtil.upgradeConvert(metric));

        // Verify nothing was logged.
        assertThat(mFakeListener.getLogs()).isEmpty();

        // Setup mocks to write the coverage measurement to the file.
        doReturn(true).when(mMockDevice).enableAdbRoot();
        File tar =
                createTar(
                        ImmutableMap.of(
                                "path/to/coverage.gcda", ByteString.copyFromUtf8("coverage.gcda")));
        doReturn(tar).when(mMockDevice).pullFile(anyString());

        // Manually call logCoverageMeasurements().
        mCodeCoverageListener.logCoverageMeasurements(mMockDevice, "manual");

        // Verify testLog(..) was called with the coverage file in a zip.
        List<ByteString> logs = mFakeListener.getLogs();
        assertThat(logs).hasSize(1);
        File outputZip = folder.newFile("coverage.zip");
        try (OutputStream out = new FileOutputStream(outputZip)) {
            logs.get(0).writeTo(out);
        }

        URI uri = URI.create(String.format("jar:file:%s", outputZip));
        try (FileSystem filesystem = FileSystems.newFileSystem(uri, new HashMap<>())) {
            Path path = filesystem.getPath("/path/to/coverage.gcda");
            assertThat(ByteString.readFrom(Files.newInputStream(path)))
                    .isEqualTo(ByteString.copyFromUtf8("coverage.gcda"));
        }
    }

    @Test
    public void testInit_adbRootAndCoverageFlushed() throws Exception {
        enableGcovCoverage();

        // Setup mocks.
        when(mMockDevice.isAdbRoot()).thenReturn(false).thenReturn(true);
        when(mMockDevice.enableAdbRoot()).thenReturn(true);

        // Test init(...).
        mCodeCoverageListener.init(mMockContext, mFakeListener);

        InOrder inOrder = Mockito.inOrder(mMockDevice);
        inOrder.verify(mMockDevice).isAdbRoot();
        inOrder.verify(mMockDevice).enableAdbRoot();
        inOrder.verify(mMockDevice).executeShellCommand("kill -37 123");
        inOrder.verify(mMockDevice, times(4)).executeShellCommand(anyString());
        inOrder.verify(mMockDevice).disableAdbRoot();
    }

    /** An {@link ITestInvocationListener} which reads test log data streams for verification. */
    private static class LogFileReader implements ITestInvocationListener {
        private List<ByteString> mLogs = new ArrayList<>();

        /** Reads the contents of the {@code dataStream} and saves it in the logs. */
        @Override
        public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
            try (InputStream input = dataStream.createInputStream()) {
                mLogs.add(ByteString.readFrom(input));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        List<ByteString> getLogs() {
            return new ArrayList<>(mLogs);
        }
    }

    /** Utility method to create .tar files. */
    private File createTar(Map<String, ByteString> fileContents) throws IOException {
        File tarFile = folder.newFile("coverage.tar");
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

    private void enableGcovCoverage() throws ConfigurationException {
        mCoverageOptionsSetter.setOptionValue("coverage", "true");
        mCoverageOptionsSetter.setOptionValue("coverage-toolchain", "GCOV");
    }
}
