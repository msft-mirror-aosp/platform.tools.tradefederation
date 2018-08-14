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
package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestResult.TestStatus;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * A {@link ITestInvocationListener} that will collect all test results.
 *
 * <p>Although the data structures used in this object are thread-safe, the {@link
 * ITestInvocationListener} callbacks must be called in the correct order.
 */
public class CollectingTestListener implements ITestInvocationListener, ILogSaverListener {

    @Option(
            name = "aggregate-metrics",
            description = "attempt to add test metrics values for test runs with the same name.")
    private boolean mIsAggregateMetrics = false;

    /** Toggle the 'aggregate metrics' option */
    protected void setIsAggregrateMetrics(boolean aggregate) {
        mIsAggregateMetrics = aggregate;
    }

    private IInvocationContext mContext;
    private IBuildInfo mBuildInfo;
    private Map<String, IInvocationContext> mModuleContextMap = new HashMap<>();
    // Use LinkedHashMap to provide consistent iterations over the keys.
    private Map<String, List<TestRunResult>> mTestRunResultMap = new LinkedHashMap<>();

    private IInvocationContext mCurrentModuleContext = null;
    private TestRunResult mCurrentTestRunResult = new TestRunResult();

    // Tracks if mStatusCounts are accurate, or if they need to be recalculated
    private boolean mIsCountDirty = true;
    // Represents the merged test results. This should not be accessed directly since it's only
    // calculated when needed.
    private List<TestRunResult> mMergedTestRunResults = new ArrayList<>();
    // Represents the number of tests in each TestStatus state of the merged test results. Indexed
    // by TestStatus.ordinal()
    private int[] mStatusCounts = new int[TestStatus.values().length];

    /**
     * Return the primary build info that was reported via {@link
     * #invocationStarted(IInvocationContext)}. Primary build is the build returned by the first
     * build provider of the running configuration. Returns null if there is no context (no build to
     * test case).
     */
    public IBuildInfo getPrimaryBuildInfo() {
        if (mContext == null) {
            return null;
        } else {
            return mContext.getBuildInfos().get(0);
        }
    }

    /**
     * Return the invocation context that was reported via {@link
     * #invocationStarted(IInvocationContext)}
     */
    public IInvocationContext getInvocationContext() {
        return mContext;
    }

    /**
     * Returns the build info.
     *
     * @deprecated rely on the {@link IBuildInfo} from {@link #getInvocationContext()}.
     */
    @Deprecated
    public IBuildInfo getBuildInfo() {
        return mBuildInfo;
    }

    /**
     * Set the build info. Should only be used for testing.
     *
     * @deprecated Not necessary for testing anymore.
     */
    @VisibleForTesting
    @Deprecated
    public void setBuildInfo(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    /** {@inheritDoc} */
    @Override
    public void invocationStarted(IInvocationContext context) {
        mContext = context;
        mBuildInfo = getPrimaryBuildInfo();
    }

    /** {@inheritDoc} */
    @Override
    public void invocationEnded(long elapsedTime) {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void invocationFailed(Throwable cause) {
        // ignore
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        mCurrentModuleContext = moduleContext;
    }

    @Override
    public void testModuleEnded() {
        mCurrentModuleContext = null;
    }

    /** {@inheritDoc} */
    @Override
    public void testRunStarted(String name, int numTests) {
        testRunStarted(name, numTests, 0);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunStarted(String name, int numTests, int attemptNumber) {
        mIsCountDirty = true;

        // Associate the run name with the current module context
        if (mCurrentModuleContext != null) {
            mModuleContextMap.put(name, mCurrentModuleContext);
        }

        // Add the list of maps if the run doesn't exist
        if (!mTestRunResultMap.containsKey(name)) {
            mTestRunResultMap.put(name, new LinkedList<>());
        }
        List<TestRunResult> results = mTestRunResultMap.get(name);

        // Set the current test run result based on the attempt
        if (attemptNumber < results.size()) {
            if (results.get(attemptNumber) == null) {
                throw new RuntimeException(
                        "Test run results should never be null in internal structure.");
            }
        } else if (attemptNumber == results.size()) {
            // new run
            TestRunResult result = new TestRunResult();
            result.setAggregateMetrics(mIsAggregateMetrics);
            results.add(result);
        } else {
            throw new IllegalArgumentException(
                    String.format(
                            "The attempt number cannot contain gaps. Expected [0-%d] and got %d",
                            results.size(), attemptNumber));
        }
        mCurrentTestRunResult = results.get(attemptNumber);

        mCurrentTestRunResult.testRunStarted(name, numTests);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testRunEnded(elapsedTime, runMetrics);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunFailed(String errorMessage) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testRunFailed(errorMessage);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunStopped(long elapsedTime) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testRunStopped(elapsedTime);
    }

    /** {@inheritDoc} */
    @Override
    public void testStarted(TestDescription test) {
        testStarted(test, System.currentTimeMillis());
    }

    /** {@inheritDoc} */
    @Override
    public void testStarted(TestDescription test, long startTime) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testStarted(test, startTime);
    }

    /** {@inheritDoc} */
    @Override
    public void testEnded(TestDescription test, HashMap<String, Metric> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    /** {@inheritDoc} */
    @Override
    public void testEnded(TestDescription test, long endTime, HashMap<String, Metric> testMetrics) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testEnded(test, endTime, testMetrics);
    }

    /** {@inheritDoc} */
    @Override
    public void testFailed(TestDescription test, String trace) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testFailed(test, trace);
    }

    @Override
    public void testAssumptionFailure(TestDescription test, String trace) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testAssumptionFailure(test, trace);
    }

    @Override
    public void testIgnored(TestDescription test) {
        mIsCountDirty = true;
        mCurrentTestRunResult.testIgnored(test);
    }

    /** {@inheritDoc} */
    @Override
    public void logAssociation(String dataName, LogFile logFile) {
        mCurrentTestRunResult.testLogSaved(dataName, logFile);
    }

    /**
     * Gets the results for the current test run.
     *
     * <p>Note the results may not be complete. It is recommended to test the value of {@link
     * TestRunResult#isRunComplete()} and/or (@link TestRunResult#isRunFailure()} as appropriate
     * before processing the results.
     *
     * @return the {@link TestRunResult} representing data collected during last test run
     */
    public TestRunResult getCurrentRunResults() {
        return mCurrentTestRunResult;
    }

    /** Returns the total number of complete tests for all runs. */
    public int getNumTotalTests() {
        computeMergedResults();
        int total = 0;
        for (TestStatus s : TestStatus.values()) {
            total += mStatusCounts[s.ordinal()];
        }
        return total;
    }

    /** Returns the number of tests in given state for this run. */
    public int getNumTestsInState(TestStatus status) {
        computeMergedResults();
        return mStatusCounts[status.ordinal()];
    }

    /** Returns if the invocation had any failed or assumption failed tests. */
    public boolean hasFailedTests() {
        return getNumAllFailedTests() > 0;
    }

    /** Returns the total number of test runs in a failure state */
    public int getNumAllFailedTestRuns() {
        int count = 0;
        for (TestRunResult result : getMergedTestRunResults()) {
            if (result.isRunFailure()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of tests in a failure state (only failed, assumption failures do not
     * count toward it).
     */
    public int getNumAllFailedTests() {
        return getNumTestsInState(TestStatus.FAILURE);
    }

    /**
     * Return the merged collection of results for all runs across different attempts.
     *
     * <p>If there are multiple results, each test run is merged, with the latest test result
     * overwriting test results of previous runs. Test runs are ordered by attempt number.
     *
     * <p>Metrics for the same attempt will be merged based on the preference set by {@code
     * aggregate-metrics}. The final metrics will be the metrics of the last attempt.
     */
    public List<TestRunResult> getMergedTestRunResults() {
        computeMergedResults();
        return mMergedTestRunResults;
    }

    /**
     * Returns the results for all test runs.
     *
     * @deprecated Use {@link #getMergedTestRunResults()}
     */
    @Deprecated
    public Collection<TestRunResult> getRunResults() {
        return getMergedTestRunResults();
    }

    /**
     * Computes and stores the merged results and the total status counts since both operations are
     * expensive.
     */
    private void computeMergedResults() {
        if (!mIsCountDirty) {
            return;
        }

        // Merge results
        mMergedTestRunResults = new ArrayList<>(mTestRunResultMap.size());
        for (List<TestRunResult> results : mTestRunResultMap.values()) {
            mMergedTestRunResults.add(TestRunResult.merge(results));
        }

        // Reset counts
        for (TestStatus s : TestStatus.values()) {
            mStatusCounts[s.ordinal()] = 0;
        }

        // Calculate results
        for (TestRunResult result : mMergedTestRunResults) {
            for (TestStatus s : TestStatus.values()) {
                mStatusCounts[s.ordinal()] += result.getNumTestsInState(s);
            }
        }

        mIsCountDirty = false;
    }

    /**
     * Return all the names for all the test runs.
     *
     * <p>These test runs may have run multiple times with different attempts.
     */
    public Collection<String> getTestRunNames() {
        return new ArrayList<String>(mTestRunResultMap.keySet());
    }

    /**
     * Gets all the attempts for a {@link TestRunResult} of a given test run.
     *
     * @param testRunName The name given by {{@link #testRunStarted(String, int)}.
     * @return All {@link TestRunResult} for a given test run, ordered by attempts.
     */
    public List<TestRunResult> getTestRunAttempts(String testRunName) {
        return mTestRunResultMap.get(testRunName);
    }

    /**
     * Returns whether a given test run name has any results.
     *
     * @param testRunName The name given by {{@link #testRunStarted(String, int)}.
     */
    public boolean hasTestRunResultsForName(String testRunName) {
        return mTestRunResultMap.containsKey(testRunName);
    }

    /**
     * Returns the number of attempts for a given test run name.
     *
     * @param testRunName The name given by {{@link #testRunStarted(String, int)}.
     */
    public int getTestRunAttemptCount(String testRunName) {
        List<TestRunResult> results = mTestRunResultMap.get(testRunName);
        if (results == null) {
            return 0;
        }
        return results.size();
    }

    /**
     * Return the {@link TestRunResult} for a single attempt.
     *
     * @param testRunName The name given by {{@link #testRunStarted(String, int)}.
     * @param attempt The attempt id.
     * @return The {@link TestRunResult} for the given name and attempt id or {@code null} if it
     *     does not exist.
     */
    public TestRunResult getTestRunAtAttempt(String testRunName, int attempt) {
        List<TestRunResult> results = mTestRunResultMap.get(testRunName);
        if (results == null || attempt < 0 || attempt >= results.size()) {
            return null;
        }

        return results.get(attempt);
    }

    /**
     * Returns the {@link IInvocationContext} of the module associated with the results.
     *
     * @param testRunName The name given by {{@link #testRunStarted(String, int)}.
     * @return The {@link IInvocationContext} of the module for a given test run name {@code null}
     *     if there are no results for that name.
     */
    public IInvocationContext getModuleContextForRunResult(String testRunName) {
        return mModuleContextMap.get(testRunName);
    }
}
