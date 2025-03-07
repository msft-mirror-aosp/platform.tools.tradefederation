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

import static com.google.common.truth.Truth.assertThat;

import com.android.resultdb.proto.FailureReason;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.TestStatus;
import com.android.resultdb.proto.Variant;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.proto.TestRecordProto;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ResultDBReporterTest {

    private InvocationSimulator mSimulator;
    private ResultDBReporterTester mReporter;

    private class ResultDBReporterTester extends ResultDBReporter {

        public StubClient mRecorder = StubClient.create();

        public ResultDBReporterTester() {
            super();
        }

        @Override
        IRecorderClient createRecorderClient(String invocationId, String updateToken) {
            return mRecorder;
        }

        @Override
        long currentTimestamp() {
            return 1000000000L;
        }

        @Override
        String randomUUIDString() {
            return "result_id";
        }

        @Override
        String randomHexString() {
            return "1234abcd";
        }
    }

    private TestResult.Builder newTestResult(String method) {
        return TestResult.newBuilder()
                .setTestId(
                        String.format(
                                "ants://%s/%s/%s",
                                "example-module", "com.google.ExampleClass", method))
                .setResultId("1234abcd-00001")
                .setStartTime(Timestamps.fromSeconds(1536333825L))
                .setDuration(Durations.fromMillis(100))
                .setStatus(TestStatus.PASS)
                .setExpected(true)
                .setVariant(
                        Variant.newBuilder()
                                .putDef("name", "test tag")
                                .putDef("scheduler", "ATP")
                                .build());
    }

    @Before
    public void setUp() {
        mReporter = new ResultDBReporterTester();
        mSimulator = InvocationSimulator.create().withModule("example-module");
    }

    @Test
    public void noInvocationIdInInvocationContext_reporterDisabled() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults()).isEmpty();
    }

    @Test
    public void uploadFunctionalResults() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(newTestResult("testExampleMethod").build());
    }

    @Test
    public void uploadResultWithVariant() {
        BuildInfo info = new BuildInfo("1", "target_1");
        info.setBuildBranch("test-branch");
        info.setBuildFlavor("test-flavor");
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest("com.google.ExampleClass", "testExampleMethod")
                .withModuleAttribute("should-ignore", "ignore")
                .withModuleAttribute(ModuleDefinition.MODULE_ABI, "test-abi")
                .withModuleAttribute(
                        ModuleDefinition.MODULE_PARAMETERIZATION, "test-parameterization")
                .withBuildInfo(info)
                .simulateInvocation(mReporter);

        Variant variant =
                Variant.newBuilder()
                        .putDef("branch", "test-branch")
                        .putDef("build_provider", "androidbuild")
                        .putDef("module-abi", "test-abi")
                        .putDef("module-param", "test-parameterization")
                        .putDef("name", "test tag")
                        .putDef("scheduler", "ATP")
                        .putDef("target", "test-flavor")
                        .build();
        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(newTestResult("testExampleMethod").setVariant(variant).build());
    }

    @Test
    public void uploadVariantResultStatus() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodPassed",
                        InvocationSimulator.TestStatus.PASS)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        InvocationSimulator.TestStatus.FAIL)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodAssumption",
                        InvocationSimulator.TestStatus.ASSUMPTION_FAILURE)
                .withTest(
                        "com.google.ExampleClass",
                        "testMethodIgnored",
                        InvocationSimulator.TestStatus.IGNORED)
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodPassed")
                                .setResultId("1234abcd-00001")
                                .setStatus(TestStatus.PASS)
                                .setExpected(true)
                                .build(),
                        newTestResult("testMethodFailed")
                                .setResultId("1234abcd-00002")
                                .setStatus(TestStatus.FAIL)
                                .setStartTime(Timestamps.fromMillis(1536333825200L))
                                .setExpected(false)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setPrimaryErrorMessage("Fail Trace"))
                                .build(),
                        newTestResult("testMethodAssumption")
                                .setResultId("1234abcd-00003")
                                .setStatus(TestStatus.SKIP)
                                .setStartTime(Timestamps.fromMillis(1536333825400L))
                                .setExpected(true)
                                .build(),
                        newTestResult("testMethodIgnored")
                                .setResultId("1234abcd-00004")
                                .setStatus(TestStatus.SKIP)
                                .setStartTime(Timestamps.fromMillis(1536333825600L))
                                .setExpected(true)
                                .build());
    }

    @Test
    public void testWithFailureDescription() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        FailureDescription.create(
                                "Failure Message", TestRecordProto.FailureStatus.TEST_FAILURE))
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodFailed")
                                .setStatus(TestStatus.FAIL)
                                .setExpected(false)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setPrimaryErrorMessage("Failure Message"))
                                .build());
    }

    @Test
    public void testWithFailureDescription_crashFailureStatus() {
        mSimulator
                .withInvocationAttribute("resultdb_invocation_id", "invocation_001")
                .withInvocationAttribute("resultdb_invocation_update_token", "update_token")
                .withTestFailure(
                        "com.google.ExampleClass",
                        "testMethodFailed",
                        FailureDescription.create(
                                "Failure Message\n java.lang.RuntimeException: Failure Message",
                                TestRecordProto.FailureStatus.TIMED_OUT))
                .simulateInvocation(mReporter);

        assertThat(mReporter.mRecorder.getTestResults())
                .containsExactly(
                        newTestResult("testMethodFailed")
                                .setStatus(TestStatus.CRASH)
                                .setExpected(false)
                                .setFailureReason(
                                        FailureReason.newBuilder()
                                                .setPrimaryErrorMessage("Failure Message"))
                                .build());
    }
}
