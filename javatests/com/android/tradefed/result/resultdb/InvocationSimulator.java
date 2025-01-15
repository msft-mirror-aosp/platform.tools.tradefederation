/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tradefed.result.resultdb;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestSummaryListener;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestSummary;
import com.android.tradefed.util.MultiMap;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Invocation simulator for testing. */
public class InvocationSimulator {

    public enum TestStatus {
        PASS("pass"),
        FAIL("fail"),
        IGNORED("ignored"),
        ASSUMPTION_FAILURE("assumptionFailure"),
        TEST_ERROR("testError"),
        TEST_SKIPPED("testSkipped");

        private final String value;

        private TestStatus(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private String mModuleName = "example-module";
    private MultiMap<String, String> mModuleMetadata = new MultiMap<>();
    private Map<String, String> mModuleAttributes = new HashMap<>();
    private List<TestDescription> mTests;
    private List<TestStatus> mTestOutcomes;
    private List<HashMap<String, MetricMeasurement.Metric>> mTestMetricsList;
    private Map<TestDescription, FailureDescription> mFailureDescriptions;
    private String mSummary = "";
    private String mTestRunError = "";
    private FailureDescription mTestRunDescription;
    private HashMap<String, MetricMeasurement.Metric> mTestRunMetrics;
    private String mInvocationId;
    private String mWorkUnitId;
    private String mLegacyResultId;
    private Map<String, String> mInvocationAttributes;
    private List<IBuildInfo> mBuildInfos;
    private List<LogFile> mTestLogs;
    private List<LogFile> mTestRunLogs;
    private List<LogFile> mInvocationLogs;
    private boolean mIgnoreExceptions = false;
    private boolean mSkipTestRuns = false;

    public static InvocationSimulator create() {
        return new InvocationSimulator();
    }

    private InvocationSimulator() {
        mTests = new ArrayList<>();
        mTestOutcomes = new ArrayList<>();
        mTestMetricsList = new ArrayList<>();
        mFailureDescriptions = new HashMap<>();
        mTestRunMetrics = new HashMap<>();
        mBuildInfos = new ArrayList<>();
        mTestLogs = new ArrayList<>();
        mTestRunLogs = new ArrayList<>();
        mInvocationLogs = new ArrayList<>();
        mInvocationAttributes = new HashMap<>();
    }

    public InvocationSimulator setInvocationId(String invocationId) {
        mInvocationId = invocationId;
        return this;
    }

    public InvocationSimulator setWorkUnitId(String workUnitId) {
        mWorkUnitId = workUnitId;
        return this;
    }

    public InvocationSimulator setLegacyResultId(String id) {
        mLegacyResultId = id;
        return this;
    }

    public InvocationSimulator withBuildInfo(IBuildInfo info) {
        mBuildInfos.add(info);
        return this;
    }

    public InvocationSimulator withInvocationAttribute(String name, String value) {
        mInvocationAttributes.put(name, value);
        return this;
    }

    private IInvocationContext getModule() {
        ConfigurationDescriptor module = new ConfigurationDescriptor();
        module.setModuleName(mModuleName);
        if (!mModuleMetadata.isEmpty()) {
            module.setMetaData(mModuleMetadata);
        }
        InvocationContext moduleContext = createInvocation();
        moduleContext.setConfigurationDescriptor(module);
        for (Map.Entry<String, String> attribute : mModuleAttributes.entrySet()) {
            moduleContext.addInvocationAttribute(attribute.getKey(), attribute.getValue());
        }
        return moduleContext;
    }

    private long getStartTime() {
        String in = "2018-09-07 15:23:45";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        ZonedDateTime date = formatter.parse(in, LocalDateTime::from).atZone(ZoneId.of("UTC"));
        return date.toInstant().toEpochMilli();
    }

    public InvocationSimulator withTest(String clazz, String method) {
        return withTest(clazz, method, TestStatus.PASS);
    }

    public InvocationSimulator withTest(String clazz, String method, TestStatus outcome) {
        return withTest(clazz, method, outcome, new HashMap<String, MetricMeasurement.Metric>());
    }

    public InvocationSimulator withTestFailure(
            String clazz, String method, FailureDescription failure) {
        mFailureDescriptions.put(new TestDescription(clazz, method), failure);
        return this;
    }

    public InvocationSimulator withTest(
            String clazz,
            String method,
            TestStatus outcome,
            HashMap<String, MetricMeasurement.Metric> metrics) {
        mTests.add(new TestDescription(clazz, method));
        mTestOutcomes.add(outcome);
        mTestMetricsList.add(metrics);
        return this;
    }

    public InvocationSimulator withModule(String module) {
        mModuleName = module;
        return this;
    }

    public InvocationSimulator withModuleMetadata(String name, String value) {
        mModuleMetadata.put(name, value);
        return this;
    }

    public InvocationSimulator withModuleAttribute(String name, String value) {
        mModuleAttributes.put(name, value);
        return this;
    }

    public InvocationSimulator withoutModules() {
        mModuleName = "";
        return this;
    }

    public InvocationSimulator withSummary(String summary) {
        mSummary = summary;
        return this;
    }

    public InvocationSimulator withTestRunMetrics(
            HashMap<String, MetricMeasurement.Metric> runMetrics) {
        mTestRunMetrics = runMetrics;
        return this;
    }

    public InvocationSimulator withTestRunFailure(String message) {
        mTestRunError = message;
        return this;
    }

    public InvocationSimulator withTestRunFailure(FailureDescription failure) {
        mTestRunDescription = failure;
        return this;
    }

    public InvocationSimulator withTestLog(LogFile log) {
        mTestLogs.add(log);
        return this;
    }

    public InvocationSimulator withTestRunLog(LogFile log) {
        mTestRunLogs.add(log);
        return this;
    }

    public InvocationSimulator withInvocationLog(LogFile log) {
        mInvocationLogs.add(log);
        return this;
    }

    public InvocationSimulator ignoreExceptions() {
        mIgnoreExceptions = true;
        return this;
    }

    public InvocationSimulator skipTestRuns() {
        mSkipTestRuns = true;
        return this;
    }

    public <T extends ILogSaverListener & ITestSummaryListener> void simulateInvocation(
            T reporter) {
        if (!mSummary.isEmpty()) {
            reporter.putEarlySummary(Arrays.asList(new TestSummary(mSummary)));
        }
        try {
            reporter.invocationStarted(createInvocation());
        } catch (Exception e) {
            if (!mIgnoreExceptions) {
                throw e;
            }
        }
        try {
            if (!mModuleName.isEmpty()) {
                reporter.testModuleStarted(getModule());
            }
        } catch (Exception e) {
            if (!mIgnoreExceptions) {
                throw e;
            }
        }
        try {
            if (!mSkipTestRuns) {
                reporter.testRunStarted("TestRun1", 1, 1);
            }
        } catch (Exception e) {
            if (!mIgnoreExceptions) {
                throw e;
            }
        }

        simulateTests(reporter);
        if (!mTestRunError.isEmpty()) {
            reporter.testRunFailed(mTestRunError);
        }
        if (mTestRunDescription != null) {
            reporter.testRunFailed(mTestRunDescription);
        }
        simulateLogs("test-run-", mTestRunLogs, reporter);
        if (!mSkipTestRuns) {
            reporter.testRunEnded(1000L, mTestRunMetrics);
        }
        if (!mModuleName.isEmpty()) {
            reporter.testModuleEnded();
        }
        simulateLogs("invocation-log-", mInvocationLogs, reporter);
        reporter.invocationEnded(100);
    }

    private InvocationContext createInvocation() {
        InvocationContext context = new InvocationContext();
        context.setTestTag("test tag");
        context.addInvocationAttribute("invocation_id", mInvocationId);
        context.addInvocationAttribute("work_unit_id", mWorkUnitId);
        context.addInvocationAttribute("test_result_id", mLegacyResultId);
        for (int i = 0; i < mBuildInfos.size(); i++) {
            context.addDeviceBuildInfo(String.format("device_%d", i), mBuildInfos.get(i));
        }
        for (Map.Entry<String, String> attr : mInvocationAttributes.entrySet()) {
            context.addInvocationAttribute(attr.getKey(), attr.getValue());
        }
        return context;
    }

    private <T extends ILogSaverListener & ITestSummaryListener> void simulateTests(T reporter) {
        long startTime = getStartTime();
        for (int i = 0; i < mTests.size(); i++) {
            reporter.testStarted(mTests.get(i), startTime);
            startTime += 100;
            switch (mTestOutcomes.get(i)) {
                case FAIL:
                    reporter.testFailed(mTests.get(i), "Fail Trace");
                    break;
                case ASSUMPTION_FAILURE:
                    reporter.testAssumptionFailure(mTests.get(i), "Assumption Fail Trace");
                    break;
                case IGNORED:
                    reporter.testIgnored(mTests.get(i));
                    break;
                default:
                    break;
            }
            simulateLogs("test-log-", mTestLogs, reporter);
            reporter.testEnded(mTests.get(i), startTime, mTestMetricsList.get(i));
            startTime += 100;
        }
        for (Map.Entry<TestDescription, FailureDescription> entry :
                mFailureDescriptions.entrySet()) {
            reporter.testStarted(entry.getKey(), startTime);
            startTime += 100;
            reporter.testFailed(entry.getKey(), entry.getValue());
            reporter.testEnded(entry.getKey(), startTime, Collections.emptyMap());
            startTime += 100;
        }
    }

    private void simulateLogs(String prefix, List<LogFile> logs, ILogSaverListener reporter) {
        for (int i = 0; i < logs.size(); i++) {
            String dataName = String.format("%s%d", prefix, i);
            reporter.logAssociation(dataName, logs.get(i));
        }
    }
}
