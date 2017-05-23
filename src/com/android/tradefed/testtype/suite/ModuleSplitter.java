/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IShardableTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * Helper to split a list of modules represented by {@link IConfiguration} into a list of execution
 * units represented by {@link ModuleDefinition}.
 *
 * <p>Each configuration may generate 1 or more {@link ModuleDefinition} depending on its options
 * and test types:
 *
 * <ul>
 *   <li>A non-shardable {@link IConfiguration} will generate a single {@link ModuleDefinition}.
 *   <li>A shardable {@link IConfiguration} will generate a number of ModuleDefinition linked to the
 *       {@link IRemoteTest} properties:
 *       <ul>
 *         <li>A non - {@link IShardableTest} will generate a single ModuleDefinition.
 *         <li>A {@link IShardableTest} generates one ModuleDefinition per tests returned by {@link
 *             IShardableTest#split()}.
 *       </ul>
 *
 * </ul>
 */
public class ModuleSplitter {

    /**
     * Create a List of executable unit {@link ModuleDefinition}s based on the map of configuration
     * that was loaded.
     *
     * @param runConfig {@link LinkedHashMap} loaded from {@link ITestSuite#loadTests()}.
     * @param shardCount a shard count hint to help with sharding.
     * @return List of {@link ModuleDefinition}
     */
    public static List<ModuleDefinition> splitConfiguration(
            LinkedHashMap<String, IConfiguration> runConfig, int shardCount) {
        List<ModuleDefinition> runModules = new ArrayList<>();
        for (Entry<String, IConfiguration> configMap : runConfig.entrySet()) {
            validateConfig(configMap.getValue());
            createAndAddModule(runModules, configMap.getKey(), configMap.getValue(), shardCount);
        }
        return runModules;
    }

    private static void createAndAddModule(
            List<ModuleDefinition> currentList,
            String moduleName,
            IConfiguration config,
            int shardCount) {
        // If this particular configuration module is declared as 'not shardable' we take it whole
        // no need to clone target_preparers.
        if (config.getConfigurationDescription().isNotShardable()) {
            ModuleDefinition module =
                    new ModuleDefinition(
                            moduleName, config.getTests(), config.getTargetPreparers());
            currentList.add(module);
            return;
        }

        // If configuration is possibly shardable we attempt to shard it.
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof IShardableTest) {
                Collection<IRemoteTest> shardedTests = ((IShardableTest) test).split(shardCount);
                if (shardedTests == null) {
                    List<IRemoteTest> testList = new ArrayList<>();
                    testList.add(test);
                    // test did not shard
                    ModuleDefinition module =
                            new ModuleDefinition(moduleName, testList, clonePreparers(config));
                    currentList.add(module);
                } else {
                    // Test did shard we put the shard pool in ModuleDefinition which has a polling
                    // behavior on the pool.
                    for (int i = 0; i < shardCount; i++) {
                        ModuleDefinition module =
                                new ModuleDefinition(
                                        moduleName, shardedTests, clonePreparers(config));
                        currentList.add(module);
                    }
                }
            } else {
                List<IRemoteTest> testList = new ArrayList<>();
                testList.add(test);
                // test is not shardable
                ModuleDefinition module =
                        new ModuleDefinition(moduleName, testList, clonePreparers(config));
                currentList.add(module);
            }
        }
    }

    private static void validateConfig(IConfiguration config) {
        if (!ValidateSuiteConfigHelper.validateConfig(config)) {
            throw new RuntimeException(
                    new ConfigurationException(
                            String.format(
                                    "Configuration %s cannot be run in a suite.",
                                    config.getName())));
        }
    }

    /**
     * Deep clone a list of {@link ITargetPreparer}. We are ensured to find a default constructor
     * with no arguments since that's the expectation from Tradefed when loading configuration.
     * Cloning preparers is required since they may be stateful and we cannot share instance across
     * devices.
     */
    private static List<ITargetPreparer> clonePreparers(IConfiguration config) {
        List<ITargetPreparer> clones = new ArrayList<>();
        for (ITargetPreparer prep : config.getTargetPreparers()) {
            try {
                ITargetPreparer clone = prep.getClass().newInstance();
                OptionCopier.copyOptions(prep, clone);
                // Ensure we copy the Abi too.
                if (clone instanceof IAbiReceiver) {
                    ((IAbiReceiver) clone).setAbi(((IAbiReceiver) prep).getAbi());
                }
                clones.add(clone);
            } catch (InstantiationException | IllegalAccessException | ConfigurationException e) {
                throw new RuntimeException(e);
            }
        }
        return clones;
    }
}
