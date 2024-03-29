/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.result.suite;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.TestStatus;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.result.TestSummary.Type;
import com.android.tradefed.result.TestSummary.TypedString;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.TimeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

/** Collect test results for an entire suite invocation and output the final results. */
public class SuiteResultReporter extends CollectingTestListener {

    public static final String SUITE_REPORTER_SOURCE = SuiteResultReporter.class.getName();

    private Long mStartTime = null;
    private long mEndTime = 0L;

    private AtomicInteger mTotalModules = new AtomicInteger(0);
    private AtomicInteger mCompleteModules = new AtomicInteger(0);

    private long mTotalTests = 0L;
    private long mPassedTests = 0L;
    private long mFailedTests = 0L;
    private long mSkippedTests = 0L;
    private long mAssumeFailureTests = 0L;

    // Retry information
    private long mTotalRetrySuccess = 0L;
    private Map<String, Long> mModuleRetrySuccess = new LinkedHashMap<>();
    private long mTotalRetryFail = 0L;
    private Map<String, Long> mModuleRetryFail = new LinkedHashMap<>();
    private long mTotalRetryTime = 0L;
    private Map<String, Long> mModuleRetryTime = new LinkedHashMap<>();

    private Map<String, String> mFailedModule = new HashMap<>();
    // Map holding the preparation time for each Module.
    private Map<String, ModulePrepTimes> mPreparationMap = new HashMap<>();

    private Map<String, IAbi> mModuleAbi = new LinkedHashMap<>();

    private StringBuilder mSummary;

    public SuiteResultReporter() {
        super();
        // force aggregate true to get full metrics.
        setIsAggregrateMetrics(true);
        mSummary = new StringBuilder();
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        super.invocationStarted(context);
        if (mStartTime == null) {
            mStartTime = getCurrentTime();
        }
    }

    @Override
    public void testModuleStarted(IInvocationContext moduleContext) {
        super.testModuleStarted(moduleContext);
        // Keep track of the module abi if it has one.
        List<String> abiName = moduleContext.getAttributes().get(ModuleDefinition.MODULE_ABI);
        if (abiName != null) {
            IAbi abi = new Abi(abiName.get(0), AbiUtils.getBitness(abiName.get(0)));
            mModuleAbi.put(
                    moduleContext.getAttributes().get(ModuleDefinition.MODULE_ID).get(0), abi);
        }
    }

    /** Helper to remove the module checker results from the final list of real module results. */
    private List<TestRunResult> extractModuleCheckers(Collection<TestRunResult> results) {
        List<TestRunResult> moduleCheckers = new ArrayList<TestRunResult>();
        for (TestRunResult t : results) {
            if (t.getName().startsWith(ITestSuite.MODULE_CHECKER_POST)
                    || t.getName().startsWith(ITestSuite.MODULE_CHECKER_PRE)) {
                moduleCheckers.add(t);
            }
        }
        results.removeAll(moduleCheckers);
        return moduleCheckers;
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        mEndTime = getCurrentTime();
        super.invocationEnded(elapsedTime);

        // finalize and print results - general
        Collection<TestRunResult> results = getMergedTestRunResults();
        List<TestRunResult> moduleCheckers = extractModuleCheckers(results);

        mTotalModules.set(results.size());

        for (TestRunResult moduleResult : results) {
            if (!moduleResult.isRunFailure()) {
                mCompleteModules.incrementAndGet();
            } else {
                mFailedModule.put(moduleResult.getName(), moduleResult.getRunFailureMessage());
            }
            mTotalTests += moduleResult.getExpectedTestCount();
            mPassedTests += moduleResult.getNumTestsInState(TestStatus.PASSED);
            mFailedTests += moduleResult.getNumAllFailedTests();
            mSkippedTests += moduleResult.getNumTestsInState(TestStatus.IGNORED);
            // TODO(b/322204420): differentiate SKIPPED and IGNORED
            mSkippedTests += moduleResult.getNumTestsInState(TestStatus.SKIPPED);
            mAssumeFailureTests += moduleResult.getNumTestsInState(TestStatus.ASSUMPTION_FAILURE);

            // Get the module metrics for target preparation
            String prepTime = moduleResult.getRunMetrics().get(ModuleDefinition.PREPARATION_TIME);
            String tearTime = moduleResult.getRunMetrics().get(ModuleDefinition.TEAR_DOWN_TIME);
            if (prepTime != null && tearTime != null) {
                mPreparationMap.put(
                        moduleResult.getName(),
                        new ModulePrepTimes(Long.parseLong(prepTime), Long.parseLong(tearTime)));
            }

            // If they exists, get all the retry information
            String retrySuccess =
                    moduleResult.getRunMetrics().get(ModuleDefinition.RETRY_SUCCESS_COUNT);
            if (retrySuccess != null) {
                mTotalRetrySuccess += Long.parseLong(retrySuccess);
                mModuleRetrySuccess.put(moduleResult.getName(), Long.parseLong(retrySuccess));
            }
            String retryFailure =
                    moduleResult.getRunMetrics().get(ModuleDefinition.RETRY_FAIL_COUNT);
            if (retryFailure != null) {
                mTotalRetryFail += Long.parseLong(retryFailure);
                mModuleRetryFail.put(moduleResult.getName(), Long.parseLong(retryFailure));
            }
            String retryTime = moduleResult.getRunMetrics().get(ModuleDefinition.RETRY_TIME);
            if (retryTime != null) {
                mTotalRetryTime += Long.parseLong(retryTime);
                mModuleRetryTime.put(moduleResult.getName(), Long.parseLong(retryTime));
            }
        }
        // print a short report summary
        mSummary.append("\n============================================\n");
        mSummary.append("================= Results ==================\n");
        printModuleTestTime(results);
        printTopSlowModules(results);
        printPreparationMetrics(mPreparationMap);
        printModuleCheckersMetric(moduleCheckers);
        printModuleRetriesInformation();
        mSummary.append("=============== Summary ===============\n");
        // Print the time from invocation start to end
        if (mStartTime == null) {
            mStartTime = 0L;
        }
        mSummary.append(
                String.format(
                        "Total Run time: %s\n", TimeUtil.formatElapsedTime(mEndTime - mStartTime)));
        mSummary.append(
                String.format("%s/%s modules completed\n", mCompleteModules, mTotalModules));
        if (!mFailedModule.isEmpty()) {
            mSummary.append("Module(s) with run failure(s):\n");
            for (Entry<String, String> e : mFailedModule.entrySet()) {
                mSummary.append(String.format("    %s: %s\n", e.getKey(), e.getValue()));
            }
        }
        mSummary.append(String.format("Total Tests       : %s\n", mTotalTests));
        mSummary.append(String.format("PASSED            : %s\n", mPassedTests));
        mSummary.append(String.format("FAILED            : %s\n", mFailedTests));

        if (mSkippedTests > 0L) {
            mSummary.append(String.format("IGNORED           : %s\n", mSkippedTests));
        }
        if (mAssumeFailureTests > 0L) {
            mSummary.append(String.format("ASSUMPTION_FAILURE: %s\n", mAssumeFailureTests));
        }

        if (mCompleteModules.get() != mTotalModules.get()) {
            mSummary.append(
                    "IMPORTANT: Some modules failed to run to completion, tests counts "
                            + "may be inaccurate.\n");
        }

        for (Entry<Integer, List<String>> shard :
                getInvocationContext().getShardsSerials().entrySet()) {
            mSummary.append(String.format("Shard %s used: %s\n", shard.getKey(), shard.getValue()));
        }
        mSummary.append("============== End of Results ==============\n");
        mSummary.append("============================================\n");
        CLog.logAndDisplay(LogLevel.INFO, mSummary.toString());
    }

    /** Displays the time consumed by each module to run. */
    private void printModuleTestTime(Collection<TestRunResult> results) {
        List<TestRunResult> moduleTime = new ArrayList<>();
        moduleTime.addAll(results);
        Collections.sort(
                moduleTime,
                new Comparator<TestRunResult>() {
                    @Override
                    public int compare(TestRunResult o1, TestRunResult o2) {
                        return (int) (o2.getElapsedTime() - o1.getElapsedTime());
                    }
                });
        long totalRunTime = 0L;
        mSummary.append("=============== Consumed Time ==============\n");
        for (int i = 0; i < moduleTime.size(); i++) {
            mSummary.append(
                    String.format(
                            "    %s: %s\n",
                            moduleTime.get(i).getName(),
                            TimeUtil.formatElapsedTime(moduleTime.get(i).getElapsedTime())));
            totalRunTime += moduleTime.get(i).getElapsedTime();
        }
        mSummary.append(
                String.format(
                        "Total aggregated tests run time: %s\n",
                        TimeUtil.formatElapsedTime(totalRunTime)));
    }

    /**
     * Display the average tests / second of modules because elapsed is not always relevant. (Some
     * modules have way more test cases than others so only looking at elapsed time is not a good
     * metric for slow modules).
     */
    private void printTopSlowModules(Collection<TestRunResult> results) {
        List<TestRunResult> moduleTime = new ArrayList<>();
        moduleTime.addAll(results);
        // We don't consider module which runs in less than 5 sec or that didn't run tests
        for (TestRunResult t : results) {
            if (t.getElapsedTime() < 5000) {
                moduleTime.remove(t);
            }
            if (t.getNumTests() == 0) {
                moduleTime.remove(t);
            }
        }
        Collections.sort(
                moduleTime,
                new Comparator<TestRunResult>() {
                    @Override
                    public int compare(TestRunResult o1, TestRunResult o2) {
                        Float rate1 = ((float) o1.getNumTests() / o1.getElapsedTime());
                        Float rate2 = ((float) o2.getNumTests() / o2.getElapsedTime());
                        return rate1.compareTo(rate2);
                    }
                });
        int maxModuleDisplay = moduleTime.size();
        if (maxModuleDisplay == 0) {
            return;
        }
        mSummary.append(
                String.format(
                        "============== TOP %s Slow Modules ==============\n", maxModuleDisplay));
        for (int i = 0; i < maxModuleDisplay; i++) {
            mSummary.append(
                    String.format(
                            "    %s: %.02f tests/sec [%s tests / %s msec]\n",
                            moduleTime.get(i).getName(),
                            (moduleTime.get(i).getNumTests()
                                    / (moduleTime.get(i).getElapsedTime() / 1000f)),
                            moduleTime.get(i).getNumTests(),
                            moduleTime.get(i).getElapsedTime()));
        }
    }

    /** Print the collected times for Module preparation and tear Down. */
    private void printPreparationMetrics(Map<String, ModulePrepTimes> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        mSummary.append("============== Modules Preparation Times ==============\n");
        long totalPrep = 0L;
        long totalTear = 0L;

        for (String moduleName : metrics.keySet()) {
            mSummary.append(
                    String.format(
                            "    %s => %s\n", moduleName, metrics.get(moduleName).toString()));
            totalPrep += metrics.get(moduleName).mPrepTime;
            totalTear += metrics.get(moduleName).mTearDownTime;
        }
        mSummary.append(
                String.format(
                        "Total preparation time: %s  ||  Total tear down time: %s\n",
                        TimeUtil.formatElapsedTime(totalPrep),
                        TimeUtil.formatElapsedTime(totalTear)));
        mSummary.append("=======================================================\n");
    }

    private void printModuleCheckersMetric(List<TestRunResult> moduleCheckerResults) {
        if (moduleCheckerResults.isEmpty()) {
            return;
        }
        mSummary.append("============== Modules Checkers Times ==============\n");
        long totalTime = 0L;
        for (TestRunResult t : moduleCheckerResults) {
            mSummary.append(
                    String.format(
                            "    %s: %s\n",
                            t.getName(), TimeUtil.formatElapsedTime(t.getElapsedTime())));
            totalTime += t.getElapsedTime();
        }
        mSummary.append(
                String.format(
                        "Total module checkers time: %s\n", TimeUtil.formatElapsedTime(totalTime)));
        mSummary.append("====================================================\n");
    }

    private void printModuleRetriesInformation() {
        if (mModuleRetrySuccess.isEmpty() || mTotalRetryTime == 0L) {
            return;
        }
        mSummary.append("============== Modules Retries Information ==============\n");
        for (String t : mModuleRetrySuccess.keySet()) {
            if (mModuleRetryTime.get(t) == 0L) {
                continue;
            }
            mSummary.append(
                    String.format(
                            "    %s:\n"
                                    + "        Retry Success (Failed test became Pass)   = %s\n"
                                    + "        Retry Failure (Failed test stayed Failed) = %s\n"
                                    + "        Retry Time                                = %s\n",
                            t,
                            mModuleRetrySuccess.get(t),
                            mModuleRetryFail.get(t),
                            TimeUtil.formatElapsedTime(mModuleRetryTime.get(t))));
        }
        mSummary.append("Summary:\n");
        mSummary.append(
                String.format(
                        "Total Retry Success (Failed test became Pass) = %s\n"
                                + "Total Retry Failure (Failed test stayed Failed) = %s\n"
                                + "Total Retry Time                                = %s\n",
                        mTotalRetrySuccess,
                        mTotalRetryFail,
                        TimeUtil.formatElapsedTime(mTotalRetryTime)));
        mSummary.append("====================================================\n");
    }

    /** Returns a map of modules abi: <module id, abi>. */
    public Map<String, IAbi> getModulesAbi() {
        return mModuleAbi;
    }

    public int getTotalModules() {
        return mTotalModules.get();
    }

    public int getCompleteModules() {
        return mCompleteModules.get();
    }

    public long getTotalTests() {
        return mTotalTests;
    }

    public long getPassedTests() {
        return mPassedTests;
    }

    public long getFailedTests() {
        return mFailedTests;
    }

    /** Object holder for the preparation and tear down time of one module. */
    public static class ModulePrepTimes {

        public final long mPrepTime;
        public final long mTearDownTime;

        public ModulePrepTimes(long prepTime, long tearTime) {
            mPrepTime = prepTime;
            mTearDownTime = tearTime;
        }

        @Override
        public String toString() {
            return String.format("prep = %s ms || clean = %s ms", mPrepTime, mTearDownTime);
        }
    }

    @Override
    public TestSummary getSummary() {
        if (mSummary == null || mSummary.toString().isEmpty()) {
            return null;
        }
        TestSummary summary = new TestSummary(new TypedString(mSummary.toString(), Type.TEXT));
        summary.setSource(SUITE_REPORTER_SOURCE);
        return summary;
    }

    /** Returns the start time of the invocation. */
    protected long getStartTime() {
        return mStartTime;
    }

    /** Returns the end time of the invocation. */
    protected long getEndTime() {
        return mEndTime;
    }

    @VisibleForTesting
    protected long getCurrentTime() {
        return System.currentTimeMillis();
    }
}
