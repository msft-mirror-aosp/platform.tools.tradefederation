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

package com.android.tradefed.observatory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.testtype.IRemoteTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link TestMappingDiscoveryAgent}. */
@RunWith(JUnit4.class)
public class TestMappingDiscoveryAgentTest {
    private IConfiguration mConfiguration;
    private TestDiscoveryInvoker mTestDiscoveryInvoker;
    private TestDiscoveryUtil mTestDiscoveryUtil;

    private List<IRemoteTest> mTestList = new ArrayList<>();

    private FakeDiscoverableTestClassesTest mFakeValidationTest =
            new FakeDiscoverableTestClassesTest(
                    TestMappingDiscoveryAgent.getValidationTestClassNames());

    private FakeDiscoverableTestClassesTest mFakeTestMappingValidationTest =
            new FakeDiscoverableTestClassesTest(
                    TestMappingDiscoveryAgent.getTestMappingValidationTestClassNames());

    private FakeDiscoverableTestClassesTest mFakeOtherDiscoverableTestClassesTest =
            new FakeDiscoverableTestClassesTest(
                    new HashSet<String>(Arrays.asList("some.test.class")));

    private FakeRemoteTest mFakeNonValidationTest = new FakeRemoteTest();

    private String[] mArgs = {};

    private static Map<String, List<String>> mFakeTestDiscoveryResult = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        mConfiguration = Mockito.mock(IConfiguration.class);
        mTestDiscoveryInvoker = Mockito.mock(TestDiscoveryInvoker.class);
        mTestDiscoveryUtil = Mockito.mock(TestDiscoveryUtil.class);
        mFakeTestDiscoveryResult.put(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, new ArrayList<>());
        mFakeTestDiscoveryResult.put(
                TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, new ArrayList<>());
        when(mTestDiscoveryUtil.getConfiguration(Mockito.any())).thenReturn(mConfiguration);
        when(mConfiguration.getTests()).thenReturn(mTestList);
        when(mTestDiscoveryInvoker.discoverTestMappingDependencies())
                .thenReturn(mFakeTestDiscoveryResult);
    }

    /** Test the case of test config that has a device&general validation test entry. */
    @Test
    public void testValidationTestDiscovery() throws Exception {
        mTestList.add(mFakeValidationTest);
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should discover validation test.
        assertTrue(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should not go to discover test mapping test modules logic.
        Mockito.verify(mTestDiscoveryInvoker, never()).discoverTestMappingDependencies();
    }

    /** Test the case of test config that has a test mapping validation test entry. */
    @Test
    public void testTestMappingValidationTestDiscovery() throws Exception {
        mTestList.add(mFakeTestMappingValidationTest);
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should discover test mapping validation test.
        assertTrue(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should not go to discover test mapping test modules logic.
        Mockito.verify(mTestDiscoveryInvoker, never()).discoverTestMappingDependencies();
    }

    /** Test the case of test config that normal test entries that has discoverable test classes. */
    @Test
    public void testOtherDiscoverableTestClassTestDiscovery() throws Exception {
        mTestList.add(mFakeOtherDiscoverableTestClassesTest);
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should not discover as validation test.
        assertFalse(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should go to test mapping test discovery logic.
        Mockito.verify(mTestDiscoveryInvoker, atLeastOnce()).discoverTestMappingDependencies();
    }

    /** Test the case of test config that has single non-validation test entries. */
    @Test
    public void testNonValidationTestDiscovery_SingleTest() throws Exception {
        mTestList.add(mFakeNonValidationTest);
        when(mTestDiscoveryInvoker.discoverTestDependencies()).thenReturn(new HashMap<>());
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should not discover as validation test.
        assertFalse(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should go to test mapping test discovery logic.
        Mockito.verify(mTestDiscoveryInvoker, atLeastOnce()).discoverTestMappingDependencies();
    }

    /** Test the case of test config that has multiple non-validation test entries. */
    @Test
    public void testNonValidationTestDiscovery_MultipleTests() throws Exception {
        mTestList.add(mFakeNonValidationTest);
        mTestList.add(mFakeNonValidationTest);
        when(mTestDiscoveryInvoker.discoverTestDependencies()).thenReturn(new HashMap<>());
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should not discover as validation test.
        assertFalse(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should go to test mapping test discovery logic.
        Mockito.verify(mTestDiscoveryInvoker, atLeastOnce()).discoverTestMappingDependencies();
    }

    /** Test the case of test config that has multiple test entries of discoverable test class. */
    @Test
    public void testMultipleValidationTestDiscovery() throws Exception {
        mTestList.add(mFakeValidationTest);
        mTestList.add(mFakeOtherDiscoverableTestClassesTest);
        TestMappingDiscoveryAgent testMappingDiscoveryAgent =
                new TestMappingDiscoveryAgent(mTestDiscoveryInvoker, mTestDiscoveryUtil);
        testMappingDiscoveryAgent.discoverTestMapping(mArgs);
        // Should not discover as validation test when there's multiple tests in the config.
        assertFalse(testMappingDiscoveryAgent.isValidationTestDiscovered());
        // Should go to test mapping test discovery logic.
        Mockito.verify(mTestDiscoveryInvoker, atLeastOnce()).discoverTestMappingDependencies();
    }

    static class FakeDiscoverableTestClassesTest implements IRemoteTest, IDiscoverTestClasses {
        Set<String> mClassNames;

        public FakeDiscoverableTestClassesTest(Set<String> classNames) {
            mClassNames = classNames;
        }

        @Override
        public Set<String> getClassNames() {
            return mClassNames;
        }
    }

    static class FakeRemoteTest implements IRemoteTest {}
}
