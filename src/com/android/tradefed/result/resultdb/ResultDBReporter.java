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

import com.android.resultdb.proto.Invocation;
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
import com.android.tradefed.result.retry.ISupportGranularResults;
import com.android.tradefed.result.skipped.SkipReason;

import java.util.HashMap;

/** Result reporter that uploads test results to ResultDB. */
public class ResultDBReporter
        implements ITestSummaryListener,
                ILogSaverListener,
                ISupportGranularResults,
                IConfigurationReceiver {

    private Invocation mInvocation;
    private String mInvocationId;

    private String mUpdateToken;

    private IRecorderClient mRecorder;
    // If true result report will be enabled.
    private boolean mEnable = false;

    private ResultDBReporter() {}

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

    @Override
    public void invocationStarted(IInvocationContext context) {
        // Obtain invocation ID from context.
        String invocationId = context.getAttribute("resultdb_invocation_id");
        String updateToken = context.getAttribute("resultdb_invocation_update_token");

        if (!invocationId.isEmpty() && !updateToken.isEmpty()) {
            mEnable = true;
        }
        if (!mEnable) {
            CLog.i("ResultReporter is disabled");
            return;
        }
        mInvocationId = invocationId;
        mUpdateToken = updateToken;
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
        // TODO: Update invocation.
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        // TODO: implement this method.
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

    @Override
    public void testRunStopped(long elapsedTime) {
        // TODO: implement this method.
    }

    @Override
    public void testStarted(TestDescription test) {
        // TODO: implement this method.
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        // TODO: implement this method.
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        // TODO: implement this method.
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        // TODO: implement this method.
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        // TODO: implement this method.
    }

    @Override
    public void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void testSkipped(TestDescription test, SkipReason reason) {
        // TODO: implement this method.
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        // TODO: implement this method.
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        // TODO: implement this method.
    }

    @Override
    public void testIgnored(TestDescription test) {
        // TODO: implement this method.
    }

    @Override
    public boolean supportGranularResults() {
        // TODO: implement this method.
        return true;
    }
}
