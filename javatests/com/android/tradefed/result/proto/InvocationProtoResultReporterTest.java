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
package com.android.tradefed.result.proto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;

import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.proto.TestRecordProtoUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

/** Unit tests for {@link InvocationProtoResultReporter}. */
@RunWith(JUnit4.class)
public class InvocationProtoResultReporterTest {

    private InvocationProtoResultReporter mReporter;
    private File mOutput;
    @Mock ITestInvocationListener mMockListener;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        mOutput = FileUtil.createTempFile("proto-file-reporter-test", ".pb");
        mReporter = new InvocationProtoResultReporter();
        mReporter.setFileOutput(mOutput);
    }

    @After
    public void tearDown() {
        FileUtil.deleteFile(mOutput);
    }

    @Test
    public void testInvocationReporting() throws Exception {
        IInvocationContext context = new InvocationContext();
        TestDescription test1 = new TestDescription("class1", "test1");

        mReporter.invocationStarted(context);
        IInvocationContext module1Context = createModuleContext("module1");
        mReporter.testModuleStarted(module1Context);
        mReporter.testRunStarted("run1", 1);
        mReporter.testStarted(test1);
        mReporter.testEnded(test1, new HashMap<String, Metric>());
        mReporter.testRunEnded(200L, new HashMap<String, Metric>());
        module1Context.addInvocationAttribute(ITestSuite.MODULE_END_TIME, "endTime");
        mReporter.testModuleEnded();
        mReporter.invocationEnded(0L);

        ProtoResultParser parser = new ProtoResultParser(mMockListener, context, true);
        parser.processFinalizedProto(TestRecordProtoUtil.readFromFile(mOutput, false));

        verify(mMockListener).invocationStarted(Mockito.any());
        verify(mMockListener).testModuleStarted(Mockito.any());
        verify(mMockListener)
                .testRunStarted(
                        Mockito.eq("run1"), Mockito.eq(1), Mockito.eq(0), Mockito.anyLong());
        verify(mMockListener).testStarted(Mockito.eq(test1), Mockito.anyLong());
        verify(mMockListener)
                .testEnded(
                        Mockito.eq(test1),
                        Mockito.anyLong(),
                        Mockito.<HashMap<String, Metric>>any());
        verify(mMockListener).testRunEnded(200L, new HashMap<String, Metric>());
        verify(mMockListener).testModuleEnded();
        verify(mMockListener).invocationEnded(anyLong());
        assertFalse(mReporter.stopCaching());
    }

    @Test
    public void testInvocationReporting_failure() throws Exception {
        IInvocationContext context = new InvocationContext();

        mReporter.invocationStarted(context);
        mReporter.invocationFailed(new RuntimeException("failure"));
        mReporter.invocationEnded(0L);

        assertTrue(mReporter.stopCaching());
    }

    private IInvocationContext createModuleContext(String moduleId) {
        IInvocationContext context = new InvocationContext();
        context.addInvocationAttribute(ModuleDefinition.MODULE_ID, moduleId);
        context.setConfigurationDescriptor(new ConfigurationDescriptor());
        context.addInvocationAttribute(ITestSuite.MODULE_START_TIME, "startTime");
        return context;
    }
}
