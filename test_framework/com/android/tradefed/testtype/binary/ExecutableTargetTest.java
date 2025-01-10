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
package com.android.tradefed.testtype.binary;

import com.android.ddmlib.MultiLineReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.testtype.GTestResultParser;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.PythonUnitTestResultParser;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Test runner for executable running on the target. The runner implements {@link IDeviceTest} since
 * the binary run on a device.
 */
@OptionClass(alias = "executable-target-test")
public class ExecutableTargetTest extends ExecutableBaseTest implements IDeviceTest {

    public static final String DEVICE_LOST_ERROR = "Device was lost prior to %s; aborting run.";
    public static final String ROOT_LOST_ERROR = "Root access was lost prior to %s; aborting run.";

    private ITestDevice mDevice = null;

    @Option(name = "abort-if-device-lost", description = "Abort the test if the device is lost.")
    private boolean mAbortIfDeviceLost = false;

    @Option(name = "abort-if-root-lost", description = "Abort the test if root access is lost.")
    private boolean mAbortIfRootLost = false;

    @Option(name = "skip-binary-check", description = "Skip the binary check in findBinary().")
    private boolean mSkipBinaryCheck = false;

    @Option(name = "parse-gtest", description = "Parse test outputs in GTest format.")
    private boolean mParseGTest = false;

    @Option(
            name = "parse-python-unit-test",
            description = "Parse test outputs in Python unit test format.")
    private boolean mParsePythonUnitTest = false;

    @Override
    protected boolean doesRunBinaryGenerateTestResults() {
        return mParseGTest || mParsePythonUnitTest;
    }

    @Override
    protected boolean doesRunBinaryGenerateTestRuns() {
        // when using the GTestParser testRun events are triggered
        // by the TEST_RUN_MARKER in stdout
        // so we should not generate testRuns on the RunBinary event

        // when using the PythonUnitTestResultParser, testRun events are triggered in the parser
        // with the given run names
        // so we should not generate testRuns on the RunBinary event
        return !mParseGTest && !mParsePythonUnitTest;
    }

    @Override
    public boolean getCollectTestsOnly() {
        if (super.getCollectTestsOnly()) {
            throw new UnsupportedOperationException("collect-tests-only mode not support");
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    protected boolean getSkipBinaryCheck() {
        return mSkipBinaryCheck;
    }

    @Override
    public FailureDescription shouldAbortRun(TestDescription description) {
        if (mParseGTest && mParsePythonUnitTest) {
            return FailureDescription.create(
                    "Only one of parse-gtest and parse-python-unit-test can be set.",
                    FailureStatus.CUSTOMER_ISSUE);
        }
        if (mAbortIfDeviceLost) {
            if (!TestDeviceState.ONLINE.equals(getDevice().getDeviceState())) {
                return FailureDescription.create(
                        String.format(DEVICE_LOST_ERROR, description),
                        FailureStatus.SYSTEM_UNDER_TEST_CRASHED);
            }
        }
        if (mAbortIfRootLost) {
            try {
                if (!getDevice().isAdbRoot()) {
                    return FailureDescription.create(
                            String.format(ROOT_LOST_ERROR, description),
                            FailureStatus.DEPENDENCY_ISSUE);
                }
            } catch (DeviceNotAvailableException e) {
                return FailureDescription.create(
                        String.format(DEVICE_LOST_ERROR, description),
                        FailureStatus.SYSTEM_UNDER_TEST_CRASHED);
            }
        }
        return null;
    }

    @Override
    public String findBinary(String binary) throws DeviceNotAvailableException {
        if (getSkipBinaryCheck()) {
            return binary;
        }
        for (String path : binary.split(" ")) {
            if (getDevice().isExecutable(path)) {
                return binary;
            }
        }
        return null;
    }

    @Override
    public void runBinary(
            String binaryPath, ITestInvocationListener listener, TestDescription description)
            throws DeviceNotAvailableException, IOException {
        if (mDevice == null) {
            throw new IllegalArgumentException("Device has not been set");
        }
        CommandResult result =
                mDevice.executeShellV2Command(
                        binaryPath, getTimeoutPerBinaryMs(), TimeUnit.MILLISECONDS);
        checkCommandResult(result, listener, description);
    }

    /**
     * Check the result of the test command.
     *
     * @param result test result of the command {@link CommandResult}
     * @param listener the {@link ITestInvocationListener}
     * @param description The test in progress.
     */
    protected void checkCommandResult(
            CommandResult result, ITestInvocationListener listener, TestDescription description) {
        if (mParseGTest) {
            MultiLineReceiver parser;
            // the parser automatically reports the test result back to the infra through the
            // listener.
            parser =
                    new GTestResultParser(
                            description.getTestName(), listener, true
                            /** allowRustTestName */
                            );
            parser.processNewLines(result.getStdout().split("\n"));
            parser.done();
        } else if (mParsePythonUnitTest) {
            // the parser automatically reports the test result back to the infra through the
            // listener.
            MultiLineReceiver parser =
                    new PythonUnitTestResultParser(listener, description.getTestName());
            parser.processNewLines(result.getStderr().split("\n"));
            parser.done();
        } else if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            String error_message;
            error_message =
                    String.format(
                            "binary returned non-zero. Exit code: %d, stderr: %s, stdout: %s",
                            result.getExitCode(), result.getStderr(), result.getStdout());
            listener.testFailed(
                    description,
                    FailureDescription.create(error_message)
                            .setFailureStatus(FailureStatus.TEST_FAILURE));
        }
    }
}
