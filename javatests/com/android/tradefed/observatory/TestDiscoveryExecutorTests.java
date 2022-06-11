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

package com.android.tradefed.observatory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class TestDiscoveryExecutorTests {

    private static final ConfigurationFactory CONFIG_FACTORY =
            Mockito.mock(ConfigurationFactory.class);
    private static final Configuration CONFIG = Mockito.mock(Configuration.class);
    private TestDiscoveryExecutor mTestDiscoveryExecutor;

    @Before
    public void setUp() throws Exception {
        mTestDiscoveryExecutor =
                new TestDiscoveryExecutor() {
                    @Override
                    IConfigurationFactory getConfigurationFactory() {
                        return CONFIG_FACTORY;
                    }
                };
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return CONFIG;
                            }
                        })
                .when(CONFIG_FACTORY)
                .createConfigurationFromArgs(Mockito.any());
    }

    /** Test the executor to discover test modules from multiple tests. */
    @Test
    public void testDiscoverTestModules() throws Exception {
        // Mock to return some include filters
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                BaseTestSuite test1 = new BaseTestSuite();
                                Set<String> includeFilters1 = new HashSet<>();
                                includeFilters1.add("TestModule1 class#function1");
                                includeFilters1.add("TestModule2");
                                includeFilters1.add("x86_64 TestModule3 class#function3");
                                test1.setIncludeFilter(includeFilters1);

                                BaseTestSuite test2 = new BaseTestSuite();
                                Set<String> includeFilters2 = new HashSet<>();
                                includeFilters2.add("TestModule1 class#function6");
                                includeFilters2.add("x86 TestModule4");
                                includeFilters2.add("TestModule5 class#function2");
                                includeFilters2.add("TestModule6");
                                test2.setIncludeFilter(includeFilters2);

                                List<IRemoteTest> testList = new ArrayList<>();
                                testList.add(test1);
                                testList.add(test2);
                                return testList;
                            }
                        })
                .when(CONFIG)
                .getTests();
        // We don't test with real command line input here. Because for a real command line input,
        // the test module names will be different with respect to those test config resource files
        // can be changed in different builds.
        try {
            String output = mTestDiscoveryExecutor.discoverDependencies(new String[0]);
            String expected =
                    "{\"TestDependencies\":[\"TestModule1\",\"TestModule2\",\"TestModule3\","
                            + "\"TestModule4\",\"TestModule5\",\"TestModule6\"]}";
            assertEquals(expected, output);
        } catch (Exception e) {
            fail(String.format("Should not throw exception %s", e.getMessage()));
        }
    }

    /** Test the executor to handle where there is no tests from the config. */
    @Test
    public void testDiscoverNoTestModules() throws Exception {
        // Mock to return no include filters
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return new ArrayList<>();
                            }
                        })
                .when(CONFIG)
                .getTests();
        // We don't test with real command line input here. Because for a real command line input,
        // the test module names will be different with respect to those test config resource files
        // can be changed in different builds.
        try {
            mTestDiscoveryExecutor.discoverDependencies(new String[0]);
            fail("Should throw an IllegalStateException");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalStateException);
        }
    }
}
