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
package com.android.tradefed.retry;

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.TestStatus;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A helper class to track the retry attempts for each test case and determine if a test run should
 * be retried.
 *
 * <p>This class is used by {@link BaseRetryDecision} to determine which tests should be retried and
 * which tests should be excluded from the next attempt.
 *
 * <p>When processing failed test runs, {@link BaseRetryDecision} calls {@link #recordTestRun}
 * to record the results of the previous test run, and uses {@link #shouldRetry} to determine if
 * the test run should be retried. If the test run should be retried, {@link BaseRetryDecision} will
 * use the information from {@link #getExcludedTests} to exclude tests that have already passed or
 * have reached the maximum number of attempts.
 */
class RetryTracker {
    /** Track the number of attempts for each test case. */
    private Map<TestDescription, Integer> mTestAttemptCounter = new LinkedHashMap<>();

    /** Track the tests that have finished retrying. */
    private Set<TestDescription> mFinishedRetries = new HashSet<>();

    /** Track the tests to be excluded on the upcoming attempt. */
    private Set<TestDescription> mExcludedTests = new HashSet<>();

    /** Whether or not the test run failed (or encountered a fatal run failure). */
    private boolean mHasRunFailure;
    private boolean mHasFatalRunFailure;

    /** Whether we've ever seen a passing test run (at the module level) */
    private boolean mHasRunEverPassed;

    /** The maximum number of empty retries. */
    private static final int MAX_EMPTY_RETRIES = 1;

    /** The number of test runs that finished with no tests to retry. */
    private int mEmptyRetries = 0;

    /** The number of the attempt that was just executed. */
    private int mAttemptJustExecuted;

    /** The maximum number of attempts for each test case. */
    private int mMaxTestCaseAttempts;

    final static private Set<TestStatus> RETRIABLE_STATUSES =
        Set.of(TestStatus.FAILURE, TestStatus.SKIPPED);

    /**
     * Creates a new RetryTracker for the given module.
     *
     * @param testCaseAttempts The maximum number of attempts for the test cases.
     */
    public RetryTracker(int testCaseAttempts) {
        mMaxTestCaseAttempts = testCaseAttempts;
    }

    /** Returns the set of tests that passed or have finished retrying. */
    public Set<TestDescription> getExcludedTests() {
        return mExcludedTests;
    }

    /**
     * Record a test run that was just executed.
     *
     * @param runs The results of the latest attempt.
     * @param attemptJustExecuted The number of the attempt that was just executed.
     * @param skipList The list of tests that should not be retried.
     */
    public void recordTestRun(List<TestRunResult> runs, int attemptJustExecuted, Set<String> skipList) {
        mAttemptJustExecuted = attemptJustExecuted;

        // Only keep the tests that failed in the previous run.
        mHasRunFailure = false;
        mHasFatalRunFailure = false;
        for (var run : runs) {
            if (run.isRunFailure()) {
                mHasRunFailure = true;
                if (!run.getRunFailureDescription().isRetriable()) {
                    mHasFatalRunFailure = true;
                }
            }

            run.getTestResults().forEach((testCase, testResult) -> {
                recordTestCase(testCase, testResult, skipList);
            });

        }

        if (!mHasRunFailure) {
            // Record whether we've ever seen a passing test run.
            mHasRunEverPassed = true;
        }

        mExcludedTests.clear();
        mExcludedTests.addAll(mFinishedRetries);

        for (var run : runs) {
            run.getTestResults().forEach((testCase, testResult) -> {
                if (!isRetriable(testCase, testResult, skipList)) {
                    mExcludedTests.add(testCase);
                }
            });
        }

        // Record an empty retry.
        if (mTestAttemptCounter.isEmpty() && shouldRetry()) {
            mEmptyRetries++;
        }
    }

    /**
     * Returns true if the test run should be retried.
     */
    public boolean shouldRetry() {
        if (mHasFatalRunFailure) {
            CLog.d("Not retrying due to fatal run failure.");
            return false;
        }
        if (!mTestAttemptCounter.isEmpty()) {
            CLog.d("Retrying because there are tests that have not finished retrying.");
            return true;
        }
        if (!mHasRunFailure) {
            CLog.d("Not retrying because there are no tests to retry and module passed.");
            return false;
        }
        if (mHasRunEverPassed) {
            // If the only problem is a module error and it's passed before, we can skip retries.
            CLog.d("Not retrying because there are no tests to retry and module passed before.");
            return false;
        }
        if (mEmptyRetries > MAX_EMPTY_RETRIES && mAttemptJustExecuted >= mMaxTestCaseAttempts) {
            CLog.d("Not retrying because we hit empty retry limit: %d/%d", mEmptyRetries,
                    MAX_EMPTY_RETRIES);
            return false;
        }
        CLog.d("Retrying because the module failed.");
        return true;
    }

    /**
     * Returns true if the test case can ever be retried.
     */
    private boolean isRetriable(TestDescription test, TestResult result, Set<String> skipList) {
        // Don't retry passed tests.
        if (!RETRIABLE_STATUSES.contains(result.getResultStatus())) {
            return false;
        }

        // Don't retry tests that failed with a non-retriable failure (e.g. timeouts).
        var failureDescription = result.getFailure();
        if (failureDescription != null && !failureDescription.isRetriable()) {
            return false;
        }

        // Exclude tests that are finished retrying.
        int attempts = mTestAttemptCounter.getOrDefault(test, 0);
        if (attempts >= mMaxTestCaseAttempts || mFinishedRetries.contains(test)) {
            return false;
        }

        // Exclude tests that are in the skip-retry-list.
        if (skipList.contains(test.toString())) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.RETRY_TEST_SKIPPED_COUNT, 1);
            CLog.d("Skip retry of %s, it's in skip-retry-list.", test.toString());
            return false;
        }

        return true;
    }

    /**
     * Record a test case that was just executed.
     *
     * @param test The test case that was just executed.
     * @param result The result of the test case.
     * @param skipList The list of tests that should not be retried.
     */
    private void recordTestCase(TestDescription test, TestResult result, Set<String> skipList) {
        if (TestStatus.SKIPPED.equals(result.getResultStatus())) {
            mTestAttemptCounter.putIfAbsent(test, 0);
            return;
        }

        // Increment the attempt count for the test.
        mTestAttemptCounter.put(test, mTestAttemptCounter.getOrDefault(test, 0) + 1);

        // Record unretriable tests so we don't retry them again (or miscalculate
        // mSmallestAttemptCount).
        if (!isRetriable(test, result, skipList)) {
            mTestAttemptCounter.remove(test);
            mFinishedRetries.add(test);
        }
    }
}