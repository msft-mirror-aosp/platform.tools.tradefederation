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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;
import com.android.tradefed.testtype.suite.SuiteTestFilter;
import com.android.tradefed.testtype.suite.TestMappingSuiteRunner;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.keystore.DryRunKeyStore;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A class for getting test modules and target preparers for a given command line args.
 *
 * <p>TestDiscoveryExecutor will consume the command line args and print test module names and
 * target preparer apks on stdout for the parent TradeFed process to receive and parse it.
 *
 * <p>
 */
public class TestDiscoveryExecutor {

    IConfigurationFactory getConfigurationFactory() {
        return ConfigurationFactory.getInstance();
    }

    /**
     * An TradeFederation entry point that will use command args to discover test artifact
     * information.
     *
     * <p>Intended for use with cts partial download to only download necessary files for the test
     * run.
     *
     * <p>Will only exit with 0 when successfully discovered test modules.
     *
     * <p>Expected arguments: [commands options] (config to run)
     */
    public static void main(String[] args) {
        DiscoveryExitCode exitCode = DiscoveryExitCode.SUCCESS;
        TestDiscoveryExecutor testDiscoveryExecutor = new TestDiscoveryExecutor();
        try {
            String testModules = testDiscoveryExecutor.discoverDependencies(args);
            System.out.print(testModules);
        } catch (TestDiscoveryException e) {
            System.err.print(e.getMessage());
            if (e.exitCode() != null) {
                exitCode = e.exitCode();
            } else {
                exitCode = DiscoveryExitCode.ERROR;
            }
        } catch (Exception e) {
            System.err.print(e.getMessage());
            exitCode = DiscoveryExitCode.ERROR;
        }
        System.exit(exitCode.exitCode());
    }

    /**
     * Discover test dependencies base on command line args.
     *
     * @param args the command line args of the test.
     * @return A JSON string with one test module names array and one other test dependency array.
     */
    public String discoverDependencies(String[] args)
            throws TestDiscoveryException, ConfigurationException {
        // Create IConfiguration base on command line args.
        IConfiguration config = getConfiguration(args);

        // Get tests from the configuration.
        List<IRemoteTest> tests = config.getTests();

        // Tests could be empty if input args are corrupted.
        if (tests == null || tests.isEmpty()) {
            throw new TestDiscoveryException(
                    "Tradefed Observatory discovered no tests from the IConfiguration created from"
                            + " command line args.",
                    null,
                    DiscoveryExitCode.ERROR);
        }

        List<String> testModules = new ArrayList<>(discoverTestModulesFromTests(tests));

        List<String> testDependencies = new ArrayList<>(discoverDependencies(config));
        Collections.sort(testModules);
        Collections.sort(testDependencies);

        JsonObject jsonObject = new JsonObject();
        Gson gson = new Gson();
        JsonArray testModulesArray = gson.toJsonTree(testModules).getAsJsonArray();
        JsonArray testDependenciesArray = gson.toJsonTree(testDependencies).getAsJsonArray();
        jsonObject.add(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, testModulesArray);
        jsonObject.add(TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, testDependenciesArray);
        return jsonObject.toString();
    }

    /**
     * Retrieve configuration base on command line args.
     *
     * @param args the command line args of the test.
     * @return A {@link IConfiguration} which constructed based on command line args.
     */
    private IConfiguration getConfiguration(String[] args) throws ConfigurationException {
        IConfigurationFactory configurationFactory = getConfigurationFactory();
        return configurationFactory.createConfigurationFromArgs(args, null, new DryRunKeyStore());
    }

    /**
     * Discover configuration by a list of {@link IRemoteTest}.
     *
     * @param testList a list of {@link IRemoteTest}.
     * @return A set of test module names.
     */
    private Set<String> discoverTestModulesFromTests(List<IRemoteTest> testList)
            throws IllegalStateException, TestDiscoveryException {
        Set<String> testModules = new LinkedHashSet<String>();
        Set<String> includeFilters = new HashSet<>();
        // Collect include filters from every test.
        for (IRemoteTest test : testList) {
            if (!(test instanceof BaseTestSuite)) {
                throw new TestDiscoveryException(
                        "Tradefed Observatory can't do test discovery on non suite-based test"
                                + " runner.",
                        null,
                        DiscoveryExitCode.ERROR);
            }
            if (test instanceof TestMappingSuiteRunner) {
                if (getEnvironment(TestDiscoveryInvoker.TEST_DIRECTORY_ENV_VARIABLE_KEY) == null) {
                    throw new TestDiscoveryException(
                            "The TestDiscoveryInvoker need test "
                                    + "directory to be set to do test mapping "
                                    + "discovery.",
                            null,
                            DiscoveryExitCode.ERROR);
                }
                ((TestMappingSuiteRunner) test).loadTestInfos();
            }
            Set<String> suiteIncludeFilters = ((BaseTestSuite) test).getIncludeFilter();
            MultiMap<String, String> moduleMetadataIncludeFilters =
                    ((BaseTestSuite) test).getModuleMetadataIncludeFilters();
            // Include/Exclude filters in suites are evaluated first,
            // then metadata are applied on top, so having metadata filters
            // and include-filters can actually be resolved to a super-set
            // which is better than falling back.
            if (!suiteIncludeFilters.isEmpty()) {
                includeFilters.addAll(suiteIncludeFilters);
            } else if (!moduleMetadataIncludeFilters.isEmpty()) {
                String rootDirPath =
                        getEnvironment(TestDiscoveryInvoker.ROOT_DIRECTORY_ENV_VARIABLE_KEY);
                boolean throwException = true;
                if (rootDirPath != null) {
                    File rootDir = new File(rootDirPath);
                    if (rootDir.exists() && rootDir.isDirectory()) {
                        Set<String> configs =
                                searchConfigsForMetadata(rootDir, moduleMetadataIncludeFilters);
                        if (configs != null) {
                            testModules.addAll(configs);
                            throwException = false;
                        }
                    }
                }
                if (throwException) {
                    throw new TestDiscoveryException(
                            "Tradefed Observatory can't do test discovery because the existence of"
                                    + " metadata include filter option.",
                            null,
                            DiscoveryExitCode.COMPONENT_METADATA);
                }
            } else if (!Strings.isNullOrEmpty(((BaseTestSuite) test).getRunSuiteTag())) {
                throw new TestDiscoveryException(
                        "Tradefed Observatory can't do test discovery because the existence of"
                                + " run-suite-tag option.",
                        null,
                        DiscoveryExitCode.COMPONENT_METADATA);
            }
        }
        // Extract test module names from included filters.
        testModules.addAll(extractTestModulesFromIncludeFilters(includeFilters));
        return testModules;
    }

    /**
     * Extract test module names from include filters.
     *
     * @param includeFilters a set of include filters.
     * @return A set of test module names.
     */
    private Set<String> extractTestModulesFromIncludeFilters(Set<String> includeFilters)
            throws IllegalStateException {
        Set<String> testModuleNames = new HashSet<>();
        // Extract module name from each include filter.
        // TODO: Ensure if a module is fully excluded then it's excluded.
        for (String includeFilter : includeFilters) {
            String testModuleName = SuiteTestFilter.createFrom(includeFilter).getBaseName();
            if (testModuleName == null) {
                // If unable to parse an include filter, throw exception to exit.
                throw new IllegalStateException(
                        String.format(
                                "Unable to parse test module name from include filter %s",
                                includeFilter));
            } else {
                testModuleNames.add(testModuleName);
            }
        }
        return testModuleNames;
    }

    private Set<String> discoverDependencies(IConfiguration config) {
        Set<String> dependencies = new HashSet<>();
        for (Object o :
                config.getAllConfigurationObjectsOfType(Configuration.TARGET_PREPARER_TYPE_NAME)) {
            if (o instanceof IDiscoverDependencies) {
                dependencies.addAll(((IDiscoverDependencies) o).reportDependencies());
            }
        }
        return dependencies;
    }

    private Set<String> searchConfigsForMetadata(
            File rootDir, MultiMap<String, String> moduleMetadataIncludeFilters) {
        try {
            Set<File> configFiles = FileUtil.findFilesObject(rootDir, "\\.config$");
            Set<File> shouldRunFiles =
                    configFiles.stream()
                            .filter(
                                    f -> {
                                        try {
                                            IConfiguration c =
                                                    getConfigurationFactory()
                                                            .createPartialConfigurationFromArgs(
                                                                    new String[] {
                                                                        f.getAbsolutePath()
                                                                    },
                                                                    new DryRunKeyStore(),
                                                                    ImmutableSet.of(
                                                                            Configuration
                                                                                    .CONFIGURATION_DESCRIPTION_TYPE_NAME),
                                                                    null);
                                            return new BaseTestSuite()
                                                    .filterByConfigMetadata(
                                                            c,
                                                            moduleMetadataIncludeFilters,
                                                            new MultiMap<String, String>());
                                        } catch (ConfigurationException e) {
                                            return false;
                                        }
                                    })
                            .collect(Collectors.toSet());
            return shouldRunFiles.stream().map(c -> c.getName()).collect(Collectors.toSet());
        } catch (IOException e) {
            System.err.println(e);
        }
        return null;
    }

    @VisibleForTesting
    protected String getEnvironment(String var) {
        return System.getenv(var);
    }
}
