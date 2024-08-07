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
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.result.error.ErrorIdentifier;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;

/**
 * Interface for running timed operations and system commands.
 */
public interface IRunUtil {

    /**
     * An interface for asynchronously executing an operation that returns a boolean status.
     */
    public static interface IRunnableResult {
        /**
         * Execute the operation.
         *
         * @return <code>true</code> if operation is performed successfully, <code>false</code>
         *         otherwise
         * @throws Exception if operation terminated abnormally
         */
        public boolean run() throws Exception;

        /**
         * Cancel the operation.
         */
        public void cancel();

        /** Returns the command associated with the runnable. */
        public default List<String> getCommand() {
            return null;
        }

        /** Returns the {@link CommandResult} associated with the command. */
        public default CommandResult getResult() {
            return null;
        }

        /**
         * Checks if the currently running operation has made progress since the last check.
         *
         * @param idleOutputTimeout ms idle with no observed progress before beginning to assume no
         *     progress is being made.
         * @return true if progress has been detected otherwise false.
         */
        public default boolean checkOutputMonitor(Long idleOutputTimeout) {
            // Allow existing implementations not to implement this method.
            throw new UnsupportedOperationException("checkOutputMonitor() has no implementation.");
        }
    }

    /**
     * Sets the working directory for system commands.
     *
     * @param dir the working directory
     *
     * @see ProcessBuilder#directory(File)
     */
    public void setWorkingDir(File dir);

    /**
     * Sets a environment variable to be used when running system commands.
     *
     * @param key the variable name
     * @param value the variable value
     *
     * @see ProcessBuilder#environment()
     *
     */
    public void setEnvVariable(String key, String value);

    /**
     * Unsets an environment variable, so the system commands run without this environment variable.
     *
     * @param key the variable name
     *
     * @see ProcessBuilder#environment()
     */
    public void unsetEnvVariable(String key);

    /**
     * Set the standard error stream to redirect to the standard output stream when running system
     * commands. Initial value is false.
     *
     * @param redirect new value for whether or not to redirect
     * @see ProcessBuilder#redirectErrorStream(boolean)
     */
    public void setRedirectStderrToStdout(boolean redirect);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time.
     *
     * @param timeout maximum time to wait in ms. 0 means no timeout.
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmd(final long timeout, final String... command);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time. Also monitors the output streams for activity, aborting if no stream activity is
     * observed for a specified time. If the idleOutputTimeout is set to zero, no stream monitoring
     * will occur.
     *
     * @param timeout maximum time to wait in ms. 0 means no timeout.
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdWithOutputMonitor(
            final long timeout, final long idleOutputTimeout, final String... command);

    /**
     * Helper method to execute a system command, abort if it takes longer than a specified time,
     * and redirect output to files if specified. When {@link OutputStream} are provided this way,
     * they will be left open at the end of the function.
     *
     * @param timeout timeout maximum time to wait in ms. 0 means no timeout.
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param stdout {@link OutputStream} where the std output will be redirected. Can be null.
     * @param stderr {@link OutputStream} where the error output will be redirected. Can be null.
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdWithOutputMonitor(
            final long timeout,
            final long idleOutputTimeout,
            OutputStream stdout,
            OutputStream stderr,
            final String... command);

    /**
     * Helper method to execute a system command with caching.
     *
     * <p>If {@code cacheClient} is specified, the caching will be enabled. If the cache is
     * available, the cached result will be returned. Otherwise, {@link
     * IRunUtil#runTimedCmdWithOutputMonitor( long, long, OutputStream, OutputStream, String...)}
     * will be used to execute the command and the result will be uploaded for caching.
     *
     * @param timeout timeout maximum time to wait in ms. 0 means no timeout.
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams.
     * @param stdout {@link OutputStream} where the std output will be redirected. Can be null.
     * @param stderr {@link OutputStream} where the error output will be redirected. Can be null.
     * @param cacheClient an instance of {@link ICacheClient} used to handle caching.
     * @param command the specified system command and optionally arguments to exec.
     * @return a {@link CommandResult} containing result from command run.
     */
    public CommandResult runTimedCmdWithOutputMonitor(
            final long timeout,
            final long idleOutputTimeout,
            OutputStream stdout,
            OutputStream stderr,
            ICacheClient cacheClient,
            final String... command);

    /**
     * Helper method to execute a system command, abort if it takes longer than a specified time,
     * and redirect output to files if specified. When {@link OutputStream} are provided this way,
     * they will be left open at the end of the function.
     *
     * @param timeout timeout maximum time to wait in ms. 0 means no timeout.
     * @param stdout {@link OutputStream} where the std output will be redirected. Can be null.
     * @param stderr {@link OutputStream} where the error output will be redirected. Can be null.
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmd(
            final long timeout, OutputStream stdout, OutputStream stderr, final String... command);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time.
     *
     * @param timeout maximum time to wait in ms for each attempt
     * @param command the specified system command and optionally arguments to exec
     * @param retryInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdRetry(final long timeout, long retryInterval,
            int attempts, final String... command);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time. Also monitors the output streams for activity, aborting if no stream activity is
     * observed for a specified time. If the idleOutputTimeout is set to zero, no stream monitoring
     * will occur.
     *
     * @param timeout maximum time to wait in ms for each attempt
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param command the specified system command and optionally arguments to exec
     * @param retryInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdRetryWithOutputMonitor(
            final long timeout,
            final long idleOutputTimeout,
            long retryInterval,
            int attempts,
            final String... command);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time. Similar to {@link #runTimedCmd(long, String...)}, but does not log any errors on
     * exception.
     *
     * @param timeout maximum time to wait in ms
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdSilently(final long timeout, final String... command);

    /**
     * Helper method to execute a system command, and aborting if it takes longer than a specified
     * time. Similar to {@link #runTimedCmdRetry(long, long, int, String[])},
     * but does not log any errors on exception.
     *
     * @param timeout maximum time to wait in ms
     * @param command the specified system command and optionally arguments to exec
     * @param retryInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdSilentlyRetry(final long timeout, long retryInterval,
            int attempts, final String... command);

    /**
     * Helper method to execute a system command that requires stdin input, and aborting if it
     * takes longer than a specified time.
     *
     * @param timeout maximum time to wait in ms
     * @param input the stdin input to pass to process
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    CommandResult runTimedCmdWithInput(long timeout, String input, String... command);

    /**
     * Helper method to execute a system command that requires stdin input, and aborting if it
     * takes longer than a specified time.
     *
     * @param timeout maximum time to wait in ms
     * @param input the stdin input to pass to process
     * @param command {@link List} containing the system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    CommandResult runTimedCmdWithInput(long timeout, String input, List<String> command);

    /**
     * Helper method to execute a system command, abort if it takes longer than a specified time,
     * and redirect output to files if specified.
     *
     * @param timeout timeout maximum time to wait in ms. 0 means no timeout.
     * @param input the stdin input to pass to process
     * @param command the specified system command and optionally arguments to exec
     * @param stdoutFile {@link File} where the std output will be redirected. Can be null.
     * @param stderrFile {@link File} where the error output will be redirected. Can be null.
     * @return a {@link CommandResult} containing result from command run
     */
    public CommandResult runTimedCmdWithInput(
            long timeout, String input, File stdoutFile, File stderrFile, final String... command);

    /**
     * Helper method to execute a system command that requires redirecting Stdin from a file, and
     * aborting if it takes longer than a specified time.
     *
     * @param timeout maximum time to wait in ms
     * @param inputRedirect the {@link File} to redirect as standard input using {@link
     *     ProcessBuilder#redirectInput()}. If null, stdin won't be redirected.
     * @param command the specified system command and optionally arguments to exec
     * @return a {@link CommandResult} containing result from command run
     */
    CommandResult runTimedCmdWithInputRedirect(
            long timeout, @Nullable File inputRedirect, String... command);

    /**
     * Helper method to execute a system command asynchronously.
     *
     * <p>Will return immediately after launching command.
     *
     * @param command the specified system command and optionally arguments to exec
     * @return the {@link Process} of the executed command
     * @throws IOException if command failed to run
     */
    public Process runCmdInBackground(String... command) throws IOException;

    /**
     * Helper method to execute a system command asynchronously.
     *
     * <p>Will return immediately after launching command.
     *
     * @param redirect The {@link Redirect} to apply to the {@link ProcessBuilder}.
     * @param command the specified system command and optionally arguments to exec
     * @return the {@link Process} of the executed command
     * @throws IOException if command failed to run
     */
    public Process runCmdInBackground(Redirect redirect, final String... command)
            throws IOException;

    /**
     * An alternate {@link #runCmdInBackground(String...)} method that accepts the command arguments
     * in {@link List} form.
     *
     * @param command the {@link List} containing specified system command and optionally arguments
     *            to exec
     * @return the {@link Process} of the executed command
     * @throws IOException if command failed to run
     */
    public Process runCmdInBackground(List<String> command) throws IOException;

    /**
     * An alternate {@link #runCmdInBackground(String...)} method that accepts the command arguments
     * in {@link List} form.
     *
     * @param redirect The {@link Redirect} to apply to the {@link ProcessBuilder}.
     * @param command the {@link List} containing specified system command and optionally arguments
     *     to exec
     * @return the {@link Process} of the executed command
     * @throws IOException if command failed to run
     */
    public Process runCmdInBackground(Redirect redirect, List<String> command) throws IOException;

    /**
     * Running command with a {@link OutputStream} log the output of the command.
     * Stdout and stderr are merged together.
     * @param command the command to run
     * @param output the OutputStream to save the output
     * @return the {@link Process} running the command
     * @throws IOException
     */
    public Process runCmdInBackground(List<String> command, OutputStream output)
            throws IOException;

    /**
     * Block and executes an operation, aborting if it takes longer than a specified time.
     *
     * @param timeout maximum time to wait in ms
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @param logErrors log errors on exception or not.
     * @return the {@link CommandStatus} result of operation.
     */
    public CommandStatus runTimed(long timeout, IRunUtil.IRunnableResult runnable,
            boolean logErrors);

    /**
     * Block and executes an operation, aborting if it takes longer than a specified time. Also
     * monitors the output streams for activity, aborting if no stream activity is observed for a
     * specified time. If the idleOutputTimeout is set to zero, no stream monitoring will occur.
     *
     * @param timeout maximum time to wait in ms
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @param logErrors log errors on exception or not.
     * @return the {@link CommandStatus} result of operation.
     */
    public CommandStatus runTimedWithOutputMonitor(
            final long timeout,
            final long idleOutputTimeout,
            IRunUtil.IRunnableResult runnable,
            boolean logErrors);

    /**
     * Block and executes an operation multiple times until it is successful.
     *
     * @param opTimeout maximum time to wait in ms for one operation attempt
     * @param pollInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before attempts reached.
     */
    public boolean runTimedRetry(long opTimeout, long pollInterval, int attempts,
            IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful. Also monitors the
     * output streams for activity, aborting if no stream activity is observed for a specified time.
     * If the idleOutputTimeout is set to zero, no stream monitoring will occur.
     *
     * @param opTimeout maximum time to wait in ms for one operation attempt
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param pollInterval time to wait between command retries
     * @param attempts the maximum number of attempts to try
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before attempts reached.
     */
    public boolean runTimedRetryWithOutputMonitor(
            final long opTimeout,
            final long idleOutputTimeout,
            long pollInterval,
            int attempts,
            IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful.
     *
     * @param opTimeout maximum time to wait in ms for a single operation attempt
     * @param pollInterval initial time to wait between operation attempts
     * @param maxTime the total approximate maximum time to keep trying the operation
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before maxTime expired
     */
    public boolean runFixedTimedRetry(final long opTimeout, final long pollInterval,
            final long maxTime, final IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful. Also monitors the
     * output streams for activity, aborting if no stream activity is observed for a specified time.
     * If the idleOutputTimeout is set to zero, no stream monitoring will occur.
     *
     * @param opTimeout maximum time to wait in ms for a single operation attempt
     * @param idleOutputTimeout maximum time to wait in ms for output on the output streams
     * @param pollInterval initial time to wait between operation attempts
     * @param maxTime the total approximate maximum time to keep trying the operation
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before maxTime expired
     */
    public boolean runFixedTimedRetryWithOutputMonitor(
            final long opTimeout,
            final long idleOutputTimeout,
            final long pollInterval,
            final long maxTime,
            final IRunUtil.IRunnableResult runnable);

    /**
     * Block and executes an operation multiple times until it is successful.
     * <p/>
     * Exponentially increase the wait time between operation attempts. This is intended to be used
     * when performing an operation such as polling a server, to give it time to recover in case it
     * is temporarily down.
     *
     * @param opTimeout maximum time to wait in ms for a single operation attempt
     * @param initialPollInterval initial time to wait between operation attempts
     * @param maxPollInterval the max time to wait between operation attempts
     * @param maxTime the total approximate maximum time to keep trying the operation
     * @param runnable {@link IRunUtil.IRunnableResult} to execute
     * @return <code>true</code> if operation completed successfully before maxTime expired
     */
    public boolean runEscalatingTimedRetry(final long opTimeout, final long initialPollInterval,
            final long maxPollInterval, final long maxTime, final IRunUtil.IRunnableResult
            runnable);

    /**
     * Helper method to sleep for given time, ignoring any exceptions.
     *
     * @param time ms to sleep. values less than or equal to 0 will be ignored
     */
    public void sleep(long time);

    /**
     * Allows/disallows run interrupts on the current thread. If it is allowed, run operations of
     * the current thread can be interrupted from other threads via {@link #interrupt} method.
     *
     * @param allow whether to allow run interrupts on the current thread.
     */
    public void allowInterrupt(boolean allow);

    /**
     * Give the interrupt status of the RunUtil.
     * @return true if the Run can be interrupted, false otherwise.
     */
    public boolean isInterruptAllowed();

    /**
     * Set as interruptible after some waiting time.
     * {@link CommandScheduler#shutdownHard()} to enforce we terminate eventually.
     *
     * @param thread the thread that will become interruptible.
     * @param timeMs time to wait before setting interruptible.
     */
    public void setInterruptibleInFuture(Thread thread, long timeMs);

    /**
     * Interrupts the ongoing/forthcoming run operations on the given thread. The run operations on
     * the given thread will throw {@link RunInterruptedException}.
     *
     * @param thread
     * @param message the message for {@link RunInterruptedException}.
     */
    public void interrupt(Thread thread, String message);

    /**
     * Interrupts the ongoing/forthcoming run operations on the given thread. The run operations on
     * the given thread will throw {@link RunInterruptedException}.
     *
     * @param thread
     * @param message the message for {@link RunInterruptedException}.
     * @param errorId Representing the cause of the interruption when known.
     */
    public void interrupt(Thread thread, String message, ErrorIdentifier errorId);

    /**
     * Decide whether or not when creating a process, unsetting environment variable is higher
     * priority than setting them.
     * By Default, unsetting is higher priority: meaning if an attempt to set a variable with the
     * same name is made, it won't happen since the variable will be unset.
     * Cannot be used on the default {@link IRunUtil} instance.
     */
    public void setEnvVariablePriority(EnvPriority priority);

    /**
     * Allow to use linux 'kill' interruption on process running through #runTimed methods when it
     * reaches a timeout.
     *
     * Cannot be used on the default {@link IRunUtil} instance.
     */
    public void setLinuxInterruptProcess(boolean interrupt);

    /**
     * Enum that defines whether setting or unsetting a particular env. variable has priority.
     */
    public enum EnvPriority {
        SET,
        UNSET
    }
}
