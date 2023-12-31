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
package com.android.tradefed.result.suite;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.StubTest;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.retry.ResultsPlayer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unit tests for {@link FormattedGeneratorReporter}. */
@RunWith(JUnit4.class)
public class FormattedGeneratorReporterTest {

    private FormattedGeneratorReporter mReporter;
    private IInvocationContext mContext;
    private IBuildInfo mBuildInfo;
    private IConfiguration mConfig;

    @Before
    public void setUp() {
        mContext = new InvocationContext();
        mBuildInfo = new BuildInfo();
        mContext.addDeviceBuildInfo("default", mBuildInfo);
        mConfig = new Configuration("stub", "stub");
    }

    /** Test the value in the result holder when no module or tests actually ran. */
    @Test
    public void testFinalizedResults_nothingRan() {
        mReporter =
                new FormattedGeneratorReporter() {
                    private boolean mCalledOnce = false;

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        validateSuiteHolder(
                                resultHolder, 0, 0, 0, 0, 500L, mContext, new HashMap<>());
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }

                    @Override
                    protected long getCurrentTime() {
                        if (mCalledOnce) {
                            return 500L;
                        }
                        mCalledOnce = true;
                        return 0L;
                    }
                };
        mReporter.invocationStarted(mContext);
        mReporter.invocationEnded(500L);
    }

    /**
     * Test the values in the result holder when no module abi was reported, but we got some tests
     * results.
     */
    @Test
    public void testFinalizeResults_noAbi() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        validateSuiteHolder(
                                resultHolder, 1, 1, 1, 0, 0L, mContext, new HashMap<>());
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }

                    @Override
                    protected long getCurrentTime() {
                        return 0L;
                    }
                };
        mReporter.invocationStarted(mContext);
        mReporter.testRunStarted("run1", 1);
        mReporter.testStarted(new TestDescription("class", "method"));
        mReporter.testEnded(new TestDescription("class", "method"), new HashMap<String, Metric>());
        mReporter.testRunEnded(450L, new HashMap<String, Metric>());
        mReporter.invocationEnded(500L);
    }

    /** Test the values inside the suite holder when some tests and module were reported. */
    @Test
    public void testFinalizeResults() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        Map<String, IAbi> expectedModuleAbi = new LinkedHashMap<>();
                        expectedModuleAbi.put("module1", new Abi("abi1", "64"));
                        validateSuiteHolder(
                                resultHolder, 1, 1, 1, 0, 0L, mContext, expectedModuleAbi);
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }

                    @Override
                    protected long getCurrentTime() {
                        return 0L;
                    }
                };
        mReporter.invocationStarted(mContext);
        IInvocationContext moduleContext = new InvocationContext();
        moduleContext.addInvocationAttribute(ModuleDefinition.MODULE_ID, "module1");
        moduleContext.addInvocationAttribute(ModuleDefinition.MODULE_ABI, "abi1");
        mReporter.testModuleStarted(moduleContext);
        mReporter.testRunStarted("run1", 1);
        mReporter.testStarted(new TestDescription("class", "method"));
        mReporter.testEnded(new TestDescription("class", "method"), new HashMap<String, Metric>());
        mReporter.testRunEnded(450L, new HashMap<String, Metric>());
        mReporter.testModuleEnded();
        mReporter.invocationEnded(500L);
    }

    /**
     * Test the value in the result holder when no module or tests actually ran and the invocation
     * failed.
     */
    @Test
    public void testFinalizedResults_skipped() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        throw new RuntimeException("finalizeResults should not have been called.");
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }
                };
        mConfig.setTest(new ResultsPlayer(false));
        mReporter.setConfiguration(mConfig);
        mReporter.invocationStarted(mContext);
        DeviceDescriptor descriptor = null;
        mReporter.invocationFailed(new TargetSetupError("Invocation failed.", descriptor));
        mReporter.invocationEnded(500L);
    }

    /**
     * Test the value in the result holder when no module or tests actually ran and the invocation
     * failed.
     */
    @Test
    public void testFinalizedResults_skipped_noreplay() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        throw new RuntimeException("finalizeResults should not have been called.");
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }
                };
        mContext.getBuildInfos().get(0).addBuildAttribute(ResultsPlayer.REPLAY_DONE, "false");
        mConfig.setTest(new ResultsPlayer());
        mReporter.setConfiguration(mConfig);
        mReporter.invocationStarted(mContext);
        DeviceDescriptor descriptor = null;
        mReporter.invocationFailed(new TargetSetupError("Invocation failed.", descriptor));
        mReporter.invocationEnded(500L);
    }

    @Test
    public void testFinalizedResults_skippedByNPE() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        throw new RuntimeException("finalizeResults should not have been called.");
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }
                };
        mConfig.setTest(new ResultsPlayer(false));
        mReporter.setConfiguration(mConfig);
        mReporter.invocationStarted(mContext);
        mReporter.invocationFailed(new NullPointerException("Invocation failed."));
        mReporter.invocationEnded(500L);
    }

    @Test
    public void testFinalizedResults_notRetry() {
        mReporter =
                new FormattedGeneratorReporter() {

                    @Override
                    public void finalizeResults(
                            IFormatterGenerator generator, SuiteResultHolder resultHolder) {
                        validateSuiteHolder(
                                resultHolder, 1, 1, 1, 0, 0L, mContext, new HashMap<>());
                    }

                    @Override
                    public IFormatterGenerator createFormatter() {
                        return null;
                    }

                    @Override
                    protected long getCurrentTime() {
                        return 0L;
                    }
                };
        mConfig.setTest(new StubTest());
        mReporter.setConfiguration(mConfig);
        mReporter.invocationStarted(mContext);
        mReporter.testRunStarted("run1", 1);
        mReporter.testStarted(new TestDescription("class", "method"));
        mReporter.testEnded(new TestDescription("class", "method"), new HashMap<String, Metric>());
        mReporter.testRunEnded(450L, new HashMap<String, Metric>());
        mReporter.invocationFailed(new NullPointerException("Invocation failed."));
        mReporter.invocationEnded(500L);
    }

    /** Validate the information inside the suite holder. */
    private void validateSuiteHolder(
            SuiteResultHolder holder,
            int completeModules,
            int totalModules,
            long passedTests,
            long failedTests,
            long elapsedTime,
            IInvocationContext context,
            Map<String, IAbi> moduleAbis) {
        Assert.assertEquals(completeModules, holder.completeModules);
        Assert.assertEquals(totalModules, holder.totalModules);
        Assert.assertEquals(passedTests, holder.passedTests);
        Assert.assertEquals(failedTests, holder.failedTests);
        Assert.assertEquals(elapsedTime, holder.endTime - holder.startTime);
        Assert.assertEquals(context, holder.context);
        for (String keyModule : moduleAbis.keySet()) {
            Assert.assertEquals(moduleAbis.get(keyModule), holder.modulesAbi.get(keyModule));
        }
    }
}
