/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.command.CommandInterrupter;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.IRunUtil.EnvPriority;
import com.android.tradefed.util.IRunUtil.IRunnableResult;
import com.android.tradefed.util.RunUtil.RunnableResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link RunUtil} */
@RunWith(JUnit4.class)
public class RunUtilTest {

    private RunUtil mRunUtil;
    private RunnableResult mMockRunnableResult;
    private long mSleepTime = 0L;
    private File mWorkingDir;
    private static final long VERY_SHORT_TIMEOUT_MS = 10L;
    private static final long SHORT_TIMEOUT_MS = 200L;
    private static final long LONG_TIMEOUT_MS = 1000L;
    // Timeout to ensure that IO depend tests have enough time to finish. They should not use the
    // full duration in most cases.
    private static final long VERY_LONG_TIMEOUT_MS = 5000L;

    @Before
    public void setUp() throws Exception {
        mRunUtil = new RunUtil(new CommandInterrupter());
        mRunUtil.setPollingInterval(SHORT_TIMEOUT_MS);
        mMockRunnableResult = null;
        mWorkingDir = FileUtil.createTempDir("working_dir_");
    }

    @After
    public void tearDown() {
        // clear interrupted status
        Thread.interrupted();
        FileUtil.recursiveDelete(mWorkingDir);
    }

    /** Test class on {@link RunUtil} in order to avoid creating a real process. */
    class SpyRunUtil extends RunUtil {
        private boolean mShouldThrow = false;

        public SpyRunUtil(boolean shouldThrow) {
            mShouldThrow = shouldThrow;
        }

        @Override
        RunnableResult createRunnableResult(
                OutputStream stdout, OutputStream stderr, ProcessBuilder processBuilder) {
            RunnableResult real = super.createRunnableResult(stdout, stderr, processBuilder);
            mMockRunnableResult = Mockito.spy(real);
            try {
                if (mShouldThrow) {
                    // Test if the binary does not exists, startProcess throws directly in this case
                    doThrow(
                                    new RuntimeException(
                                            String.format(
                                                    "Cannot run program \"%s\": error=2,"
                                                            + "No such file or directory",
                                                    processBuilder.command().get(0))))
                            .when(mMockRunnableResult)
                            .startProcess();
                } else {
                    doReturn(new FakeProcess()).when(mMockRunnableResult).startProcess();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return mMockRunnableResult;
        }
    }

    /** Test class on {@link RunUtil} in order to monitor the real process. */
    class MonitoredRunUtil extends RunUtil {
        public ProcessBuilder processBuilder;

        public MonitoredRunUtil(boolean inheritEnvVars) {
            super(inheritEnvVars);
        }

        @Override
        RunnableResult createRunnableResult(
                OutputStream stdout, OutputStream stderr, ProcessBuilder processBuilder) {
            this.processBuilder = processBuilder;
            return super.createRunnableResult(stdout, stderr, processBuilder);
        }
    }

    /** Test success case for {@link RunUtil#runTimed(long, IRunnableResult, boolean)}. */
    @Test
    public void testRunTimed() throws Exception {
        IRunUtil.IRunnableResult mockRunnable = mock(IRunUtil.IRunnableResult.class);
        when(mockRunnable.getCommand()).thenReturn(new ArrayList<>());
        when(mockRunnable.run()).thenReturn(Boolean.TRUE);

        assertEquals(
                CommandStatus.SUCCESS, mRunUtil.runTimed(SHORT_TIMEOUT_MS, mockRunnable, true));
    }

    /** Test failure case for {@link RunUtil#runTimed(long, IRunnableResult, boolean)}. */
    @Test
    public void testRunTimed_failed() throws Exception {
        IRunUtil.IRunnableResult mockRunnable = mock(IRunUtil.IRunnableResult.class);
        when(mockRunnable.getCommand()).thenReturn(new ArrayList<>());
        when(mockRunnable.run()).thenReturn(Boolean.FALSE);

        assertEquals(CommandStatus.FAILED, mRunUtil.runTimed(SHORT_TIMEOUT_MS, mockRunnable, true));
    }

    /** Test exception case for {@link RunUtil#runTimed(long, IRunnableResult, boolean)}. */
    @Test
    public void testRunTimed_exception() throws Exception {
        IRunUtil.IRunnableResult mockRunnable = mock(IRunUtil.IRunnableResult.class);
        when(mockRunnable.getCommand()).thenReturn(new ArrayList<>());
        when(mockRunnable.run()).thenThrow(new RuntimeException());
        when(mockRunnable.getResult()).thenReturn(null);

        assertEquals(
                CommandStatus.EXCEPTION,
                mRunUtil.runTimed(VERY_LONG_TIMEOUT_MS, mockRunnable, true));
    }

    /** Test interrupted case for {@link RunUtil#runTimed(long, IRunnableResult, boolean)}. */
    @Test
    public void testRunTimed_interrupted() {
        IRunnableResult runnable = Mockito.mock(IRunnableResult.class);
        CommandInterrupter interrupter = Mockito.mock(CommandInterrupter.class);
        RunUtil runUtil = new RunUtil(interrupter);

        // interrupted during execution
        doNothing().doThrow(RunInterruptedException.class).when(interrupter).checkInterrupted();

        try {
            runUtil.runTimed(VERY_SHORT_TIMEOUT_MS, runnable, true);
            fail("RunInterruptedException was expected, but not thrown.");
        } catch (RunInterruptedException e) {
            // Execution was cancelled due to interruption
            Mockito.verify(runnable, Mockito.atLeast(1)).cancel();
        }
    }

    /** Test that {@link RunUtil#runTimedCmd(long, String[])} fails when given a garbage command. */
    @Test
    public void testRunTimedCmd_failed() {
        RunUtil spyUtil = new SpyRunUtil(true);
        CommandResult result = spyUtil.runTimedCmd(VERY_LONG_TIMEOUT_MS, "blahggggwarggg");
        assertEquals(CommandStatus.EXCEPTION, result.getStatus());
        assertEquals("", result.getStdout());
        assertTrue(result.getStderr().contains("Cannot run program \"blahggggwarggg\""));
    }

    /**
     * Test {@link RunUtil#runTimedCmd(long, String[])} exits with status SUCCESS since the output
     * monitor observed output on streams through the command time until finished.
     */
    @Test
    public void testRunTimed_output_monitor() {
        // Long-running operation with changing output stream.
        String[] command = {"/bin/bash", "-c", "for i in {1..5}; do echo hello; sleep 1; done"};

        // Should succeed and return sooner regardless of timeout.
        CommandResult result =
                mRunUtil.runTimedCmdWithOutputMonitor(VERY_LONG_TIMEOUT_MS * 5, 1200, command);
        assertEquals(CommandStatus.SUCCESS, result.getStatus());
    }

    /**
     * Test {@link RunUtil#runTimedCmd(long, String[])} exits with status FAILED due to the output
     * monitor not observing any output on the streams.
     */
    @Test
    public void testRunTimed_output_monitor_failed() {
        // Long-running operation with no output sent to stream.
        String[] command = {"sleep", String.valueOf(VERY_LONG_TIMEOUT_MS * 5)};

        // Should fail and return sooner regardless of timeout.
        CommandResult result =
                mRunUtil.runTimedCmdWithOutputMonitor(VERY_LONG_TIMEOUT_MS * 5, 1200, command);
        assertEquals(CommandStatus.FAILED, result.getStatus());
    }

    /**
     * Test {@link RunUtil#runTimedCmd(long, String[])} exits with status TIMED_OUT even if the
     * output monitor is observing new output on the output streams since the timeout is short.
     */
    @Test
    public void testRunTimed_output_monitor_timeout() {
        // Long-running operation with no output.
        String[] command = {"sleep", String.valueOf(VERY_LONG_TIMEOUT_MS * 5)};

        // Should run out of time and timeout.
        CommandResult result =
                mRunUtil.runTimedCmdWithOutputMonitor(
                        SHORT_TIMEOUT_MS, SHORT_TIMEOUT_MS * 2, command);
        assertEquals(CommandStatus.TIMED_OUT, result.getStatus());
    }

    /**
     * Test that {@link RunUtil#runTimedCmdWithInput(long, String, File, File, String...)} properly
     * backfill errors.
     */
    @Test
    public void testRunTimedCmdWithInput_failed() throws Exception {
        RunUtil spyUtil = new SpyRunUtil(true);
        File stdout = FileUtil.createTempFile("stdout-test", "txt");
        File stderr = FileUtil.createTempFile("stderr-test", "txt");
        try {
            CommandResult result =
                    spyUtil.runTimedCmdWithInput(
                            VERY_LONG_TIMEOUT_MS, null, stdout, stderr, "blahggggwarggg");
            assertEquals(CommandStatus.EXCEPTION, result.getStatus());
            assertEquals("", result.getStdout());
            assertTrue(result.getStderr().contains("Cannot run program \"blahggggwarggg\""));
            // Error was backfilled in stderr file
            assertTrue(
                    FileUtil.readStringFromFile(stderr)
                            .contains("Cannot run program \"blahggggwarggg\""));
        } finally {
            FileUtil.deleteFile(stdout);
            FileUtil.deleteFile(stderr);
        }
    }

    /**
     * Test that {@link RunUtil#runTimedCmd(long, String[])} is returning timed out state when the
     * command does not return in time.
     */
    @Test
    public void testRunTimedCmd_timeout() {
        String[] command = {"sleep", "10000"};
        CommandResult result = mRunUtil.runTimedCmd(VERY_SHORT_TIMEOUT_MS, command);
        assertEquals(CommandStatus.TIMED_OUT, result.getStatus());
        assertEquals("", result.getStdout());
        assertEquals("", result.getStderr());
    }

    /**
     * Verify that calling {@link RunUtil#setWorkingDir(File)} is not allowed on default instance.
     */
    @Test
    public void testSetWorkingDir_default() {
        try {
            RunUtil.getDefault().setWorkingDir(new File("foo"));
            fail("could set working dir on RunUtil.getDefault()");
        } catch (RuntimeException e) {
            // expected
        }
    }

    /**
     * Verify that calling {@link RunUtil#setEnvVariable(String, String)} is not allowed on default
     * instance.
     */
    @Test
    public void testSetEnvVariable_default() {
        try {
            RunUtil.getDefault().setEnvVariable("foo", "bar");
            fail("could set env var on RunUtil.getDefault()");
        } catch (RuntimeException e) {
            // expected
        }
    }

    /**
     * Verify that calling {@link RunUtil#unsetEnvVariable(String)} is not allowed on default
     * instance.
     */
    @Test
    public void testUnsetEnvVariable_default() {
        try {
            RunUtil.getDefault().unsetEnvVariable("foo");
            fail("could unset env var on RunUtil.getDefault()");
        } catch (Exception e) {
            // expected
        }
    }

    /**
     * Test that {@link RunUtil#runEscalatingTimedRetry(long, long, long, long, IRunnableResult)}
     * fails when operation continually fails, and that the maxTime variable is respected.
     */
    @Test
    public void testRunEscalatingTimedRetry_timeout() throws Exception {
        // create a RunUtil fixture with methods mocked out for
        // fast execution
        RunUtil runUtil =
                new RunUtil() {
                    @Override
                    public void sleep(long time) {
                        mSleepTime += time;
                    }

                    @Override
                    long getCurrentTime() {
                        return mSleepTime;
                    }

                    @Override
                    public CommandStatus runTimed(
                            long timeout, IRunUtil.IRunnableResult runnable, boolean logErrors) {
                        try {
                            // override parent with simple version that doesn't create a thread
                            return runnable.run() ? CommandStatus.SUCCESS : CommandStatus.FAILED;
                        } catch (Exception e) {
                            return CommandStatus.EXCEPTION;
                        }
                    }
                };

        IRunUtil.IRunnableResult mockRunnable = mock(IRunUtil.IRunnableResult.class);
        // expect a call 4 times, at sleep time 0, 1, 4 and 10 ms
        when(mockRunnable.run()).thenReturn(Boolean.FALSE);

        long maxTime = 12;
        assertFalse(runUtil.runEscalatingTimedRetry(1, 1, 512, maxTime, mockRunnable));
        assertEquals(maxTime, mSleepTime);
        verify(mockRunnable, Mockito.atLeast(3)).run();
    }

    /** Test a success case for {@link RunUtil#interrupt}. */
    @Test
    public void testInterrupt() {
        final String message = "it is alright now";
        mRunUtil.allowInterrupt(true);
        try {
            mRunUtil.interrupt(
                    Thread.currentThread(), message, InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
            fail("RunInterruptedException was expected, but not thrown.");
        } catch (final RunInterruptedException e) {
            assertEquals(message, e.getMessage());
        }
    }

    /**
     * Test whether a {@link RunUtil#interrupt} call is respected when called while interrupts are
     * not allowed.
     */
    @Test
    public void testInterrupt_delayed() {
        final String message = "it is alright now";
        try {
            mRunUtil.allowInterrupt(false);
            mRunUtil.interrupt(
                    Thread.currentThread(), message, InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
            mRunUtil.sleep(1);
            mRunUtil.allowInterrupt(true);
            mRunUtil.sleep(1);
            fail("RunInterruptedException was expected, but not thrown.");
        } catch (final RunInterruptedException e) {
            assertEquals(message, e.getMessage());
        }
    }

    /** Test whether a {@link RunUtil#interrupt} call is respected when called multiple times. */
    @Test
    public void testInterrupt_multiple() {
        final String message1 = "it is alright now";
        final String message2 = "without a fight";
        final String message3 = "rock this town";
        mRunUtil.allowInterrupt(true);
        try {
            mRunUtil.interrupt(
                    Thread.currentThread(), message1, InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
            mRunUtil.interrupt(
                    Thread.currentThread(), message2, InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
            mRunUtil.interrupt(
                    Thread.currentThread(), message3, InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
            fail("RunInterruptedException was expected, but not thrown.");
        } catch (final RunInterruptedException e) {
            assertEquals(message1, e.getMessage());
        }
    }

    /**
     * Test whether a {@link RunUtil#runTimedCmd(long, OutputStream, OutputStream, String[])} call
     * correctly redirect the output to files.
     */
    @Test
    public void testRuntimedCmd_withFileOutputStream() {
        File stdout = null;
        File stderr = null;
        OutputStream stdoutStream = null;
        OutputStream stderrStream = null;
        try {
            stdout = FileUtil.createTempFile("stdout_subprocess_", ".txt");
            stdoutStream = new FileOutputStream(stdout);
            stderr = FileUtil.createTempFile("stderr_subprocess_", ".txt");
            stderrStream = new FileOutputStream(stderr);
        } catch (IOException e) {
            fail("Failed to create output files: " + e.getMessage());
        }
        RunUtil spyUtil = new SpyRunUtil(false);
        String[] command = {"unused", "cmd"};
        CommandResult result =
                spyUtil.runTimedCmd(LONG_TIMEOUT_MS, stdoutStream, stderrStream, command);
        assertEquals(CommandStatus.SUCCESS, result.getStatus());
        assertEquals(
                result.getStdout(), "redirected to " + stdoutStream.getClass().getSimpleName());
        assertEquals(
                result.getStderr(), "redirected to " + stderrStream.getClass().getSimpleName());
        assertTrue(stdout.exists());
        assertTrue(stderr.exists());
        try {
            assertEquals("TEST STDOUT\n", FileUtil.readStringFromFile(stdout));
            assertEquals("TEST STDERR\n", FileUtil.readStringFromFile(stderr));
        } catch (IOException e) {
            fail(e.getMessage());
        } finally {
            FileUtil.deleteFile(stdout);
            FileUtil.deleteFile(stderr);
        }
    }

    /**
     * Test whether a {@link RunUtil#runTimedCmd(long, OutputStream, OutputStream, String[])} call
     * correctly redirect the output to stdout because files are null. Replace the process by a fake
     * one to avoid waiting on real system IO.
     */
    @Test
    public void testRuntimedCmd_regularOutput_fileNull() {
        String[] command = {"echo", "TEST STDOUT"};
        CommandResult result = mRunUtil.runTimedCmd(VERY_LONG_TIMEOUT_MS, null, null, command);
        assertEquals(CommandStatus.SUCCESS, result.getStatus());
        assertEquals("TEST STDOUT\n", result.getStdout());
        assertEquals("", result.getStderr());
    }

    /**
     * Test whether a {@link RunUtil#runTimedCmd(long, OutputStream, OutputStream, String[])}
     * redirect to the file even if they become non-writable afterward.
     */
    @Test
    public void testRuntimedCmd_notWritable() {
        File stdout = null;
        File stderr = null;
        OutputStream stdoutStream = null;
        OutputStream stderrStream = null;
        try {
            stdout = FileUtil.createTempFile("stdout_subprocess_", ".txt");
            stdoutStream = new FileOutputStream(stdout);
            stdout.setWritable(false);
            stderr = FileUtil.createTempFile("stderr_subprocess_", ".txt");
            stderrStream = new FileOutputStream(stderr);
            stderr.setWritable(false);
        } catch (IOException e) {
            fail("Failed to create output files: " + e.getMessage());
        }
        RunUtil spyUtil = new SpyRunUtil(false);
        String[] command = {"unused", "cmd"};
        CommandResult result =
                spyUtil.runTimedCmd(LONG_TIMEOUT_MS, stdoutStream, stderrStream, command);
        try {
            assertEquals(CommandStatus.SUCCESS, result.getStatus());
            assertEquals(
                    result.getStdout(), "redirected to " + stdoutStream.getClass().getSimpleName());
            assertEquals(
                    result.getStderr(), "redirected to " + stderrStream.getClass().getSimpleName());
            assertTrue(stdout.exists());
            assertTrue(stderr.exists());
            assertEquals("TEST STDOUT\n", FileUtil.readStringFromFile(stdout));
            assertEquals("TEST STDERR\n", FileUtil.readStringFromFile(stderr));
        } catch (IOException e) {
            fail(e.getMessage());
        } finally {
            stdout.setWritable(true);
            stderr.setWritable(true);
            FileUtil.deleteFile(stdout);
            FileUtil.deleteFile(stderr);
        }
    }

    /**
     * Test whether a {@link RunUtil#setInterruptibleInFuture} change properly the interruptible
     * state.
     */
    @Test
    public void testSetInterruptibleInFuture() {
        CommandInterrupter interrupter = Mockito.mock(CommandInterrupter.class);
        RunUtil runUtil = new RunUtil(interrupter);

        Thread thread = new Thread();
        runUtil.setInterruptibleInFuture(thread, 123L);

        // RunUtil delegates to CommandInterrupter#allowInterruptAsync
        Mockito.verify(interrupter)
                .allowInterruptAsync(
                        Mockito.eq(thread), Mockito.eq(123L), Mockito.eq(TimeUnit.MILLISECONDS));
        Mockito.verifyNoMoreInteractions(interrupter);
    }

    /** Test {@link RunUtil#setEnvVariablePriority(EnvPriority)} properly prioritize unset. */
    @Test
    public void testUnsetPriority() {
        final String ENV_NAME = "TF_GLO";
        RunUtil testRunUtil = new RunUtil();
        testRunUtil.setEnvVariablePriority(EnvPriority.UNSET);
        testRunUtil.setEnvVariable(ENV_NAME, "initvalue");
        testRunUtil.unsetEnvVariable(ENV_NAME);
        CommandResult result =
                testRunUtil.runTimedCmd(
                        VERY_LONG_TIMEOUT_MS, "/bin/bash", "-c", "echo $" + ENV_NAME);
        assertNotNull(result.getStdout());
        // Variable should be unset, some echo return empty line break.
        assertEquals("\n", result.getStdout());
    }

    /** Test {@link RunUtil#setEnvVariablePriority(EnvPriority)} properly prioritize set. */
    @Test
    public void testUnsetPriority_inverted() {
        final String ENV_NAME = "TF_GLO";
        final String expected = "initvalue";
        RunUtil testRunUtil = new RunUtil();
        testRunUtil.setEnvVariablePriority(EnvPriority.SET);
        testRunUtil.setEnvVariable(ENV_NAME, expected);
        testRunUtil.unsetEnvVariable(ENV_NAME);
        CommandResult result =
                testRunUtil.runTimedCmd(
                        VERY_LONG_TIMEOUT_MS, "/bin/bash", "-c", "echo $" + ENV_NAME);
        assertNotNull(result.getStdout());
        // Variable should be set and returned.
        assertEquals(expected + "\n", result.getStdout());
    }

    @Test
    public void testGotExitCodeFromCommand() {
        RunUtil testRunUtil = new RunUtil();
        CommandResult result =
                testRunUtil.runTimedCmd(VERY_LONG_TIMEOUT_MS, "/bin/bash", "-c", "exit 2");
        assertEquals("", result.getStdout());
        assertEquals("", result.getStderr());
        assertEquals(2, (int) result.getExitCode());
    }

    @Test
    public void testSetRedirectStderrToStdout() {
        RunUtil testRunUtil = new RunUtil();
        testRunUtil.setRedirectStderrToStdout(true);
        CommandResult result =
                testRunUtil.runTimedCmd(
                        VERY_LONG_TIMEOUT_MS,
                        "/bin/bash",
                        "-c",
                        "echo 'TEST STDOUT'; echo 'TEST STDERR' >&2");
        assertEquals("TEST STDOUT\nTEST STDERR\n", result.getStdout());
        assertEquals("", result.getStderr());
    }

    /**
     * Implementation of {@link Process} to simulate a success of a command that echos to both
     * stdout and stderr without actually calling the underlying system.
     */
    private class FakeProcess extends Process {

        @Override
        public int waitFor() throws InterruptedException {
            return 0;
        }

        @Override
        public OutputStream getOutputStream() {
            return null;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream("TEST STDOUT\n".getBytes());
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream("TEST STDERR\n".getBytes());
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // ignore
        }
    }
}
