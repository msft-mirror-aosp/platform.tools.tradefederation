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

import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFileFilterReceiver;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interface for providing exclude filter functionality.
 */
interface ExcludeFilterDelegate {
    /**
     * Initializes the exclude filters.
     */
    void initFilters();

    /**
     * Resets the exclude filters to their default state.
     */
    void resetDefaultFilters();

    /**
     * Adds the given filters to the exclude filters.
     *
     * @param filters The set of filters to add.
     */
    void addExcludeFilters(Set<String> filters);
}

/**
 * Provides exclude filters for {@link ITestFileFilterReceiver} tests.
 */
class FileExcludeFilterDelegate implements ExcludeFilterDelegate {
    private File mDefaultFilterFile;
    private ITestFileFilterReceiver mTest;

    /**
     * Creates a new {@link FileExcludeFilterDelegate}.
     *
     * @param test The test to manage filters for.
     */
    public FileExcludeFilterDelegate(ITestFileFilterReceiver test) {
        mTest = test;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initFilters() {
        mDefaultFilterFile = mTest.getExcludeTestFile();
        try {
            mTest.setExcludeTestFile(FileUtil.createTempFile("exclude-filter", ".txt"));
        } catch (IOException e) {
            throw new HarnessRuntimeException(
                    e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetDefaultFilters() {
        File excludeFilterFile = mTest.getExcludeTestFile();
        if (mDefaultFilterFile != null) {
            try {
                FileUtil.copyFile(mDefaultFilterFile, excludeFilterFile);
            } catch (IOException e) {
                throw new HarnessRuntimeException(
                        e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addExcludeFilters(Set<String> filters) {
        File excludeFilterFile = mTest.getExcludeTestFile();
        String content = filters.stream().collect(Collectors.joining("\n", "", "\n"));
        try {
            FileUtil.writeToFile(content, excludeFilterFile, true);
        } catch (IOException e) {
            throw new HarnessRuntimeException(
                    e.getMessage(), e, InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }
}

/**
 * Provides exclude filters for {@link ITestFilterReceiver} tests.
 */
class ListExcludeFilterDelegate implements ExcludeFilterDelegate {
    private Set<String> mDefaultExcludeFilters;
    private ITestFilterReceiver mTest;

    /**
     * Creates a new {@link ListExcludeFilterDelegate}.
     *
     * @param test The test to manage filters for.
     */
    public ListExcludeFilterDelegate(ITestFilterReceiver test) {
        mTest = test;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initFilters() {
        mDefaultExcludeFilters = new HashSet<>();
        if (mTest.getExcludeFilters() != null) {
            mDefaultExcludeFilters.addAll(mTest.getExcludeFilters());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetDefaultFilters() {
        mTest.clearExcludeFilters();
        mTest.addAllExcludeFilters(mDefaultExcludeFilters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addExcludeFilters(Set<String> filters) {
        mTest.addAllExcludeFilters(filters);
    }
}

/**
 * A helper class to manage exclude filters for a given test.
 *
 * <p>This class is responsible for initializing, adding, and resetting exclude filters for a test.
 * It supports both {@link ITestFilterReceiver} and {@link ITestFileFilterReceiver} tests.
 *
 * <p>This class is used by {@link BaseRetryDecision} to manage exclude filters for tests that are
 * being retried. It initializes the exclude filters for a test, adds new exclude filters based on
 * the failed tests from previous runs, and resets the filters to their original state after each
 * retry attempt.
 */
class ExcludeFilterManager {
    private ExcludeFilterDelegate mExcludeFilterDelegate;
    private boolean mInitialized = false;

    /**
     * Creates a new {@link ExcludeFilterManager}.
     *
     * @param test The test to manage exclude filters for.
     */
    ExcludeFilterManager(IRemoteTest test) {
        // ITestFileFilterReceiver is preferred due to bugs in parameterized exclusion (b/192510082)
        if (test instanceof ITestFileFilterReceiver) {
            mExcludeFilterDelegate = new FileExcludeFilterDelegate((ITestFileFilterReceiver) test);
        } else if (test instanceof ITestFilterReceiver) {
            mExcludeFilterDelegate = new ListExcludeFilterDelegate((ITestFilterReceiver) test);
        }
    }

    /**
     * Adds exclude filters for the given set of test descriptions.
     *
     * @param excludeFilters The set of test descriptions to exclude.
     */
    public void addExcludeFilters(Set<TestDescription> excludeFilters) {
        if (!mInitialized) {
            mExcludeFilterDelegate.initFilters();
            mInitialized = true;
        }
        var filters = new HashSet<String>();
        for (var testCase : excludeFilters) {
            filters.add(String.format("%s#%s", testCase.getClassName(), testCase.getTestName()));
        }
        mExcludeFilterDelegate.addExcludeFilters(filters);
    }

    /**
     * Resets the exclude filters to their default state.
     */
    public void resetDefaultFilters() {
        if (!mInitialized) {
            mExcludeFilterDelegate.initFilters();
            mInitialized = true;
        }
        mExcludeFilterDelegate.resetDefaultFilters();
    }
}