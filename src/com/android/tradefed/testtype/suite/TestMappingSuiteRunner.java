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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil2;
import com.android.tradefed.util.testmapping.TestInfo;
import com.android.tradefed.util.testmapping.TestMapping;
import com.android.tradefed.util.testmapping.TestOption;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of {@link BaseTestSuite} to run tests specified by option include-filter, or
 * TEST_MAPPING files from build, as a suite.
 */
public class TestMappingSuiteRunner extends BaseTestSuite {

    @Option(
        name = "test-mapping-test-group",
        description =
                "Group of tests to run, e.g., presubmit, postsubmit. The suite runner "
                        + "shall load the tests defined in all TEST_MAPPING files in the source "
                        + "code, through build artifact test_mappings.zip."
    )
    private String mTestGroup = null;

    @Option(
        name = "test-mapping-keyword",
        description =
                "Keyword to be matched to the `keywords` setting of a test configured in "
                        + "a TEST_MAPPING file. The test will only run if it has all the keywords "
                        + "specified in the option. If option test-mapping-test-group is not set, "
                        + "test-mapping-keyword option is ignored as the tests to run are not "
                        + "loaded directly from TEST_MAPPING files but is supplied via the "
                        + "--include-filter arg."
    )
    private Set<String> mKeywords = new HashSet<>();

    @Option(
        name = "force-test-mapping-module",
        description =
                "Run the specified tests only. The tests loaded from all TEST_MAPPING files in "
                        + "the source code will be filtered again to force run the specified tests."
    )
    private Set<String> mTestModulesForced = new HashSet<>();

    @Option(
        name = "test-mapping-path",
        description = "Run tests according to the test mapping path."
    )
    private List<String> mTestMappingPaths = new ArrayList<>();

    @Option(
        name = RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION,
        description = RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_DESCRIPTION
    )
    private Duration mRemoteTestTimeOut = null;

    @Option(
        name = "use-test-mapping-path",
        description = "Whether or not to run tests based on the given test mapping path."
    )
    private boolean mUseTestMappingPath = false;

    @Option(
            name = "ignore-test-mapping-imports",
            description = "Whether or not to ignore test mapping import paths.")
    private boolean mIgnoreTestMappingImports = true;

    @Option(
            name = "test-mapping-allowed-tests-list",
            description =
                    "A list of artifacts that contains allowed tests. Only tests in the lists "
                            + "will be run. If no list is specified, the tests will not be "
                            + "filtered by allowed tests.")
    private Set<String> mAllowedTestLists = new HashSet<>();

    @Option(
            name = "additional-test-mapping-zip",
            description =
                    "A list of additional test_mappings.zip that contains TEST_MAPPING files. The "
                            + "runner will collect tests based on them. If none is specified, "
                            + "only the tests on the triggering device build will be run.")
    private List<String> mAdditionalTestMappingZips = new ArrayList<>();

    @Option(
            name = "test-mapping-unmatched-file-pattern-paths",
            description =
                    "A list of modified paths that does not match with a certain file_pattern in "
                            + "the TEST_MAPPING file. This is used only for Work Node, and handled "
                            + "by provider service. If none is specified, all tests are needed "
                            + "to run for the given change.")
    private Set<String> mUnmatchedFilePatternPaths = new HashSet<>();

    @Option(
            name = "test-mapping-matched-pattern-paths",
            description =
                    "A list of modified paths that matches with a certain file_pattern in "
                            + "the TEST_MAPPING file. This is used only for Work Node, and handled "
                            + "by provider service.")
    private Set<String> mMatchedPatternPaths = new HashSet<>();

    @Option(
            name = "allow-empty-tests",
            description =
                    "Whether or not to raise an exception if no tests to be ran. This is to "
                            + "provide a feasibility for test mapping sampling.")
    private boolean mAllowEmptyTests = false;

    @Option(
            name = "force-full-run",
            description =
                    "Whether or not to run full tests. It is to provide a feasibility for tests on "
                            + "kernel branches. The option should only be used for kernel tests.")
    private boolean mForceFullRun = false;

    /** Flag to indicate whether the test mapping suite runner is in test discovery mode. */
    private Boolean mIsTestDiscovery = false;

    /** Special definition in the test mapping structure. */
    private static final String TEST_MAPPING_INCLUDE_FILTER = "include-filter";

    private static final String TEST_MAPPING_EXCLUDE_FILTER = "exclude-filter";

    private IBuildInfo mBuildInfo;

    public TestMappingSuiteRunner() {
        setSkipjarLoading(true);
    }

    /** Set the test discovery mode flag. */
    public void setTestDiscovery(Boolean testDiscovery) {
        mIsTestDiscovery = testDiscovery;
    }

    /**
     * Load the tests configuration that will be run. Each tests is defined by a {@link
     * IConfiguration} and a unique name under which it will report results. There are 2 ways to
     * load tests for {@link TestMappingSuiteRunner}:
     *
     * <p>1. --test-mapping-test-group, which specifies the group of tests in TEST_MAPPING files.
     * The runner will parse all TEST_MAPPING files in the source code through build artifact
     * test_mappings.zip, and load tests grouped under the given test group.
     *
     * <p>2. --include-filter, which specifies the name of the test to run. The use case is for
     * presubmit check to only run a list of tests related to the Cls to be verifies. The list of
     * tests are compiled from the related TEST_MAPPING files in modified source code.
     *
     * @return a map of test name to the {@link IConfiguration} object of each test.
     */
    @Override
    public LinkedHashMap<String, IConfiguration> loadTests() {
        Set<String> includeFilter = getIncludeFilter();
        // Name of the tests
        Set<String> testNames = new HashSet<>();
        Set<TestInfo> testInfosToRun = new HashSet<>();
        mBuildInfo = getBuildInfo();
        if (mTestGroup == null && includeFilter.isEmpty()) {
            throw new HarnessRuntimeException(
                    "At least one of the options, --test-mapping-test-group or --include-filter, "
                            + "should be set.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        if (mTestGroup == null && !mKeywords.isEmpty()) {
            throw new HarnessRuntimeException(
                    "Must specify --test-mapping-test-group when applying --test-mapping-keyword.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        if (mTestGroup == null && !mTestModulesForced.isEmpty()) {
            throw new HarnessRuntimeException(
                    "Must specify --test-mapping-test-group when applying "
                            + "--force-test-mapping-module.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        if (mTestGroup != null && !includeFilter.isEmpty()) {
            throw new HarnessRuntimeException(
                    "If options --test-mapping-test-group is set, option --include-filter should "
                            + "not be set.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        if (!includeFilter.isEmpty() && !mTestMappingPaths.isEmpty()) {
            throw new HarnessRuntimeException(
                    "If option --include-filter is set, option --test-mapping-path should "
                            + "not be set.",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        if (mTestGroup != null) {
            TestMapping.setIgnoreTestMappingImports(mIgnoreTestMappingImports);
            if (mForceFullRun) {
                CLog.d("--force-full-run is specified, all tests in test group %s will be ran.",
                        mTestGroup);
                mTestMappingPaths.clear();
            }
            if (!mTestMappingPaths.isEmpty()) {
                TestMapping.setTestMappingPaths(mTestMappingPaths);
            }
            testInfosToRun =
                    TestMapping.getTests(
                            mBuildInfo,
                            mTestGroup,
                            getPrioritizeHostConfig(),
                            mKeywords,
                            mAdditionalTestMappingZips,
                            mMatchedPatternPaths);
            if (!mTestModulesForced.isEmpty()) {
                CLog.i("Filtering tests for the given names: %s", mTestModulesForced);
                testInfosToRun =
                        testInfosToRun
                                .stream()
                                .filter(testInfo -> mTestModulesForced.contains(testInfo.getName()))
                                .collect(Collectors.toSet());
            }
            if (!mAllowedTestLists.isEmpty()) {
                CLog.i("Filtering tests from allowed test lists: %s", mAllowedTestLists);
                testInfosToRun = filterByAllowedTestLists(testInfosToRun);
            }
            if (testInfosToRun.isEmpty() && !mAllowEmptyTests) {
                throw new HarnessRuntimeException(
                        String.format("No test found for the given group: %s.", mTestGroup),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            for (TestInfo testInfo : testInfosToRun) {
                testNames.add(testInfo.getName());
            }
            setIncludeFilter(testNames);
            // With include filters being set, the test no longer needs group and path settings.
            // Clear the settings to avoid conflict when the test is running in a shard.
            mTestGroup = null;
            mTestMappingPaths.clear();
            mUseTestMappingPath = false;
        }

        // In test discovery mode, abort here and the return value should not be used.
        if (mIsTestDiscovery) {
            return null;
        }

        // load all the configurations with include-filter injected.
        LinkedHashMap<String, IConfiguration> testConfigs = super.loadTests();

        // Create and inject individual tests by calling super.loadTests() with each test info.
        for (Map.Entry<String, IConfiguration> entry : testConfigs.entrySet()) {
            List<IRemoteTest> allTests = new ArrayList<>();
            IConfiguration moduleConfig = entry.getValue();
            ConfigurationDescriptor configDescriptor =
                    moduleConfig.getConfigurationDescription();
            IAbi abi = configDescriptor.getAbi();
            // Get the parameterized module name by striping the abi information out.
            String moduleName = entry.getKey().replace(String.format("%s ", abi.getName()), "");
            Set<TestInfo> testInfos = getTestInfos(testInfosToRun, moduleName);
            // Only keep the same matching abi runner
            allTests.addAll(createIndividualTests(testInfos, moduleConfig, abi));
            if (!allTests.isEmpty()) {
                // Set back to IConfiguration only if IRemoteTests are created.
                moduleConfig.setTests(allTests);
                // Set test sources to ConfigurationDescriptor.
                List<String> testSources = getTestSources(testInfos);
                configDescriptor.addMetadata(TestMapping.TEST_SOURCES, testSources);
            }
            if (mRemoteTestTimeOut != null) {
                // Add the timeout to metadata so that it can be used in the ModuleDefinition.
                configDescriptor.addMetadata(
                        RemoteTestTimeOutEnforcer.REMOTE_TEST_TIMEOUT_OPTION,
                        mRemoteTestTimeOut.toString()
                );
            }

        }
        return testConfigs;
    }

    @VisibleForTesting
    String getTestGroup() {
        return mTestGroup;
    }

    public void clearTestGroup() {
        mTestGroup = null;
    }

    @VisibleForTesting
    List<String> getTestMappingPaths() {
        return mTestMappingPaths;
    }

    @VisibleForTesting
    boolean getUseTestMappingPath() {
        return mUseTestMappingPath;
    }

    /**
     * Create individual tests with test infos for a module.
     *
     * @param testInfos A {@code Set<TestInfo>} containing multiple test options.
     * @param moduleConfig The {@link IConfiguration} of the module config.
     * @return The {@link List} that are injected with the test options.
     */
    @VisibleForTesting
    List<IRemoteTest> createIndividualTests(
            Set<TestInfo> testInfos, IConfiguration moduleConfig, IAbi abi) {
        List<IRemoteTest> tests = new ArrayList<>();
        String configPath = moduleConfig.getName();
        // Save top-level exclude-filter test options so that we can inject them back
        // afterwards when creating individual test.
        Set<String> excludeFilterSet = getExcludeFilter();
        if (configPath == null) {
            throw new RuntimeException(String.format("Configuration path is null."));
        }
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            configFile = null;
        }
        // De-duplicate test infos so that there won't be duplicate test options.
        testInfos = dedupTestInfos(testInfos);
        Set<String> duplicateSources = new LinkedHashSet<>();

        for (TestInfo testInfo : testInfos) {
            // Clean up all the test options injected in SuiteModuleLoader.
            super.cleanUpSuiteSetup();
            super.clearModuleArgs();
            // Inject back the original exclude-filter test options.
            super.setExcludeFilter(excludeFilterSet);
            if (configFile != null) {
                clearConfigPaths();
                // Set config path to BaseTestSuite to limit the search.
                addConfigPaths(configFile);
            }
            // Inject the test options from each test info to SuiteModuleLoader.
            parseOptions(testInfo);
            LinkedHashMap<String, IConfiguration> config = super.loadTests();
            for (Map.Entry<String, IConfiguration> entry : config.entrySet()) {
                if (entry.getValue().getConfigurationDescription().getAbi() != null
                        && !entry.getValue().getConfigurationDescription().getAbi().equals(abi)) {
                    continue;
                }
                List<IRemoteTest> remoteTests = entry.getValue().getTests();
                if (mRemoteTestTimeOut != null) {
                    addTestSourcesToConfig(moduleConfig, remoteTests, testInfo.getSources());
                }
                duplicateSources.addAll(testInfo.getSources());
                tests.addAll(remoteTests);
            }
        }
        // If size above 1 that means we have duplicated modules with different options
        if (tests.size() > 1) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DUPLICATE_MAPPING_DIFFERENT_OPTIONS,
                    String.format(
                            "%s:" + Joiner.on("+").join(duplicateSources), configPath));
        }
        return tests;
    }

    /** Add test mapping's path into module configuration. */
    private void addTestSourcesToConfig(IConfiguration config, List<IRemoteTest> tests,
            Set<String> sources) {
        for (IRemoteTest test : tests) {
            config.getConfigurationDescription().addMetadata(
                Integer.toString(test.hashCode()), new ArrayList<>(sources)
            );
        }
    }

    /**
     * Get a list of path of TEST_MAPPING for a module.
     *
     * @param testInfos A {@code Set<TestInfo>} containing multiple test options.
     * @return A {@code List<String>} of TEST_MAPPING path.
     */
    @VisibleForTesting
    List<String> getTestSources(Set<TestInfo> testInfos) {
        List<String> testSources = new ArrayList<>();
        for (TestInfo testInfo : testInfos) {
            testSources.addAll(testInfo.getSources());
        }
        return testSources;
    }

    /**
     * Parse the test options for the test info.
     *
     * @param testInfo A {@code Set<TestInfo>} containing multiple test options.
     */
    @VisibleForTesting
    void parseOptions(TestInfo testInfo) {
        Set<String> mappingIncludeFilters = new HashSet<>();
        Set<String> mappingExcludeFilters = new HashSet<>();
        // module-arg options compiled from test options for each test.
        Set<String> moduleArgs = new HashSet<>();
        Set<String> testNames = new HashSet<>();
        for (TestOption option : testInfo.getOptions()) {
            switch (option.getName()) {
                // Handle include and exclude filter at the suite level to hide each
                // test runner specific implementation and option names related to filtering
                case TEST_MAPPING_INCLUDE_FILTER:
                    mappingIncludeFilters.add(
                            String.format("%s %s", testInfo.getName(), option.getValue()));
                    break;
                case TEST_MAPPING_EXCLUDE_FILTER:
                    mappingExcludeFilters.add(
                            String.format("%s %s", testInfo.getName(), option.getValue()));
                    break;
                default:
                    String moduleArg =
                            String.format("%s:%s", testInfo.getName(), option.getName());
                    if (option.getValue() != null && !option.getValue().isEmpty()) {
                        moduleArg = String.format("%s:%s", moduleArg, option.getValue());
                    }
                    moduleArgs.add(moduleArg);
                    break;
            }
        }

        if (mappingIncludeFilters.isEmpty()) {
            testNames.add(testInfo.getName());
            setIncludeFilter(testNames);
        } else {
            setIncludeFilter(mappingIncludeFilters);
        }
        if (!mappingExcludeFilters.isEmpty()) {
            setExcludeFilter(mappingExcludeFilters);
        }
        addModuleArgs(moduleArgs);
    }

    /**
     * De-duplicate test infos with the same test options.
     *
     * @param testInfos A {@code Set<TestInfo>} containing multiple test options.
     * @return A {@code Set<TestInfo>} of tests without duplicated test options.
     */
    @VisibleForTesting
    Set<TestInfo> dedupTestInfos(Set<TestInfo> testInfos) {
        Set<String> nameOptions = new HashSet<>();
        Set<TestInfo> dedupTestInfos = new HashSet<>();
        for (TestInfo testInfo : testInfos) {
            String nameOption = testInfo.getName() + testInfo.getOptions().toString();
            if (!nameOptions.contains(nameOption)) {
                dedupTestInfos.add(testInfo);
                nameOptions.add(nameOption);
            }
        }
        return dedupTestInfos;
    }

    /**
     * Get the test infos for the given module name.
     *
     * @param testInfos A {@code Set<TestInfo>} containing multiple test options.
     * @param moduleName A {@code String} name of a test module.
     * @return A {@code Set<TestInfo>} of tests for a module.
     */
    @VisibleForTesting
    Set<TestInfo> getTestInfos(Set<TestInfo> testInfos, String moduleName) {
        return testInfos
                .stream()
                .filter(testInfo -> moduleName.equals(testInfo.getName()))
                .collect(Collectors.toSet());
    }

    /**
     * Filter test infos by the given allowed test lists.
     *
     * @param testInfos A {@code Set<TestInfo>} containing multiple test options.
     * @return A {@code Set<TestInfo>} of tests matching the allowed test lists.
     */
    @VisibleForTesting
    Set<TestInfo> filterByAllowedTestLists(Set<TestInfo> testInfos) {
        // Read the list of allowed tests, and compile a set of allowed test module names.
        Set<String> allowedTests = new HashSet<String>();
        for (String testList : mAllowedTestLists) {
            File testListZip = getBuildInfo().getFile(testList);
            if (testListZip == null) {
                throw new RuntimeException("Failed to locate allowed test list " + testList);
            }
            File testListFile = null;
            try {
                ZipFile zipFile = new ZipFile(testListZip);
                testListFile =
                        ZipUtil2.extractFileFromZip(
                                zipFile, Files.getNameWithoutExtension(testList));
                zipFile.close();
                String content = FileUtil.readStringFromFile(testListFile);
                final String pattern = "([^//]*).config$";
                Pattern namePattern = Pattern.compile(pattern);
                for (String line : content.split("\n")) {
                    Matcher matcher = namePattern.matcher(line);
                    if (matcher.find()) {
                        allowedTests.add(matcher.group(1));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(
                        String.format(
                                "IO exception (%s) when accessing allowed test list (%s)",
                                e.getMessage(), testList),
                        e);
            } finally {
                FileUtil.recursiveDelete(testListFile);
            }
        }

        return testInfos.stream()
                .filter(testInfo -> allowedTests.contains(testInfo.getName()))
                .collect(Collectors.toSet());
    }
}
