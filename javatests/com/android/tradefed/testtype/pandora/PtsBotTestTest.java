/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tradefed.testtype.pandora;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;

/** Unit tests for {@link PtsBotTest}. */
@RunWith(JUnit4.class)
public class PtsBotTestTest {

    private PtsBotTest mSpyTest;
    private ITestDevice mMockDevice;
    private TestInformation mTestInfo;
    private File mPandoraTestDir;
    private File mConfigFlagsFile;

    @Before
    public void setup() throws Exception {
        mSpyTest = Mockito.spy(new PtsBotTest());
        mMockDevice = Mockito.mock(ITestDevice.class);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mPandoraTestDir = FileUtil.createTempDir("pandora_tests");
        mConfigFlagsFile =
                FileUtil.createTempFile("pts_bot_tests_config", ".json", mPandoraTestDir);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mPandoraTestDir);
    }

    @Test
    public void testParse_configFlagsFile() throws Exception {
        Mockito.doReturn(true).when(mSpyTest).getBluetoothFlag(any(), anyString());
        String flag = "baguette_flag";
        String jsonString =
                String.format(
                        "{\"flags\":[{\"flags\":[\"%s\"],\"tests\":[\"test1\",\"test2\"]}]}", flag);
        FileUtil.writeToFile(jsonString, mConfigFlagsFile);

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFlagsFile);
        PtsBotTest.TestFlagConfiguration config = mSpyTest.getTestFlagConfiguration();

        assertThat(config.flags).isNotEmpty();
        assertThat(config.flags.get(0).flags).containsExactly(flag);
        assertThat(config.flags.get(0).tests).containsExactly("test1", "test2");
        assertThat(mSpyTest.getFlagsDefaultValues().get(flag)).isTrue();

        mSpyTest.getTestFlagConfiguration().flags.clear();
        mSpyTest.getFlagsDefaultValues().clear();
    }

    @Test
    public void testParse_emptyConfigFlagsFile() throws Exception {
        String json_string = String.format("{\"flags\": []}");
        FileUtil.writeToFile(json_string, mConfigFlagsFile);

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFlagsFile);

        assertThat(mSpyTest.getTestFlagConfiguration().flags).isEmpty();
        assertThat(mSpyTest.getFlagsDefaultValues()).isEmpty();

        mSpyTest.getFlagsDefaultValues().clear();
    }
}
