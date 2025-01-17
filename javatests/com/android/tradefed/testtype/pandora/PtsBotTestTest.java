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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Assert;
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
    private File mConfigFile;

    @Before
    public void setup() throws Exception {
        mSpyTest = Mockito.spy(new PtsBotTest());
        mMockDevice = Mockito.mock(ITestDevice.class);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mPandoraTestDir = FileUtil.createTempDir("pandora_tests");
        mConfigFile = FileUtil.createTempFile("pts_bot_tests_config", ".json", mPandoraTestDir);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mPandoraTestDir);
    }

    @Test
    public void testParse_configFlagsFile() throws Exception {
        String flag = "baguette_flag";
        String jsonString =
                String.format(
                        "{\"flags\":[{\"flags\":[\"%s\"],\"tests\":[\"test1\",\"test2\"]}]}", flag);
        FileUtil.writeToFile(jsonString, mConfigFile);

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFile);
        PtsBotTest.TestFlagConfiguration config = mSpyTest.getTestFlagConfiguration();

        assertThat(config.flags).isNotEmpty();
        assertThat(config.flags.get(0).flags).containsExactly(flag);
        assertThat(config.flags.get(0).tests).containsExactly("test1", "test2");

        mSpyTest.getTestFlagConfiguration().flags.clear();
    }

    @Test
    public void testParse_emptyConfigFlagsFile() throws Exception {
        String json_string = String.format("{\"flags\": []}");
        FileUtil.writeToFile(json_string, mConfigFile);

        mSpyTest.initFlagsConfig(mMockDevice, mConfigFile);

        assertThat(mSpyTest.getTestFlagConfiguration().flags).isEmpty();
    }

    @Test
    public void testParse_configSystemPropertiesFile() throws Exception {
        String jsonString =
                String.format(
                        "{\"system_properties\":[{\"system_properties\":"
                            + " {\"prop1\":\"true\",\"prop2\":\"false\",\"prop3\":null},\"tests\":[\"test1\",\"test2\"]}]}");

        FileUtil.writeToFile(jsonString, mConfigFile);

        mSpyTest.initSystemPropertiesConfig(mConfigFile);
        PtsBotTest.TestSyspropConfiguration config = mSpyTest.getSyspropConfiguration();

        assertThat(config.system_properties).isNotEmpty();
        Assert.assertEquals(config.system_properties.get(0).system_properties.get("prop1"), "true");
        Assert.assertEquals(
                config.system_properties.get(0).system_properties.get("prop2"), "false");
        Assert.assertEquals(config.system_properties.get(0).system_properties.get("prop3"), null);
        assertThat(config.system_properties.get(0).tests).containsExactly("test1", "test2");

        mSpyTest.getSyspropConfiguration().system_properties.clear();
    }

    @Test
    public void testParse_emptyConfigSystemPropertiesFile() throws Exception {
        String json_string = String.format("{\"system_properties\": []}");
        FileUtil.writeToFile(json_string, mConfigFile);

        mSpyTest.initSystemPropertiesConfig(mConfigFile);

        assertThat(mSpyTest.getSyspropConfiguration().system_properties).isEmpty();
    }
}
