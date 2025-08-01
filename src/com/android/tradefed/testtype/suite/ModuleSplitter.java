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

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IShardableTest;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Set an upper limit to how much a given module can be sharded. This can avoid over-granular
     * intra-module sharding.
     */
    private static final int MAX_MODULE_LOCAL_SHARDING = 8;

    /**
     * Create a List of executable unit {@link ModuleDefinition}s based on the map of configuration
     * that was loaded.
     *
     * @param testInfo the current {@link TestInformation} to proceed with sharding.
     * @param runConfig {@link LinkedHashMap} loaded from {@link ITestSuite#loadTests()}.
     * @param suitePreparersPerDevice map of suite level preparers per test device.
     * @param shardCount a shard count hint to help with sharding.
     * @param dynamicModule Whether or not module can be shared in pool or must be independent
     *     (strict sharding).
     * @param intraModuleSharding Whether or not to shard within the modules.
     * @return List of {@link ModuleDefinition}
     */
    public static List<ModuleDefinition> splitConfiguration(
            TestInformation testInfo,
            LinkedHashMap<String, IConfiguration> runConfig,
            Map<String, List<ITargetPreparer>> suitePreparersPerDevice,
            int shardCount,
            boolean dynamicModule,
            boolean intraModuleSharding) {
        if (dynamicModule) {
            // We maximize the sharding for dynamic to reduce time difference between first and
            // last shard as much as possible. Overhead is low due to our test pooling.
            shardCount *= 2;
        }
        List<ModuleDefinition> runModules = new ArrayList<>();
        for (Entry<String, IConfiguration> configMap : runConfig.entrySet()) {
            // Check that it's a valid configuration for suites, throw otherwise.
            ValidateSuiteConfigHelper.validateConfig(configMap.getValue());

            try {
                createAndAddModule(
                        testInfo,
                        runModules,
                        configMap.getKey(),
                        configMap.getValue(),
                        shardCount,
                        dynamicModule,
                        intraModuleSharding,
                        suitePreparersPerDevice);
            } catch (RuntimeException e) {
                CLog.e("Exception while creating module for '%s'", configMap.getKey());
                throw e;
            }
        }
        return runModules;
    }

    private static void createAndAddModule(
            TestInformation testInfo,
            List<ModuleDefinition> currentList,
            String moduleName,
            IConfiguration config,
            int shardCount,
            boolean dynamicModule,
            boolean intraModuleSharding,
            Map<String, List<ITargetPreparer>> suitePreparersPerDevice) {
        List<IRemoteTest> tests = config.getTests();
        // Get rid of the IRemoteTest reference on the shared configuration. It will not be used
        // to run.
        config.setTests(new ArrayList<>());

        if (config.getConfigurationDescription().isNotIRemoteTestShardable()) {
            // If it cannot be sharded at all, then put all the tests together.
            ModuleDefinition module =
                    new ModuleDefinition(
                            moduleName,
                            tests,
                            clonePreparersMap(config),
                            clonePreparersMap(suitePreparersPerDevice),
                            clonePreparers(config.getMultiTargetPreparers()),
                            config);
            currentList.add(module);
            clearPreparersFromConfig(config);
            return;
        }

        // If this particular configuration module is declared as 'not shardable' we take it whole
        // but still split the individual IRemoteTest in a pool.
        if (!intraModuleSharding
                || config.getConfigurationDescription().isNotShardable()
                || (!dynamicModule
                        && config.getConfigurationDescription().isNotStrictShardable())) {
            for (int i = 0; i < tests.size(); i++) {
                if (dynamicModule) {
                    ModuleDefinition module =
                            new ModuleDefinition(
                                    moduleName,
                                    tests,
                                    clonePreparersMap(config),
                                    clonePreparersMap(suitePreparersPerDevice),
                                    clonePreparers(config.getMultiTargetPreparers()),
                                    config);
                    currentList.add(module);
                } else {
                    addModuleToListFromSingleTest(
                            currentList, tests.get(i), moduleName, config, suitePreparersPerDevice);
                }
            }
            clearPreparersFromConfig(config);
            return;
        }

        // If configuration is possibly shardable we attempt to shard it.
        for (IRemoteTest test : tests) {
            if (test instanceof IShardableTest) {
                int shard = Math.min(MAX_MODULE_LOCAL_SHARDING, shardCount);
                Collection<IRemoteTest> shardedTests =
                        ((IShardableTest) test).split(shard, testInfo);
                if (shardedTests != null) {
                    // Test did shard we put the shard pool in ModuleDefinition which has a polling
                    // behavior on the pool.
                    if (dynamicModule) {
                        for (int i = 0; i < shardedTests.size(); i++) {
                            ModuleDefinition module =
                                    new ModuleDefinition(
                                            moduleName,
                                            shardedTests,
                                            clonePreparersMap(config),
                                            clonePreparersMap(suitePreparersPerDevice),
                                            clonePreparers(config.getMultiTargetPreparers()),
                                            config);
                            module.setIntraModuleInformation(shardedTests.size(), i);
                            currentList.add(module);
                        }
                    } else {
                        // We create independent modules with each sharded test.
                        int i = 0;
                        for (IRemoteTest moduleTest : shardedTests) {
                            ModuleDefinition module =
                                    addModuleToListFromSingleTest(
                                            currentList,
                                            moduleTest,
                                            moduleName,
                                            config,
                                            suitePreparersPerDevice);
                            module.setIntraModuleInformation(shardedTests.size(), i);
                            i++;
                        }
                    }
                    continue;
                }
            }
            // test is not shardable or did not shard
            addModuleToListFromSingleTest(
                    currentList, test, moduleName, config, suitePreparersPerDevice);
        }
        clearPreparersFromConfig(config);
    }

    /**
     * Helper to add a new {@link ModuleDefinition} to our list of Modules from a single {@link
     * IRemoteTest}.
     */
    private static ModuleDefinition addModuleToListFromSingleTest(
            List<ModuleDefinition> currentList,
            IRemoteTest test,
            String moduleName,
            IConfiguration config,
            Map<String, List<ITargetPreparer>> suitePreparersPerDevice) {
        List<IRemoteTest> testList = new ArrayList<>();
        testList.add(test);
        ModuleDefinition module =
                new ModuleDefinition(
                        moduleName,
                        testList,
                        clonePreparersMap(config),
                        clonePreparersMap(suitePreparersPerDevice),
                        clonePreparers(config.getMultiTargetPreparers()),
                        config);
        currentList.add(module);
        return module;
    }

    /**
     * Deep clone a list of {@link ITargetPreparer} or {@link IMultiTargetPreparer}. We are ensured
     * to find a default constructor with no arguments since that's the expectation from Tradefed
     * when loading configuration. Cloning preparers is required since they may be stateful and we
     * cannot share instance across devices.
     */
    private static <T> List<T> clonePreparers(List<T> preparerList) {
        List<T> clones = new ArrayList<>();
        for (T prep : preparerList) {
            try {
                @SuppressWarnings("unchecked")
                T clone = (T) prep.getClass().getDeclaredConstructor().newInstance();
                OptionCopier.copyOptions(prep, clone);
                // Ensure we copy the Abi too.
                if (clone instanceof IAbiReceiver) {
                    ((IAbiReceiver) clone).setAbi(((IAbiReceiver) prep).getAbi());
                }
                clones.add(clone);
            } catch (InstantiationException
                    | IllegalAccessException
                    | ConfigurationException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        return clones;
    }

    /** Deep cloning of potentially multi-device preparers. */
    private static Map<String, List<ITargetPreparer>> clonePreparersMap(IConfiguration config) {
        Map<String, List<ITargetPreparer>> res = new LinkedHashMap<>();
        for (IDeviceConfiguration holder : config.getDeviceConfig()) {
            List<ITargetPreparer> preparers = new ArrayList<>();
            res.put(holder.getDeviceName(), preparers);
            preparers.addAll(clonePreparers(holder.getTargetPreparers()));
        }
        return res;
    }

    /** Deep cloning of potentially multi-device preparers. */
    private static Map<String, List<ITargetPreparer>> clonePreparersMap(
            Map<String, List<ITargetPreparer>> suitePreparersPerDevice) {
        Map<String, List<ITargetPreparer>> res = new LinkedHashMap<>();
        for (String device : suitePreparersPerDevice.keySet()) {
            List<ITargetPreparer> preparers = new ArrayList<>();
            res.put(device, preparers);
            preparers.addAll(clonePreparers(suitePreparersPerDevice.get(device)));
        }
        return res;
    }

    private static void clearPreparersFromConfig(IConfiguration config) {
        try {
            for (IDeviceConfiguration holder : config.getDeviceConfig()) {
                holder.removeObjectType(Configuration.TARGET_PREPARER_TYPE_NAME);
            }
            config.setMultiTargetPreparers(new ArrayList<>());
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
