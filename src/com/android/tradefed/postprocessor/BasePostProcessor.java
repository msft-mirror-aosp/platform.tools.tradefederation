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
package com.android.tradefed.postprocessor;

import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * The base {@link IPostProcessor} that every implementation should extend. Ensure that the post
 * processing methods are called before the final result reporters.
 */
public abstract class BasePostProcessor implements IPostProcessor {

    @Option(name = "disable", description = "disables the post processor.")
    private boolean mDisable = false;

    private ITestInvocationListener mForwarder;
    private ArrayListMultimap<String, Metric> storedTestMetrics = ArrayListMultimap.create();
    private Map<TestDescription, Map<String, LogFile>> mTestLogs = new LinkedHashMap<>();
    private Map<String, LogFile> mRunLogs = new HashMap<>();

    private Map<String, LogFile> mCopiedLogs = new HashMap<>();
    private Set<LogFile> mToDelete = new HashSet<>();

    // Keeps track of the current test; takes null value when the post processor is not in the scope
    // of any test (i.e. before the first test, in-between tests and after the last test).
    private TestDescription mCurrentTest = null;

    private ILogSaver mLogSaver = null;

    // Marks whether the post processor is actively post processing, which changes some event
    // forwarding behavior.
    private boolean mIsPostProcessing = false;

    private String mRunName;

    /** {@inheritDoc} */
    @Override
    public abstract Map<String, Metric.Builder> processRunMetricsAndLogs(
            HashMap<String, Metric> rawMetrics, Map<String, LogFile> runLogs);

    /** {@inheritDoc} */
    @Override
    public Map<String, Metric.Builder> processTestMetricsAndLogs(
            TestDescription testDescription,
            HashMap<String, Metric> testMetrics,
            Map<String, LogFile> testLogs) {
        return new HashMap<String, Metric.Builder>();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Metric.Builder> processAllTestMetricsAndLogs(
            ListMultimap<String, Metric> allTestMetrics,
            Map<TestDescription, Map<String, LogFile>> allTestLogs) {
        return new HashMap<String, Metric.Builder>();
    }

    /** =================================== */
    /** {@inheritDoc} */
    @Override
    public final ITestInvocationListener init(ITestInvocationListener listener) {
        long start = System.currentTimeMillis();
        setUp();
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.COLLECTOR_TIME, System.currentTimeMillis() - start);
        mForwarder = listener;
        return this;
    }

    /** =================================== */
    /** {@inheritDoc} */
    @Override
    public final boolean isDisabled() {
        return mDisable;
    }

    /** =================================== */
    /** Invocation Listeners for forwarding */
    @Override
    public final void invocationStarted(IInvocationContext context) {
        mForwarder.invocationStarted(context);
    }

    @Override
    public final void invocationFailed(Throwable cause) {
        mForwarder.invocationFailed(cause);
    }

    @Override
    public final void invocationFailed(FailureDescription failure) {
        mForwarder.invocationFailed(failure);
    }

    @Override
    public void invocationSkipped(SkipReason reason) {
        mForwarder.invocationSkipped(reason);
    }

    @Override
    public final void invocationEnded(long elapsedTime) {
        mForwarder.invocationEnded(elapsedTime);
    }

    /** Use this method to log a file from the PostProcessor implementation. */
    @Override
    public final void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        // If currently post processing, the file should be saved; otherwise, the file should have
        // been logged elsewhere and this call only needs to be forwarded.
        if (mIsPostProcessing) {
            CLog.i("Saving file with data name %s in post processor.", dataName);
            if (mLogSaver != null) {
                try {
                    LogFile log = null;
                    if (dataStream instanceof FileInputStreamSource) {
                        log =
                                mLogSaver.saveLogFile(
                                        dataName,
                                        dataType,
                                        ((FileInputStreamSource) dataStream).getFile());
                    } else {
                        log =
                                mLogSaver.saveLogData(
                                        dataName, dataType, dataStream.createInputStream());
                    }
                    testLogSaved(dataName, dataType, dataStream, log);
                    logAssociation(dataName, log);
                } catch (IOException e) {
                    CLog.e("Failed to save log file %s.", dataName);
                    CLog.e(e);
                }
            } else {
                CLog.e("Attempting to save log in post processor when its log saver is not set.");
            }
        }
        mForwarder.testLog(dataName, dataType, dataStream);
    }

    @Override
    public final void testModuleStarted(IInvocationContext moduleContext) {
        mForwarder.testModuleStarted(moduleContext);
    }

    @Override
    public final void testModuleEnded() {
        mForwarder.testModuleEnded();
    }

    /** Test run callbacks */
    @Override
    public final void testRunStarted(String runName, int testCount) {
        testRunStarted(runName, testCount, 0, System.currentTimeMillis());
    }

    @Override
    public final void testRunStarted(String runName, int testCount, int attemptNumber) {
        testRunStarted(runName, testCount, attemptNumber, System.currentTimeMillis());
    }

    @Override
    public final void testRunStarted(
            String runName, int testCount, int attemptNumber, long startTime) {
        mRunName = runName;
        mForwarder.testRunStarted(runName, testCount, attemptNumber, startTime);
    }

    @Override
    public final void testRunFailed(String errorMessage) {
        mForwarder.testRunFailed(errorMessage);
    }

    @Override
    public final void testRunFailed(FailureDescription failure) {
        mForwarder.testRunFailed(failure);
    }

    @Override
    public final void testRunStopped(long elapsedTime) {
        mForwarder.testRunStopped(elapsedTime);
    }

    @Override
    public final void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
        testRunEnded(elapsedTime, TfMetricProtoUtil.upgradeConvert(runMetrics));
    }

    @Override
    public final void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        long start = System.currentTimeMillis();
        mIsPostProcessing = true;
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("run_processor_" + this.getClass().getSimpleName())) {
            HashMap<String, Metric> rawValues = getRawMetricsOnly(runMetrics);
            // Add post-processed run metrics.
            Map<String, Metric.Builder> postprocessedResults =
                    processRunMetricsAndLogs(rawValues, mRunLogs);
            addProcessedMetricsToExistingMetrics(postprocessedResults, runMetrics);
            // Add aggregated test metrics (results from post-processing all test metrics).
            Map<String, Metric.Builder> aggregateResults =
                    processAllTestMetricsAndLogs(storedTestMetrics, mTestLogs);
            addProcessedMetricsToExistingMetrics(aggregateResults, runMetrics);
        } catch (RuntimeException e) {
            // Prevent exception from messing up the status reporting.
            CLog.e(e);
        } finally {
            // Clear out the stored test metrics.
            storedTestMetrics.clear();
            // Clear out the stored test and run logs.
            mTestLogs.clear();
            mRunLogs.clear();
            // Delete all the copies
            cleanUp();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.COLLECTOR_TIME, System.currentTimeMillis() - start);
        }
        mIsPostProcessing = false;
        mForwarder.testRunEnded(elapsedTime, runMetrics);
    }

    /** Test cases callbacks */
    @Override
    public final void testStarted(TestDescription test) {
        testStarted(test, System.currentTimeMillis());
    }

    @Override
    public final void testStarted(TestDescription test, long startTime) {
        mCurrentTest = test;
        if (mTestLogs.containsKey(test)) {
            mTestLogs.get(test).clear();
        } else {
            mTestLogs.put(test, new HashMap<String, LogFile>());
        }
        mForwarder.testStarted(test, startTime);
    }

    @Override
    public final void testFailed(TestDescription test, String trace) {
        mForwarder.testFailed(test, trace);
    }

    @Override
    public final void testFailed(TestDescription test, FailureDescription failure) {
        mForwarder.testFailed(test, failure);
    }

    @Override
    public final void testEnded(TestDescription test, Map<String, String> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    @Override
    public final void testEnded(
            TestDescription test, long endTime, Map<String, String> testMetrics) {
        testEnded(test, endTime, TfMetricProtoUtil.upgradeConvert(testMetrics));
    }

    @Override
    public final void testEnded(TestDescription test, HashMap<String, Metric> testMetrics) {
        testEnded(test, System.currentTimeMillis(), testMetrics);
    }

    @Override
    public final void testEnded(
            TestDescription test, long endTime, HashMap<String, Metric> testMetrics) {
        mIsPostProcessing = true;
        long start = System.currentTimeMillis();
        try {
            HashMap<String, Metric> rawValues = getRawMetricsOnly(testMetrics);
            // Store the raw metrics from the test in storedTestMetrics for potential aggregation.
            for (Map.Entry<String, Metric> entry : rawValues.entrySet()) {
                storedTestMetrics.put(entry.getKey(), entry.getValue());
            }
            Map<String, Metric.Builder> results =
                    processTestMetricsAndLogs(
                            test,
                            rawValues,
                            mTestLogs.containsKey(test)
                                    ? mTestLogs.get(test)
                                    : new HashMap<String, LogFile>());
            for (Entry<String, Metric.Builder> newEntry : results.entrySet()) {
                String newKey = newEntry.getKey();
                if (testMetrics.containsKey(newKey)) {
                    CLog.e(
                            "Key '%s' is already asssociated with a metric and will not be "
                                    + "replaced.",
                            newKey);
                    continue;
                }
                // Force the metric to 'processed' since generated in a post-processor.
                Metric newMetric =
                        newEntry.getValue()
                                .setType(getMetricType())
                                .build();
                testMetrics.put(newKey, newMetric);
            }
        } catch (RuntimeException e) {
            // Prevent exception from messing up the status reporting.
            CLog.e(e);
        } finally {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.COLLECTOR_TIME, System.currentTimeMillis() - start);
        }
        mIsPostProcessing = false;
        mCurrentTest = null;
        mForwarder.testEnded(test, endTime, testMetrics);
    }

    @Override
    public final void testAssumptionFailure(TestDescription test, String trace) {
        mForwarder.testAssumptionFailure(test, trace);
    }

    @Override
    public final void testAssumptionFailure(TestDescription test, FailureDescription failure) {
        mForwarder.testAssumptionFailure(test, failure);
    }

    @Override
    public final void testIgnored(TestDescription test) {
        mForwarder.testIgnored(test);
    }

    @Override
    public final void testSkipped(TestDescription test, SkipReason reason) {
        mForwarder.testSkipped(test, reason);
    }

    /**
     * Override this method in the child post processors to initialize before the test runs.
     */
    public void setUp() {
        // NO-OP by default
    }

    @Override
    public final void setLogSaver(ILogSaver logSaver) {
        mLogSaver = logSaver;
        if (mForwarder instanceof ILogSaverListener) {
            ((ILogSaverListener) mForwarder).setLogSaver(logSaver);
        }
    }

    @Override
    public final void testLogSaved(
            String dataName, LogDataType dataType, InputStreamSource dataStream, LogFile logFile) {
        if (!mIsPostProcessing) {
            mCopiedLogs.put(dataName, copyLogFile(logFile));
        }
        if (mForwarder instanceof ILogSaverListener) {
            ((ILogSaverListener) mForwarder).testLogSaved(dataName, dataType, dataStream, logFile);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Updates the log-to-test assocation. If this method is called during a test, then the log
     * belongs to the test; otherwise it will be a run log.
     */
    @Override
    public final void logAssociation(String dataName, LogFile logFile) {
        // Only associate files created outside of the current post processor.
        if (!mIsPostProcessing) {
            LogFile copyFile = mCopiedLogs.remove(dataName);
            if (copyFile != null) {
                mToDelete.add(copyFile);
                // mCurrentTest is null only outside the scope of a test.
                if (mCurrentTest != null) {
                    mTestLogs.get(mCurrentTest).put(dataName, copyFile);
                } else {
                    mRunLogs.put(dataName, copyFile);
                }
            }
        }

        if (mForwarder instanceof ILogSaverListener) {
            ((ILogSaverListener) mForwarder).logAssociation(dataName, logFile);
        }
    }

    // Internal utilities

    /**
     * We only allow post-processing of raw values. Already processed values will not be considered.
     */
    private HashMap<String, Metric> getRawMetricsOnly(HashMap<String, Metric> runMetrics) {
        HashMap<String, Metric> rawMetrics = new HashMap<>();
        for (Entry<String, Metric> entry : runMetrics.entrySet()) {
            if (DataType.RAW.equals(entry.getValue().getType())) {
                rawMetrics.put(entry.getKey(), entry.getValue());
            }
        }
        return rawMetrics;
    }

    /** Add processed metrics to the metrics to be reported. */
    private void addProcessedMetricsToExistingMetrics(
            Map<String, Metric.Builder> processed, Map<String, Metric> existing) {
        for (Entry<String, Metric.Builder> newEntry : processed.entrySet()) {
            String newKey = newEntry.getKey();
            if (existing.containsKey(newKey)) {
                CLog.e(
                        "Key '%s' is already asssociated with a metric and will not be "
                                + "replaced.",
                        newKey);
                continue;
            }
            // Force the metric to 'processed' since generated in a post-processor.
            Metric newMetric =
                    newEntry.getValue()
                            .setType(getMetricType())
                            .build();
            existing.put(newKey, newMetric);
        }
    }

    /**
     * Override this method to change the metric type if needed. By default metric is set to
     * processed type.
     */
    protected DataType getMetricType() {
        return DataType.PROCESSED;
    }

    /*
     * TODO: b/191168103 Remove this method after run name dependency is removed from metric file
     * post processor.
     */
    protected String getRunName() {
        return mRunName;
    }

    protected void cleanUp() {
        // Delete all the copies
        for (LogFile f : mToDelete) {
            FileUtil.deleteFile(new File(f.getPath()));
        }
        mToDelete.clear();
    }

    private LogFile copyLogFile(LogFile original) {
        if (Strings.isNullOrEmpty(original.getPath())) {
            CLog.d("%s doesn't exist and can't be copied for post processor.", original);
            return null;
        }
        File originalFile = new File(original.getPath());
        if (!originalFile.exists()) {
            CLog.d("%s doesn't exist and can't be copied for post processor.", original.getPath());
            return null;
        }
        try {
            File copy =
                    FileUtil.createTempFile(
                            originalFile.getName(), "", CurrentInvocation.getWorkFolder());
            copy.delete();
            FileUtil.hardlinkFile(originalFile, copy);
            return new LogFile(
                    copy.getAbsolutePath(),
                    original.getUrl(),
                    original.isCompressed(),
                    original.getType(),
                    original.getSize());
        } catch (IOException e) {
            CLog.e(e);
        }
        return null;
    }
}
