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
import java.util.Set;

/** Listener that allows to read the final test run status. */
public final class TestRunResultListener implements ITestInvocationListener {
    private Set<String> mFailedRuns = new HashSet<>();
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
        handleFailure();
    }

    /** {@inheritDoc} */
    @Override
    public void testRunFailed(FailureDescription failure) {
        handleFailure();
    }

    /** {@inheritDoc} */
    @Override
    public void testFailed(TestDescription test, String trace) {
        handleFailure();
    }

    /** {@inheritDoc} */
    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        handleFailure();
    }

    public boolean isTestRunFailed(String testRunName) {
        return mFailedRuns.contains(testRunName);
    }

    private void handleFailure() {
        if (mCurrentTestRun == null) {
            throw new RuntimeException(
                    "Failed to catch the test run start before the test run failed.");
        }
        mFailedRuns.add(mCurrentTestRun);
    }
}
