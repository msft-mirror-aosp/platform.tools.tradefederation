/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tradefed.targetprep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit Tests for {@link FastbootCommandPreparer}. */
@RunWith(JUnit4.class)
public final class FastbootCommandPreparerTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

    @Mock private TestInformation mMockTestInfo;
    @Mock private ITestDevice mMockDevice;
    @Mock private IInvocationContext mMockInvocationContext;
    @Mock private IBuildInfo mMockBuildInfo;

    private FastbootCommandPreparer mPreparer;
    private CommandResult fastbootResult;

    @Before
    public void setUp() throws Exception {
        mPreparer = new FastbootCommandPreparer();
        when(mMockTestInfo.getDevice()).thenReturn(mMockDevice);
        when(mMockTestInfo.getContext()).thenReturn(mMockInvocationContext);
        when(mMockInvocationContext.getAttribute(ModuleDefinition.MODULE_NAME)).thenReturn(null);
        when(mMockInvocationContext.getBuildInfo(eq(mMockDevice))).thenReturn(mMockBuildInfo);

        // Default to successful execution.
        fastbootResult = new CommandResult(CommandStatus.SUCCESS);
        fastbootResult.setExitCode(0);
        when(mMockDevice.executeFastbootCommand(any())).thenReturn(fastbootResult);
    }

    @Test
    public void testSetUp_extraFile() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue(
                "command", "command $EXTRA_FILE(test_file1) $EXTRA_FILE(test_file2)");

        BuildInfo stubBuild = new BuildInfo("stub", "stub");
        File testFile1 = tmpDir.newFile("test_file1");
        stubBuild.setFile("test_file1", testFile1, "0");
        when(mMockTestInfo.getBuildInfo()).thenReturn(stubBuild);

        fastbootResult = new CommandResult(CommandStatus.SUCCESS);
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        fastbootResult.setExitCode(0);
        when(mMockDevice.executeFastbootCommand(
                        eq("command"),
                        eq(testFile1.getAbsolutePath()),
                        eq("$EXTRA_FILE(test_file2)")))
                .thenReturn(fastbootResult);
        when(mMockDevice.getDeviceDescriptor()).thenReturn(null);

        mPreparer.setUp(mMockTestInfo);

        verify(mMockDevice, times(1)).rebootIntoBootloader();
        verify(mMockDevice, times(1))
                  .executeFastbootCommand(
                      eq("command"),
                      eq(testFile1.getAbsolutePath()),
                      eq("$EXTRA_FILE(test_file2)"));
        verify(mMockDevice, times(1)).reboot();
    }

    @Test
    public void testTearDown_extraFile() throws Exception {
        OptionSetter optionSetter = new OptionSetter(mPreparer);
        optionSetter.setOptionValue(
                "teardown-command", "command $EXTRA_FILE(test_file1) $EXTRA_FILE(test_file2)");

        BuildInfo stubBuild = new BuildInfo("stub", "stub");
        File testFile1 = tmpDir.newFile("test_file1");
        stubBuild.setFile("test_file1", testFile1, "0");
        when(mMockTestInfo.getBuildInfo()).thenReturn(stubBuild);

        fastbootResult = new CommandResult(CommandStatus.SUCCESS);
        fastbootResult.setStatus(CommandStatus.SUCCESS);
        fastbootResult.setExitCode(0);
        when(mMockDevice.executeFastbootCommand(
                        eq("command"),
                        eq(testFile1.getAbsolutePath()),
                        eq("$EXTRA_FILE(test_file2)")))
                .thenReturn(fastbootResult);
        when(mMockDevice.getDeviceDescriptor()).thenReturn(null);

        mPreparer.tearDown(mMockTestInfo, null);

        verify(mMockDevice, times(1)).rebootIntoBootloader();
        verify(mMockDevice, times(1))
                  .executeFastbootCommand(
                      eq("command"),
                      eq(testFile1.getAbsolutePath()),
                      eq("$EXTRA_FILE(test_file2)"));
        verify(mMockDevice, times(1)).reboot();
    }
}