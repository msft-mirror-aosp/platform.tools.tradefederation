/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Listener that allows to read the final test run status. */
public final class TestRunResultListener implements ITestInvocationListener {
    private Map<String, Set<String>> mFailedRuns = new HashMap<>();
    private String mCurrentTestRun = null;

    /** {@inheritDoc} */
    @Override
    public void testRunStarted(String runName, int testCount) {
        if (mCurrentTestRun != null) {
            throw new RuntimeException(
                    "Failed to clean the current test run name before starting a new test run.");
        }
        mCurrentTestRun = runName;
    }

    /** {@inheritDoc} */
    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        mCurrentTestRun = null;
    }

    /** {@inheritDoc} */
    @Override
    public void testRunFailed(String errorMessage) {
        handleFailure(null);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunFailed(FailureDescription failure) {
        handleFailure(null);
    }

    /** {@inheritDoc} */
    @Override
    public void testFailed(TestDescription test, String trace) {
        handleFailure(test.getTestName());
    }

    /** {@inheritDoc} */
    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        handleFailure(test.getTestName());
    }

    public boolean isTestRunFailed(String testRunName) {
        return mFailedRuns.containsKey(testRunName);
    }

    public boolean isTestFailed(String testName) {
        if (!mFailedRuns.containsKey(mCurrentTestRun)) {
            return false;
        }
        Set<String> failedTests = mFailedRuns.get(mCurrentTestRun);
        return failedTests.isEmpty() || failedTests.contains(testName);
    }

    private void handleFailure(String testName) {
        if (mCurrentTestRun == null) {
            throw new RuntimeException(
                    "Failed to catch the test run start before the test run failed.");
        }
        Set<String> failedTests = mFailedRuns.getOrDefault(mCurrentTestRun, new HashSet<>());
        // If the test name is null, the whole test run is failed and the failed test set is empty.
        if (testName != null) {
            failedTests.add(testName);
        }
        mFailedRuns.put(mCurrentTestRun, failedTests);
    }
}
