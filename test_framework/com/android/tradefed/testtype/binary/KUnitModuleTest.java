/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.testtype.binary;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.KernelModuleUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test runner for running KUnit test modules on device. */
@OptionClass(alias = "kunit-module-test")
public class KUnitModuleTest extends ExecutableTargetTest {

    @Option(
            name = "ktap-result-parser-resolution",
            description = "Parser resolution for KTap results.")
    private KTapResultParser.ParseResolution mKTapResultParserResolution =
            KTapResultParser.ParseResolution.AGGREGATED_MODULE;

    public static final String KUNIT_DEBUGFS_PATH =
            String.format("%s/kunit", NativeDevice.DEBUGFS_PATH);
    public static final String KUNIT_RESULTS_FMT =
            String.format("%s/%%s/results", KUNIT_DEBUGFS_PATH);

    @Override
    protected boolean doesRunBinaryGenerateTestResults() {
        return true;
    }

    @Override
    public boolean getCollectTestsOnly() {
        if (super.getCollectTestsOnly()) {
            // TODO(b/310965570) Implement collect only mode for KUnit modules
            throw new UnsupportedOperationException("collect-tests-only mode not support");
        }
        return false;
    }

    @Override
    protected Map<String, String> getAllTestCommands() {
        Map<String, String> originalTestCommands = super.getAllTestCommands();
        Map<String, String> modifiedTestCommands = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : originalTestCommands.entrySet()) {
            modifiedTestCommands.put(
                    KernelModuleUtils.removeKoExtension(entry.getKey()), entry.getValue());
        }
        return modifiedTestCommands;
    }

    @Override
    public String findBinary(String binary) throws DeviceNotAvailableException {
        return getSkipBinaryCheck() || getDevice().doesFileExist(binary) ? binary : null;
    }

    @Override
    public void runBinary(
            String modulePath, ITestInvocationListener listener, TestDescription description)
            throws DeviceNotAvailableException, IOException {

        if (getDevice() == null) {
            throw new IllegalArgumentException("Device has not been set");
        }
        String kunitModule = KernelModuleUtils.getDisplayedModuleName(modulePath);

        // Unload module before hand in case it's already loaded for some reason
        CommandResult result = KernelModuleUtils.removeSingleModule(getDevice(), kunitModule);

        if (CommandStatus.SUCCESS.equals(result.getStatus())) {
            CLog.w("Module '%s' unexpectedly still loaded, it has been unloaded.", kunitModule);
        }

        boolean debugfsAlreadyMounted = getDevice().isDebugfsMounted();

        try {
            if (!debugfsAlreadyMounted) {
                getDevice().mountDebugfs();
            }

            // Collect list of pre-existing KUnit results under debugfs. So, we can ignore later.
            List<String> kunitTestSuitesBefore =
                    Arrays.asList(getDevice().getChildren(KUNIT_DEBUGFS_PATH));
            if (!kunitTestSuitesBefore.isEmpty()) {
                CLog.w(
                        "Stale KUnit test suite results found [%s]",
                        String.join(",", kunitTestSuitesBefore));
            }

            try {
                result =
                        KernelModuleUtils.installModule(
                                getDevice(), modulePath, "", getTimeoutPerBinaryMs());
            } catch (TargetSetupError e) {
                String errorMessage = e.toString();
                listener.testStarted(description);
                listener.testFailed(
                        description,
                        FailureDescription.create(errorMessage)
                                .setFailureStatus(FailureStatus.TEST_FAILURE));
                listener.testEnded(description, new HashMap<String, Metric>());
                return;
            }

            // Parse new KUnit results in debugfs
            List<String> kunitTestSuitesAfter =
                    new ArrayList<String>(
                            Arrays.asList(getDevice().getChildren(KUNIT_DEBUGFS_PATH)));
            kunitTestSuitesAfter.removeAll(kunitTestSuitesBefore);

            if (kunitTestSuitesAfter.isEmpty()) {
                String errorMessage =
                        String.format(
                                "No KTAP results generated in '%s' for module '%s'",
                                KUNIT_DEBUGFS_PATH, kunitModule);
                CLog.e(errorMessage);
                listener.testStarted(description);
                listener.testFailed(
                        description,
                        FailureDescription.create(errorMessage)
                                .setFailureStatus(FailureStatus.TEST_FAILURE));
                listener.testEnded(description, new HashMap<String, Metric>());
            }

            List<String> ktapResultsList = new ArrayList<>();
            for (String testSuite : kunitTestSuitesAfter) {
                String ktapResults =
                        getDevice().pullFileContents(String.format(KUNIT_RESULTS_FMT, testSuite));
                CLog.i(
                        "KUnit module '%s' suite '%s' KTAP result:\n%s",
                        description.getTestName(), testSuite, ktapResults);
                ktapResultsList.add(ktapResults);
            }

            try {
                KTapResultParser.applyKTapResultToListener(
                        listener,
                        description.getTestName(),
                        ktapResultsList,
                        mKTapResultParserResolution,
                        true);
            } catch (RuntimeException exception) {
                CLog.e("KTAP parse error: %s", exception.toString());
                listener.testStarted(description);
                listener.testFailed(
                        description,
                        FailureDescription.create(exception.toString())
                                .setFailureStatus(FailureStatus.TEST_FAILURE));
                listener.testEnded(description, new HashMap<String, Metric>());
            }

            // Clean up, unload module.
            result = KernelModuleUtils.removeSingleModule(getDevice(), kunitModule);

            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                String errorMessage =
                        String.format(
                                "binary returned non-zero. Exit code: %d, stderr: %s, stdout: %s",
                                result.getExitCode(), result.getStderr(), result.getStdout());
                CLog.w("Unable to unload module '%s'. %s", kunitModule, errorMessage);
            }
        } finally {
            if (!debugfsAlreadyMounted) {
                // If debugfs was not mounted before this test, unmount it.
                getDevice().unmountDebugfs();
            }
        }
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {

        if (mKTapResultParserResolution == KTapResultParser.ParseResolution.AGGREGATED_MODULE) {
            super.run(testInfo, listener);
            return;
        }

        // KUnit does not support querying for number of test cases.
        // This listener delays all events and counts the number of testStarted calls.
        // After the tests end, it calls testRunStarted with the correct number.
        List<Runnable> testEvents = new ArrayList<Runnable>();
        int[] testCount = {0};
        ITestInvocationListener delayListener =
                new ITestInvocationListener() {
                    @Override
                    public void testRunStarted(String runName, int ignoredTestCount) {
                        testEvents.add(() -> listener.testRunStarted(runName, testCount[0]));
                    }

                    @Override
                    public void testRunStarted(
                            String runName, int ignoredTestCount, int attemptNumber) {
                        testEvents.add(
                                () ->
                                        listener.testRunStarted(
                                                runName, testCount[0], attemptNumber));
                    }

                    @Override
                    public void testRunStarted(
                            String runName,
                            int ignoredTestCount,
                            int attemptNumber,
                            long startTime) {
                        testEvents.add(
                                () ->
                                        listener.testRunStarted(
                                                runName, testCount[0], attemptNumber, startTime));
                    }

                    @Override
                    public void testRunFailed(String errorMessage) {
                        testEvents.add(() -> listener.testRunFailed(errorMessage));
                    }

                    @Override
                    public void testRunFailed(FailureDescription failure) {
                        testEvents.add(() -> listener.testRunFailed(failure));
                    }

                    @Override
                    public void testRunEnded(
                            long elapsedTimeMillis, Map<String, String> runMetrics) {
                        testEvents.add(() -> listener.testRunEnded(elapsedTimeMillis, runMetrics));
                    }

                    @Override
                    public void testRunEnded(
                            long elapsedTimeMillis, HashMap<String, Metric> runMetrics) {
                        testEvents.add(() -> listener.testRunEnded(elapsedTimeMillis, runMetrics));
                    }

                    @Override
                    public void testRunStopped(long elapsedTime) {
                        testEvents.add(() -> listener.testRunStopped(elapsedTime));
                    }

                    @Override
                    public void testStarted(TestDescription test) {
                        testEvents.add(() -> listener.testStarted(test));
                        testCount[0]++;
                    }

                    @Override
                    public void testStarted(TestDescription test, long startTime) {
                        testEvents.add(() -> listener.testStarted(test, startTime));
                        testCount[0]++;
                    }

                    @Override
                    public void testFailed(TestDescription test, String trace) {
                        testEvents.add(() -> listener.testFailed(test, trace));
                    }

                    @Override
                    public void testFailed(TestDescription test, FailureDescription failure) {
                        testEvents.add(() -> listener.testFailed(test, failure));
                    }

                    @Override
                    public void testAssumptionFailure(TestDescription test, String trace) {
                        testEvents.add(() -> listener.testAssumptionFailure(test, trace));
                    }

                    @Override
                    public void testAssumptionFailure(
                            TestDescription test, FailureDescription failure) {
                        testEvents.add(() -> listener.testAssumptionFailure(test, failure));
                    }

                    @Override
                    public void testIgnored(TestDescription test) {
                        testEvents.add(() -> listener.testIgnored(test));
                    }

                    @Override
                    public void testSkipped(TestDescription test, SkipReason reason) {
                        testEvents.add(() -> listener.testSkipped(test, reason));
                    }

                    @Override
                    public void testEnded(TestDescription test, Map<String, String> testMetrics) {
                        testEvents.add(() -> listener.testEnded(test, testMetrics));
                    }

                    @Override
                    public void testEnded(
                            TestDescription test, HashMap<String, Metric> testMetrics) {
                        testEvents.add(() -> listener.testEnded(test, testMetrics));
                    }

                    @Override
                    public void testEnded(
                            TestDescription test, long endTime, Map<String, String> testMetrics) {
                        testEvents.add(() -> listener.testEnded(test, endTime, testMetrics));
                    }

                    @Override
                    public void testEnded(
                            TestDescription test,
                            long endTime,
                            HashMap<String, Metric> testMetrics) {
                        testEvents.add(() -> listener.testEnded(test, endTime, testMetrics));
                    }
                };

        try {
            super.run(testInfo, delayListener);
        } finally {
            for (Runnable testEvent : testEvents) {
                testEvent.run();
            }
        }
    }
}
