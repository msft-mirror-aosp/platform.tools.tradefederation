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

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.testrunner.TestResult.TestStatus;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.device.internal.DeviceResetHandler;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.IsolationGrade;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.testtype.retry.IAutoRetriableTest;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.SuiteTestFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link IRetryDecision}. Base implementation only take local signals into
 * account.
 */
public class BaseRetryDecision implements IRetryDecision, IConfigurationReceiver {

    private static final int ABORT_MAX_FAILURES = 75;

    @Option(
        name = "reboot-at-last-retry",
        description = "Reboot the device at the last retry attempt."
    )
    private boolean mRebootAtLastRetry = false;

    @Option(
            name = "reset-at-last-retry",
            description =
                    "Reset or powerwash the device at the last retry attempt. If this option is "
                            + "set, option `reboot-at-last-retry` will be ignored.")
    private boolean mResetAtLastRetry = false;

    @Option(
            name = "retry-isolation-grade",
            description = "Control the isolation level that should be attempted between retries."
    )
    private IsolationGrade mRetryIsolationGrade = IsolationGrade.NOT_ISOLATED;

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

    @Option(
            name = "skip-retrying-list",
            description =
                    "If a test in the list, skip retrying it. The format is the same as the "
                            + "SuiteTestFilter.")
    private Set<String> mSkipRetryingList = new HashSet<>();

    @Option(
            name = "updated-retry-reporting",
            description = "Feature flag to use the updated retry reporting strategy.")
    private boolean mUpdatedReporting = true;

    @Option(
            name = "updated-filtering",
            description = "Feature flag to use the updated filtering logic.")
    private boolean mUpdatedFiltering = true;

    private IInvocationContext mContext;
    private IConfiguration mConfiguration;

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
    public boolean rebootAtLastAttempt() {
        return mRebootAtLastRetry;
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
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    @Override
    public boolean shouldRetry(
            IRemoteTest test, int attemptJustExecuted, List<TestRunResult> previousResults)
            throws DeviceNotAvailableException {
        return shouldRetry(test, null, attemptJustExecuted, previousResults);
    }

    @Override
    public boolean shouldRetry(
            IRemoteTest test,
            ModuleDefinition module,
            int attemptJustExecuted,
            List<TestRunResult> previousResults)
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

        // TODO(b/179206516): Skip known failure list in class/method Level.
        // Currently, only support skip list in module level.
        if (module != null && isInSkipList(module)) {
            CLog.d("Skip retrying known failure test of %s", module.getId());
            return false;
        }

        mStatistics.addResultsFromRun(previousResults);
        if (test instanceof ITestFilterReceiver) {
            // TODO(b/77548917): Right now we only support ITestFilterReceiver. We should expect to
            // support ITestFile*Filter*Receiver in the future.
            ITestFilterReceiver filterableTest = (ITestFilterReceiver) test;
            boolean shouldRetry = handleRetryFailures(filterableTest, previousResults);
            if (shouldRetry) {
                // In case of retry, go through the recovery routine
                recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
            }
            return shouldRetry;
        } else if (test instanceof IAutoRetriableTest) {
            // Routine for IRemoteTest that don't support filters but still needs retry.
            IAutoRetriableTest autoRetryTest = (IAutoRetriableTest) test;
            boolean shouldRetry = autoRetryTest.shouldRetry(attemptJustExecuted, previousResults);
            if (shouldRetry) {
                recoverStateOfDevices(getDevices(), attemptJustExecuted, module);
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

    /** Returns the map of failed test cases that should be retried. */
    public static Map<TestDescription, TestResult> getFailedTestCases(
            List<TestRunResult> previousResults) {
        Map<TestDescription, TestResult> failedTestCases = new LinkedHashMap<>();
        for (TestRunResult run : previousResults) {
            if (run != null) {
                for (Entry<TestDescription, TestResult> entry : run.getTestResults().entrySet()) {
                    if (TestStatus.FAILURE.equals(entry.getValue().getStatus())) {
                        failedTestCases.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return failedTestCases;
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

    private static Set<TestDescription> getPassedTestCases(List<TestRunResult> previousResults) {
        Set<TestDescription> previousPassed = new LinkedHashSet<>();
        for (TestRunResult run : previousResults) {
            if (run != null) {
                for (Entry<TestDescription, TestResult> entry : run.getTestResults().entrySet()) {
                    if (!TestStatus.FAILURE.equals(entry.getValue().getStatus())) {
                        previousPassed.add(entry.getKey());
                    }
                }
            }
        }
        return previousPassed;
    }

    private boolean isInSkipList(ModuleDefinition module) {
        String moduleId = module.getId();
        if (moduleId == null) {
            return false;
        }
        SuiteTestFilter moduleIdFilter = SuiteTestFilter.createFrom(moduleId);
        String abi = moduleIdFilter.getAbi();
        String name = moduleIdFilter.getName();
        for (String skipTest : mSkipRetryingList) {
            SuiteTestFilter skipRetryingFilter = SuiteTestFilter.createFrom(skipTest);
            String skipAbi = skipRetryingFilter.getAbi();
            String skipName = skipRetryingFilter.getName();
            if (abi != null
                    && skipAbi != null
                    && name != null
                    && skipName != null
                    && abi.equals(skipAbi)
                    && name.equals(skipName)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the list of failure from the previous results. */
    private static List<TestRunResult> getRunFailures(List<TestRunResult> previousResults) {
        List<TestRunResult> runFailed = new ArrayList<>();
        for (TestRunResult run : previousResults) {
            if (run != null && run.isRunFailure()) {
                runFailed.add(run);
            }
        }
        return runFailed;
    }

    private static List<TestRunResult> getNonRetriableFailures(List<TestRunResult> failedRun) {
        List<TestRunResult> nonRetriableRuns = new ArrayList<>();
        for (TestRunResult run : failedRun) {
            if (!run.getRunFailureDescription().isRetriable()) {
                nonRetriableRuns.add(run);
            }
        }
        return nonRetriableRuns;
    }

    private boolean handleRetryFailures(
            ITestFilterReceiver test, List<TestRunResult> previousResults) {
        List<TestRunResult> runFailures = getRunFailures(previousResults);
        List<TestRunResult> nonRetriableRunFailures = getNonRetriableFailures(runFailures);
        if (!nonRetriableRunFailures.isEmpty()) {
            CLog.d("Skipping retry since there was a non-retriable failure.");
            return false;
        }
        if (mUpdatedFiltering && mUpdatedReporting) {
            CLog.d("Using updated filtering logic.");
            Map<TestDescription, TestResult> previousFailedTests =
                    getFailedTestCases(previousResults);
            if (runFailures.isEmpty() && previousFailedTests.isEmpty()) {
                CLog.d("No test run or test case failures. No need to retry.");
                return false;
            }
            Set<TestDescription> previouslyPassedTests = getPassedTestCases(previousResults);
            excludePassedTests(test, previouslyPassedTests);
            excludeNonRetriableFailure(test, previousFailedTests);
            return true;
        } else if (!runFailures.isEmpty()) {
            if (shouldFullRerun(runFailures)) {
                List<String> names =
                        runFailures.stream().map(e -> e.getName()).collect(Collectors.toList());
                CLog.d("Retry the full run since [%s] runs have failures.", names);
                return true;
            }
            // If we don't attempt full rerun add filters.
            CLog.d("Full rerun not required, excluding previously passed tests.");
            Set<TestDescription> previouslyPassedTests = getPassedTestCases(previousResults);
            excludePassedTests(test, previouslyPassedTests);
            return true;
        }

        // In case of test case failure, we retry with filters.
        Map<TestDescription, TestResult> previousFailedTests = getFailedTestCases(previousResults);
        if (!mPreviouslyFailing.isEmpty()) {
            previousFailedTests.keySet().retainAll(mPreviouslyFailing);
            mPreviouslyFailing.retainAll(previousFailedTests.keySet());
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
            addRetriedTestsToFilters(test, previousFailedTests);
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

    /** If none of the run failures require a full rerun, trigger the partial rerun logic. */
    private boolean shouldFullRerun(List<TestRunResult> runFailures) {
        for (TestRunResult run : runFailures) {
            if (run.getRunFailureDescription().rerunFull()) {
                return true;
            }
        }
        return false;
    }

    /** Set the filters on the test runner for the retry. */
    private void addRetriedTestsToFilters(
            ITestFilterReceiver test, Map<TestDescription, TestResult> tests) {
        // Limit the re-run to the failure we include, so clear filters then put our failures
        test.clearIncludeFilters();
        for (Entry<TestDescription, TestResult> testCaseEntry : tests.entrySet()) {
            TestDescription testCase = testCaseEntry.getKey();
            if (testCaseEntry.getValue().getFailure().isRetriable()) {
                // We have to retry without the parameters since some runner don't support it.
                String filter =
                        String.format(
                                "%s#%s",
                                testCase.getClassName(), testCase.getTestNameWithoutParams());
                test.addIncludeFilter(filter);
            } else {
                // If a test case failure is not retriable, track it, but don't retry it so we
                // exclude it from the filters.
                String filter =
                        String.format("%s#%s", testCase.getClassName(), testCase.getTestName());
                test.addExcludeFilter(filter);
            }
            mPreviouslyFailing.add(testCase);
        }
    }

    private void excludePassedTests(ITestFilterReceiver test, Set<TestDescription> passedTests) {
        // Exclude all passed tests for the retry.
        for (TestDescription testCase : passedTests) {
            String filter = String.format("%s#%s", testCase.getClassName(), testCase.getTestName());
            test.addExcludeFilter(filter);
        }
    }

    private void excludeNonRetriableFailure(
          ITestFilterReceiver test, Map<TestDescription, TestResult> previousFailedTests) {
        for (Entry<TestDescription, TestResult> testCaseEntry : previousFailedTests.entrySet()) {
            TestDescription testCase = testCaseEntry.getKey();
            if (!testCaseEntry.getValue().getFailure().isRetriable()) {
                // If a test case failure is not retriable, exclude it from the filters.
                String filter =
                        String.format("%s#%s", testCase.getClassName(), testCase.getTestName());
                test.addExcludeFilter(filter);
            }
        }
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
            try {
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
        } else if (lastAttempt == (mMaxRetryAttempts - 2)) {
            // Reset only works for suite right now
            if (mResetAtLastRetry && module != null) {
                compatibleReset(mConfiguration, module, devices);
            } else if (mRebootAtLastRetry) {
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
        try {
            isolateRetry(devices);
            // Rerun suite level preparer if we are inside a subprocess
            reSetupModule(module, mConfiguration.getCommandOptions()
                    .getInvocationData()
                    .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME));
        } finally {
            InvocationMetricLogger.addInvocationPairMetrics(
                    InvocationMetricKey.RESET_RETRY_ISOLATION_PAIR,
                    start, System.currentTimeMillis());
        }
    }

    private void compatibleReset(
            IConfiguration config, ModuleDefinition module, List<ITestDevice> devices)
                    throws DeviceNotAvailableException {
        SandboxOptions options = (SandboxOptions)
                config.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME);
        if (config.getConfigurationDescription().shouldUseSandbox()) {
            if (options.startAvdInParent()) {
                resetIsolation(module, devices);
            } else {
                // TODO: When sandbox has been switched to start device in parent, remove the
                // compatible handling.
                resetDevice(module, devices);
            }
        } else {
            CLog.d("Not a sandboxed run, reset-at-last-retry is ignored.");
        }
        // TODO: Add support for non-sandbox
    }

    private void resetDevice(ModuleDefinition module, List<ITestDevice> devices)
            throws DeviceNotAvailableException {
        CLog.d("Reset devices...");
        for (ITestDevice device : devices) {
            if (!(device instanceof RemoteAndroidVirtualDevice)) {
                CLog.i(
                        "Device %s of type %s does not support powerwash.",
                        device.getSerialNumber(), device.getClass());
                continue;
            }
            boolean success = false;
            try {
                success = ((RemoteAndroidVirtualDevice) device).powerwashGce();
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.DEVICE_RESET_COUNT, 1);
                CurrentInvocation.setModuleIsolation(IsolationGrade.FULLY_ISOLATED);
                CurrentInvocation.setRunIsolation(IsolationGrade.FULLY_ISOLATED);
            } catch (TargetSetupError e) {
                CLog.e(e);
                throw new DeviceNotAvailableException(
                        String.format(
                                "Failed to powerwash device: %s\nError: %s",
                                device.getSerialNumber(), e.toString()),
                        e,
                        device.getSerialNumber(),
                        DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
            }

            if (!success) {
                throw new DeviceNotAvailableException(
                        String.format("Failed to powerwash device: %s", device.getSerialNumber()),
                        device.getSerialNumber(),
                        DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
            }
        }

        reSetupModule(module, true);
    }

    private void isolateRetry(List<ITestDevice> devices) throws DeviceNotAvailableException {
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
