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

package com.android.tradefed.result;

import com.android.tradefed.invoker.logger.CurrentInvocation.IsolationGrade;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An Extension of {@link ResultAndLogForwarder} that adds extra module related metrics to the test
 * results.
 */
public class ModuleResultsAndMetricsForwarder extends ResultAndLogForwarder {
    /** Track if we are within an isolated run or not */
    private IsolationGrade mAttemptIsolation = IsolationGrade.NOT_ISOLATED;

    private List<String> mTestMappingSources = new ArrayList<>();
    private static final String TEST_MAPPING_SOURCE = "test_mapping_source";
    private String mModuleId = null;

    public ModuleResultsAndMetricsForwarder(ITestInvocationListener... listeners) {
        super(listeners);
    }

    /** Sets whether or not the attempt should be reported as isolated. */
    public void setAttemptIsolation(IsolationGrade isolation) {
        mAttemptIsolation = isolation;
    }

    /** Sets test-mapping sources that will be inserted into metrics. */
    public void setTestMappingSources(List<String> testMappingSources) {
        mTestMappingSources = testMappingSources;
    }

    public void setModuleId(String moduleId) {
        mModuleId = moduleId;
    }

    @Override
    public void testRunStarted(String runName, int testCount) {
        super.testRunStarted(mModuleId, testCount);
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber) {
        testRunStarted(mModuleId, testCount, attemptNumber, System.currentTimeMillis());
    }

    @Override
    public void testRunStarted(String runName, int testCount, int attemptNumber, long startTime) {
        super.testRunStarted(mModuleId, testCount, attemptNumber, startTime);
    }

    @Override
    public void testStarted(TestDescription test) {
        super.testStarted(test);
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        super.testStarted(test, startTime);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        super.testFailed(test, trace);
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        super.testFailed(test, failure);
    }

    @Override
    public void testEnded(
            TestDescription test, HashMap<String, MetricMeasurement.Metric> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    @Override
    public void testEnded(
            TestDescription test,
            long endTime,
            HashMap<String, MetricMeasurement.Metric> testMetrics) {
        if (!mTestMappingSources.isEmpty()) {
            testMetrics.put(
                    TEST_MAPPING_SOURCE,
                    TfMetricProtoUtil.stringToMetric(mTestMappingSources.toString()));
        }
        super.testEnded(test, endTime, testMetrics);
    }

    @Override
    public void testRunFailed(String errorMessage) {
        super.testRunFailed(errorMessage);
    }

    @Override
    public void testRunFailed(FailureDescription failure) {
        super.testRunFailed(failure);
    }

    @Override
    public void testRunEnded(
            long elapsedTime, HashMap<String, MetricMeasurement.Metric> runMetrics) {
        if (!IsolationGrade.NOT_ISOLATED.equals(mAttemptIsolation)) {
            runMetrics.put(
                    "run-isolated", TfMetricProtoUtil.stringToMetric(mAttemptIsolation.toString()));
            // In case something was off, reset isolation.
            mAttemptIsolation = IsolationGrade.NOT_ISOLATED;
        }
        super.testRunEnded(elapsedTime, runMetrics);
    }

    @Override
    public void testRunEnded(long elapsedTimeMillis, Map<String, String> runMetrics) {
        super.testRunEnded(elapsedTimeMillis, runMetrics);
    }
}
