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

import com.android.tradefed.util.AbiUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Represents a filter for including and excluding tests. */
public class SuiteTestFilter {

    private final Integer mShardIndex;
    private final String mAbi;
    private final String mName;
    private final String mTest;

    private static final Pattern PARAMETERIZED_TEST_REGEX = Pattern.compile("(.*)?\\[(.*)\\]$");

    /**
     * Builds a new {@link SuiteTestFilter} from the given string. Filters can be in one of four
     * forms, the instance will be initialized as; -"name" -> abi = null, name = "name", test = null
     * -"name" "test..." -> abi = null, name = "name", test = "test..." -"abi" "name" -> abi =
     * "abi", name = "name", test = null -"abi" "name" "test..." -> abi = "abi", name = "name", test
     * = "test..."
     *
     * <p>Test identifier can contain multiple parts, eg parameterized tests.
     *
     * @param filter the filter to parse
     * @return the {@link SuiteTestFilter}
     */
    public static SuiteTestFilter createFrom(String filter) {
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("Filter was empty");
        }
        String[] parts = filter.split(" +");
        Integer shardIndex = null;
        String abi = null, name = null, test = null;
        // Either:
        // <name>
        // <name> <test>
        // <abi> <name>
        // <abi> <name> <test>
        if (parts.length == 1) {
            name = parts[0];
        } else {
            int index = 0;
            if (parts[index].startsWith("shard_")) {
                shardIndex = Integer.parseInt(parts[index].substring("shard_".length()));
                index++;
            }
            if (AbiUtils.isAbiSupportedByCompatibility(parts[index])) {
                abi = parts[index];
                index++;
            }
            name = parts[index];
            index++;
            parts = filter.split(" +", index + 1);
            if (parts.length > index) {
                test = parts[index];
            }
        }
        return new SuiteTestFilter(shardIndex, abi, name, test);
    }

    /**
     * Creates a new {@link SuiteTestFilter} from the given parts.
     *
     * @param abi The ABI must be supported {@link AbiUtils#isAbiSupportedByCompatibility(String)}
     * @param name The module's name
     * @param test The test's identifier eg <package>.<class>#<method>
     */
    public SuiteTestFilter(String abi, String name, String test) {
        this(null, abi, name, test);
    }

    /**
     * Creates a new {@link SuiteTestFilter} from the given parts.
     *
     * @param abi The ABI must be supported {@link AbiUtils#isAbiSupportedByCompatibility(String)}
     * @param name The module's name
     * @param test The test's identifier eg <package>.<class>#<method>
     */
    public SuiteTestFilter(Integer shardIndex, String abi, String name, String test) {
        mShardIndex = shardIndex;
        mAbi = abi;
        mName = name;
        mTest = test;
    }

    /**
     * Returns a String representation of this filter. This function is the inverse of {@link
     * SuiteTestFilter#createFrom(String)}.
     *
     * <p>For a valid filter f;
     *
     * <pre>{@code
     * new TestFilter(f).toString().equals(f)
     * }</pre>
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (mShardIndex != null) {
            sb.append(mShardIndex.toString());
            sb.append(" ");
        }
        if (mAbi != null) {
            sb.append(mAbi.trim());
            sb.append(" ");
        }
        if (mName != null) {
            sb.append(mName.trim());
        }
        if (mTest != null) {
            sb.append(" ");
            sb.append(mTest.trim());
        }
        return sb.toString();
    }

    /** Returns the shard index of the test, or null if not specified. */
    public Integer getShardIndex() {
        return mShardIndex;
    }

    /** @return the abi of this filter, or null if not specified. */
    public String getAbi() {
        return mAbi;
    }

    /** @return the module name of this filter, or null if not specified. */
    public String getName() {
        return mName;
    }

    /**
     * Returns the base name of the module without any parameterization. If not parameterized, it
     * will return {@link #getName()};
     */
    public String getBaseName() {
        // If the module looks parameterized, return the base non-parameterized name.
        Matcher m = PARAMETERIZED_TEST_REGEX.matcher(mName);
        if (m.find()) {
            return m.group(1);
        }
        return mName;
    }

    /** @return the test identifier of this filter, or null if not specified. */
    public String getTest() {
        return mTest;
    }
}
