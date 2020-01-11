/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Unit tests for {@link CreateUserPreparer}. */
@RunWith(JUnit4.class)
public class CreateUserPreparerTest {

    private CreateUserPreparer mPreparer;
    private ITestDevice mMockDevice;
    private TestInformation mTestInfo;

    @Before
    public void setUp() {
        mMockDevice = Mockito.mock(ITestDevice.class);
        mPreparer = new CreateUserPreparer();
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @Test
    public void testSetUp_tearDown() throws Exception {
        doReturn(10).when(mMockDevice).getCurrentUser();
        doReturn(5).when(mMockDevice).createUser(Mockito.any());
        doReturn(true).when(mMockDevice).switchUser(5);
        doReturn(true).when(mMockDevice).startUser(5, true);
        mPreparer.setUp(mTestInfo);

        doReturn(true).when(mMockDevice).removeUser(5);
        doReturn(true).when(mMockDevice).switchUser(10);
        mPreparer.tearDown(mTestInfo, null);
    }

    @Test
    public void testSetUp_tearDown_noCurrent() throws Exception {
        doReturn(NativeDevice.INVALID_USER_ID).when(mMockDevice).getCurrentUser();
        try {
            mPreparer.setUp(mTestInfo);
            fail("Should have thrown an exception.");
        } catch (TargetSetupError expected) {
            // Expected
        }

        mPreparer.tearDown(mTestInfo, null);
        verify(mMockDevice, never()).removeUser(Mockito.anyInt());
        verify(mMockDevice, never()).switchUser(Mockito.anyInt());
    }

    @Test
    public void testSetUp_failed() throws Exception {
        doThrow(new IllegalStateException("failed to create"))
                .when(mMockDevice)
                .createUser(Mockito.any());

        try {
            mPreparer.setUp(mTestInfo);
            fail("Should have thrown an exception.");
        } catch (TargetSetupError expected) {
            // Expected
        }
    }

    @Test
    public void testTearDown_only() throws Exception {
        mPreparer.tearDown(mTestInfo, null);

        verify(mMockDevice, never()).removeUser(Mockito.anyInt());
    }
}
