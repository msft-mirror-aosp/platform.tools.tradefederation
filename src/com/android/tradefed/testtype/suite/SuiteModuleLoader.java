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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.ConfigurationUtil;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFileFilterReceiver;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Retrieves Compatibility test module definitions from the repository. TODO: Add the expansion of
 * suite when loading a module.
 */
public class SuiteModuleLoader {

    private static final String CONFIG_EXT = ".config";
    private Map<String, Map<String, List<String>>> mTestArgs = new HashMap<>();
    private Map<String, Map<String, List<String>>> mModuleArgs = new HashMap<>();
    private boolean mIncludeAll;
    private Map<String, List<SuiteTestFilter>> mIncludeFilters = new HashMap<>();
    private Map<String, List<SuiteTestFilter>> mExcludeFilters = new HashMap<>();
    private IConfigurationFactory mConfigFactory = ConfigurationFactory.getInstance();

    /**
     * Ctor for the SuiteModuleLoader.
     *
     * @param includeFilters The formatted and parsed include filters.
     * @param excludeFilters The formatted and parsed exclude filters.
     * @param testArgs the list of test ({@link IRemoteTest}) arguments.
     * @param moduleArgs the list of module arguments.
     */
    public SuiteModuleLoader(
            Map<String, List<SuiteTestFilter>> includeFilters,
            Map<String, List<SuiteTestFilter>> excludeFilters,
            List<String> testArgs,
            List<String> moduleArgs) {
        mIncludeAll = includeFilters.isEmpty();
        mIncludeFilters = includeFilters;
        mExcludeFilters = excludeFilters;

        putArgs(testArgs, mTestArgs);
        putArgs(moduleArgs, mModuleArgs);
    }

    /** Main loading of configurations, looking into a folder */
    public LinkedHashMap<String, IConfiguration> loadConfigsFromDirectory(
            File testsDir, Set<IAbi> abis, String suitePrefix, String suiteTag) {
        LinkedHashMap<String, IConfiguration> toRun = new LinkedHashMap<>();

        List<File> listConfigFiles = new ArrayList<>();
        List<File> extraTestCasesDirs = Arrays.asList(testsDir);
        listConfigFiles.addAll(
                ConfigurationUtil.getConfigNamesFileFromDirs(suitePrefix, extraTestCasesDirs));
        // Ensure stable initial order of configurations.
        Collections.sort(listConfigFiles);
        for (File configFile : listConfigFiles) {
            toRun.putAll(
                    loadOneConfig(
                            configFile.getName(), configFile.getAbsolutePath(), abis, suiteTag));
        }
        return toRun;
    }

    /**
     * Main loading of configurations, looking into the resources on the classpath. (TF configs for
     * example).
     */
    public LinkedHashMap<String, IConfiguration> loadConfigsFromJars(
            Set<IAbi> abis, String suitePrefix, String suiteTag) {
        LinkedHashMap<String, IConfiguration> toRun = new LinkedHashMap<>();

        IConfigurationFactory configFactory = ConfigurationFactory.getInstance();
        List<String> configs = configFactory.getConfigList(suitePrefix, false);
        // Sort configs to ensure they are always evaluated and added in the same order.
        Collections.sort(configs);
        for (String configName : configs) {
            toRun.putAll(loadOneConfig(configName, configName, abis, suiteTag));
        }
        return toRun;
    }

    /**
     * Load a single config location (file or on TF classpath). It can results in several {@link
     * IConfiguration}. If a single configuration get expanded in different ways.
     *
     * @param configName The actual config name only. (no path)
     * @param configFullName The fully qualified config name. (with path, if any).
     * @param abis The set of all abis that needs to run.
     * @param suiteTag the Tag of the suite aimed to be run.
     * @return A map of loaded configuration.
     */
    private LinkedHashMap<String, IConfiguration> loadOneConfig(
            String configName, String configFullName, Set<IAbi> abis, String suiteTag) {
        LinkedHashMap<String, IConfiguration> toRun = new LinkedHashMap<>();
        final String name = configName.replace(CONFIG_EXT, "");
        final String[] pathArg = new String[] {configFullName};
        try {
            // Invokes parser to process the test module config file
            // Need to generate a different config for each ABI as we cannot guarantee the
            // configs are idempotent. This however means we parse the same file multiple times
            for (IAbi abi : abis) {
                String id = AbiUtils.createId(abi.getName(), name);
                if (!shouldRunModule(id)) {
                    // If the module should not run tests based on the state of filters,
                    // skip this name/abi combination.
                    continue;
                }
                IConfiguration config = mConfigFactory.createConfigurationFromArgs(pathArg);

                // If a suiteTag is used, we load with it.
                if (suiteTag != null
                        && !config.getConfigurationDescription()
                                .getSuiteTags()
                                .contains(suiteTag)) {
                    continue;
                }

                Map<String, List<String>> args = new HashMap<>();
                if (mModuleArgs.containsKey(name)) {
                    args.putAll(mModuleArgs.get(name));
                }
                if (mModuleArgs.containsKey(id)) {
                    args.putAll(mModuleArgs.get(id));
                }
                injectOptionsToConfig(args, config);

                // Set target preparers
                List<ITargetPreparer> preparers = config.getTargetPreparers();
                for (ITargetPreparer preparer : preparers) {
                    if (preparer instanceof IAbiReceiver) {
                        ((IAbiReceiver) preparer).setAbi(abi);
                    }
                }

                // Set IRemoteTests
                List<IRemoteTest> tests = config.getTests();
                for (IRemoteTest test : tests) {
                    String className = test.getClass().getName();
                    Map<String, List<String>> testArgsMap = new HashMap<>();
                    if (mTestArgs.containsKey(className)) {
                        testArgsMap.putAll(mTestArgs.get(className));
                    }
                    injectOptionsToConfig(testArgsMap, config);
                    addFiltersToTest(test, abi, name);
                    if (test instanceof IAbiReceiver) {
                        ((IAbiReceiver) test).setAbi(abi);
                    }
                }

                // add the abi to the description
                config.getConfigurationDescription().setAbi(abi);
                toRun.put(id, config);
            }
        } catch (ConfigurationException e) {
            throw new RuntimeException(
                    String.format("Error parsing configuration: %s", configFullName), e);
        }
        return toRun;
    }

    /** Helper to inject options to a config. */
    @VisibleForTesting
    void injectOptionsToConfig(Map<String, List<String>> optionMap, IConfiguration config)
            throws ConfigurationException {
        for (Entry<String, List<String>> entry : optionMap.entrySet()) {
            for (String entryValue : entry.getValue()) {
                String entryName = entry.getKey();
                if (entryValue.contains(":=")) {
                    // entryValue is key-value pair
                    String key = entryValue.substring(0, entryValue.indexOf(":="));
                    String value = entryValue.substring(entryValue.indexOf(":=") + 2);
                    config.injectOptionValue(entryName, key, value);
                } else {
                    // entryValue is just the argument value
                    config.injectOptionValue(entryName, entryValue);
                }
            }
        }
    }

    /** @return the {@link List} of modules whose name contains the given pattern. */
    public static List<String> getModuleNamesMatching(File directory, String pattern) {
        String[] names =
                directory.list(
                        new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                return name.contains(pattern) && name.endsWith(CONFIG_EXT);
                            }
                        });
        List<String> modules = new ArrayList<String>(names.length);
        for (String name : names) {
            int index = name.indexOf(CONFIG_EXT);
            if (index > 0) {
                String module = name.substring(0, index);
                if (module.equals(pattern)) {
                    // Pattern represents a single module, just return a single-item list
                    modules = new ArrayList<>(1);
                    modules.add(module);
                    return modules;
                }
                modules.add(module);
            }
        }
        return modules;
    }

    /**
     * Utility method that allows to parse and create a structure with the option filters.
     *
     * @param stringFilters The original option filters format.
     * @param filters The filters parsed from the string format.
     * @param abis The Abis to consider in the filtering.
     */
    public static void addFilters(
            Set<String> stringFilters, Map<String, List<SuiteTestFilter>> filters, Set<IAbi> abis) {
        for (String filterString : stringFilters) {
            SuiteTestFilter filter = SuiteTestFilter.createFrom(filterString);
            String abi = filter.getAbi();
            if (abi == null) {
                for (IAbi a : abis) {
                    addFilter(a.getName(), filter, filters);
                }
            } else {
                addFilter(abi, filter, filters);
            }
        }
    }

    private static void addFilter(
            String abi, SuiteTestFilter filter, Map<String, List<SuiteTestFilter>> filters) {
        getFilterList(filters, AbiUtils.createId(abi, filter.getName())).add(filter);
    }

    private static List<SuiteTestFilter> getFilterList(
            Map<String, List<SuiteTestFilter>> filters, String id) {
        List<SuiteTestFilter> fs = filters.get(id);
        if (fs == null) {
            fs = new ArrayList<>();
            filters.put(id, fs);
        }
        return fs;
    }

    private void addFiltersToTest(IRemoteTest test, IAbi abi, String name) {
        String moduleId = AbiUtils.createId(abi.getName(), name);
        if (!(test instanceof ITestFilterReceiver)) {
            CLog.e("Test in module %s does not implement ITestFilterReceiver.", moduleId);
            return;
        }
        List<SuiteTestFilter> mdIncludes = getFilterList(mIncludeFilters, moduleId);
        List<SuiteTestFilter> mdExcludes = getFilterList(mExcludeFilters, moduleId);
        if (!mdIncludes.isEmpty()) {
            addTestIncludes((ITestFilterReceiver) test, mdIncludes, name);
        }
        if (!mdExcludes.isEmpty()) {
            addTestExcludes((ITestFilterReceiver) test, mdExcludes, name);
        }
    }

    private boolean shouldRunModule(String moduleId) {
        List<SuiteTestFilter> mdIncludes = getFilterList(mIncludeFilters, moduleId);
        List<SuiteTestFilter> mdExcludes = getFilterList(mExcludeFilters, moduleId);
        // if including all modules or includes exist for this module, and there are not excludes
        // for the entire module, this module should be run.
        return (mIncludeAll || !mdIncludes.isEmpty()) && !containsModuleExclude(mdExcludes);
    }

    private void addTestIncludes(
            ITestFilterReceiver test, List<SuiteTestFilter> includes, String name) {
        if (test instanceof ITestFileFilterReceiver) {
            File includeFile = createFilterFile(name, ".include", includes);
            ((ITestFileFilterReceiver) test).setIncludeTestFile(includeFile);
        } else {
            // add test includes one at a time
            for (SuiteTestFilter include : includes) {
                String filterTestName = include.getTest();
                if (filterTestName != null) {
                    test.addIncludeFilter(filterTestName);
                }
            }
        }
    }

    private void addTestExcludes(
            ITestFilterReceiver test, List<SuiteTestFilter> excludes, String name) {
        if (test instanceof ITestFileFilterReceiver) {
            File excludeFile = createFilterFile(name, ".exclude", excludes);
            ((ITestFileFilterReceiver) test).setExcludeTestFile(excludeFile);
        } else {
            // add test excludes one at a time
            for (SuiteTestFilter exclude : excludes) {
                test.addExcludeFilter(exclude.getTest());
            }
        }
    }

    private File createFilterFile(String prefix, String suffix, List<SuiteTestFilter> filters) {
        File filterFile = null;
        PrintWriter out = null;
        try {
            filterFile = FileUtil.createTempFile(prefix, suffix);
            out = new PrintWriter(filterFile);
            for (SuiteTestFilter filter : filters) {
                String filterTest = filter.getTest();
                if (filterTest != null) {
                    out.println(filterTest);
                }
            }
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create filter file");
        } finally {
            StreamUtil.close(out);
        }
        filterFile.deleteOnExit();
        return filterFile;
    }

    /** Returns true iff one or more test filters in excludes apply to the entire module. */
    private boolean containsModuleExclude(Collection<SuiteTestFilter> excludes) {
        for (SuiteTestFilter exclude : excludes) {
            if (exclude.getTest() == null) {
                return true;
            }
        }
        return false;
    }

    /** A {@link FilenameFilter} to find all the config files in a directory. */
    public static class ConfigFilter implements FilenameFilter {

        /** {@inheritDoc} */
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(CONFIG_EXT);
        }
    }

    private void putArgs(List<String> args, Map<String, Map<String, List<String>>> argsMap) {
        for (String arg : args) {
            String[] parts = arg.split(":");
            String target = parts[0];
            String key = parts[1];
            String value = parts[2];
            Map<String, List<String>> map = argsMap.get(target);
            if (map == null) {
                map = new HashMap<>();
                argsMap.put(target, map);
            }
            List<String> valueList = map.get(key);
            if (valueList == null) {
                valueList = new ArrayList<>();
                map.put(key, valueList);
            }
            valueList.add(value);
        }
    }
}
