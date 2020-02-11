/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link SuiteModuleLoader}. */
@RunWith(JUnit4.class)
public class SuiteModuleLoaderTest {

    private static final String TEST_CONFIG =
            "<configuration description=\"Runs a stub tests part of some suite\">\n"
                    + "    <target_preparer class=\"com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$PreparerInject\" />\n"
                    + "    <test class=\"com.android.tradefed.testtype.suite.SuiteModuleLoaderTest"
                    + "$TestInject\" />\n"
                    + "</configuration>";

    private static final String TEST_INSTANT_CONFIG =
            "<configuration description=\"Runs a stub tests part of some suite\">\n"
                    + "    <option name=\"config-descriptor:metadata\" key=\"parameter\" value=\"instant_app\" />"
                    + "    <test class=\"com.android.tradefed.testtype.suite.TestSuiteStub\" />\n"
                    + "</configuration>";

    private SuiteModuleLoader mRepo;
    private File mTestsDir;
    private Set<IAbi> mAbis;

    @Before
    public void setUp() throws Exception {
        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new ArrayList<>(),
                        new ArrayList<>());
        mTestsDir = FileUtil.createTempDir("suite-module-loader-tests");
        mAbis = new HashSet<>();
        mAbis.add(new Abi("armeabi-v7a", "32"));
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mTestsDir);
    }

    private void createModuleConfig(String moduleName) throws IOException {
        File module = new File(mTestsDir, moduleName + SuiteModuleLoader.CONFIG_EXT);
        FileUtil.writeToFile(TEST_CONFIG, module);
    }

    private void createInstantModuleConfig(String moduleName) throws IOException {
        File module = new File(mTestsDir, moduleName + SuiteModuleLoader.CONFIG_EXT);
        FileUtil.writeToFile(TEST_INSTANT_CONFIG, module);
    }

    @OptionClass(alias = "preparer-inject")
    public static class PreparerInject extends BaseTargetPreparer {
        @Option(name = "preparer-string")
        public String preparer = null;
    }

    @OptionClass(alias = "test-inject")
    public static class TestInject implements IRemoteTest {
        @Option(name = "simple-string")
        public String test = null;

        @Option(name = "empty-string")
        public String testEmpty = null;

        @Option(name = "alias-option")
        public String testAlias = null;

        @Option(name = "list-string")
        public List<String> testList = new ArrayList<>();

        @Option(name = "map-string")
        public Map<String, String> testMap = new HashMap<>();

        @Override
        public void run(TestInformation testInfo, ITestInvocationListener listener)
                throws DeviceNotAvailableException {}
    }

    /** Test an end-to-end injection of --module-arg. */
    @Test
    public void testInjectConfigOptions_moduleArgs() throws Exception {
        List<String> moduleArgs = new ArrayList<>();
        moduleArgs.add("module1:simple-string:value1");
        moduleArgs.add("module1:empty-string:"); // value is the empty string

        moduleArgs.add("module1:list-string:value2");
        moduleArgs.add("module1:list-string:value3");
        moduleArgs.add("module1:list-string:set-option:moreoption");
        moduleArgs.add("module1:list-string:"); // value is the empty string
        moduleArgs.add("module1:map-string:set-option:=moreoption");
        moduleArgs.add("module1:map-string:empty-option:="); // value is the empty string

        createModuleConfig("module1");

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new ArrayList<>(),
                        moduleArgs);
        List<String> patterns = new ArrayList<>();
        patterns.add(".*.config");
        patterns.add(".*.xml");
        LinkedHashMap<String, IConfiguration> res =
                mRepo.loadConfigsFromDirectory(
                        Arrays.asList(mTestsDir), mAbis, null, null, patterns);
        assertNotNull(res.get("armeabi-v7a module1"));
        IConfiguration config = res.get("armeabi-v7a module1");

        TestInject checker = (TestInject) config.getTests().get(0);
        assertEquals("value1", checker.test);
        assertEquals("", checker.testEmpty);
        // Check list
        assertTrue(checker.testList.size() == 4);
        assertTrue(checker.testList.contains("value2"));
        assertTrue(checker.testList.contains("value3"));
        assertTrue(checker.testList.contains("set-option:moreoption"));
        assertTrue(checker.testList.contains(""));
        // Chech map
        assertTrue(checker.testMap.size() == 2);
        assertEquals("moreoption", checker.testMap.get("set-option"));
        assertEquals("", checker.testMap.get("empty-option"));
    }

    /** Test an end-to-end injection of --test-arg. */
    @Test
    public void testInjectConfigOptions_testArgs() throws Exception {
        List<String> testArgs = new ArrayList<>();
        // Value for ITargetPreparer
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$PreparerInject:"
                        + "preparer-string:preparer");
        // Values for IRemoteTest
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "simple-string:value1");
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "empty-string:"); // value is the empty string
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "list-string:value2");
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "list-string:value3");
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "list-string:set-option:moreoption");
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "list-string:"); // value is the empty string
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "map-string:set-option:=moreoption");
        testArgs.add(
                "com.android.tradefed.testtype.suite.SuiteModuleLoaderTest$TestInject:"
                        + "map-string:empty-option:="); // value is the empty string

        createModuleConfig("module1");

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        testArgs,
                        new ArrayList<>());
        List<String> patterns = new ArrayList<>();
        patterns.add(".*.config");
        patterns.add(".*.xml");
        LinkedHashMap<String, IConfiguration> res =
                mRepo.loadConfigsFromDirectory(
                        Arrays.asList(mTestsDir), mAbis, null, null, patterns);
        assertNotNull(res.get("armeabi-v7a module1"));
        IConfiguration config = res.get("armeabi-v7a module1");

        PreparerInject preparer = (PreparerInject) config.getTargetPreparers().get(0);
        assertEquals("preparer", preparer.preparer);

        TestInject checker = (TestInject) config.getTests().get(0);
        assertEquals("value1", checker.test);
        assertEquals("", checker.testEmpty);
        // Check list
        assertTrue(checker.testList.size() == 4);
        assertTrue(checker.testList.contains("value2"));
        assertTrue(checker.testList.contains("value3"));
        assertTrue(checker.testList.contains("set-option:moreoption"));
        assertTrue(checker.testList.contains(""));
        // Chech map
        assertTrue(checker.testMap.size() == 2);
        assertEquals("moreoption", checker.testMap.get("set-option"));
        assertEquals("", checker.testMap.get("empty-option"));
    }

    @Test
    public void testInjectConfigOptions_moduleArgs_alias() throws Exception {
        List<String> moduleArgs = new ArrayList<>();
        moduleArgs.add("module1:{test-inject}alias-option:value1");

        createModuleConfig("module1");

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new ArrayList<>(),
                        moduleArgs);
        List<String> patterns = new ArrayList<>();
        patterns.add(".*.config");
        patterns.add(".*.xml");
        LinkedHashMap<String, IConfiguration> res =
                mRepo.loadConfigsFromDirectory(
                        Arrays.asList(mTestsDir), mAbis, null, null, patterns);
        assertNotNull(res.get("armeabi-v7a module1"));
        IConfiguration config = res.get("armeabi-v7a module1");

        TestInject checker = (TestInject) config.getTests().get(0);
        assertEquals("value1", checker.testAlias);
    }

    /**
     * Test that if the base module is excluded in full, the filters of parameterized modules are
     * still populated with the proper filters.
     */
    @Test
    public void testFilterParameterized() throws Exception {
        Map<String, List<SuiteTestFilter>> excludeFilters = new LinkedHashMap<>();
        createInstantModuleConfig("basemodule");
        SuiteTestFilter fullFilter = SuiteTestFilter.createFrom("armeabi-v7a basemodule");
        excludeFilters.put("armeabi-v7a basemodule", Arrays.asList(fullFilter));

        SuiteTestFilter instantMethodFilter =
                SuiteTestFilter.createFrom(
                        "armeabi-v7a basemodule[instant] NativeDnsAsyncTest#Async_Cancel");
        excludeFilters.put("armeabi-v7a basemodule[instant]", Arrays.asList(instantMethodFilter));

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        excludeFilters,
                        new ArrayList<>(),
                        new ArrayList<>());
        mRepo.setParameterizedModules(true);

        List<String> patterns = new ArrayList<>();
        patterns.add(".*.config");
        patterns.add(".*.xml");
        LinkedHashMap<String, IConfiguration> res =
                mRepo.loadConfigsFromDirectory(
                        Arrays.asList(mTestsDir), mAbis, null, null, patterns);
        assertEquals(1, res.size());
        // Full module was excluded completely
        IConfiguration instantModule = res.get("armeabi-v7a basemodule[instant]");
        assertNotNull(instantModule);
        TestSuiteStub stubTest = (TestSuiteStub) instantModule.getTests().get(0);
        assertEquals(1, stubTest.getExcludeFilters().size());
        assertEquals(
                "NativeDnsAsyncTest#Async_Cancel", stubTest.getExcludeFilters().iterator().next());
    }

    /**
     * Test that the configuration can be found if specifying specific path.
     */
    @Test
    public void testLoadConfigsFromSpecifiedPaths_OneModule() throws Exception {
        createModuleConfig("module1");
        File module1 = new File(mTestsDir, "module1" + SuiteModuleLoader.CONFIG_EXT);

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new ArrayList<>(),
                        new ArrayList<>());

        LinkedHashMap<String, IConfiguration> res =
            mRepo.loadConfigsFromSpecifiedPaths(
                Arrays.asList(module1), mAbis, null);
        assertEquals(1, res.size());
        assertNotNull(res.get("armeabi-v7a module1"));
    }

    /**
     * Test that multiple configurations can be found if specifying specific paths.
     */
    @Test
    public void testLoadConfigsFromSpecifiedPaths_MultipleModules() throws Exception {
        createModuleConfig("module1");
        File module1 = new File(mTestsDir, "module1" + SuiteModuleLoader.CONFIG_EXT);
        createModuleConfig("module2");
        File module2 = new File(mTestsDir, "module2" + SuiteModuleLoader.CONFIG_EXT);

        mRepo =
                new SuiteModuleLoader(
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new LinkedHashMap<String, List<SuiteTestFilter>>(),
                        new ArrayList<>(),
                        new ArrayList<>());

        LinkedHashMap<String, IConfiguration> res =
            mRepo.loadConfigsFromSpecifiedPaths(
                Arrays.asList(module1, module2), mAbis, null);
        assertEquals(2, res.size());
        assertNotNull(res.get("armeabi-v7a module1"));
        assertNotNull(res.get("armeabi-v7a module2"));
    }

    /**
     * Test that configuration can be found correctly if specifying specific paths but someone is
     * excluded.
     */
    @Test
    public void testLoadConfigsFromSpecifiedPaths_WithExcludeFilter() throws Exception {
        createModuleConfig("module1");
        File module1 = new File(mTestsDir, "module1" + SuiteModuleLoader.CONFIG_EXT);
        createModuleConfig("module2");
        File module2 = new File(mTestsDir, "module2" + SuiteModuleLoader.CONFIG_EXT);

        Map<String, List<SuiteTestFilter>> excludeFilters = new LinkedHashMap<>();
        SuiteTestFilter filter =
            SuiteTestFilter.createFrom(
                "armeabi-v7a module2");
        excludeFilters.put("armeabi-v7a module2", Arrays.asList(filter));

        mRepo =
            new SuiteModuleLoader(
                new LinkedHashMap<String, List<SuiteTestFilter>>(),
                excludeFilters,
                new ArrayList<>(),
                new ArrayList<>());

        LinkedHashMap<String, IConfiguration> res =
            mRepo.loadConfigsFromSpecifiedPaths(
                Arrays.asList(module1, module2), mAbis, null);
        assertEquals(1, res.size());
        assertNotNull(res.get("armeabi-v7a module1"));
        assertNull(res.get("armeabi-v7a module2"));
    }
}
