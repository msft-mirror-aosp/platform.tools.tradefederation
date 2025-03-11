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

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.internal.DeviceResetHandler;
import com.android.tradefed.device.internal.DeviceSnapshotHandler;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.IsolationGrade;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFileFilterReceiver;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.ITestInformationReceiver;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.testtype.retry.IAutoRetriableTest;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.SuiteTestFilter;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link IRetryDecision}. Base implementation only take local signals into
 * account.
 */
public class BaseRetryDecision
        implements IRetryDecision, IConfigurationReceiver, ITestInformationReceiver {

    @Option(
        name = "reboot-at-last-retry",
        description = "Reboot the device at the last retry attempt."
    )
    private boolean mRebootAtLastRetry = false;

    @Option(
            name = "retry-isolation-grade",
            description = "Control the isolation level that should be attempted between retries."
    )
    private IsolationGrade mRetryIsolationGrade = IsolationGrade.NOT_ISOLATED;

    @Option(
            name = "max-testrun-run-count",
            description =
                    "If the IRemoteTest can have its modules run multiple times, "
                            + "the max number of runs for each test run (module). "
                            + "This is different from max-testcase-run-count which "
                            + "is for each test case. For example, if the testcase "
                            + "run count is 1 and the testrun run count is 3, we "
                            + "will run the module up to 3 times so as to execute "
                            + "each test case once. Format is "
                            + "[<module id>:]<run count> . If module is "
                            + "unspecified, it applies to all modules. Default is "
                            + "to use the value of max-testcase-run-count.")
    private Set<String> mTestRunAttempts = new LinkedHashSet<>();

    @Option(
            name = "max-testcase-run-count",
            description =
                    "If the IRemoteTest can have its testcases run multiple times, "
                            + "the max number of runs for each testcase. Format is "
                            + "[<module id>:]<run count> . If module is "
                            + "unspecified, it applies to all modules. "
                            + "Default is 1 attempt.")
    private Set<String> mTestCaseAttempts = new LinkedHashSet<>();

    @Option(
        name = "retry-strategy",
        description =
                "The retry strategy to be used when re-running some tests with "
                        + "--max-testcase-run-count"
    )
    private RetryStrategy mRetryStrategy = RetryStrategy.NO_RETRY;

    @Option(
            name = "skip-retry-in-presubmit",
            description = "Skip retry attempts specifically in presubmit builds")
    private boolean mSkipRetryInPresubmit = false;

    @Option(
        name = "auto-retry",
        description =
                "Whether or not to enable the new auto-retry. This is a feature flag for testing."
    )
    private boolean mEnableAutoRetry = true;

    @Option(
            name = "skip-retrying-list",
            description =
                    "If a test in the list, skip retrying it. The format is the same as the "
                            + "SuiteTestFilter.")
    private Set<String> mSkipRetryingSet = new LinkedHashSet<>();

    @Deprecated
    @Option(
            name = "updated-retry-reporting",
            description = "Feature flag to use the updated retry reporting strategy.")
    private boolean mUpdatedReporting = true;

    @Deprecated
    @SuppressWarnings("unused")
    @Option(
            name = "updated-filtering",
            description = "Feature flag to use the updated filtering logic.")
    private boolean mUpdatedFiltering = true;

    @Deprecated
    @SuppressWarnings("unused")
    @Option(
            name = "module-preparation-retry",
            description = "Whether or not to retry any module-level target preparation errors." +
                    "This flag is for feature testing, and eventualy it's all controlled under " +
                    "retry strategy."
    )
    private boolean mModulePreparationRetry = false;

    @Option(
            name = "use-snapshot-for-reset",
            description = "Feature flag to use snapshot/restore instead of powerwash.")
    private boolean mUseSnapshotForReset = false;

    private IInvocationContext mContext;
    private IConfiguration mConfiguration;
    private TestInformation mTestInformation;

    private IRemoteTest mCurrentlyConsideredTest;
    private RetryStatsHelper mStatistics;
    private RetryTracker mRetryTracker;
    private ExcludeFilterManager mExcludeManager;
    private RetryCountParser mRetryCountParser;

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
    public boolean rebootAtLastAttempt() {
        return mRebootAtLastRetry;
    }

    private RetryCountParser getRetryCountParser() {
        if (mRetryCountParser == null) {
            mRetryCountParser = new RetryCountParser(mTestCaseAttempts, mTestRunAttempts);
        }
        return mRetryCountParser;
    }

    @Override
    public int getMaxTestRunAttempts() {
        return getMaxTestRunAttempts(null);
    }

    @Override
    public int getMaxTestRunAttempts(ModuleDefinition module) {
        return getRetryCountParser().getMaxTestRunAttempts(module);
    }

    @Override
    public int getMaxTestCaseAttempts() {
        return getRetryCountParser().getMaxTestCaseAttempts(null);
    }

    @Override
    public int getMaxTestCaseAttempts(ModuleDefinition module) {
        return getRetryCountParser().getMaxTestCaseAttempts(module);
    }

    @Override
    public List<String> getCommandLineArgs() {
        List<String> args = new ArrayList<>();
        args.addAll(getRetryCountParser().getCommandLineArgs());
        args.addAll(List.of("--retry-strategy", mRetryStrategy.toString()));
        if (mRebootAtLastRetry) {
            args.add("--reboot-at-last-retry");
        }
        args.addAll(List.of("--retry-isolation-grade", mRetryIsolationGrade.toString()));
        for (String filterEntry : mSkipRetryingSet) {
            args.add("--skip-retrying-list");
            args.add(filterEntry);
        }
        if (mSkipRetryInPresubmit) {
            args.add("--skip-retry-in-presubmit");
        }
        return args;
    }

    @Override
    public void addToSkipRetryList(String filterEntry) {
        mSkipRetryingSet.add(filterEntry);
    }

    @Override
    public RetryPreparationDecision shouldRetryPreparation(
            ModuleDefinition module,
            int attempt,
            int maxAttempt) {
        RetryPreparationDecision decision = new RetryPreparationDecision(false, true);
        switch (mRetryStrategy) {
            case NO_RETRY:
                // Currently, do not retry if RetryStrategy is NO_RETRY.
                return decision;
            default:
                // Continue the logic for retry the failures.
                break;
        }
        if (attempt == maxAttempt) {
            // No need to retry if it reaches the maximum retry count.
            return decision;
        }
        if (mSkipRetryInPresubmit && InvocationContext.isPresubmit(mContext)) {
            CLog.d("Skipping retry due to --skip-retry-in-presubmit");
            return decision;
        }

        // Resetting the device only happends when FULLY_ISOLATED is set, and that cleans up the
        // device to pure state and re-run suite-level or module-level setup. Besides, it doesn't
        // need to retry module for reboot isolation.
        if (!IsolationGrade.FULLY_ISOLATED.equals(mRetryIsolationGrade)) {
            CLog.i("Do not proceed on module retry because it's not set FULLY_ISOLATED.");
            return decision;
        }

        try {
            recoverStateOfDevices(getDevices(), attempt, module);
        } catch (DeviceNotAvailableException e) {
            // Retried failed, set the exception and return the decision.
            decision = new RetryPreparationDecision(true, false);
            decision.setPreviousException(e.getCause());
            return decision;
        }
        // Retried successfully, no exception will be caught, return the decision.
        decision = new RetryPreparationDecision(false, false);
        decision.setPreviousException(null);
        return decision;
    }

    @Override
    public void setInvocationContext(IInvocationContext context) {
        mContext = context;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public void setTestInformation(TestInformation testInformation) {
        mTestInformation = testInformation;
    }

    @Override
    public TestInformation getTestInformation() {
        return mTestInformation;
    }

    @Override
    public boolean shouldRetry(
            IRemoteTest test, int attemptJustExecuted, List<TestRunResult> previousResults)
            throws DeviceNotAvailableException {
        return shouldRetry(test, null, attemptJustExecuted, previousResults, null);
    }

    @Override
    public boolean shouldRetry(
            IRemoteTest test,
            ModuleDefinition module,
            int attemptJustExecuted,
            List<TestRunResult> previousResults,
            DeviceNotAvailableException dnae)
            throws DeviceNotAvailableException {
        // Keep track of some results for the test in progress for statistics purpose.
        if (test != mCurrentlyConsideredTest) {
            mCurrentlyConsideredTest = test;
            mStatistics = new RetryStatsHelper();
            mRetryTracker = new RetryTracker(getMaxTestCaseAttempts(module));
            mExcludeManager = new ExcludeFilterManager(test);
        }

        if (mSkipRetryInPresubmit && InvocationContext.isPresubmit(mContext)) {
            CLog.d("Skipping retry due to --skip-retry-in-presubmit");
            return false;
        }

        boolean isAlreadyRecovered = false;
        if (dnae != null) {
            if (!module.shouldRecoverVirtualDevice()) {
                throw dnae;
            }
            recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
            isAlreadyRecovered = true;
            // Add metrics towards device is recovered by device reset.
            if (IsolationGrade.FULLY_ISOLATED.equals(mRetryIsolationGrade)) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricLogger.InvocationMetricKey
                                .DEVICE_RECOVERED_FROM_DEVICE_RESET,
                        1);
            }
        }

        // Return early for strategies other than RETRY_ANY_FAILURE.
        switch (mRetryStrategy) {
            case NO_RETRY:
                // Return directly if we are not considering retry at all.
                return false;
            case ITERATIONS:
                // Still support isolating the iterations if that's configured
                if (!isAlreadyRecovered) {
                    recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
                }
                // For iterations, retry directly, we have nothing to setup
                return true;
            case RERUN_UNTIL_FAILURE:
                // For retrying until failure, if any failures occurred, skip retry.
                return !hasAnyFailures(previousResults);
            default:
                // Continue the logic for retry the failures.
                break;
        }

        if (!hasAnyFailures(previousResults)) {
            CLog.d("No test run or test case failures. No need to retry.");
            mStatistics.addResultsFromRun(previousResults, 0L, attemptJustExecuted);
            return false;
        }

        Set<String> moduleSkipList = new LinkedHashSet<String>();
        if (module != null && isInSkipList(module, moduleSkipList)) {
            CLog.d("Skip retrying known failure test of %s", module.getId());
            InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.RETRY_SKIPPED_ALL_FILTERED_COUNT, 1);
            return false;
        }
        if (module == null) {
            // If it's not a module, carry all filters
            moduleSkipList.addAll(mSkipRetryingSet);
        }

        boolean shouldRetry = false;
        long retryStartTime = System.currentTimeMillis();
        if (test instanceof ITestFilterReceiver || test instanceof ITestFileFilterReceiver) {
            // Record the attempt for the previous failed tests.
            mRetryTracker.recordTestRun(previousResults, attemptJustExecuted, moduleSkipList);

            // Setup exclude filters.
            mExcludeManager.resetDefaultFilters();
            mExcludeManager.addExcludeFilters(mRetryTracker.getExcludedTests());

            // Check if we should retry.
            shouldRetry = mRetryTracker.shouldRetry();

            if (shouldRetry && !isAlreadyRecovered) {
                // In case of retry, go through the recovery routine
                recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
            }
        } else if (test instanceof IAutoRetriableTest) {
            // Routine for IRemoteTest that don't support filters but still needs retry.
            IAutoRetriableTest autoRetryTest = (IAutoRetriableTest) test;
            shouldRetry =
                    autoRetryTest.shouldRetry(attemptJustExecuted, previousResults, moduleSkipList);
            if (shouldRetry && !isAlreadyRecovered) {
                recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
            }
        } else {
            CLog.d(
                    "%s does not implement ITestFilterReceiver or ITestFileFilterReceiver or "
                            + "IAutoRetriableTest, thus cannot work with auto-retry.",
                    test);
            return false;
        }
        long retryCost = System.currentTimeMillis() - retryStartTime;
        if (!shouldRetry) {
            retryCost = 0L;
        }
        mStatistics.addResultsFromRun(previousResults, retryCost, attemptJustExecuted);
        return shouldRetry;
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

    /** Returns true if we should use the updated reporting. */
    @Override
    public boolean useUpdatedReporting() {
        return mUpdatedReporting;
    }

    @VisibleForTesting
    public IsolationGrade getIsolationGrade() {
        return mRetryIsolationGrade;
    }

    public Set<String> getSkipRetrySet() {
        return mSkipRetryingSet;
    }

    /**
     * Skips retry if the module is fully skipped and populate module skip list if only some tests
     * need to stop retrying.
     */
    private boolean isInSkipList(ModuleDefinition module, Set<String> moduleSkipList) {
        String moduleId = module.getId();
        if (moduleId == null) {
            return false;
        }
        SuiteTestFilter moduleIdFilter = SuiteTestFilter.createFrom(moduleId);
        String abi = moduleIdFilter.getAbi();
        String name = moduleIdFilter.getName();

        boolean shouldSkip = false;
        for (String skipTest : mSkipRetryingSet) {
            // Only handle module level exclusion
            SuiteTestFilter skipRetryingFilter = SuiteTestFilter.createFrom(skipTest);
            String skipAbi = skipRetryingFilter.getAbi();
            String skipName = skipRetryingFilter.getName();
            String skipTestName = skipRetryingFilter.getTest();
            if (abi != null
                    && name != null
                    && skipName != null
                    && name.equals(skipName)) {
                if (skipAbi != null && !abi.equals(skipAbi)) {
                    // If the skip has an explicit abi that doesn't match
                    // module, don't skip. If not specified, consider all modules
                    continue;
                }
                if (skipTestName == null) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.RETRY_MODULE_SKIPPED_COUNT, 1);
                    shouldSkip = true;
                } else {
                    moduleSkipList.add(skipTestName);
                }
            }
        }
        return shouldSkip;
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

    /** Returns all the non-stub device associated with the {@link IRemoteTest}. */
    private List<ITestDevice> getDevices() {
        List<ITestDevice> listDevices = new ArrayList<>(mContext.getDevices());
        // Return all the non-stub device (the one we can actually do some recovery against)
        return listDevices
                .stream()
                .filter(d -> !(d.getIDevice() instanceof StubDevice))
                .collect(Collectors.toList());
    }

    /** Recovery attempt on the device to get it a better state before next retry. */
    private void recoverStateOfDevices(
            List<ITestDevice> devices, int lastAttempt, ModuleDefinition module)
            throws DeviceNotAvailableException {
        if (IsolationGrade.REBOOT_ISOLATED.equals(mRetryIsolationGrade)) {
            long start = System.currentTimeMillis();
            try (CloseableTraceScope ignored = new CloseableTraceScope("reboot_isolation")) {
                for (ITestDevice device : devices) {
                    device.reboot();
                }
                CurrentInvocation.setModuleIsolation(IsolationGrade.REBOOT_ISOLATED);
                CurrentInvocation.setRunIsolation(IsolationGrade.REBOOT_ISOLATED);
            } finally {
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.REBOOT_RETRY_ISOLATION_PAIR,
                        start, System.currentTimeMillis());
            }
        } else if (IsolationGrade.FULLY_ISOLATED.equals(mRetryIsolationGrade)) {
            resetIsolation(module, devices);
        } else if (lastAttempt >= (getMaxTestCaseAttempts(module) - 2)) {
            // Reset only works for suite right now
            if (mRebootAtLastRetry) {
                for (ITestDevice device : devices) {
                    device.reboot();
                }
                CurrentInvocation.setModuleIsolation(IsolationGrade.REBOOT_ISOLATED);
                CurrentInvocation.setRunIsolation(IsolationGrade.REBOOT_ISOLATED);
            }
        }
    }

    private void resetIsolation(ModuleDefinition module, List<ITestDevice> devices)
            throws DeviceNotAvailableException {
        long start = System.currentTimeMillis();
        try (CloseableTraceScope ignored = new CloseableTraceScope("reset_isolation")) {
            isolateRetry(devices);
            CLog.d(
                    "Current host properties being erased by reset: %s",
                    mTestInformation.properties().getAll());
            mTestInformation.properties().clear();
            // Rerun suite level preparer if we are inside a subprocess
            reSetupModule(
                    module,
                    (mConfiguration
                                    .getCommandOptions()
                                    .getInvocationData()
                                    .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)
                            && !mUseSnapshotForReset));
        } finally {
            InvocationMetricLogger.addInvocationPairMetrics(
                    InvocationMetricKey.RESET_RETRY_ISOLATION_PAIR,
                    start, System.currentTimeMillis());
        }
    }

    @VisibleForTesting
    protected void isolateRetry(List<ITestDevice> devices) throws DeviceNotAvailableException {
        if (!mUseSnapshotForReset) {
            DeviceResetHandler handler = new DeviceResetHandler(mContext);
            for (ITestDevice device : devices) {
                boolean resetSuccess = handler.resetDevice(device);
                if (!resetSuccess) {
                    throw new DeviceNotAvailableException(
                            String.format("Failed to reset device: %s", device.getSerialNumber()),
                            device.getSerialNumber(),
                            DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
                }
            }
        } else {
            for (ITestDevice device : devices) {
                new DeviceSnapshotHandler()
                        .restoreSnapshotDevice(device, mContext.getInvocationId());
            }
        }
    }

    private void reSetupModule(ModuleDefinition module, boolean includeSuitePreparers)
            throws DeviceNotAvailableException {
        if (module == null) {
            return;
        }
        if (module.getId() != null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICE_RESET_MODULES, module.getId());
        }
        // Run all preparers including optionally suite level ones.
        Throwable preparationException =
                module.runPreparation(includeSuitePreparers);
        if (preparationException != null) {
            CLog.e(preparationException);
            throw new DeviceNotAvailableException(
                    String.format(
                            "Failed to reset devices before retry: %s",
                            preparationException.toString()),
                    preparationException,
                    "serial",
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
        }
    }
}