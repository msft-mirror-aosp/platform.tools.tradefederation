/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.TestStatus;
import com.android.resultdb.proto.Variant;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.retry.ISupportGranularResults;
import com.android.tradefed.result.skipped.SkipReason;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Result reporter that uploads test results to ResultDB. */
public class ResultDBReporter
        implements ITestSummaryListener,
                ILogSaverListener,
                ISupportGranularResults,
                IConfigurationReceiver {
    private Invocation mInvocation;
    private String mInvocationId;
    private IRecorderClient mRecorder;
    // If true result reporter will be enabled.
    private boolean mEnable = false;
    // Common variant values for all test in this TF invocation.
    private Variant mBaseVariant;
    private String mCurrentModule;
    private TestResult mCurrentTestResult;
    // Counter for generate test result ID.
    private AtomicInteger mResultCounter = new AtomicInteger(0);
    // Base for generate test result ID.
    private String mResultIdBase;

    @Override
    public void setConfiguration(IConfiguration configuration) {
        // TODO: implement this method.
    }

    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        // TODO: implement this method.
    }

    @Override
    public void logAssociation(String dataName, LogFile logFile) {
        // TODO: implement this method.
    }

    @Override
    public void setLogSaver(ILogSaver logSaver) {
        // TODO: implement this method.
    }

    @Override
    public TestSummary getSummary() {
        // TODO: implement this method.
        return null;
    }

    @VisibleForTesting
    IRecorderClient createRecorderClient(String invocationId, String updateToken) {
        return Client.create(invocationId, updateToken);
    }

    // Generate a random hexadecimal string of length 8.
    @VisibleForTesting
    String randomHexString() throws NoSuchAlgorithmException {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return ResultDBUtil.bytesToHex(bytes);
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        // Obtain invocation ID from context.
        String invocationId = context.getAttribute("resultdb_invocation_id");
        String updateToken = context.getAttribute("resultdb_invocation_update_token");
        if (!invocationId.isEmpty() && !updateToken.isEmpty()) {
            mEnable = true;
        }
        if (!mEnable) {
            CLog.i("ResultDBReporter is disabled");
            return;
        }
        mInvocationId = invocationId;
        mRecorder = createRecorderClient(invocationId, updateToken);
        try {
            mResultIdBase = this.randomHexString();
        } catch (NoSuchAlgorithmException e) {
            CLog.e("Failed to generate random result ID base.");
            return;
        }
        // TODO: Obtain more test variants from build info.
        mBaseVariant = Variant.newBuilder().putDef("name", context.getTestTag()).build();
    }

    @Override
    public void invocationFailed(Throwable cause) {
        // TODO: implement this method.
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void invocationSkipped(SkipReason reason) {
        // TODO: implement this method.
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        if (!mEnable) {
            return;
        }
        mRecorder.finalizeTestResults();
        // TODO: Update ResultDB invocation with information from TF invocation.
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        if (!mEnable) {
            return;
        }
        // Extract module informations.
        mCurrentModule = moduleContext.getConfigurationDescriptor().getModuleName();
    }

    @Override
    public void testModuleEnded() {
        // TODO: implement this method.
    }

    @Override
    public void testRunEnded(
            long elapsedTimeMillis, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        // TODO: implement this method.
    }

    @Override
    public void testRunFailed(String errorMessage) {
        // TODO: implement this method.
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        // TODO: implement this method.
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        // TODO: implement this method.
    }

    @VisibleForTesting
    long currentTimestamp() {
        return System.currentTimeMillis();
    }

    @VisibleForTesting
    String randomUUIDString() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void testRunStopped(long elapsedTime) {
        // TODO: implement this method.
    }

    @Override
    public void testStarted(TestDescription test) {
        testStarted(test, currentTimestamp());
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        if (!mEnable) {
            return;
        }
        Variant.Builder variantBuilder = Variant.newBuilder().mergeFrom(mBaseVariant);
        // TODO: Add more test variants from test module parameters.
        mCurrentTestResult =
                TestResult.newBuilder()
                        // TODO: Use test id format designed in go/resultdb-test-hierarchy-proposal
                        .setTestId(
                                String.format(
                                        "ants://%s/%s/%s",
                                        mCurrentModule, test.getClassName(), test.getTestName()))
                        .setResultId(
                                String.format(
                                        "%s-%05d", mResultIdBase, mResultCounter.incrementAndGet()))
                        .setStartTime(Timestamps.fromMillis(startTime))
                        .setStatus(TestStatus.PASS)
                        .setExpected(true)
                        .setVariant(variantBuilder.build())
                        .build();
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        testAssumptionFailure(test, FailureDescription.create(trace));
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        if (!mEnable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testAssumptionFailure(%s) without a valid testStart before.", test);
            return;
        }
        // TODO: set failure reason somewhere.
        mCurrentTestResult =
                mCurrentTestResult.toBuilder().setStatus(TestStatus.SKIP).setExpected(true).build();
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        if (!mEnable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testIgnored(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder().setStatus(TestStatus.SKIP).setExpected(true).build();
        // TODO: set skip reason somewhere.
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        if (!mEnable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setFailureReason(
                                FailureReason.newBuilder()
                                        .setPrimaryErrorMessage(extractFailureReason(trace)))
                        .setStatus(TestStatus.FAIL)
                        .setExpected(false)
                        .build();
        // TODO: set summary HTML.
        // TODO: set local instruction.
        // TODO: trace is too long to fit in any test result field. Put it in artifact.
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (!mEnable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testFailed(%s) without a valid testStart before.", test);
            return;
        }
        TestStatus status = TestStatus.FAIL;
        Set<FailureStatus> crashStatus =
                new HashSet<>(
                        Arrays.asList(
                                FailureStatus.TIMED_OUT,
                                FailureStatus.CANCELLED,
                                FailureStatus.INFRA_FAILURE,
                                FailureStatus.SYSTEM_UNDER_TEST_CRASHED));
        if (crashStatus.contains(failure.getFailureStatus())) {
            status = TestStatus.CRASH;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setFailureReason(
                                FailureReason.newBuilder()
                                        .setPrimaryErrorMessage(
                                                extractFailureReason(failure.getErrorMessage())))
                        .setStatus(status)
                        .setExpected(false)
                        .build();
        // TODO: set summary HTML.
        // TODO: save the tf error type somewhere.
        // TODO: set local instruction.
        // TODO: trace is too long to fit in any test result field. Put it in artifact.
    }

    @Override
    public void testIgnored(TestDescription test) {
        if (!mEnable) {
            return;
        }
        if (mCurrentTestResult == null) {
            CLog.e("Received #testIgnored(%s) without a valid testStart before.", test);
            return;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder().setStatus(TestStatus.SKIP).setExpected(true).build();
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        testEnded(test, currentTimestamp(), testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        if (!mEnable) {
            return;
        }
        mCurrentTestResult =
                mCurrentTestResult.toBuilder()
                        .setDuration(
                                Durations.fromMillis(
                                        endTime
                                                - Timestamps.toMillis(
                                                        mCurrentTestResult.getStartTime())))
                        .build();
        mRecorder.uploadTestResult(mCurrentTestResult);
        mCurrentTestResult = null;
    }

    @Override
    public boolean supportGranularResults() {
        return true;
    }

    /**
     * Extract the first line of the stack trace as the error message.
     *
     * <p>In most cases, this ends up being the exception + error message.
     */
    @VisibleForTesting
    String extractFailureReason(String trace) {
        String firstLine = trace.split("[\\r\\n]+", 2)[0];
        if (!firstLine.trim().isEmpty()) {
            return firstLine;
        }
        return "";
    }
}
