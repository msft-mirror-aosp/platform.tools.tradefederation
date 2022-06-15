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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.nio.file.Files;

/** Unit tests for {@link FeatureFlagTargetPreparer}. */
@RunWith(JUnit4.class)
public class FeatureFlagTargetPreparerTest {

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public final TemporaryFolder mTmpDir = new TemporaryFolder();

    @Mock private TestInformation mTestInfo;
    @Mock private ITestDevice mDevice;

    private FeatureFlagTargetPreparer mPreparer;
    private CommandResult mCommandResult;
    private File mFlagFile;

    @Before
    public void setUp() throws Exception {
        mPreparer = new FeatureFlagTargetPreparer();
        when(mTestInfo.getDevice()).thenReturn(mDevice);
        // Default to successful command execution.
        mCommandResult = new CommandResult(CommandStatus.SUCCESS);
        when(mDevice.executeShellV2Command(anyString())).thenReturn(mCommandResult);
        // Default to returning a tmp flag file.
        mFlagFile = mTmpDir.newFile();
        new OptionSetter(mPreparer).setOptionValue("flag-file", mFlagFile.getAbsolutePath());
    }

    @Test
    public void testSetUp() throws Exception {
        mCommandResult.setStdout("namespace/f1=v1\n");
        Files.writeString(mFlagFile.toPath(), "namespace/f1=v2\nnamespace/f2=v3\n");

        // Updates to parsed flags (modify f1 and add f2) during setUp and reboots.
        mPreparer.setUp(mTestInfo);
        verify(mDevice).executeShellV2Command(eq("device_config list"));
        verify(mDevice).executeShellV2Command(eq("device_config put 'namespace' 'f1' 'v2'"));
        verify(mDevice).executeShellV2Command(eq("device_config put 'namespace' 'f2' 'v3'"));
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);

        // Reverts to previous flags (revert f1 and delete f2) during tearDown and reboots.
        clearInvocations(mDevice);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice).executeShellV2Command(eq("device_config put 'namespace' 'f1' 'v1'"));
        verify(mDevice).executeShellV2Command(eq("device_config delete 'namespace' 'f2'"));
        verify(mDevice).reboot();
        verifyNoMoreInteractions(mDevice);
    }

    @Test(expected = TargetSetupError.class)
    public void testSetUp_fileNotFound() throws Exception {
        mFlagFile.delete();
        // Throws if the flag file is not found.
        mPreparer.setUp(mTestInfo);
    }

    @Test(expected = TargetSetupError.class)
    public void testSetUp_commandError() throws Exception {
        mCommandResult.setStatus(CommandStatus.FAILED);
        // Throws a TargetSetupError if any command fails.
        mPreparer.setUp(mTestInfo);
    }

    @Test
    public void testSetUp_ignoreInvalid() throws Exception {
        mCommandResult.setStdout("");
        Files.writeString(mFlagFile.toPath(), "invalid=data\n");

        // Invalid flag data is ignored, and reboot skipped (nothing to update/revert).
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice, never()).executeShellV2Command(startsWith("device_config put"));
        verify(mDevice, never()).executeShellV2Command(eq("device_config delete"));
        verify(mDevice, never()).reboot();
    }

    @Test
    public void testSetUp_ignoreUnchanged() throws Exception {
        mCommandResult.setStdout("namespace/flag=value\n");
        Files.writeString(mFlagFile.toPath(), "namespace/flag=value\n");

        // Unchanged flags are not updated/reverted, and reboot skipped (nothing to update/revert).
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, null);
        verify(mDevice, never()).executeShellV2Command(startsWith("device_config put"));
        verify(mDevice, never()).executeShellV2Command(eq("device_config delete"));
        verify(mDevice, never()).reboot();
    }
}
