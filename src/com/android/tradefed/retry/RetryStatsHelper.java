/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.retry;

import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.TestStatus;

import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Calculate the retry statistics and metrics based on attempts comparison. */
final class RetryStatsHelper {

    private RetryStatistics mStats = new RetryStatistics();
    private Set<TestDescription> mPassedTestCases = new HashSet<>();
    private Set<TestDescription> mFailedTestCases = new HashSet<>();

    /** Add the results from the latest run to be tracked for statistics purpose. */
    public void addResultsFromRun(List<TestRunResult> mLatestResults) {
        addResultsFromRun(mLatestResults, 0L, 0);
    }

    /** Add the results from the latest run to be tracked for statistics purpose. */
    public void addResultsFromRun(
            List<TestRunResult> mLatestResults, long timeForIsolation, int attempt) {
        if (timeForIsolation != 0L) {
            mStats.mAttemptIsolationCost.put(attempt, timeForIsolation);
        }
        for (var runResult : mLatestResults) {
            if (runResult != null) {
                // Track all tests where we failed to clear the failure (so if the test either
                // failed or didn't run in subsequent attempts, it's a failed retry.)
                var failedStatuses = Arrays.asList(TestStatus.FAILURE, TestStatus.SKIPPED);
                mFailedTestCases.addAll(runResult.getTestsInState(failedStatuses));
                mFailedTestCases.removeAll(runResult.getPassedTests());

                // Only track retries as retrySuccess.
                if (attempt > 0) {
                    mPassedTestCases.addAll(runResult.getPassedTests());
                }
            }
        }
    }

    /**
     * Calculate the retry statistics based on currently known results and return the associated
     * {@link RetryStatistics} to represent the results.
     */
    public RetryStatistics calculateStatistics() {
        mStats.mRetryFailure = mFailedTestCases.size();
        mStats.mRetrySuccess = mPassedTestCases.size();
        return mStats;
    }

}
