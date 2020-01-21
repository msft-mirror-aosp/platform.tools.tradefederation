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

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.retry.IAutoRetriableTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link IRetryDecision}. Base implementation only take local signals into
 * account.
 */
public class BaseRetryDecision implements IRetryDecision {

    private static final int ABORT_MAX_FAILURES = 75;

    @Option(
        name = "reboot-at-last-retry",
        description = "Reboot the device at the last retry attempt."
    )
    private boolean mRebootAtLastRetry = false;

    @Option(
        name = "max-testcase-run-count",
        description =
                "If the IRemoteTest can have its testcases run multiple times, "
                        + "the max number of runs for each testcase."
    )
    private int mMaxRetryAttempts = 1;

    @Option(
        name = "retry-strategy",
        description =
                "The retry strategy to be used when re-running some tests with "
                        + "--max-testcase-run-count"
    )
    private RetryStrategy mRetryStrategy = RetryStrategy.NO_RETRY;

    @Option(
        name = "auto-retry",
        description =
                "Whether or not to enable the new auto-retry. This is a feature flag for testing."
    )
    private boolean mEnableAutoRetry = true;

    private IInvocationContext mContext;

    private IRemoteTest mCurrentlyConsideredTest;
    private Set<TestDescription> mPreviouslyFailing;
    private RetryStatsHelper mStatistics;

    /** Constructor for the retry decision */
    public BaseRetryDecision() {}

    @Override
    public boolean isAutoRetryEnabled() {
        return mEnableAutoRetry;
    }

    @Override
    public RetryStrategy getRetryStrategy() {
        return mRetryStrategy;
    }

    @Override
    public int getMaxRetryCount() {
        return mMaxRetryAttempts;
    }

    @Override
    public void setInvocationContext(IInvocationContext context) {
        mContext = context;
    }

    @Override
    public boolean shouldRetry(
            IRemoteTest test, int attemptJustExecuted, List<TestRunResult> previousResults)
            throws DeviceNotAvailableException {
        // Keep track of some results for the test in progress for statistics purpose.
        if (test != mCurrentlyConsideredTest) {
            mCurrentlyConsideredTest = test;
            mStatistics = new RetryStatsHelper();
            mPreviouslyFailing = new HashSet<>();
        }

        switch (mRetryStrategy) {
            case NO_RETRY:
                // Return directly if we are not considering retry at all.
                return false;
            case ITERATIONS:
                // For iterations, retry directly, we have nothing to setup
                return true;
            case RERUN_UNTIL_FAILURE:
                // For retrying until failure, if any failures occurred, skip retry.
                return !hasAnyFailures(previousResults);
            default:
                // Continue the logic for retry the failures.
                break;
        }

        mStatistics.addResultsFromRun(previousResults);
        if (test instanceof ITestFilterReceiver) {
            // TODO(b/77548917): Right now we only support ITestFilterReceiver. We should expect to
            // support ITestFile*Filter*Receiver in the future.
            ITestFilterReceiver filterableTest = (ITestFilterReceiver) test;
            boolean shouldRetry = handleRetryFailures(filterableTest, previousResults);
            if (shouldRetry) {
                // In case of retry, go through the recovery routine
                recoverStateOfDevices(getDevices(), attemptJustExecuted);
            }
            return shouldRetry;
        } else if (test instanceof IAutoRetriableTest) {
            // Routine for IRemoteTest that don't support filters but still needs retry.
            IAutoRetriableTest autoRetryTest = (IAutoRetriableTest) test;
            boolean shouldRetry = autoRetryTest.shouldRetry(attemptJustExecuted, previousResults);
            if (shouldRetry) {
                autoRetryTest.recoverStateOfDevices(getDevices(), attemptJustExecuted);
            }
            return shouldRetry;
        } else {
            CLog.d(
                    "%s does not implement ITestFilterReceiver or IAutoRetriableTest, thus "
                            + "cannot work with auto-retry.",
                    test);
            return false;
        }
    }

    @Override
    public void addLastAttempt(List<TestRunResult> lastResults) {
        mStatistics.addResultsFromRun(lastResults);
    }

    @Override
    public RetryStatistics getRetryStatistics() {
        if (mStatistics == null) {
            return new RetryStatsHelper().calculateStatistics();
        }
        return mStatistics.calculateStatistics();
    }

    /** Returns the set of failed test cases that should be retried. */
    public static Set<TestDescription> getFailedTestCases(List<TestRunResult> previousResults) {
        Set<TestDescription> failedTestCases = new HashSet<TestDescription>();
        for (TestRunResult run : previousResults) {
            if (run != null) {
                failedTestCases.addAll(run.getFailedTests());
            }
        }
        return failedTestCases;
    }

    /** Returns true if there are any run failures in the previous results. */
    public static boolean hasRunFailures(List<TestRunResult> previousResults) {
        for (TestRunResult run : previousResults) {
            if (run != null && run.isRunFailure()) {
                return true;
            }
        }
        return false;
    }

    private boolean handleRetryFailures(
            ITestFilterReceiver test, List<TestRunResult> previousResults) {
        if (hasRunFailures(previousResults)) {
            return true;
        }

        // In case of test case failure, we retry with filters.
        Set<TestDescription> previousFailedTests = getFailedTestCases(previousResults);
        if (!mPreviouslyFailing.isEmpty()) {
            previousFailedTests.retainAll(mPreviouslyFailing);
            mPreviouslyFailing.retainAll(previousFailedTests);
        }
        // Abort if number of failures is high for a given one test
        if (previousFailedTests.size() > ABORT_MAX_FAILURES) {
            CLog.d(
                    "Found %s failures, skipping auto-retry to avoid large overhead.",
                    previousFailedTests.size());
            return false;
        }

        if (!previousFailedTests.isEmpty()) {
            CLog.d("Retrying the test case failure.");
            addRetriedTestsToIncludeFilters(test, previousFailedTests);
            return true;
        }

        CLog.d("No test run or test case failures. No need to retry.");
        return false;
    }

    /** Returns true if there are any failures in the previous results. */
    private boolean hasAnyFailures(List<TestRunResult> previousResults) {
        for (TestRunResult run : previousResults) {
            if (run != null && (run.isRunFailure() || run.hasFailedTests())) {
                return true;
            }
        }
        return false;
    }

    /** Set the filters on the test runner for the retry. */
    private void addRetriedTestsToIncludeFilters(
            ITestFilterReceiver test, Set<TestDescription> testDescriptions) {
        // Limit the re-run to the failure we include, so clear filters then put our failures
        test.clearIncludeFilters();
        for (TestDescription testCase : testDescriptions) {
            // We have to retry without the parameters since some runner don't support it.
            String filter =
                    String.format(
                            "%s#%s", testCase.getClassName(), testCase.getTestNameWithoutParams());
            test.addIncludeFilter(filter);
            mPreviouslyFailing.add(testCase);
        }
    }

    /** Returns all the non-stub device associated with the {@link IRemoteTest}. */
    private List<ITestDevice> getDevices() {
        List<ITestDevice> listDevices = new ArrayList<>(mContext.getDevices());
        // Return all the non-stub device (the one we can actually do some recovery against)
        return listDevices
                .stream()
                .filter(d -> (!(d.getIDevice() instanceof StubDevice)))
                .collect(Collectors.toList());
    }

    /** Recovery attempt on the device to get it a better state before next retry. */
    private void recoverStateOfDevices(List<ITestDevice> devices, int lastAttempt)
            throws DeviceNotAvailableException {
        for (ITestDevice device : devices) {
            if (mRebootAtLastRetry && (lastAttempt == (mMaxRetryAttempts - 2))) {
                device.reboot();
                continue;
            }
        }
    }
}
