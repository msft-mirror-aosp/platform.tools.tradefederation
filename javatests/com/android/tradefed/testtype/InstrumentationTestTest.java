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

package com.android.tradefed.testtype;

import static com.android.tradefed.testtype.InstrumentationTest.RUN_TESTS_AS_USER_KEY;
import static com.android.tradefed.testtype.InstrumentationTest.RUN_TESTS_ON_SDK_SANDBOX;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestLifeCycleReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.ddmlib.RemoteAndroidTestRunner;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.testtype.suite.GranularRetriableTestWrapperTest.CalledMetricCollector;
import com.android.tradefed.util.ListInstrumentationParser;
import com.android.tradefed.util.ListInstrumentationParser.InstrumentationTarget;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link InstrumentationTest} */
@RunWith(JUnit4.class)
public class InstrumentationTestTest {

    private static final String TEST_PACKAGE_VALUE = "com.foo";
    private static final String TEST_RUNNER_VALUE = ".FooRunner";
    private static final TestDescription TEST1 = new TestDescription("Test", "test1");
    private static final TestDescription TEST2 = new TestDescription("Test", "test2");
    private static final TestDescription TEST_PARAM1 = new TestDescription("Test", "test[0]");
    private static final TestDescription TEST_PARAM2 = new TestDescription("Test", "test[1]");
    private static final String RUN_ERROR_MSG = "error";
    private static final HashMap<String, Metric> EMPTY_STRING_MAP = new HashMap<>();

    /** The {@link InstrumentationTest} under test, with all dependencies mocked out */
    @Spy InstrumentationTest mInstrumentationTest;

    // The configuration objects.
    private IConfiguration mConfig = null;
    private TestInformation mTestInfo = null;
    private CoverageOptions mCoverageOptions = null;
    private OptionSetter mCoverageOptionsSetter = null;
    private IInvocationContext mContext = null;

    // The mock objects.
    @Mock IDevice mMockIDevice;
    @Mock ITestDevice mMockTestDevice;
    @Mock ITestInvocationListener mMockListener;
    @Mock ListInstrumentationParser mMockListInstrumentationParser;

    @Captor private ArgumentCaptor<Collection<TestDescription>> testCaptor;
    @Captor private ArgumentCaptor<HashMap<String, Metric>> testCapture1;
    @Captor private ArgumentCaptor<HashMap<String, Metric>> testCapture2;
    @Captor private ArgumentCaptor<HashMap<String, Metric>> runCapture;

    /**
     * Helper class for providing an {@link Answer} to a {@link
     * ITestDevice#runInstrumentationTests(IRemoteAndroidTestRunner, ITestInvocationListener...)}
     * call.
     */
    @FunctionalInterface
    interface RunInstrumentationTestsAnswer extends Answer<Boolean> {
        @Override
        default Boolean answer(InvocationOnMock invocation) throws Exception {
            Object[] args = invocation.getArguments();
            return answer((IRemoteAndroidTestRunner) args[0], (ITestLifeCycleReceiver) args[1]);
        }

        Boolean answer(IRemoteAndroidTestRunner runner, ITestLifeCycleReceiver listener)
                throws Exception;
    }

    @Before
    public void setUp() throws ConfigurationException {
        MockitoAnnotations.initMocks(this);

        doReturn(mMockIDevice).when(mMockTestDevice).getIDevice();
        doReturn("serial").when(mMockTestDevice).getSerialNumber();

        InstrumentationTarget target1 =
                new InstrumentationTarget(TEST_PACKAGE_VALUE, "runner1", "target1");
        InstrumentationTarget target2 = new InstrumentationTarget("package2", "runner2", "target2");
        doReturn(ImmutableList.of(target1, target2))
                .when(mMockListInstrumentationParser)
                .getInstrumentationTargets();

        mInstrumentationTest.setPackageName(TEST_PACKAGE_VALUE);
        mInstrumentationTest.setRunnerName(TEST_RUNNER_VALUE);
        mInstrumentationTest.setDevice(mMockTestDevice);
        mInstrumentationTest.setListInstrumentationParser(mMockListInstrumentationParser);
        mInstrumentationTest.setReRunUsingTestFile(false);

        // Set up configuration.
        mConfig = new Configuration("", "");
        mCoverageOptions = new CoverageOptions();
        mCoverageOptionsSetter = new OptionSetter(mCoverageOptions);

        mConfig.setCoverageOptions(mCoverageOptions);
        mInstrumentationTest.setConfiguration(mConfig);
        mContext = new InvocationContext();
        mContext.addAllocatedDevice("main", mMockTestDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();
    }

    /** Test normal run scenario. */
    @Test
    public void testRun() throws DeviceNotAvailableException {
        // verify the mock listener is passed through to the runner
        RunInstrumentationTestsAnswer runTests =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        doAnswer(runTests)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestInvocationListener.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mInstrumentationTest, mMockTestDevice, mMockListener);
        ArgumentCaptor<IRemoteAndroidTestRunner> runner =
                ArgumentCaptor.forClass(IRemoteAndroidTestRunner.class);
        inOrder.verify(mInstrumentationTest).setRunnerArgs(runner.capture());
        inOrder.verify(mMockTestDevice, times(2))
                .runInstrumentationTests(eq(runner.getValue()), any(ITestLifeCycleReceiver.class));

        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
    }

    @Test
    public void testRun_nullTestInfo() throws Exception {
        mInstrumentationTest.run(/* testInfo= */ null, mMockListener);

        verify(mMockTestDevice, atLeastOnce())
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestInvocationListener.class));
    }

    @Test
    public void testRun_runTestsAsUser() throws DeviceNotAvailableException {
        mTestInfo.properties().put(RUN_TESTS_AS_USER_KEY, "10");
        mInstrumentationTest.run(mTestInfo, mMockListener);

        verify(mMockTestDevice, atLeastOnce())
                .runInstrumentationTestsAsUser(
                        any(IRemoteAndroidTestRunner.class),
                        eq(10),
                        any(ITestInvocationListener.class));
    }

    @Test
    public void testRun_bothAbi() throws DeviceNotAvailableException {
        mInstrumentationTest.setAbi(mock(IAbi.class));
        mInstrumentationTest.setForceAbi("test");
        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("Should have thrown an exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /** Test normal run scenario with --no-hidden-api-check specified */
    @Test
    public void testRun_hiddenApiCheck() throws Exception {
        doReturn(28).when(mMockTestDevice).getApiLevel();
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("hidden-api-checks", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--no-hidden-api-checks");
    }

    /** Test normal run scenario with --no-hidden-api-check specified */
    @Test
    public void testRun_testApiCheck() throws Exception {
        doReturn(true).when(mMockTestDevice).checkApiLevelAgainstNextRelease(30);
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("hidden-api-checks", "true");
        setter.setOptionValue("test-api-access", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--no-test-api-access");
    }

    /** Test normal run scenario with --no-isolated-storage specified */
    @Test
    public void testRun_isolatedStorage() throws Exception {
        doReturn(true).when(mMockTestDevice).checkApiLevelAgainstNextRelease(29);
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("isolated-storage", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--no-isolated-storage");
    }

    /** Test normal run scenario with --no-isolated-storage specified */
    @Test
    public void testRun_windowAnimation() throws Exception {
        doReturn(14).when(mMockTestDevice).getApiLevel();
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("window-animation", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--no-window-animation");
    }

    /** Test normal run scenario with --no-restart specified */
    @Test
    public void testRun_noRestart() throws Exception {
        doReturn(true).when(mMockTestDevice).checkApiLevelAgainstNextRelease(31);
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("restart", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--no-restart");
    }

    /** Test normal run scenario with --instrument-sdk-sandbox specified */
    @Test
    public void testRun_runOnSdkSandbox() throws Exception {
        doReturn(true).when(mMockTestDevice).checkApiLevelAgainstNextRelease(33);
        mTestInfo.properties().put(RUN_TESTS_ON_SDK_SANDBOX, Boolean.TRUE.toString());
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);
        assertThat(runner.getRunOptions()).contains("--instrument-sdk-in-sandbox");
    }

    /** Ensure isolated-Tests is turned off when run in dry-mode */
    @Test
    public void testRun_runInIsolatedTestsAndDryModeTurnsOffIsolatedTests() throws Exception {
        mInstrumentationTest.setOrchestrator(true);
        mInstrumentationTest.setCollectTestsOnly(true);
        mInstrumentationTest.run(mTestInfo, mMockListener);
        assertThat(mInstrumentationTest.isOrchestrator()).isFalse();
    }

    /** Ensure runOptions are comma-delimited to be compatible with the orchestrator */
    @Test
    public void testRun_runOptionsAreCommaDelimitedWhenRunInIsolatedTests() throws Exception {
        doReturn(31).when(mMockTestDevice).getApiLevel();
        mInstrumentationTest.setOrchestrator(true);
        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("window-animation", "false");
        setter.setOptionValue("hidden-api-checks", "false");
        RemoteAndroidTestRunner runner =
                (RemoteAndroidTestRunner)
                        mInstrumentationTest.createRemoteAndroidTestRunner(
                                "", "", mMockIDevice, mTestInfo);

        // Use three different assert to avoid creating a dependency on the order of flags.
        assertThat(runner.getRunOptions()).contains("--no-hidden-api-checks");
        assertThat(runner.getRunOptions()).contains(",");
        assertThat(runner.getRunOptions()).contains("--no-window-animation");
    }

    /** Test normal run scenario with a test class specified. */
    @Test
    public void testRun_class() {
        String className = "FooTest";
        FakeTestRunner runner = new FakeTestRunner("unused", "unused");

        mInstrumentationTest.setClassName(className);
        mInstrumentationTest.setRunnerArgs(runner);

        assertThat(runner.getArgs()).containsEntry("class", "'" + className + "'");
    }

    /** Test normal run scenario with a test class and method specified. */
    @Test
    public void testRun_classMethod() {
        String className = "FooTest";
        String methodName = "testFoo";
        FakeTestRunner runner = new FakeTestRunner("unused", "unused");

        mInstrumentationTest.setClassName(className);
        mInstrumentationTest.setMethodName(methodName);
        mInstrumentationTest.setRunnerArgs(runner);

        assertThat(runner.getArgs()).containsEntry("class", "'FooTest#testFoo'");
    }

    /** Test normal run scenario with a test package specified. */
    @Test
    public void testRun_testPackage() {
        String testPackageName = "com.foo";
        FakeTestRunner runner = new FakeTestRunner("unused", "unused");

        mInstrumentationTest.setTestPackageName(testPackageName);
        mInstrumentationTest.setRunnerArgs(runner);

        assertThat(runner.getArgs()).containsEntry("package", testPackageName);
    }

    /** Verify test package name is not passed to the runner if class name is set */
    @Test
    public void testRun_testPackageAndClass() {
        String testClassName = "FooTest";
        FakeTestRunner runner = new FakeTestRunner("unused", "unused");

        mInstrumentationTest.setTestPackageName("com.foo");
        mInstrumentationTest.setClassName(testClassName);
        mInstrumentationTest.setRunnerArgs(runner);
        assertThat(runner.getArgs()).containsEntry("class", "'" + testClassName + "'");
        assertThat(runner.getArgs()).doesNotContainKey("package");
    }

    /** Test that IllegalArgumentException is thrown when attempting run without setting device. */
    @Test
    public void testRun_noDevice() throws DeviceNotAvailableException {
        mInstrumentationTest.setDevice(null);

        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /** Test the rerun mode when test run has no tests. */
    @Test
    public void testRun_rerunEmpty() throws DeviceNotAvailableException {
        mInstrumentationTest.setRerunMode(true);

        // collect tests run
        RunInstrumentationTestsAnswer collectTest =
                (runner, listener) -> {
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 0);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        doAnswer(collectTest)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        // Report an empty run since nothing had to be run.
        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq(TEST_PACKAGE_VALUE), eq(0));
        inOrder.verify(mMockListener).testRunEnded(0, new HashMap<String, Metric>());
        inOrder.verifyNoMoreInteractions();
        Mockito.verifyNoMoreInteractions(mMockListener);
    }

    @Test
    public void testRun_betterFailure() throws Exception {
        mInstrumentationTest.setRerunMode(true);
        mInstrumentationTest.setReRunUsingTestFile(false);

        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunFailed("Test run failed to complete");
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        FailureDescription error1 =
                FailureDescription.create(
                        "Detected device offline causing instrumentation error: Test run failed to"
                                + " complete",
                        FailureStatus.TEST_FAILURE);
        error1.setDebugHelpMessage("The following tests didn't run: [Test#test2]");
        inOrder.verify(mMockListener).testRunFailed(error1);
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verifyNoMoreInteractions();
    }

    /** Test the rerun mode when first test run fails. */
    @Test
    public void testRun_rerun() throws Exception {
        mInstrumentationTest.setRerunMode(true);
        mInstrumentationTest.setReRunUsingTestFile(true);
        when(mMockTestDevice.pushFile(Mockito.any(), Mockito.any())).thenReturn(true);

        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunFailed(RUN_ERROR_MSG);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer rerun =
                (runner, listener) -> {
                    // perform call back on listeners to show run remaining test was run
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 1);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .doAnswer(rerun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener)
                .testRunFailed(
                        FailureDescription.create(RUN_ERROR_MSG, FailureStatus.TEST_FAILURE));
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testSkipped(eq(TEST2), (SkipReason) any());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        verify(mMockTestDevice).waitForDeviceAvailable();
        verify(mMockTestDevice).pushFile(Mockito.any(), Mockito.any());

        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(1), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verifyNoMoreInteractions();
    }

    /** Test the run when instrumentation collection reports the same tests several times. */
    @Test
    public void testRun_duplicate() throws Exception {
        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener, mMockTestDevice);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        FailureDescription failure =
                FailureDescription.create(
                        "The following tests ran more than once: [Test#test1]. Check your run "
                                + "configuration, you might be including the same test class "
                                + "several times.");
        failure.setFailureStatus(FailureStatus.TEST_FAILURE);
        inOrder.verify(mMockListener).testRunFailed(failure);
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testRun_duplicate_disable() throws Exception {
        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        OptionSetter setter = new OptionSetter(mInstrumentationTest);
        setter.setOptionValue("disable-duplicate-test-check", "true");
        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener, mMockTestDevice);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * When retrying a parameterized test we run all the parameters (since AJUR doesn't support
     * re-running only one). So we should ignore the unexpected ones in the retry.
     */
    @Test
    public void testRun_rerun_Parameterized() throws Exception {
        mInstrumentationTest.setRerunMode(true);

        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 3);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testStarted(TEST_PARAM2);
                    listener.testEnded(TEST_PARAM2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 3);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunFailed(RUN_ERROR_MSG);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer rerun =
                (runner, listener) -> {
                    // perform call back on listeners to show run remaining test was run
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 3);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testStarted(TEST_PARAM1);
                    listener.testEnded(TEST_PARAM1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST_PARAM2);
                    listener.testEnded(TEST_PARAM2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .doAnswer(rerun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        CollectingTestListener listener = new CollectingTestListener();
        mInstrumentationTest.run(mTestInfo, listener);

        assertEquals(3, listener.getExpectedTests());
        assertEquals(3, listener.getNumTotalTests());
        assertTrue(listener.getMergedTestRunResults().get(0).isRunComplete());
        assertTrue(listener.getMergedTestRunResults().get(0).isRunFailure());
        assertEquals(
                RUN_ERROR_MSG, listener.getMergedTestRunResults().get(0).getRunFailureMessage());
    }

    /** Verify that all tests are re-run when there is a failure during a coverage run. */
    @Test
    public void testRun_rerunCoverage() throws ConfigurationException, DeviceNotAvailableException {
        mInstrumentationTest.setRerunMode(true);
        mInstrumentationTest.setReRunUsingTestFile(true);
        mCoverageOptionsSetter.setOptionValue("coverage", "true");
        when(mMockTestDevice.pushFile(Mockito.any(), Mockito.any())).thenReturn(true);

        // Mock collected tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunFailed(RUN_ERROR_MSG);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer rerun1 =
                (runner, listener) -> {
                    // perform call back on listeners to show run remaining test was run
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 1);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer rerun2 =
                (runner, listener) -> {
                    // perform call back on listeners to show run remaining test was run
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 1);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .doAnswer(rerun1)
                .doAnswer(rerun2)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener)
                .testRunFailed(
                        FailureDescription.create(RUN_ERROR_MSG, FailureStatus.TEST_FAILURE));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), Mockito.anyInt(), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * Test resuming a test run when first run is aborted due to {@link DeviceNotAvailableException}
     */
    @Test
    public void testRun_resume() throws DeviceNotAvailableException {
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    // perform call back on listener to show run failed - only one test
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testRunFailed(RUN_ERROR_MSG);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    throw new DeviceNotAvailableException("test", "serial");
                };
        RunInstrumentationTestsAnswer rerun =
                (runner, listener) -> {
                    // perform call back on listeners to show run remaining test was run
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 1);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .doAnswer(rerun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }
        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener)
                .testRunFailed(
                        FailureDescription.create(RUN_ERROR_MSG, FailureStatus.TEST_FAILURE));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(1), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), eq(EMPTY_STRING_MAP));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
    }

    /**
     * Test that IllegalArgumentException is thrown when attempting run with negative timeout args.
     */
    @Test
    public void testRun_negativeTimeouts() throws DeviceNotAvailableException {
        mInstrumentationTest.setShellTimeout(-1);
        mInstrumentationTest.setTestTimeout(-2);

        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /** Test that IllegalArgumentException is thrown if an invalid test size is provided. */
    @Test
    public void testRun_badTestSize() throws DeviceNotAvailableException {
        mInstrumentationTest.setTestSize("foo");

        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testQueryRunnerName() throws DeviceNotAvailableException {
        String queriedRunner = mInstrumentationTest.queryRunnerName();
        assertThat(queriedRunner).isEqualTo("runner1");
    }

    @Test
    public void testQueryMultipleRunnerName() throws DeviceNotAvailableException {
        Mockito.reset(mMockListInstrumentationParser);
        InstrumentationTarget target1 =
                new InstrumentationTarget(
                        TEST_PACKAGE_VALUE,
                        "android.test.InstrumentationTestRunner",
                        TEST_PACKAGE_VALUE);
        InstrumentationTarget target2 =
                new InstrumentationTarget(
                        TEST_PACKAGE_VALUE,
                        "androidx.test.runner.AndroidJUnitRunner",
                        TEST_PACKAGE_VALUE);
        doReturn(ImmutableList.of(target1, target2))
                .when(mMockListInstrumentationParser)
                .getInstrumentationTargets();

        mInstrumentationTest = Mockito.spy(new InstrumentationTest());
        mInstrumentationTest.setPackageName(TEST_PACKAGE_VALUE);
        mInstrumentationTest.setRunnerName(TEST_RUNNER_VALUE);
        mInstrumentationTest.setDevice(mMockTestDevice);
        mInstrumentationTest.setListInstrumentationParser(mMockListInstrumentationParser);

        String queriedRunner = mInstrumentationTest.queryRunnerName();
        assertThat(queriedRunner).isEqualTo("androidx.test.runner.AndroidJUnitRunner");
    }

    @Test
    public void testQueryRunnerName_noMatch() throws DeviceNotAvailableException {
        mInstrumentationTest.setPackageName("noMatchPackage");
        String queriedRunner = mInstrumentationTest.queryRunnerName();
        assertThat(queriedRunner).isNull();
    }

    @Test
    public void testRun_noMatchingRunner() throws DeviceNotAvailableException {
        mInstrumentationTest.setPackageName("noMatchPackage");
        mInstrumentationTest.setRunnerName(null);
        try {
            mInstrumentationTest.run(mTestInfo, mMockListener);
            fail("Should have thrown an exception.");
        } catch (HarnessRuntimeException e) {
            // expected
        }
    }

    /**
     * Test that if we successfully collect a list of tests and the run crash and try to report 0 we
     * instead do report the number of collected test to get an appropriate count.
     */
    @Test
    public void testCollectWorks_RunCrash() throws Exception {
        doReturn(mock(IRemoteTest.class))
                .when(mInstrumentationTest)
                .getTestReRunner(anyCollection());

        // We collect successfully 5 tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    listener.testRunStarted("fakeName", 5);
                    for (int i = 0; i < 5; i++) {
                        TestDescription tid = new TestDescription("fakeclass", "fakemethod" + i);
                        listener.testStarted(tid, 5);
                        listener.testEnded(tid, 15, EMPTY_STRING_MAP);
                    }
                    listener.testRunEnded(500, EMPTY_STRING_MAP);
                    return true;
                };

        // We attempt to run and crash
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    listener.testRunStarted("fakeName", 0);
                    listener.testRunFailed("Instrumentation run failed due to 'Process crashed.'");
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        mInstrumentationTest.run(mTestInfo, mMockListener);

        // The reported number of tests is the one from the collected output
        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq("fakeName"), eq(5), eq(0), anyLong());
        inOrder.verify(mMockListener)
                .testRunFailed(
                        FailureDescription.create(
                                "Instrumentation run failed due to 'Process crashed.'",
                                FailureStatus.TEST_FAILURE));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
    }

    /** Test that after the first run if there is no tests to re-run we don't launch a rerun. */
    @Test
    public void testRun_noMoreTests() throws Exception {
        doReturn(mock(IRemoteTest.class))
                .when(mInstrumentationTest)
                .getTestReRunner(anyCollection());

        // We collect successfully 1 tests
        RunInstrumentationTestsAnswer collected =
                (runner, listener) -> {
                    listener.testRunStarted("fakeName", 1);
                    for (int i = 0; i < 1; i++) {
                        TestDescription tid = new TestDescription("fakeclass", "fakemethod" + i);
                        listener.testStarted(tid, 5);
                        listener.testEnded(tid, 15, EMPTY_STRING_MAP);
                    }
                    listener.testRunEnded(500, EMPTY_STRING_MAP);
                    return true;
                };

        // We attempt to run the test and it crash
        RunInstrumentationTestsAnswer partialRun =
                (runner, listener) -> {
                    listener.testRunStarted("fakeName", 1);
                    TestDescription tid = new TestDescription("fakeclass", "fakemethod0");
                    listener.testStarted(tid, 0L);
                    listener.testFailed(
                            tid, "Instrumentation run failed due to 'Process crashed.'");
                    listener.testEnded(tid, 15L, EMPTY_STRING_MAP);
                    listener.testRunFailed("Instrumentation run failed due to 'Process crashed.'");
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };

        doAnswer(collected)
                .doAnswer(partialRun)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestLifeCycleReceiver.class));

        InputStreamSource source = new ByteArrayInputStreamSource("".getBytes());
        doReturn(source).when(mMockTestDevice).getLogcatSince(anyLong());

        mInstrumentationTest.run(mTestInfo, mMockListener);

        // Ensure no rerunner is requested since there is no more tests.
        verify(mInstrumentationTest, times(0)).getTestReRunner(any());
        // The reported number of tests is the one from the collected output
        InOrder inOrder = Mockito.inOrder(mMockListener);
        inOrder.verify(mMockListener).testRunStarted(eq("fakeName"), eq(1), eq(0), anyLong());
        TestDescription tid = new TestDescription("fakeclass", "fakemethod0");
        inOrder.verify(mMockListener).testStarted(tid, 0L);
        FailureDescription failure =
                FailureDescription.create("Instrumentation run failed due to 'Process crashed.'")
                        .setErrorIdentifier(DeviceErrorIdentifier.INSTRUMENTATION_CRASH);
        inOrder.verify(mMockListener).testFailed(tid, failure);
        inOrder.verify(mMockListener).testEnded(tid, 15L, EMPTY_STRING_MAP);
        inOrder.verify(mMockListener)
                .testRunFailed(
                        FailureDescription.create(
                                "Instrumentation run failed due to 'Process crashed.'",
                                FailureStatus.TEST_FAILURE));
        inOrder.verify(mMockListener).testRunEnded(1, EMPTY_STRING_MAP);
    }

    /** Test normal run scenario when {@link IMetricCollector} are specified. */
    @Test
    public void testRun_withCollectors() throws DeviceNotAvailableException {
        // verify the mock listener is passed through to the runner
        RunInstrumentationTestsAnswer runTests =
                (runner, listener) -> {
                    // perform call back on listener to show run of two tests
                    listener.testRunStarted(TEST_PACKAGE_VALUE, 2);
                    listener.testStarted(TEST1);
                    listener.testEnded(TEST1, EMPTY_STRING_MAP);
                    listener.testStarted(TEST2);
                    listener.testEnded(TEST2, EMPTY_STRING_MAP);
                    listener.testRunEnded(1, EMPTY_STRING_MAP);
                    return true;
                };
        doAnswer(runTests)
                .when(mMockTestDevice)
                .runInstrumentationTests(
                        any(IRemoteAndroidTestRunner.class), any(ITestInvocationListener.class));

        List<IMetricCollector> collectors = new ArrayList<>();
        CalledMetricCollector calledCollector = new CalledMetricCollector();
        calledCollector.mName = "called";
        CalledMetricCollector notCalledCollector = new CalledMetricCollector();
        notCalledCollector.setDisable(true);
        notCalledCollector.mName = "not-called";
        collectors.add(notCalledCollector);
        collectors.add(calledCollector);
        mInstrumentationTest.setMetricCollectors(collectors);
        mInstrumentationTest.run(mTestInfo, mMockListener);

        InOrder inOrder = Mockito.inOrder(mInstrumentationTest, mMockTestDevice, mMockListener);
        ArgumentCaptor<IRemoteAndroidTestRunner> runner =
                ArgumentCaptor.forClass(IRemoteAndroidTestRunner.class);
        inOrder.verify(mInstrumentationTest).setRunnerArgs(runner.capture());
        inOrder.verify(mMockTestDevice, times(2))
                .runInstrumentationTests(eq(runner.getValue()), any(ITestLifeCycleReceiver.class));
        inOrder.verify(mMockListener)
                .testRunStarted(eq(TEST_PACKAGE_VALUE), eq(2), eq(0), anyLong());
        inOrder.verify(mMockListener).testStarted(eq(TEST1), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST1), anyLong(), testCapture1.capture());
        HashMap<String, Metric> test1Metric = testCapture1.getValue();
        assertTrue(test1Metric.containsKey("called"));
        assertFalse(test1Metric.containsKey("not-called"));
        inOrder.verify(mMockListener).testStarted(eq(TEST2), anyLong());
        inOrder.verify(mMockListener).testEnded(eq(TEST2), anyLong(), testCapture2.capture());
        HashMap<String, Metric> test2Metric = testCapture2.getValue();
        assertTrue(test2Metric.containsKey("called"));
        assertFalse(test2Metric.containsKey("not-called"));
        inOrder.verify(mMockListener).testRunEnded(anyLong(), runCapture.capture());
        HashMap<String, Metric> runMetric = runCapture.getValue();
        assertTrue(runMetric.containsKey("called"));
        assertFalse(runMetric.containsKey("not-called"));
    }

    private static class FakeTestRunner extends RemoteAndroidTestRunner {

        private Map<String, String> mArgs = new HashMap<>();

        FakeTestRunner(String packageName, String runnerName) {
            super(packageName, runnerName, null);
        }

        @Override
        public void addInstrumentationArg(String name, String value) {
            mArgs.put(name, value);
        }

        Map<String, String> getArgs() {
            return ImmutableMap.copyOf(mArgs);
        }
    }
}
