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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.FileUtil;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

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
        mSpyTest.getConfigFlags().clear();
        mSpyTest.getFlagsDefaultValues().clear();
    }

    @Test
    public void testParse_configFlagsFile() throws Exception {
        Mockito.doReturn(true).when(mSpyTest).getBluetoothFlag(any(), anyString());
        String flag = "baguette_flag";
        String json_string =
                String.format("{ \"flags\": { \"%s\": [\"A2DP/SNK/AS/BV-01-I\"] } }", flag);
        FileUtil.writeToFile(json_string, mConfigFlagsFile);
        HashMap<String, ArrayList<String>> flagsConfig = new HashMap();
        flagsConfig.put(flag, new ArrayList(Arrays.asList("A2DP/SNK/AS/BV-01-I")));

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFlagsFile);

        Truth.assertThat(mSpyTest.getConfigFlags()).isEqualTo(flagsConfig);
        assertTrue(mSpyTest.getFlagsDefaultValues().get(flag));
    }

    @Test
    public void testParse_emptyConfigFlagsFile() throws Exception {
        String json_string = String.format("{}");
        FileUtil.writeToFile(json_string, mConfigFlagsFile);

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFlagsFile);

        assertTrue(mSpyTest.getConfigFlags().isEmpty());
        assertTrue(mSpyTest.getFlagsDefaultValues().isEmpty());
    }
}
