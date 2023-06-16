/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tradefed.targetprep;

import static com.android.tradefed.targetprep.RunOnSdkSandboxTargetPreparer.DISABLE_TEST_ACTIVITIES_CMD;
import static com.android.tradefed.targetprep.RunOnSdkSandboxTargetPreparer.ENABLE_TEST_ACTIVITIES_CMD;
import static com.android.tradefed.targetprep.RunOnSdkSandboxTargetPreparer.RUN_TESTS_ON_SDK_SANDBOX;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RunOnSdkSandboxTargetPreparerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private TestInformation mTestInfo;

    @Mock private ITestDevice mMockDevice;

    private RunOnSdkSandboxTargetPreparer mPreparer;

    @Before
    public void setUp() {
        mPreparer = new RunOnSdkSandboxTargetPreparer();

        InvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @Test
    public void instrumentRunOnSdkSandbox_setupSuccess() throws Exception {
        when(mMockDevice.executeShellV2Command(eq(ENABLE_TEST_ACTIVITIES_CMD)))
                .thenReturn(new CommandResult(CommandStatus.SUCCESS));

        mPreparer.setUp(mTestInfo);

        verify(mMockDevice).executeShellV2Command(ENABLE_TEST_ACTIVITIES_CMD);
        assertTrue(
                Boolean.TRUE
                        .toString()
                        .equals(mTestInfo.properties().get(RUN_TESTS_ON_SDK_SANDBOX)));
    }

    @Test
    public void instrumentRunOnSdkSandbox_setupFailure() throws Exception {
        when(mMockDevice.executeShellV2Command(eq(ENABLE_TEST_ACTIVITIES_CMD)))
                .thenReturn(new CommandResult(CommandStatus.FAILED));

        try {
            mPreparer.setUp(mTestInfo);
            fail("Should have thrown exception");
        } catch (TargetSetupError expected) {
            // Expected
        }

        verify(mMockDevice).executeShellV2Command(ENABLE_TEST_ACTIVITIES_CMD);
    }

    @Test
    public void tearDownTest() throws Exception {
        when(mMockDevice.executeShellV2Command(eq(DISABLE_TEST_ACTIVITIES_CMD)))
                .thenReturn(new CommandResult(CommandStatus.SUCCESS));

        mPreparer.tearDown(mTestInfo, null);

        verify(mMockDevice).executeShellV2Command(DISABLE_TEST_ACTIVITIES_CMD);
    }
}
