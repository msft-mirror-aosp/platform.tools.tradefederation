/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

/** Unit Tests for {@link RunCommandTargetPreparer} */
@RunWith(JUnit4.class)
public class RunCommandTargetPreparerTest {

    private static final int LONG_WAIT_TIME_MS = 200;
    private static final TestDeviceState ONLINE_STATE = TestDeviceState.ONLINE;

    private RunCommandTargetPreparer mPreparer = null;
    @Mock ITestDevice mMockDevice;
    @Mock IBuildInfo mMockBuildInfo;
    private TestInformation mTestInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mPreparer = new RunCommandTargetPreparer();

        InvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    /**
     * Test that {@link RunCommandTargetPreparer#setUp(TestInformation)} is properly going through
     * without exception when running a command.
     */
    @Test
    public void testSetUp() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("run-command", command);
        CommandResult res = new CommandResult();
        res.setStatus(CommandStatus.SUCCESS);
        res.setStdout("");
        when(mMockDevice.executeShellV2Command(Mockito.eq(command))).thenReturn(res);

        mPreparer.setUp(mTestInfo);

        assertThat(mPreparer.getCommands()).containsExactly(command);
    }

    /**
     * Test that {@link RunCommandTargetPreparer#setUp(TestInformation)} is properly going through
     * without exception when running a command with timeout.
     */
    @Test
    public void testSetUp_withTimeout() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("run-command", command);
        setter.setOptionValue("run-command-timeout", "100");
        CommandResult res = new CommandResult();
        res.setStatus(CommandStatus.SUCCESS);
        res.setStdout("");
        when(mMockDevice.executeShellV2Command(
                        Mockito.eq(command),
                        Mockito.eq(100L),
                        Mockito.eq(TimeUnit.MILLISECONDS),
                        Mockito.eq(0)))
                .thenReturn(res);

        mPreparer.setUp(mTestInfo);
    }

    /**
     * Test that {@link RunCommandTargetPreparer#setUp(TestInformation)} and {@link
     * RunCommandTargetPreparer#tearDown(TestInformation, Throwable)} are properly skipped when
     * disabled and no command is ran.
     */
    @Test
    public void testDisabled() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("run-command", command);
        setter.setOptionValue("disable", "true");

        assertTrue(mPreparer.isDisabled());
    }

    /**
     * Test that {@link RunCommandTargetPreparer#tearDown(TestInformation, Throwable)} is properly
     * going through without exception when running a command.
     */
    @Test
    public void testTearDown() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("teardown-command", command);
        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        when(mMockDevice.executeShellV2Command(Mockito.eq(command))).thenReturn(result);

        mPreparer.tearDown(mTestInfo, null);
    }

    /**
     * Test that {@link RunCommandTargetPreparer#tearDown(TestInformation, throwable)} is properly
     * going through without exception when running a command with timeout.
     */
    @Test
    public void testTeardown_withTimeout() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("teardown-command", command);
        setter.setOptionValue("teardown-command-timeout", "100");
        CommandResult res = new CommandResult();
        res.setStatus(CommandStatus.SUCCESS);
        res.setStdout("");
        when(mMockDevice.executeShellV2Command(
                        Mockito.eq(command),
                        Mockito.eq(100L),
                        Mockito.eq(TimeUnit.MILLISECONDS),
                        Mockito.eq(0)))
                .thenReturn(res);

        mPreparer.tearDown(mTestInfo, null);
    }

    /**
     * Test that {@link RunCommandTargetPreparer#setUp(TestInformation)} and {@link
     * RunCommandTargetPreparer#tearDown(TestInformation, Throwable)} is properly going through
     * without exception when running a background command.
     */
    @Test
    public void testBgCmd() throws Exception {
        final String command = "mkdir test";
        OptionSetter setter = new OptionSetter(mPreparer);
        setter.setOptionValue("run-bg-command", command);
        IDevice mMockIDevice = mock(IDevice.class);
        when(mMockIDevice.getSerialNumber()).thenReturn("SERIAL");
        IShellOutputReceiver mMockReceiver = mock(IShellOutputReceiver.class);
        mMockReceiver.addOutput((byte[]) Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getSerialNumber()).thenReturn("SERIAL");
        mMockIDevice.executeShellCommand(
                Mockito.eq(command),
                Mockito.same(mMockReceiver),
                Mockito.anyLong(),
                Mockito.eq(TimeUnit.MILLISECONDS));
        when(mMockDevice.getDeviceState()).thenReturn(ONLINE_STATE);

        mPreparer.setUp(mTestInfo);
        RunUtil.getDefault().sleep(LONG_WAIT_TIME_MS);
        mPreparer.tearDown(mTestInfo, null);
        verify(mMockDevice, atLeastOnce()).getIDevice();
        verify(mMockDevice, atLeastOnce()).getSerialNumber();
        verify(mMockDevice, atLeastOnce()).getDeviceState();
    }
}
