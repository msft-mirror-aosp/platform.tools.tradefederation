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
package com.android.tradefed.retry;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * A helper class to parse the max test run and test case attempts from the command line options.
 * It parses the options and provides methods to get the max attempts for a given module or the
 * default value.
 *
 * <p>The class is used by {@link BaseRetryDecision} to parse the retry attempts and provide the
 * correct values for each module, as well as provide the command line.
 */
class RetryCountParser {

    private int mDefaultTestRunAttempts;
    private int mDefaultTestCaseAttempts;
    private Map<String, Integer> mTestRunAttemptsMap = new HashMap<>();
    private Map<String, Integer> mTestCaseAttemptsMap = new HashMap<>();

    /**
     * Constructor for RetryCountParser.
     *
     * @param testCaseAttempts A set of strings representing test case attempts,
     *                         e.g., "module1:2", "3".
     * @param testRunAttempts  A set of strings representing test run attempts,
     *                         e.g., "module1:5", "2".
     */
    RetryCountParser(Set<String> testCaseAttempts, Set<String> testRunAttempts) {
        mDefaultTestRunAttempts = parseAttempts(testRunAttempts, mTestRunAttemptsMap, -1);
        mDefaultTestCaseAttempts = parseAttempts(testCaseAttempts, mTestCaseAttemptsMap, 1);
    }

    /**
     * Returns the maximum test run attempts for the given module.
     * If module-specific attempts are not defined, it returns the default test run attempts.
     * If default test run attempts is -1, it returns the max test case attempts for the module.
     *
     * @param module The {@link ModuleDefinition} to get the max attempts for, or null to get the default.
     * @return The maximum test run attempts for the module.
     */
    public int getMaxTestRunAttempts(ModuleDefinition module) {
        int attempts = mDefaultTestRunAttempts;
        if (module != null) {
            attempts = mTestRunAttemptsMap.getOrDefault(module.getId(), attempts);
        }
        return attempts == -1 ? getMaxTestCaseAttempts(module) : attempts;
    }

    /**
     * Returns the maximum test case attempts for the given module.
     *
     * @param module The {@link ModuleDefinition} to get the max attempts for, or null to get the default.
     * @return The maximum test case attempts for the module.
     */
    public int getMaxTestCaseAttempts(ModuleDefinition module) {
        if (module == null) {
            return mDefaultTestCaseAttempts;
        }
        return mTestCaseAttemptsMap.getOrDefault(module.getId(), mDefaultTestCaseAttempts);
    }

    /**
     * Returns a list of command line arguments representing the configured retry attempts.
     *
     * @return A list of strings representing the command line arguments.
     */
    public List<String> getCommandLineArgs() {
        List<String> args = new ArrayList<>();
        mTestCaseAttemptsMap.forEach((module, attempts) -> {
            args.add("--max-testcase-run-count");
            args.add(module + ":" + attempts);
        });
        if (mDefaultTestCaseAttempts > 1) {
            args.add("--max-testcase-run-count");
            args.add(Integer.toString(mDefaultTestCaseAttempts));
        }
        mTestRunAttemptsMap.forEach((module, attempts) -> {
            args.add("--max-testrun-run-count");
            args.add(module + ":" + attempts);
        });
        if (mDefaultTestRunAttempts > 0) {
            args.add("--max-testrun-run-count");
            args.add(Integer.toString(mDefaultTestRunAttempts));
        }
        return args;
    }

    /**
     * Parses a set of strings representing how many retry attempts to use for each module.
     *
     * @param attempts A set of strings representing limits on retries, e.g., "module1:2", "3".
     * @param attemptsMap A map to store module-specific attempts.
     * @param defaultValue The default value to use if no default is specified in the attempts set.
     * @return The default retry attempts.
     */
    private int parseAttempts(
            Set<String> attempts, Map<String, Integer> attemptsMap, int defaultValue) {
        int defaultAttempts = defaultValue;
        for (String entry : attempts) {
            try {
                String[] parts = entry.split(":", 2); // Split into at most 2 parts
                if (parts.length == 2) {
                    attemptsMap.put(parts[0], Integer.parseInt(parts[1]));
                } else if (parts.length == 1) {
                    defaultAttempts = Integer.parseInt(parts[0]);
                }
            } catch (NumberFormatException e) {
                CLog.e("Failed to parse module run count entry: %s", entry);
            }
        }
        return defaultAttempts;
    }
}