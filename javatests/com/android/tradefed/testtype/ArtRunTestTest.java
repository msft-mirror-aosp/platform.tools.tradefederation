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

package com.android.tradefed.testtype;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link ArtRunTest}. */
@RunWith(JUnit4.class)
public class ArtRunTestTest {

    @Mock ITestInvocationListener mMockInvocationListener;
    @Mock IAbi mMockAbi;
    @Mock ITestDevice mMockITestDevice;

    private ArtRunTest mArtRunTest;
    private OptionSetter mSetter;
    private TestInformation mTestInfo;
    // Test dependencies directory on host.
    private File mTmpDepsDir;
    // Expected standard output file (within the dependencies directory).
    private File mTmpExpectedStdoutFile;
    // Expected standard error file (within the dependencies directory).
    private File mTmpExpectedStderrFile;

    @Before
    public void setUp() throws ConfigurationException, IOException {
        mMockInvocationListener = mock(ITestInvocationListener.class);
        mMockAbi = mock(IAbi.class);
        mMockITestDevice = mock(ITestDevice.class);
        mArtRunTest = new ArtRunTest();
        mArtRunTest.setAbi(mMockAbi);
        mSetter = new OptionSetter(mArtRunTest);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockITestDevice);

        // Temporary test directory (e.g. for the expectation files).
        mTmpDepsDir = FileUtil.createTempDir("art-run-test-deps");
        mTestInfo =
                TestInformation.newBuilder()
                        .setInvocationContext(context)
                        .setDependenciesFolder(mTmpDepsDir)
                        .build();
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mTmpDepsDir);
    }

    /** Helper creating an expected standard output file within the (temporary) test directory. */
    private void createExpectedStdoutFile(String runTestName) throws IOException {
        mTmpExpectedStdoutFile = new File(mTmpDepsDir, runTestName + "-expected-stdout.txt");
        try (FileWriter fw = new FileWriter(mTmpExpectedStdoutFile)) {
            fw.write("output\n");
        }
    }

    /** Helper creating an expected standard error file within the (temporary) test directory. */
    private void createExpectedStderrFile(String runTestName) throws IOException {
        mTmpExpectedStderrFile = new File(mTmpDepsDir, runTestName + "-expected-stderr.txt");
        try (FileWriter fw = new FileWriter(mTmpExpectedStderrFile)) {
            fw.write("no error\n");
        }
    }

    /** Helper creating a mock CommandResult object. */
    private CommandResult createMockCommandResult(String stdout, String stderr, int exitCode) {
        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setStdout(stdout);
        result.setStderr(stderr);
        result.setExitCode(exitCode);
        return result;
    }

    /** Test the behavior of the run method when the `run-test-name` option is not set. */
    @Test
    public void testRunSingleTest_unsetRunTestNameOption()
            throws ConfigurationException, DeviceNotAvailableException {
        final String classpath = "/data/local/tmp/test/test.jar";
        mSetter.setOptionValue("classpath", classpath);

        try {
            mArtRunTest.run(mTestInfo, mMockInvocationListener);
            fail("An exception should have been thrown.");
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }

    /** Test the behavior of the run method when the `classpath` option is not set. */
    @Test
    public void testRunSingleTest_unsetClasspathOption()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);

        try {
            mArtRunTest.run(mTestInfo, mMockInvocationListener);
            fail("An exception should have been thrown.");
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }

    /** Helper containing testing logic for a (single) test expected to run (and succeed). */
    private void doTestRunSingleTest(final String runTestName, final String classpath)
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        mSetter.setOptionValue("classpath", classpath);

        // Pre-test checks.
        when(mMockAbi.getName()).thenReturn("abi");
        when(mMockITestDevice.getSerialNumber()).thenReturn("");
        String runName = "ArtRunTest_abi";
        // Beginning of test.

        TestDescription testId = new TestDescription(runName, runTestName);

        String cmd = String.format("dalvikvm64 -classpath %s Main", classpath);
        // Test execution.
        CommandResult result = createMockCommandResult("output\n", "no error\n", /* exitCode */ 0);
        when(mMockITestDevice.executeShellV2Command(cmd, 60000L, TimeUnit.MILLISECONDS, 0))
                .thenReturn(result);
        // End of test.

        mArtRunTest.run(mTestInfo, mMockInvocationListener);

        verify(mMockInvocationListener).testRunStarted(runName, 1);
        verify(mMockInvocationListener).testStarted(testId);
        verify(mMockInvocationListener)
                .testEnded(eq(testId), (HashMap<String, Metric>) Mockito.any());
        verify(mMockInvocationListener)
                .testRunEnded(Mockito.anyLong(), (HashMap<String, Metric>) Mockito.any());
    }

    /** Helper containing testing logic for a (single) test expected not to run. */
    private void doTestDoNotRunSingleTest(final String runTestName, final String classpath)
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        mSetter.setOptionValue("classpath", classpath);

        when(mMockAbi.getName()).thenReturn("abi");

        mArtRunTest.run(mTestInfo, mMockInvocationListener);
        verify(mMockAbi).getName();
    }

    /** Test the run method for a (single) test. */
    @Test
    public void testRunSingleTest()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        final String classpath = "/data/local/tmp/test/test.jar";

        doTestRunSingleTest(runTestName, classpath);
    }

    /**
     * Test the behavior of the run method when the shell command on device returns a non-zero exit
     * code.
     */
    @Test
    public void testRunSingleTest_nonZeroExitCode()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        final String classpath = "/data/local/tmp/test/test.jar";
        mSetter.setOptionValue("classpath", classpath);

        // Pre-test checks.
        when(mMockAbi.getName()).thenReturn("abi");
        when(mMockITestDevice.getSerialNumber()).thenReturn("");
        String runName = "ArtRunTest_abi";
        // Beginning of test.

        TestDescription testId = new TestDescription(runName, runTestName);

        String cmd = String.format("dalvikvm64 -classpath %s Main", classpath);
        // Test execution.
        CommandResult result = createMockCommandResult("output\n", "no error\n", /* exitCode */ 1);
        when(mMockITestDevice.executeShellV2Command(cmd, 60000L, TimeUnit.MILLISECONDS, 0))
                .thenReturn(result);

        mArtRunTest.run(mTestInfo, mMockInvocationListener);

        verify(mMockInvocationListener).testRunStarted(runName, 1);
        verify(mMockInvocationListener).testStarted(testId);
        verify(mMockInvocationListener).testFailed(testId, "Test `test` exited with code 1");
        verify(mMockInvocationListener)
                .testEnded(eq(testId), (HashMap<String, Metric>) Mockito.any());
        verify(mMockInvocationListener)
                .testRunEnded(Mockito.anyLong(), (HashMap<String, Metric>) Mockito.any());
    }

    /**
     * Test the behavior of the run method when the standard output produced by the shell command on
     * device differs from the expected standard output.
     */
    @Test
    public void testRunSingleTest_unexpectedStandardOutput()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        final String classpath = "/data/local/tmp/test/test.jar";
        mSetter.setOptionValue("classpath", classpath);

        // Pre-test checks.
        when(mMockAbi.getName()).thenReturn("abi");
        when(mMockITestDevice.getSerialNumber()).thenReturn("");
        String runName = "ArtRunTest_abi";
        // Beginning of test.

        TestDescription testId = new TestDescription(runName, runTestName);

        String cmd = String.format("dalvikvm64 -classpath %s Main", classpath);
        // Test execution.
        CommandResult result =
                createMockCommandResult("unexpected\n", "no error\n", /* exitCode */ 0);
        when(mMockITestDevice.executeShellV2Command(cmd, 60000L, TimeUnit.MILLISECONDS, 0))
                .thenReturn(result);
        // End of test.
        String errorMessage =
                "The actual standard output does not match the expected standard output"
                        + " for test `test`:\n"
                        + "--- expected-stdout.txt\n"
                        + "+++ stdout\n"
                        + "@@ -1,1 +1,1 @@\n"
                        + "-output\n"
                        + "+unexpected\n";

        mArtRunTest.run(mTestInfo, mMockInvocationListener);

        verify(mMockInvocationListener).testRunStarted(runName, 1);
        verify(mMockInvocationListener).testStarted(testId);
        verify(mMockInvocationListener).testFailed(testId, errorMessage);
        verify(mMockInvocationListener)
                .testEnded(eq(testId), (HashMap<String, Metric>) Mockito.any());
        verify(mMockInvocationListener)
                .testRunEnded(Mockito.anyLong(), (HashMap<String, Metric>) Mockito.any());
    }

    /**
     * Test the behavior of the run method when the standard error produced by the shell command on
     * device differs from the expected standard error.
     */
    @Test
    public void testRunSingleTest_unexpectedStandardError()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        final String classpath = "/data/local/tmp/test/test.jar";
        mSetter.setOptionValue("classpath", classpath);

        // Pre-test checks.
        when(mMockAbi.getName()).thenReturn("abi");
        when(mMockITestDevice.getSerialNumber()).thenReturn("");
        String runName = "ArtRunTest_abi";
        // Beginning of test.

        TestDescription testId = new TestDescription(runName, runTestName);

        String cmd = String.format("dalvikvm64 -classpath %s Main", classpath);
        // Test execution.
        CommandResult result =
                createMockCommandResult("output\n", "unexpected error\n", /* exitCode */ 0);
        when(mMockITestDevice.executeShellV2Command(cmd, 60000L, TimeUnit.MILLISECONDS, 0))
                .thenReturn(result);
        // End of test.
        String errorMessage =
                "The actual standard error does not match the expected standard error"
                        + " for test `test`:\n"
                        + "--- expected-stderr.txt\n"
                        + "+++ stderr\n"
                        + "@@ -1,1 +1,1 @@\n"
                        + "-no error\n"
                        + "+unexpected error\n";

        mArtRunTest.run(mTestInfo, mMockInvocationListener);

        verify(mMockInvocationListener).testRunStarted(runName, 1);
        verify(mMockInvocationListener).testStarted(testId);
        verify(mMockInvocationListener).testFailed(testId, errorMessage);
        verify(mMockInvocationListener)
                .testEnded(eq(testId), (HashMap<String, Metric>) Mockito.any());
        verify(mMockInvocationListener)
                .testRunEnded(Mockito.anyLong(), (HashMap<String, Metric>) Mockito.any());
    }

    /**
     * Test the behavior of the run method when a test execution leads to multiple errors
     * (unexpected standard output, unexpected standard error, non-zero exit code).
     */
    @Test
    public void testRunSingleTest_multipleErrors()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        mSetter.setOptionValue("run-test-name", runTestName);
        createExpectedStdoutFile(runTestName);
        createExpectedStderrFile(runTestName);
        final String classpath = "/data/local/tmp/test/test.jar";
        mSetter.setOptionValue("classpath", classpath);

        // Pre-test checks.
        when(mMockAbi.getName()).thenReturn("abi");
        when(mMockITestDevice.getSerialNumber()).thenReturn("");
        String runName = "ArtRunTest_abi";
        // Beginning of test.

        TestDescription testId = new TestDescription(runName, runTestName);

        String cmd = String.format("dalvikvm64 -classpath %s Main", classpath);
        // Test execution.
        CommandResult result =
                createMockCommandResult("unexpected\n", "unexpected error\n", /* exitCode */ 2);
        when(mMockITestDevice.executeShellV2Command(cmd, 60000L, TimeUnit.MILLISECONDS, 0))
                .thenReturn(result);
        // End of test.
        String errorMessage =
                "Test `test` exited with code 2\n"
                        + "The actual standard output does not match the expected standard output"
                        + " for test `test`:\n"
                        + "--- expected-stdout.txt\n"
                        + "+++ stdout\n"
                        + "@@ -1,1 +1,1 @@\n"
                        + "-output\n"
                        + "+unexpected\n"
                        + "\n"
                        + "The actual standard error does not match the expected standard error"
                        + " for test `test`:\n"
                        + "--- expected-stderr.txt\n"
                        + "+++ stderr\n"
                        + "@@ -1,1 +1,1 @@\n"
                        + "-no error\n"
                        + "+unexpected error\n";

        mArtRunTest.run(mTestInfo, mMockInvocationListener);

        verify(mMockInvocationListener).testRunStarted(runName, 1);
        verify(mMockInvocationListener).testStarted(testId);
        verify(mMockInvocationListener).testFailed(testId, errorMessage);
        verify(mMockInvocationListener)
                .testEnded(eq(testId), (HashMap<String, Metric>) Mockito.any());
        verify(mMockInvocationListener)
                .testRunEnded(Mockito.anyLong(), (HashMap<String, Metric>) Mockito.any());
    }

    /** Test the run method for a (single) test contained in an include filter. */
    @Test
    public void testIncludeFilter()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        final String classpath = "/data/local/tmp/test/test.jar";
        // Add an include filter containing the test's name.
        mArtRunTest.addIncludeFilter(runTestName);

        doTestRunSingleTest(runTestName, classpath);
    }

    /** Test the run method for a (single) test contained in an exclude filter. */
    @Test
    public void testExcludeFilter()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        final String classpath = "/data/local/tmp/test/test.jar";
        // Add an exclude filter containing the test's name.
        mArtRunTest.addExcludeFilter(runTestName);

        doTestDoNotRunSingleTest(runTestName, classpath);
    }

    /**
     * Test the run method for a (single) test contained both in an include and an exclude filter.
     */
    @Test
    public void testIncludeAndExcludeFilter()
            throws ConfigurationException, DeviceNotAvailableException, IOException {
        final String runTestName = "test";
        final String classpath = "/data/local/tmp/test/test.jar";
        // Add an include filter containing the test's name.
        mArtRunTest.addIncludeFilter(runTestName);
        // Add an exclude filter containing the test's name.
        mArtRunTest.addExcludeFilter(runTestName);

        doTestDoNotRunSingleTest(runTestName, classpath);
    }
}
