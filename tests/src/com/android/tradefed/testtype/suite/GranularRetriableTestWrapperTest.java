/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.testrunner.TestResult.TestStatus;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.FileSystemLogSaver;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link com.android.tradefed.testtype.suite.GranularRetriableTestWrapper}.
 *
 * <p>TODO: Needs to be completed with multiple runs per runner tests.
 */
@RunWith(JUnit4.class)
public class GranularRetriableTestWrapperTest {

    private static final String RUN_NAME = "test run";

    private class BasicFakeTest implements IRemoteTest {

        protected ArrayList<TestDescription> mTestCases;
        protected Set<String> mShouldRun = new HashSet<>();

        private Map<TestDescription, Integer> mBecomePass = new HashMap<>();
        private Map<TestDescription, Boolean> mShouldFail;
        private int mAttempts = 0;

        public BasicFakeTest() {
            mTestCases = new ArrayList<>();
            TestDescription defaultTestCase = new TestDescription("ClassFoo", "TestFoo");
            mTestCases.add(defaultTestCase);
            mShouldFail = new HashMap<TestDescription, Boolean>();
            mShouldFail.put(defaultTestCase, false);
        }

        public BasicFakeTest(ArrayList<TestDescription> testCases) {
            mTestCases = testCases;
            mShouldFail = new HashMap<TestDescription, Boolean>();
            for (TestDescription testCase : testCases) {
                mShouldFail.put(testCase, false);
            }
            mAttempts = 0;
        }

        public void addFailedTestCase(TestDescription testCase) {
            mShouldFail.put(testCase, true);
        }

        public void addTestBecomePass(TestDescription testCase, int attempt) {
            mBecomePass.put(testCase, attempt);
        }

        @Override
        public void run(ITestInvocationListener listener) throws DeviceUnresponsiveException {
            listener.testRunStarted(RUN_NAME, mTestCases.size());
            for (TestDescription td : mTestCases) {
                if (!mShouldRun.isEmpty() && !mShouldRun.contains(td.toString())) {
                    continue;
                }
                listener.testStarted(td);
                int passAttempt = -1;
                if (mBecomePass.get(td) != null) {
                    passAttempt = mBecomePass.get(td);
                }
                if (mShouldFail.get(td)) {
                    if (passAttempt == -1 || mAttempts < passAttempt) {
                        listener.testFailed(td, String.format("Fake failure %s", td.toString()));
                    }
                }
                listener.testEnded(td, new HashMap<String, Metric>());
            }
            listener.testRunEnded(0, new HashMap<String, Metric>());
            mAttempts++;
        }
    }

    private class FakeTest extends BasicFakeTest implements ITestFilterReceiver {

        public FakeTest(ArrayList<TestDescription> testCases) {
            super(testCases);
        }

        public FakeTest() {
            super();
        }

        @Override
        public void addIncludeFilter(String filter) {
            mShouldRun.add(filter);
        }

        @Override
        public void addAllIncludeFilters(Set<String> filters) {}

        @Override
        public void addExcludeFilter(String filters) {}

        @Override
        public void addAllExcludeFilters(Set<String> filters) {}

        @Override
        public void clearIncludeFilters() {
            mShouldRun.clear();
        }

        @Override
        public Set<String> getIncludeFilters() {
            return mShouldRun;
        }

        @Override
        public Set<String> getExcludeFilters() {
            return new HashSet<>();
        }

        @Override
        public void clearExcludeFilters() {}
    }

    private GranularRetriableTestWrapper createGranularTestWrapper(
            IRemoteTest test, int maxRunCount) {
        GranularRetriableTestWrapper granularTestWrapper =
                new GranularRetriableTestWrapper(test, null, null, maxRunCount);
        granularTestWrapper.setModuleId("test module");
        granularTestWrapper.setMarkTestsSkipped(false);
        granularTestWrapper.setMetricCollectors(new ArrayList<IMetricCollector>());
        // Setup InvocationContext.
        granularTestWrapper.setInvocationContext(new InvocationContext());
        // Setup logsaver.
        granularTestWrapper.setLogSaver(new FileSystemLogSaver());
        IConfiguration mockModuleConfiguration = Mockito.mock(IConfiguration.class);
        granularTestWrapper.setModuleConfig(mockModuleConfiguration);
        return granularTestWrapper;
    }

    /**
     * Test the intra module "run" triggers IRemoteTest run method with a dedicated ModuleListener.
     */
    @Test
    public void testIntraModuleRun_pass() throws Exception {
        TestDescription fakeTestCase = new TestDescription("ClassFoo", "TestFoo");

        GranularRetriableTestWrapper granularTestWrapper =
                createGranularTestWrapper(new FakeTest(), 99);
        assertEquals(0, granularTestWrapper.getTestRunResultCollected().size());
        granularTestWrapper.intraModuleRun();
        assertEquals(1, granularTestWrapper.getTestRunResultCollected().size());
        assertEquals(1, granularTestWrapper.getFinalTestRunResults().size());
        Set<TestDescription> completedTests =
                granularTestWrapper.getFinalTestRunResults().get(0).getCompletedTests();
        assertEquals(1, completedTests.size());
        assertTrue(completedTests.contains(fakeTestCase));

        // No retried run because all tests passed
        assertEquals(0, granularTestWrapper.getRetrySuccess());
        assertEquals(0, granularTestWrapper.getRetryFailed());
        assertEquals(0, granularTestWrapper.getRetryTime());
    }

    /**
     * Test that the intra module "run" method catches DeviceNotAvailableException and raises it
     * after record the tests.
     */
    @Test(expected = DeviceNotAvailableException.class)
    public void testIntraModuleRun_catchDeviceNotAvailableException() throws Exception {
        IRemoteTest mockTest = Mockito.mock(IRemoteTest.class);
        Mockito.doThrow(new DeviceNotAvailableException("fake message", "serial"))
                .when(mockTest)
                .run(Mockito.any(ITestInvocationListener.class));
        GranularRetriableTestWrapper granularTestWrapper = createGranularTestWrapper(mockTest, 1);
        // Verify.
        granularTestWrapper.intraModuleRun();
    }

    /**
     * Test that the intra module "run" method catches DeviceUnresponsiveException and doesn't raise
     * it again.
     */
    @Test
    public void testIntraModuleRun_catchDeviceUnresponsiveException() throws Exception {
        FakeTest test =
                new FakeTest() {
                    @Override
                    public void run(ITestInvocationListener listener)
                            throws DeviceUnresponsiveException {
                        listener.testRunStarted("test run", 1);
                        throw new DeviceUnresponsiveException("fake message", "serial");
                    }
                };
        GranularRetriableTestWrapper granularTestWrapper = createGranularTestWrapper(test, 1);
        granularTestWrapper.intraModuleRun();
        TestRunResult attempResults =
                granularTestWrapper.getTestRunResultCollected().get(RUN_NAME).get(0);
        assertTrue(attempResults.isRunFailure());
    }

    /**
     * Test that the "run" method has built-in retry logic and each run has an individual
     * ModuleListener and TestRunResult.
     */
    @Test
    public void testRun_withMultipleRun() throws Exception {
        ArrayList<TestDescription> testCases = new ArrayList<>();
        TestDescription fakeTestCase = new TestDescription("Class", "Test");
        TestDescription fakeTestCase2 = new TestDescription("Class", "Test2");
        TestDescription fakeTestCase3 = new TestDescription("Class", "Test3");
        testCases.add(fakeTestCase);
        testCases.add(fakeTestCase2);
        testCases.add(fakeTestCase3);
        FakeTest test = new FakeTest(testCases);
        test.addFailedTestCase(fakeTestCase);
        test.addFailedTestCase(fakeTestCase2);

        int maxRunCount = 5;
        GranularRetriableTestWrapper granularTestWrapper =
                createGranularTestWrapper(test, maxRunCount);
        granularTestWrapper.run(new CollectingTestListener());
        // Verify the test runs several times but under the same run name
        assertEquals(1, granularTestWrapper.getTestRunResultCollected().size());
        assertEquals(
                maxRunCount, granularTestWrapper.getTestRunResultCollected().get(RUN_NAME).size());
        assertEquals(1, granularTestWrapper.getFinalTestRunResults().size());
        Map<TestDescription, TestResult> testResults =
                granularTestWrapper.getFinalTestRunResults().get(0).getTestResults();
        assertTrue(testResults.containsKey(fakeTestCase));
        assertTrue(testResults.containsKey(fakeTestCase2));
        assertTrue(testResults.containsKey(fakeTestCase3));
        // Verify the final TestRunResult is a merged value of every retried TestRunResults.
        assertEquals(TestStatus.FAILURE, testResults.get(fakeTestCase).getStatus());
        assertEquals(TestStatus.FAILURE, testResults.get(fakeTestCase2).getStatus());
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase3).getStatus());

        // Ensure that the PASSED test was only run the first time.
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(0)
                        .getTestResults()
                        .containsKey(fakeTestCase3));
        for (int i = 1; i < maxRunCount; i++) {
            assertFalse(
                    granularTestWrapper
                            .getTestRunResultCollected()
                            .get(RUN_NAME)
                            .get(i)
                            .getTestResults()
                            .containsKey(fakeTestCase3));
        }

        // Since tests stay failed, we have two failure in our monitoring.
        assertEquals(0, granularTestWrapper.getRetrySuccess());
        assertEquals(2, granularTestWrapper.getRetryFailed());
    }

    /** Test when a test becomes pass after failing */
    @Test
    public void testRun_withMultipleRun_becomePass() throws Exception {
        ArrayList<TestDescription> testCases = new ArrayList<>();
        TestDescription fakeTestCase = new TestDescription("Class", "Test");
        TestDescription fakeTestCase2 = new TestDescription("Class", "Test2");
        TestDescription fakeTestCase3 = new TestDescription("Class", "Test3");
        testCases.add(fakeTestCase);
        testCases.add(fakeTestCase2);
        testCases.add(fakeTestCase3);
        FakeTest test = new FakeTest(testCases);
        test.addFailedTestCase(fakeTestCase);
        test.addFailedTestCase(fakeTestCase2);
        // At attempt 3, the test case will become pass.
        test.addTestBecomePass(fakeTestCase, 3);

        int maxRunCount = 5;
        GranularRetriableTestWrapper granularTestWrapper =
                createGranularTestWrapper(test, maxRunCount);
        granularTestWrapper.run(new CollectingTestListener());
        // Verify the test runs several times but under the same run name
        assertEquals(1, granularTestWrapper.getTestRunResultCollected().size());
        assertEquals(
                maxRunCount, granularTestWrapper.getTestRunResultCollected().get(RUN_NAME).size());
        assertEquals(1, granularTestWrapper.getFinalTestRunResults().size());
        Map<TestDescription, TestResult> testResults =
                granularTestWrapper.getFinalTestRunResults().get(0).getTestResults();
        assertTrue(testResults.containsKey(fakeTestCase));
        assertTrue(testResults.containsKey(fakeTestCase2));
        assertTrue(testResults.containsKey(fakeTestCase3));
        // Verify the final TestRunResult is a merged value of every retried TestRunResults.
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase).getStatus()); // became pass
        assertEquals(TestStatus.FAILURE, testResults.get(fakeTestCase2).getStatus());
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase3).getStatus());

        // Ensure that the PASSED test was only run the first time.
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(0)
                        .getTestResults()
                        .containsKey(fakeTestCase3));
        for (int i = 1; i < maxRunCount; i++) {
            assertFalse(
                    granularTestWrapper
                            .getTestRunResultCollected()
                            .get(RUN_NAME)
                            .get(i)
                            .getTestResults()
                            .containsKey(fakeTestCase3));
        }

        // One success since one test recover, one test never recover so one failure
        assertEquals(1, granularTestWrapper.getRetrySuccess());
        assertEquals(1, granularTestWrapper.getRetryFailed());
    }

    /** Test when all tests become pass, we stop intra-module retry early. */
    @Test
    public void testRun_withMultipleRun_AllBecomePass() throws Exception {
        ArrayList<TestDescription> testCases = new ArrayList<>();
        TestDescription fakeTestCase = new TestDescription("Class", "Test");
        TestDescription fakeTestCase2 = new TestDescription("Class", "Test2");
        TestDescription fakeTestCase3 = new TestDescription("Class", "Test3");
        testCases.add(fakeTestCase);
        testCases.add(fakeTestCase2);
        testCases.add(fakeTestCase3);
        FakeTest test = new FakeTest(testCases);
        test.addFailedTestCase(fakeTestCase);
        test.addFailedTestCase(fakeTestCase2);
        // At attempt 3, the test case will become pass.
        test.addTestBecomePass(fakeTestCase, 3);
        test.addTestBecomePass(fakeTestCase2, 2);

        int maxRunCount = 5;
        GranularRetriableTestWrapper granularTestWrapper =
                createGranularTestWrapper(test, maxRunCount);
        granularTestWrapper.run(new CollectingTestListener());
        // Verify the test runs several times but under the same run name
        assertEquals(1, granularTestWrapper.getTestRunResultCollected().size());
        assertEquals(4, granularTestWrapper.getTestRunResultCollected().get(RUN_NAME).size());
        assertEquals(1, granularTestWrapper.getFinalTestRunResults().size());
        Map<TestDescription, TestResult> testResults =
                granularTestWrapper.getFinalTestRunResults().get(0).getTestResults();
        assertTrue(testResults.containsKey(fakeTestCase));
        assertTrue(testResults.containsKey(fakeTestCase2));
        assertTrue(testResults.containsKey(fakeTestCase3));
        // Verify the final TestRunResult is a merged value of every retried TestRunResults.
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase).getStatus()); // became pass
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase2).getStatus());
        assertEquals(TestStatus.PASSED, testResults.get(fakeTestCase3).getStatus());

        // Ensure that the PASSED test was only run the first time.
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(0)
                        .getTestResults()
                        .containsKey(fakeTestCase3));
        for (int i = 1; i < 4; i++) {
            assertFalse(
                    granularTestWrapper
                            .getTestRunResultCollected()
                            .get(RUN_NAME)
                            .get(i)
                            .getTestResults()
                            .containsKey(fakeTestCase3));
        }
        // Ensure that once tests start passing they stop running
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(0)
                        .getTestResults()
                        .containsKey(fakeTestCase2));
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(1)
                        .getTestResults()
                        .containsKey(fakeTestCase2));
        assertTrue(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(2)
                        .getTestResults()
                        .containsKey(fakeTestCase2));
        assertFalse(
                granularTestWrapper
                        .getTestRunResultCollected()
                        .get(RUN_NAME)
                        .get(3)
                        .getTestResults()
                        .containsKey(fakeTestCase2));

        // One success since one test recover, one test never recover so one failure
        assertEquals(2, granularTestWrapper.getRetrySuccess());
        assertEquals(0, granularTestWrapper.getRetryFailed());
    }

    /**
     * Test that if IRemoteTest doesn't implement ITestFilterReceiver, the "run" method will retry
     * all test cases.
     */
    @Test
    public void testRun_retryAllTestCasesIfNotSupportTestFilterReceiver() throws Exception {
        ArrayList<TestDescription> testCases = new ArrayList<>();
        TestDescription fakeTestCase1 = new TestDescription("Class1", "Test1");
        TestDescription fakeTestCase2 = new TestDescription("Class2", "Test2");
        testCases.add(fakeTestCase1);
        testCases.add(fakeTestCase2);
        BasicFakeTest test = new BasicFakeTest(testCases);
        // Only the first testcase is failed.
        test.addFailedTestCase(fakeTestCase1);
        // Run each testcases (if has failure) max to 3 times.
        int maxRunCount = 3;
        GranularRetriableTestWrapper granularTestWrapper =
                createGranularTestWrapper(test, maxRunCount);
        granularTestWrapper.run(new CollectingTestListener());

        assertEquals(1, granularTestWrapper.getTestRunResultCollected().size());
        // Expect only 1 run since it does not support ITestFilterReceiver
        assertEquals(1, granularTestWrapper.getTestRunResultCollected().get(RUN_NAME).size());
        List<TestRunResult> resultCollector =
                granularTestWrapper.getTestRunResultCollected().get(RUN_NAME);
        // Check that all test cases where rerun
        for (TestRunResult runResult : resultCollector) {
            assertEquals(2, runResult.getNumTests());
            assertEquals(
                    TestStatus.FAILURE, runResult.getTestResults().get(fakeTestCase1).getStatus());
            assertEquals(
                    TestStatus.PASSED, runResult.getTestResults().get(fakeTestCase2).getStatus());
        }
    }
}
