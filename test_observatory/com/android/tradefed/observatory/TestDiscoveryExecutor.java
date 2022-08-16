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

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;
import com.android.tradefed.testtype.suite.SuiteTestFilter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        TestDiscoveryExecutor testDiscoveryExecutor = new TestDiscoveryExecutor();
        try {
            String testModules = testDiscoveryExecutor.discoverDependencies(args);
            System.out.print(testModules);
            // Exit with code 0 to signal success discovery
            System.exit(0);
        } catch (Exception e) {
            // Exit with code 1 when any exception happened
            System.err.print(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Discover test dependencies base on command line args.
     *
     * @param args the command line args of the test.
     * @return A JSON string with one test module names array and one other test dependency array.
     */
    public String discoverDependencies(String[] args) throws Exception {
        // Create IConfiguration base on command line args.
        IConfiguration config = getConfiguration(args);
        List<IRemoteTest> tests = config.getTests();

        // Tests could be empty if input args are corrupted.
        if (tests == null || tests.isEmpty()) {
            throw new TestDiscoveryException(
                    "Tradefed Observatory discovered no tests from the IConfiguration created from"
                            + " command line args.");
        }

        List<String> testModules = new ArrayList<>(discoverTestModulesFromTests(tests));

        if (testModules == null || testModules.isEmpty()) {
            throw new TestDiscoveryException(
                    "Tradefed Observatory discovered no test modules from the test config, it"
                            + " might be component-based.");
        }
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
        return configurationFactory.createConfigurationFromArgs(args);
    }

    /**
     * Discover configuration by a list of {@link IRemoteTest}.
     *
     * @param testList a list of {@link IRemoteTest}.
     * @return A set of test module names.
     */
    private Set<String> discoverTestModulesFromTests(List<IRemoteTest> testList)
            throws IllegalStateException {
        Set<String> includeFilters = new HashSet<>();
        // Collect include filters from every test.
        for (IRemoteTest test : testList) {
            if (test instanceof BaseTestSuite) {
                includeFilters.addAll(((BaseTestSuite) test).getIncludeFilter());
            }
        }
        // Extract test module names from included filters.
        Set<String> testModules = extractTestModulesFromIncludeFilters(includeFilters);
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
            String testModuleName = SuiteTestFilter.createFrom(includeFilter).getName();
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
}
