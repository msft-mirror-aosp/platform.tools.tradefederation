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


import com.android.annotations.Nullable;
import com.android.tradefed.command.CommandInterrupter;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.ErrorIdentifier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

/**
 * A collection of helper methods for executing operations.
 */
public class RunUtil implements IRunUtil {

    public static final String RUNNABLE_NOTIFIER_NAME = "RunnableNotifier";
    public static final String INHERITIO_PREFIX = "inheritio-";

    private static final int POLL_TIME_INCREASE_FACTOR = 4;
    private static final long THREAD_JOIN_POLL_INTERVAL = 30 * 1000;
    private static final long PROCESS_DESTROY_TIMEOUT_SEC = 2;
    private long mPollingInterval = THREAD_JOIN_POLL_INTERVAL;
    private static IRunUtil sDefaultInstance = null;
    private File mWorkingDir = null;
    private Map<String, String> mEnvVariables = new HashMap<String, String>();
    private Set<String> mUnsetEnvVariables = new HashSet<String>();
    private EnvPriority mEnvVariablePriority = EnvPriority.UNSET;
    private boolean mRedirectStderr = false;
    private boolean mLinuxInterruptProcess = false;

    private final CommandInterrupter mInterrupter;
    private final boolean mInheritEnvVars;

    /**
     * Create a new {@link RunUtil} object to use.
     */
    public RunUtil() {
        this(CommandInterrupter.INSTANCE);
    }

    public RunUtil(boolean inheritEnvVars) {
        this(CommandInterrupter.INSTANCE, inheritEnvVars);
    }

    @VisibleForTesting
    RunUtil(@Nonnull CommandInterrupter interrupter) {
        this(interrupter, true);
    }

    private RunUtil(@Nonnull CommandInterrupter interrupter, boolean inheritEnvVars) {
        mInterrupter = interrupter;
        mInheritEnvVars = inheritEnvVars;
    }

    /**
     * Get a reference to the default {@link RunUtil} object.
     * <p/>
     * This is useful for callers who want to use IRunUtil without customization.
     * Its recommended that callers who do need a custom IRunUtil instance
     * (ie need to call either {@link #setEnvVariable(String, String)} or
     * {@link #setWorkingDir(File)} create their own copy.
     */
    public static IRunUtil getDefault() {
        if (sDefaultInstance == null) {
            sDefaultInstance = new RunUtil();
        }
        return sDefaultInstance;
    }

    /**
     * Sets a new value for the internal polling interval. In most cases, you should never have a
     * need to change the polling interval except for specific cases such as unit tests.
     *
     * @param pollInterval in ms for polling interval. Must be larger than 100ms.
     */
    @VisibleForTesting
    void setPollingInterval(long pollInterval) {
        if (pollInterval >= 100) {
            mPollingInterval = pollInterval;
            return;
        }
        throw new IllegalArgumentException("Polling interval set too low. Try 100ms.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setWorkingDir(File dir) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException("Cannot setWorkingDir on default RunUtil");
        }
        mWorkingDir = dir;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setEnvVariable(String name, String value) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException("Cannot setEnvVariable on default RunUtil");
        }
        mEnvVariables.put(name, value);
    }

    /**
     * {@inheritDoc}
     * Environment variables may inherit from the parent process, so we need to delete
     * the environment variable from {@link ProcessBuilder#environment()}
     *
     * @param key the variable name
     * @see ProcessBuilder#environment()
     */
    @Override
    public synchronized void unsetEnvVariable(String key) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException("Cannot unsetEnvVariable on default RunUtil");
        }
        mUnsetEnvVariables.add(key);
    }

    /** {@inheritDoc} */
    @Override
    public void setRedirectStderrToStdout(boolean redirect) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException(
                    "Cannot setRedirectStderrToStdout on default RunUtil");
        }
        mRedirectStderr = redirect;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandResult runTimedCmd(final long timeout, final String... command) {
        return runTimedCmd(timeout, (OutputStream) null, (OutputStream) null, command);
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdWithOutputMonitor(
            final long timeout, final long idleOutputTimeout, final String... command) {
        return runTimedCmdWithOutputMonitor(
                timeout, idleOutputTimeout, (OutputStream) null, (OutputStream) null, command);
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmd(
            final long timeout,
            final OutputStream stdout,
            OutputStream stderr,
            final String... command) {
        return runTimedCmdWithOutputMonitor(timeout, 0, stdout, stderr, command);
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdWithOutputMonitor(
            final long timeout,
            final long idleOutputTimeout,
            final OutputStream stdout,
            OutputStream stderr,
            final String... command) {
        ProcessBuilder processBuilder = createProcessBuilder(command);
        RunnableResult osRunnable = createRunnableResult(stdout, stderr, processBuilder);
        CommandStatus status =
                runTimedWithOutputMonitor(timeout, idleOutputTimeout, osRunnable, true);
        CommandResult result = osRunnable.getResult();
        result.setStatus(status);
        return result;
    }

    /**
     * Create a {@link com.android.tradefed.util.IRunUtil.IRunnableResult} that will run the
     * command.
     */
    @VisibleForTesting
    RunnableResult createRunnableResult(
            OutputStream stdout, OutputStream stderr, ProcessBuilder processBuilder) {
        return new RunnableResult(
                /* input= */ null,
                processBuilder,
                stdout,
                stderr,
                /* inputRedirect= */ null,
                false);
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdRetry(
            long timeout, long retryInterval, int attempts, String... command) {
        return runTimedCmdRetryWithOutputMonitor(timeout, 0, retryInterval, attempts, command);
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdRetryWithOutputMonitor(
            long timeout,
            long idleOutputTimeout,
            long retryInterval,
            int attempts,
            String... command) {
        CommandResult result = null;
        int counter = 0;
        while (counter < attempts) {
            result = runTimedCmdWithOutputMonitor(timeout, idleOutputTimeout, command);
            if (CommandStatus.SUCCESS.equals(result.getStatus())) {
                return result;
            }
            sleep(retryInterval);
            counter++;
        }
        return result;
    }

    private synchronized ProcessBuilder createProcessBuilder(String... command) {
        return createProcessBuilder(Arrays.asList(command));
    }

    private synchronized ProcessBuilder createProcessBuilder(Redirect redirect, String... command) {
        return createProcessBuilder(redirect, Arrays.asList(command), false);
    }

    private synchronized ProcessBuilder createProcessBuilder(List<String> commandList) {
        return createProcessBuilder(null, commandList, false);
    }

    public synchronized ProcessBuilder createProcessBuilder(
            Redirect redirect, List<String> commandList, boolean enableCache) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        if (!mInheritEnvVars) {
            processBuilder.environment().clear();
        }

        if (mWorkingDir != null) {
            processBuilder.directory(mWorkingDir);
        }
        Map<String, String> env = mEnvVariables;
        if (enableCache) {
            File workingDir =
                    processBuilder.directory() != null
                            ? processBuilder.directory()
                            : new File(System.getProperty("user.dir"));
            for (int i = 0; i < commandList.size(); i++) {
                String prefix = i < 1 ? "./" : "";
                commandList.set(i, prefix + toRelative(workingDir, commandList.get(i)));
            }
            for (Map.Entry<String, String> entry : env.entrySet()) {
                String key = entry.getKey();
                if (key.equals("LD_LIBRARY_PATH")) {
                    env.put(
                            key,
                            Arrays.asList(entry.getValue().split(pathSeparator())).stream()
                                    .map(p -> toRelative(workingDir, p))
                                    .sorted()
                                    .collect(Collectors.joining(pathSeparator())));
                }
            }
        }
        // By default unset an env. for process has higher priority, but in some case we might want
        // the 'set' to have priority.
        if (EnvPriority.UNSET.equals(mEnvVariablePriority)) {
            if (!env.isEmpty()) {
                processBuilder.environment().putAll(env);
            }
            if (!mUnsetEnvVariables.isEmpty()) {
                // in this implementation, the unsetEnv's priority is higher than set.
                processBuilder.environment().keySet().removeAll(mUnsetEnvVariables);
            }
        } else {
            if (!mUnsetEnvVariables.isEmpty()) {
                processBuilder.environment().keySet().removeAll(mUnsetEnvVariables);
            }
            if (!env.isEmpty()) {
                // in this implementation, the setEnv's priority is higher than set.
                processBuilder.environment().putAll(env);
            }
        }
        processBuilder.redirectErrorStream(mRedirectStderr);
        if (redirect != null) {
            processBuilder.redirectOutput(redirect);
            processBuilder.redirectError(redirect);
        }
        return processBuilder.command(commandList);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandResult runTimedCmdWithInput(final long timeout, String input,
            final String... command) {
        return runTimedCmdWithInput(timeout, input, ArrayUtil.list(command));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandResult runTimedCmdWithInput(final long timeout, String input,
            final List<String> command) {
        RunnableResult osRunnable = new RunnableResult(input, createProcessBuilder(command));
        CommandStatus status = runTimed(timeout, osRunnable, true);
        CommandResult result = osRunnable.getResult();
        result.setStatus(status);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdWithInput(
            long timeout, String input, File stdoutFile, File stderrFile, String... command) {
        ProcessBuilder pb = createProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.to(stdoutFile));
        pb.redirectError(ProcessBuilder.Redirect.to(stderrFile));
        RunnableResult osRunnable = new RunnableResult(input, pb);
        CommandStatus status = runTimed(timeout, osRunnable, true);
        CommandResult result = osRunnable.getResult();
        result.setStatus(status);
        // In case of error backfill, copy stderr to its file
        if (result.getExitCode() == 88) {
            try {
                FileUtil.writeToFile(result.getStderr(), stderrFile, true);
            } catch (IOException e) {
                // Ignore
            }
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdWithInputRedirect(
            final long timeout, @Nullable File inputRedirect, final String... command) {
        RunnableResult osRunnable =
                new RunnableResult(
                        /* input= */ null,
                        createProcessBuilder(command),
                        /* stdoutStream= */ null,
                        /* stderrStream= */ null,
                        inputRedirect,
                        true);
        CommandStatus status = runTimed(timeout, osRunnable, true);
        CommandResult result = osRunnable.getResult();
        result.setStatus(status);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CommandResult runTimedCmdSilently(final long timeout, final String... command) {
        RunnableResult osRunnable = new RunnableResult(null, createProcessBuilder(command), false);
        CommandStatus status = runTimed(timeout, osRunnable, false);
        CommandResult result = osRunnable.getResult();
        result.setStatus(status);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandResult runTimedCmdSilentlyRetry(long timeout, long retryInterval, int attempts,
            String... command) {
        CommandResult result = null;
        int counter = 0;
        while (counter < attempts) {
            result = runTimedCmdSilently(timeout, command);
            if (CommandStatus.SUCCESS.equals(result.getStatus())) {
                return result;
            }
            sleep(retryInterval);
            counter++;
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Process runCmdInBackground(final String... command) throws IOException  {
        return runCmdInBackground(null, command);
    }

    /** {@inheritDoc} */
    @Override
    public Process runCmdInBackground(Redirect redirect, final String... command)
            throws IOException {
        final String fullCmd = Arrays.toString(command);
        CLog.v("Running in background: %s", fullCmd);
        return createProcessBuilder(redirect, command).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Process runCmdInBackground(final List<String> command) throws IOException  {
        return runCmdInBackground(null, command);
    }

    /** {@inheritDoc} */
    @Override
    public Process runCmdInBackground(Redirect redirect, final List<String> command)
            throws IOException {
        CLog.v("Running in background: %s", command);
        return createProcessBuilder(redirect, command, false).start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Process runCmdInBackground(List<String> command, OutputStream output)
            throws IOException {
        CLog.v("Running in background: %s", command);
        Process process = createProcessBuilder(command).start();
        inheritIO(
                process.getInputStream(),
                output,
                String.format(INHERITIO_PREFIX + "stdout-%s", command));
        inheritIO(
                process.getErrorStream(),
                output,
                String.format(INHERITIO_PREFIX + "stderr-%s", command));
        return process;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandStatus runTimed(long timeout, IRunUtil.IRunnableResult runnable,
            boolean logErrors) {
        return runTimedWithOutputMonitor(timeout, 0, runnable, logErrors);
    }

    /** {@inheritDoc} */
    @Override
    public CommandStatus runTimedWithOutputMonitor(
            long timeout,
            long idleOutputTimeout,
            IRunUtil.IRunnableResult runnable,
            boolean logErrors) {
        mInterrupter.checkInterrupted();
        RunnableNotifier runThread = new RunnableNotifier(runnable, logErrors);
        if (logErrors) {
            if (timeout > 0L) {
                CLog.d(
                        "Running command %s with timeout: %s",
                        runnable.getCommand(), TimeUtil.formatElapsedTime(timeout));
            } else {
                CLog.d("Running command %s without timeout.", runnable.getCommand());
            }
        }
        CommandStatus status = CommandStatus.TIMED_OUT;
        try {
            runThread.start();
            long startTime = System.currentTimeMillis();
            long pollInterval = 0;
            if (timeout > 0L && timeout < mPollingInterval) {
                // only set the pollInterval if we have a timeout
                pollInterval = timeout;
            } else {
                pollInterval = mPollingInterval;
            }
            do {
                try {
                    // Check if the command is still making progress.
                    if (idleOutputTimeout != 0
                            && runThread.isAlive()
                            && !runnable.checkOutputMonitor(idleOutputTimeout)) {
                        // Set to Failed.
                        runThread.cancel();
                    } else {
                        runThread.join(pollInterval);
                    }
                } catch (InterruptedException e) {
                    if (isInterruptAllowed()) {
                        CLog.i("runTimed: interrupted while joining the runnable");
                        break;
                    } else {
                        CLog.i("runTimed: currently uninterruptible, ignoring interrupt");
                    }
                }
                mInterrupter.checkInterrupted();
            } while ((timeout == 0L || (System.currentTimeMillis() - startTime) < timeout)
                    && runThread.isAlive());
        } catch (RunInterruptedException e) {
            runThread.cancel();
            throw e;
        } finally {
            // Snapshot the status when out of the run loop because thread may terminate and return
            // a false FAILED instead of TIMED_OUT.
            status = runThread.getStatus();
            if (CommandStatus.TIMED_OUT.equals(status) || CommandStatus.EXCEPTION.equals(status)) {
                CLog.i("runTimed: Calling interrupt, status is %s", status);
                runThread.cancel();
            }
        }
        mInterrupter.checkInterrupted();
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean runTimedRetry(long opTimeout, long pollInterval, int attempts,
            IRunUtil.IRunnableResult runnable) {
        return runTimedRetryWithOutputMonitor(opTimeout, 0, pollInterval, attempts, runnable);
    }

    /** {@inheritDoc} */
    @Override
    public boolean runTimedRetryWithOutputMonitor(
            long opTimeout,
            long idleOutputTimeout,
            long pollInterval,
            int attempts,
            IRunUtil.IRunnableResult runnable) {
        for (int i = 0; i < attempts; i++) {
            if (runTimedWithOutputMonitor(opTimeout, idleOutputTimeout, runnable, true)
                    == CommandStatus.SUCCESS) {
                return true;
            }
            CLog.d("operation failed, waiting for %d ms", pollInterval);
            sleep(pollInterval);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean runFixedTimedRetry(final long opTimeout, final long pollInterval,
            final long maxTime, final IRunUtil.IRunnableResult runnable) {
        return runFixedTimedRetryWithOutputMonitor(opTimeout, 0, pollInterval, maxTime, runnable);
    }

    /** {@inheritDoc} */
    @Override
    public boolean runFixedTimedRetryWithOutputMonitor(
            final long opTimeout,
            long idleOutputTimeout,
            final long pollInterval,
            final long maxTime,
            final IRunUtil.IRunnableResult runnable) {
        final long initialTime = getCurrentTime();
        while (getCurrentTime() < (initialTime + maxTime)) {
            if (runTimedWithOutputMonitor(opTimeout, idleOutputTimeout, runnable, true)
                    == CommandStatus.SUCCESS) {
                return true;
            }
            CLog.d("operation failed, waiting for %d ms", pollInterval);
            sleep(pollInterval);
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean runEscalatingTimedRetry(
            final long opTimeout,
            final long initialPollInterval,
            final long maxPollInterval,
            final long maxTime,
            final IRunUtil.IRunnableResult runnable) {
        // wait an initial time provided
        long pollInterval = initialPollInterval;
        final long initialTime = getCurrentTime();
        while (true) {
            if (runTimedWithOutputMonitor(opTimeout, 0, runnable, true) == CommandStatus.SUCCESS) {
                return true;
            }
            long remainingTime = maxTime - (getCurrentTime() - initialTime);
            if (remainingTime <= 0) {
                CLog.d("operation is still failing after retrying for %d ms", maxTime);
                return false;
            } else if (remainingTime < pollInterval) {
                // cap pollInterval to a max of remainingTime
                pollInterval = remainingTime;
            }
            CLog.d("operation failed, waiting for %d ms", pollInterval);
            sleep(pollInterval);
            // somewhat arbitrarily, increase the poll time by a factor of 4 for each attempt,
            // up to the previously decided maximum
            pollInterval *= POLL_TIME_INCREASE_FACTOR;
            if (pollInterval > maxPollInterval) {
                pollInterval = maxPollInterval;
            }
        }
    }

    /**
     * Retrieves the current system clock time.
     * <p/>
     * Exposed so it can be mocked for unit testing
     */
    long getCurrentTime() {
        return System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sleep(long time) {
        mInterrupter.checkInterrupted();
        if (time <= 0) {
            return;
        }
        try (CloseableTraceScope sleep =
                new CloseableTraceScope(InvocationMetricKey.host_sleep.toString())) {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            // ignore
            CLog.d("sleep interrupted");
        }
        mInterrupter.checkInterrupted();
    }

    /** {@inheritDoc} */
    @Override
    public void allowInterrupt(boolean allow) {
        if (allow) {
            mInterrupter.allowInterrupt();
        } else {
            mInterrupter.blockInterrupt();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isInterruptAllowed() {
        return mInterrupter.isInterruptible();
    }

    /** {@inheritDoc} */
    @Override
    public void setInterruptibleInFuture(Thread thread, final long timeMs) {
        mInterrupter.allowInterruptAsync(thread, timeMs, TimeUnit.MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void interrupt(Thread thread, String message) {
        interrupt(thread, message, null);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void interrupt(Thread thread, String message, ErrorIdentifier errorId) {
        mInterrupter.interrupt(thread, message, errorId);
        mInterrupter.checkInterrupted();
    }

    /**
     * Helper thread that wraps a runnable, and notifies when done.
     */
    private static class RunnableNotifier extends Thread {

        private final IRunUtil.IRunnableResult mRunnable;
        private CommandStatus mStatus = CommandStatus.TIMED_OUT;
        private boolean mLogErrors = true;

        RunnableNotifier(IRunUtil.IRunnableResult runnable, boolean logErrors) {
            // Set this thread to be a daemon so that it does not prevent
            // TF from shutting down.
            setName(RUNNABLE_NOTIFIER_NAME);
            setDaemon(true);
            mRunnable = runnable;
            mLogErrors = logErrors;
        }

        @Override
        public void run() {
            CommandStatus status;
            try {
                status = mRunnable.run() ? CommandStatus.SUCCESS : CommandStatus.FAILED;
            } catch (InterruptedException e) {
                CLog.i("runutil interrupted");
                status = CommandStatus.EXCEPTION;
                backFillException(mRunnable.getResult(), e);
            } catch (Exception e) {
                if (mLogErrors) {
                    CLog.e("Exception occurred when executing runnable");
                    CLog.e(e);
                }
                status = CommandStatus.EXCEPTION;
                backFillException(mRunnable.getResult(), e);
            }
            synchronized (this) {
                mStatus = status;
            }
        }

        public void cancel() {
            mRunnable.cancel();
        }

        synchronized CommandStatus getStatus() {
            return mStatus;
        }

        private void backFillException(CommandResult result, Exception e) {
            if (result == null) {
                return;
            }
            if (Strings.isNullOrEmpty(result.getStderr())) {
                result.setStderr(StreamUtil.getStackTrace(e));
            }
            if (result.getExitCode() == null) {
                // Set non-zero exit code
                result.setExitCode(88);
            }
        }
    }

    class RunnableResult implements IRunUtil.IRunnableResult {
        private final ProcessBuilder mProcessBuilder;
        private final CommandResult mCommandResult;
        private final String mInput;
        private Process mProcess = null;
        private CountDownLatch mCountDown = null;
        private Thread mExecutionThread;
        private OutputStream mStdOut = null;
        private OutputStream mStdErr = null;
        private final File mInputRedirect;
        private final Object mLock = new Object();
        private boolean mCancelled = false;
        private boolean mLogErrors = true;
        private File mOutputMonitorStdoutFile;
        private File mOutputMonitorStderrFile;
        private long mOutputMonitorFileLastSize;
        private long mOutputMonitorLastChangeTime;

        RunnableResult(final String input, final ProcessBuilder processBuilder) {
            this(input, processBuilder, null, null, null, true);
        }

        RunnableResult(final String input, final ProcessBuilder processBuilder, boolean logErrors) {
            this(input, processBuilder, null, null, null, logErrors);
        }

        /**
         * Alternative constructor that allows redirecting the output to any Outputstream. Stdout
         * and stderr can be independently redirected to different Outputstream implementations. If
         * streams are null, default behavior of using a buffer will be used.
         *
         * <p>Additionally, Stdin can be redirected from a File.
         */
        RunnableResult(
                final String input,
                final ProcessBuilder processBuilder,
                final OutputStream stdoutStream,
                final OutputStream stderrStream,
                final File inputRedirect,
                final boolean logErrors) {
            mProcessBuilder = processBuilder;
            mInput = input;
            mLogErrors = logErrors;

            mInputRedirect = inputRedirect;
            if (mInputRedirect != null) {
                // Set Stdin to mInputRedirect file.
                mProcessBuilder.redirectInput(mInputRedirect);
            }

            mCommandResult = newCommandResult();
            mCountDown = new CountDownLatch(1);

            // Redirect IO, so that the outputstream for the spawn process does not fill up
            // and cause deadlock.
            mStdOut = stdoutStream;
            mStdErr = stderrStream;
        }

        @Override
        public List<String> getCommand() {
            return new ArrayList<>(mProcessBuilder.command());
        }

        @Override
        public CommandResult getResult() {
            return mCommandResult;
        }

        /** Start a {@link Process} based on the {@link ProcessBuilder}. */
        @VisibleForTesting
        Process startProcess() throws IOException {
            return mProcessBuilder.start();
        }

        @Override
        public boolean run() throws Exception {
            File stdoutFile = mProcessBuilder.redirectOutput().file();
            File stderrFile = mProcessBuilder.redirectError().file();
            boolean temporaryStdout = false;
            boolean temporaryErrOut = false;
            Thread stdoutThread = null;
            Thread stderrThread = null;
            synchronized (mLock) {
                if (mCancelled) {
                    // if cancel() was called before run() took the lock, we do not even attempt
                    // to run.
                    return false;
                }
                mExecutionThread = Thread.currentThread();
                if (stdoutFile == null && mStdOut == null) {
                    temporaryStdout = true;
                    stdoutFile =
                            FileUtil.createTempFile(
                                    String.format(
                                            "temporary-stdout-%s",
                                            mProcessBuilder.command().get(0)),
                                    ".txt");
                    stdoutFile.deleteOnExit();
                    mProcessBuilder.redirectOutput(Redirect.appendTo(stdoutFile));
                }
                if (stderrFile == null && mStdErr == null) {
                    temporaryErrOut = true;
                    stderrFile =
                            FileUtil.createTempFile(
                                    String.format(
                                            "temporary-errout-%s",
                                            mProcessBuilder.command().get(0)),
                                    ".txt");
                    stderrFile.deleteOnExit();
                    mProcessBuilder.redirectError(Redirect.appendTo(stderrFile));
                }
                // Obtain a reference to the output stream redirect file for progress monitoring.
                mOutputMonitorStdoutFile = stdoutFile;
                mOutputMonitorStderrFile = stderrFile;
                mOutputMonitorFileLastSize = 0;
                mOutputMonitorLastChangeTime = 0;
                try {
                    mProcess = startProcess();
                } catch (IOException | RuntimeException e) {
                    if (temporaryStdout) {
                        FileUtil.deleteFile(stdoutFile);
                    }
                    if (temporaryErrOut) {
                        FileUtil.deleteFile(stderrFile);
                    }
                    throw e;
                }
                if (mInput != null) {
                    BufferedOutputStream processStdin =
                            new BufferedOutputStream(mProcess.getOutputStream());
                    processStdin.write(mInput.getBytes("UTF-8"));
                    processStdin.flush();
                    processStdin.close();
                }
                if (mStdOut != null) {
                    stdoutThread =
                            inheritIO(
                                    mProcess.getInputStream(),
                                    mStdOut,
                                    String.format(
                                            "inheritio-stdout-%s", mProcessBuilder.command()));
                }
                if (mStdErr != null) {
                    stderrThread =
                            inheritIO(
                                    mProcess.getErrorStream(),
                                    mStdErr,
                                    String.format(
                                            "inheritio-stderr-%s", mProcessBuilder.command()));
                }
            }
            // Wait for process to complete.
            Integer rc = null;
            try {
                try {
                    rc = mProcess.waitFor();
                    // wait for stdout and stderr to be read
                    if (stdoutThread != null) {
                        stdoutThread.join();
                    }
                    if (stderrThread != null) {
                        stderrThread.join();
                    }
                } finally {
                    rc = (rc != null) ? rc : 1; // In case of interruption ReturnCode is null
                    mCommandResult.setExitCode(rc);

                    // Write out the streams to the result.
                    if (temporaryStdout) {
                        mCommandResult.setStdout(FileUtil.readStringFromFile(stdoutFile));
                    } else {
                        final String stdoutDest =
                                stdoutFile != null
                                        ? stdoutFile.getAbsolutePath()
                                        : mStdOut.getClass().getSimpleName();
                        mCommandResult.setStdout("redirected to " + stdoutDest);
                    }
                    if (temporaryErrOut) {
                        mCommandResult.setStderr(FileUtil.readStringFromFile(stderrFile));
                    } else {
                        final String stderrDest =
                                stderrFile != null
                                        ? stderrFile.getAbsolutePath()
                                        : mStdErr.getClass().getSimpleName();
                        mCommandResult.setStderr("redirected to " + stderrDest);
                    }
                }
            } finally {
                if (temporaryStdout) {
                    FileUtil.deleteFile(stdoutFile);
                }
                if (temporaryErrOut) {
                    FileUtil.deleteFile(stderrFile);
                }
                mCountDown.countDown();
            }

            if (rc != null && rc == 0) {
                return true;
            } else if (mLogErrors) {
                CLog.d("%s command failed. return code %d", mProcessBuilder.command(), rc);
            }
            return false;
        }

        @Override
        public void cancel() {
            if (mCancelled) {
                return;
            }
            mCancelled = true;
            synchronized (mLock) {
                if (mProcess == null || !mProcess.isAlive()) {
                    return;
                }
                CLog.d("Cancelling the process execution.");
                if (mLinuxInterruptProcess) {
                    long pid = mProcess.pid();
                    CommandResult killRes = RunUtil.getDefault().runTimedCmd(
                            60000L, "kill", "-2", "" + pid);
                    CLog.d("status=%s. stdout=%s . stderr=%s",
                            killRes.getStatus(), killRes.getStdout(), killRes.getStderr());
                    // Just give a little bit of time to terminate.
                    if (mProcess.isAlive()) {
                        RunUtil.getDefault().sleep(1000L);
                    }
                }
                // Always destroy to ensure it terminates.
                mProcess.destroy();
                try {
                    // Only allow to continue if the Stdout has been read
                    // RunnableNotifier#Interrupt is the next call and will terminate the thread
                    if (!mCountDown.await(PROCESS_DESTROY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        CLog.i("Process still not terminated, interrupting the execution thread");
                        mExecutionThread.interrupt();
                        mCountDown.await();
                    }
                } catch (InterruptedException e) {
                    CLog.i("interrupted while waiting for process output to be saved");
                }
            }
        }

        @Override
        public String toString() {
            return "RunnableResult [command="
                    + ((mProcessBuilder != null) ? mProcessBuilder.command() : null)
                    + "]";
        }

        /**
         * Checks if the currently running operation has made progress since the last check.
         *
         * @param idleOutputTimeout ms idle with no observed progress before beginning to assume no
         *     progress is being made.
         * @return true if progress has been detected otherwise false.
         */
        @Override
        public boolean checkOutputMonitor(Long idleOutputTimeout) {
            synchronized (mLock) {
                // If we don't have what we need to check for progress, abort the check.
                if ((mOutputMonitorStdoutFile == null || !mOutputMonitorStdoutFile.exists())
                        && (mOutputMonitorStderrFile == null
                                || !mOutputMonitorStderrFile.exists())) {
                    // Let the operation timeout on its own.
                    return true;
                }

                if (mOutputMonitorLastChangeTime == 0) {
                    mOutputMonitorLastChangeTime = System.currentTimeMillis();
                    // If this is the start of a new command invocation, log only once.
                    CLog.d(
                            "checkOutputMonitor activated with idle timeout set for %.2f seconds",
                            idleOutputTimeout / 1000f);
                }

                // Observing progress by monitoring the size of the output changing.
                long currentFileSize = getMonitoredStdoutSize() + getMonitoredStderrSize();
                long idleTime = System.currentTimeMillis() - mOutputMonitorLastChangeTime;
                if (currentFileSize == mOutputMonitorFileLastSize && idleTime > idleOutputTimeout) {
                    CLog.d(
                            "checkOutputMonitor: No new progress detected for over %.2f seconds",
                            idleTime / 1000f);
                    return false;
                }

                // Update change time only when new data appears on the streams.
                if (currentFileSize != mOutputMonitorFileLastSize) {
                    mOutputMonitorLastChangeTime = System.currentTimeMillis();
                    idleTime = 0;
                }
                mOutputMonitorFileLastSize = currentFileSize;
            }
            // Always default to progress being made.
            return true;
        }

        private long getMonitoredStdoutSize() {
            if (mOutputMonitorStdoutFile != null && mOutputMonitorStdoutFile.exists()) {
                return mOutputMonitorStdoutFile.length();
            }
            return 0;
        }

        private long getMonitoredStderrSize() {
            if (mOutputMonitorStderrFile != null && mOutputMonitorStderrFile.exists()) {
                return mOutputMonitorStderrFile.length();
            }
            return 0;
        }
    }

    /**
     * Helper method to redirect input stream.
     *
     * @param src {@link InputStream} to inherit/redirect from
     * @param dest {@link BufferedOutputStream} to inherit/redirect to
     * @param name the name of the thread returned.
     * @return a {@link Thread} started that receives the IO.
     */
    private static Thread inheritIO(final InputStream src, final OutputStream dest, String name) {
        // In case of some Process redirect, source stream can be null.
        if (src == null) {
            return null;
        }
        Thread t =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    StreamUtil.copyStreams(src, dest);
                                } catch (IOException e) {
                                    CLog.e("Failed to read input stream %s.", name);
                                }
                            }
                        });
        t.setName(name);
        t.start();
        return t;
    }

    /** {@inheritDoc} */
    @Override
    public void setEnvVariablePriority(EnvPriority priority) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException(
                    "Cannot setEnvVariablePriority on default RunUtil");
        }
        mEnvVariablePriority = priority;
    }

    /** {@inheritDoc} */
    @Override
    public void setLinuxInterruptProcess(boolean interrupt) {
        if (this.equals(sDefaultInstance)) {
            throw new UnsupportedOperationException(
                    "Cannot setLinuxInterruptProcess on default RunUtil");
        }
        mLinuxInterruptProcess = interrupt;
    }

    private static CommandResult newCommandResult() {
        CommandResult commandResult = new CommandResult();
        // Ensure the outputs are never null
        commandResult.setStdout("");
        commandResult.setStderr("");
        return commandResult;
    }

    private static String toRelative(File start, String target) {
        File targetFile = new File(target);
        return targetFile.exists() ? toRelative(start, targetFile) : target;
    }

    private static String toRelative(File start, File target) {
        String relPath = start.toPath().relativize(target.toPath()).toString();
        return relPath.length() != 0 ? relPath : ".";
    }

    private static String pathSeparator() {
        return System.getProperty("path.separator");
    }
}
