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


/**
 * Contains the result of a command.
 */
public class CommandResult {

    private CommandStatus mCmdStatus = CommandStatus.TIMED_OUT;
    private String mStdout = null;
    private String mStderr = null;
    private Integer mExitCode = null;
    private boolean mCached = false;

    /**
     * Create a {@link CommandResult} with the default {@link CommandStatus#TIMED_OUT} status.
     */
    public CommandResult() {
    }

    /**
     * Create a {@link CommandResult} with the given status.
     *
     * @param status the {@link CommandStatus}
     */
    public CommandResult(CommandStatus status) {
        mCmdStatus = status;
    }

    /**
     * Get status of command.
     *
     * @return the {@link CommandStatus}
     */
    public CommandStatus getStatus() {
        return mCmdStatus;
    }

    public void setStatus(CommandStatus status) {
        mCmdStatus = status;
    }

    /** Returns whether this result is a cached result or not. */
    public boolean isCached() {
        return mCached;
    }

    public void setCached(boolean cached) {
        mCached = cached;
    }

    /**
     * Get the standard output produced by command.
     *
     * @return the standard output or <code>null</code> if output could not be retrieved
     */
    public String getStdout() {
        return mStdout;
    }

    public void setStdout(String stdout) {
        mStdout = stdout;
    }

    /**
     * Get the standard error output produced by command.
     *
     * @return the standard error or <code>null</code> if output could not be retrieved
     */
    public String getStderr() {
        return mStderr;
    }

    public void setStderr(String stderr) {
        mStderr = stderr;
    }

    /**
     * Get the exit/return code produced by command.
     *
     * @return the exit code or <code>null</code> if it is unset
     */
    public Integer getExitCode() {
        return mExitCode;
    }

    public void setExitCode(int exitCode) {
        mExitCode = exitCode;
    }

    /** Returns a string representation of this object. Stdout/err can be very large. */
    @Override
    public String toString() {
        return String.format(
                "CommandResult: exit code=%d, out=%s, err=%s", mExitCode, mStdout, mStderr);
    }
}
