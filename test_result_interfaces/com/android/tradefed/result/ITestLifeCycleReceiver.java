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
package com.android.tradefed.result;

import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives event notifications during instrumentation test runs.
 *
 * <p>Patterned after org.junit.runner.notification.RunListener
 *
 * <p>The sequence of calls will be:
 *
 * <ul>
 *   <li> testRunStarted
 *   <li> testStarted
 *   <li> [testFailed]
 *   <li> [testAssumptionFailure]
 *   <li> [testIgnored]
 *   <li> testEnded
 *   <li> ....
 *   <li> [testRunFailed]
 *   <li> testRunEnded
 * </ul>
 */
public interface ITestLifeCycleReceiver {

    /**
     * Reports the start of a test run.
     *
     * @param runName the test run name
     * @param testCount total number of tests in test run
     */
    public default void testRunStarted(String runName, int testCount) {}

    /**
     * Reports the start of a test run.
     *
     * @param runName the test run name
     * @param testCount total number of tests in test run
     * @param attemptNumber order number, identifying the different attempts of the same runName
     *     that run multiple times. The attemptNumber is 0-indexed and should increment everytime
     *     a new run happens. e.g. A test is granular retried 3 times, it should have 4 total
     *     runs under the same runName and the attemptNumber is from 0 to 3.
     */
    public default void testRunStarted(String runName, int testCount, int attemptNumber) {
        testRunStarted(runName, testCount);
    }

    /**
     * Reports the start of a test run.
     *
     * @param runName the test run name
     * @param testCount total number of tests in test run
     * @param attemptNumber order number, identifying the different attempts of the same runName
     *     that run multiple times. The attemptNumber is 0-indexed and should increment everytime a
     *     new run happens. e.g. A test is granular retried 3 times, it should have 4 total runs
     *     under the same runName and the attemptNumber is from 0 to 3.
     * @param startTime the time the run started, measured via {@link System#currentTimeMillis()}
     */
    public default void testRunStarted(
            String runName, int testCount, int attemptNumber, long startTime) {
        testRunStarted(runName, testCount);
    }

    /**
     * Reports test run failed to complete due to a fatal error.
     *
     * @param errorMessage {@link String} describing reason for run failure.
     */
    public default void testRunFailed(String errorMessage) {}

    /**
     * Reports test run failed to complete due to a failure described by {@link FailureDescription}.
     *
     * @param failure {@link FailureDescription} describing the failure and its context.
     */
    public default void testRunFailed(FailureDescription failure) {
        testRunFailed(failure.toString());
    }

    /**
     * Reports end of test run.
     *
     * @param elapsedTimeMillis device reported elapsed time, in milliseconds
     * @param runMetrics key-value pairs reported at the end of a test run
     */
    public default void testRunEnded(long elapsedTimeMillis, Map<String, String> runMetrics) {
        testRunEnded(elapsedTimeMillis, TfMetricProtoUtil.upgradeConvert(runMetrics));
    }

    /**
     * Reports end of test run. FIXME: We cannot have two Map<> interfaces with different type, so
     * we have to use HashMap here.
     *
     * @param elapsedTimeMillis device reported elapsed time, in milliseconds
     * @param runMetrics key-value pairs reported at the end of a test run with {@link Metric}.
     */
    public default void testRunEnded(long elapsedTimeMillis, HashMap<String, Metric> runMetrics) {}

    /**
     * Reports test run stopped before completion due to a user request.
     *
     * <p>TODO: currently unused, consider removing
     *
     * @param elapsedTime device reported elapsed time, in milliseconds
     */
    public default void testRunStopped(long elapsedTime) {}

    /**
     * Reports the start of an individual test case. Older interface, should use {@link
     * #testStarted(TestDescription)} whenever possible.
     *
     * @param test identifies the test
     */
    public default void testStarted(TestDescription test) {}

    /**
     * Alternative to {@link #testStarted(TestDescription)} where we also specify when the test was
     * started, combined with {@link #testEnded(TestDescription, long, Map)} for accurate measure.
     *
     * @param test identifies the test
     * @param startTime the time the test started, measured via {@link System#currentTimeMillis()}
     */
    default void testStarted(TestDescription test, long startTime) {
        testStarted(test);
    }

    /**
     * Reports the failure of a individual test case.
     *
     * <p>Will be called between testStarted and testEnded.
     *
     * @param test identifies the test
     * @param trace stack trace of failure
     */
    public default void testFailed(TestDescription test, String trace) {}

    /**
     * Reports the failure of a individual test case.
     *
     * <p>Will be called between testStarted and testEnded.
     *
     * @param test identifies the test
     * @param failure {@link FailureDescription} describing the failure and its context.
     */
    public default void testFailed(TestDescription test, FailureDescription failure) {
        testFailed(test, failure.toString());
    }

    /**
     * Called when an atomic test flags that it assumes a condition that is false
     *
     * @param test identifies the test
     * @param trace stack trace of failure
     */
    public default void testAssumptionFailure(TestDescription test, String trace) {}

    /**
     * Called when an atomic test flags that it assumes a condition that is false
     *
     * @param test identifies the test
     * @param failure {@link FailureDescription} describing the failure and its context.
     */
    public default void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        testAssumptionFailure(test, failure.toString());
    }

    /**
     * Called when a test will not be run, generally because a test method is annotated with
     * org.junit.Ignore.
     *
     * @param test identifies the test
     */
    public default void testIgnored(TestDescription test) {}

    /**
     * Called when a test is skipped and did not execute for a reason that is not usually expected.
     * These tests will be attempted to be retried to attempt to get a proper execution.
     *
     * @param test identifies the test
     * @param reason {@link SkipReason}
     */
    public default void testSkipped(TestDescription test, SkipReason reason) {}

    /**
     * Reports the execution end of an individual test case.
     *
     * <p>If {@link #testFailed} was not invoked, this test passed. Also returns any key/value
     * metrics which may have been emitted during the test case's execution.
     *
     * @param test identifies the test
     * @param testMetrics a {@link Map} of the metrics emitted
     */
    public default void testEnded(TestDescription test, Map<String, String> testMetrics) {
        testEnded(test, TfMetricProtoUtil.upgradeConvert(testMetrics));
    }

    /**
     * Reports the execution end of an individual test case.
     *
     * <p>If {@link #testFailed} was not invoked, this test passed. Also returns any key/value
     * metrics which may have been emitted during the test case's execution.
     *
     * @param test identifies the test
     * @param testMetrics a {@link Map} of the metrics emitted
     */
    public default void testEnded(TestDescription test, HashMap<String, Metric> testMetrics) {}

    /**
     * Alternative to {@link #testEnded(TestDescription, Map)} where we can specify the end time
     * directly. Combine with {@link #testStarted(TestDescription, long)} for accurate measure.
     *
     * @param test identifies the test
     * @param endTime the time the test ended, measured via {@link System#currentTimeMillis()}
     * @param testMetrics a {@link Map} of the metrics emitted
     */
    public default void testEnded(
            TestDescription test, long endTime, Map<String, String> testMetrics) {
        testEnded(test, endTime, TfMetricProtoUtil.upgradeConvert(testMetrics));
    }

    /**
     * Alternative to {@link #testEnded(TestDescription, Map)} where we can specify the end time
     * directly. Combine with {@link #testStarted(TestDescription, long)} for accurate measure.
     *
     * @param test identifies the test
     * @param endTime the time the test ended, measured via {@link System#currentTimeMillis()}
     * @param testMetrics a {@link Map} of the metrics emitted
     */
    public default void testEnded(
            TestDescription test, long endTime, HashMap<String, Metric> testMetrics) {
        testEnded(test, testMetrics);
    }
}
