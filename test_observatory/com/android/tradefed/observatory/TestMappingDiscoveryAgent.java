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

package com.android.tradefed.observatory;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.tracing.ActiveTrace;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.invoker.tracing.TracingLogger;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.FileUtil;

import com.google.common.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class serves as entry point for BWYN script to call by java command to do setup works for
 * TestDiscoveryInvoker.
 *
 * <p>TestMappingDiscoveryAgent will consume the command line args, set the path to
 * test_mapping.zip, pass down output file info and use TestDiscoveryInvoker to do test mapping test
 * discovery.
 *
 * <p>
 */
public class TestMappingDiscoveryAgent {

    private TestDiscoveryInvoker mTestDiscoveryInvoker;

    private static final Set<String> VALIDATION_TEST_CLASS_NAMES =
            new HashSet<String>(
                    Arrays.asList(
                            "com.android.tradefed.presubmit.DeviceTestsConfigValidation",
                            "com.android.tradefed.presubmit.GeneralTestsConfigValidation"));

    private static final Set<String> TEST_MAPPING_VALIDATION_TEST_CLASS_NAMES =
            new HashSet<String>(
                    Arrays.asList("com.android.tradefed.presubmit.TestMappingsValidation"));

    private static final String VALIDATION_TEST_DISCOVERED_CASE_COMMENT =
            "Discovered Validation Test";

    private boolean isValidationTestDiscovered = false;

    private TestMappingDiscoveryAgent() {
        // Private constructor - prevents external instantiation.
        // This class should only serve as an entry point, and should not be used anywhere inside
        // TradeFed code.
        mTestDiscoveryUtil = new TestDiscoveryUtil();
    }

    @VisibleForTesting
    public TestMappingDiscoveryAgent(
            TestDiscoveryInvoker testDiscoveryInvoker, TestDiscoveryUtil testDiscoveryUtil) {
        mTestDiscoveryInvoker = testDiscoveryInvoker;
        mTestDiscoveryUtil = testDiscoveryUtil;
    }

    public static Set<String> getTestMappingValidationTestClassNames() {
        return TEST_MAPPING_VALIDATION_TEST_CLASS_NAMES;
    }

    public static Set<String> getValidationTestClassNames() {
        return VALIDATION_TEST_CLASS_NAMES;
    }

    private static TestDiscoveryUtil mTestDiscoveryUtil;

    /**
     * Entry point from test suite build script, discover test modules from test mapping test.
     *
     * @param args the command line args of the test.
     */
    public static void main(String[] args) {
        long pid = ProcessHandle.current().pid();
        long tid = Thread.currentThread().getId();
        ActiveTrace trace = TracingLogger.createActiveTrace(pid, tid);
        trace.startTracing(false);
        DiscoveryExitCode exitCode = DiscoveryExitCode.SUCCESS;
        try (CloseableTraceScope ignored = new CloseableTraceScope("main_discovery")) {
            TestMappingDiscoveryAgent testMappingDiscoveryAgent = new TestMappingDiscoveryAgent();
            testMappingDiscoveryAgent.discoverTestMapping(args);
        } catch (TestDiscoveryException e) {
            System.err.print(e.getMessage());
            if (e.exitCode() != null) {
                exitCode = e.exitCode();
            } else {
                exitCode = DiscoveryExitCode.ERROR;
            }
        } catch (ConfigurationException e) {
            System.err.print(e.getMessage());
            exitCode = DiscoveryExitCode.CONFIGURATION_EXCEPTION;
        } catch (Exception e) {
            System.err.print(e.getMessage());
            exitCode = DiscoveryExitCode.ERROR;
        }
        File traceFile = trace.finalizeTracing();
        if (traceFile != null) {
            if (System.getenv(TestDiscoveryInvoker.DISCOVERY_TRACE_FILE) != null) {
                try {
                    FileUtil.copyFile(
                            traceFile,
                            new File(System.getenv(TestDiscoveryInvoker.DISCOVERY_TRACE_FILE)));
                } catch (IOException | RuntimeException e) {
                    System.err.print(e.getMessage());
                }
            }
            FileUtil.deleteFile(traceFile);
        }
        System.exit(exitCode.exitCode());
    }

    /**
     * Do test discovery from a test mapping test config. Write discovery results to the output
     * file. Will write a empty test module list to the output file if the test itself is validation
     * test.
     *
     * @param args the command line args of the test.
     * @throws IOException
     * @throws JSONException
     * @throws ConfigurationException
     * @throws TestDiscoveryException
     */
    public void discoverTestMapping(String[] args)
            throws ConfigurationException, TestDiscoveryException, JSONException, IOException {
        IConfiguration config = mTestDiscoveryUtil.getConfiguration(args);

        if (isValidationTestConfig(config)) {
            System.out.print("Discovered that test config is a validation test.");
            isValidationTestDiscovered = true;
            // For validation tests, output an empty discovery result.
            JSONObject j = new JSONObject();
            j.put(TestDiscoveryInvoker.TEST_MODULES_LIST_KEY, new JSONArray());
            j.put(TestDiscoveryInvoker.TEST_DEPENDENCIES_LIST_KEY, new JSONArray());
            j.put(
                    TestDiscoveryInvoker.TEST_DISCOVERY_COMMENT_KEY,
                    VALIDATION_TEST_DISCOVERED_CASE_COMMENT);
            if (mTestDiscoveryUtil.hasOutputResultFile()) {
                FileUtil.writeToFile(
                        j.toString(), new File(System.getenv(TestDiscoveryInvoker.OUTPUT_FILE)));
            }
        } else {
            String currentDirectoryPath =
                    TestMappingDiscoveryAgent.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .getPath();
            if (mTestDiscoveryInvoker == null) {
                mTestDiscoveryInvoker =
                        new TestDiscoveryInvoker(
                                config,
                                null,
                                new File(currentDirectoryPath).getParentFile(),
                                false,
                                true);
            }
            if (mTestDiscoveryUtil.getTestMappingFilePath() != null) {
                mTestDiscoveryInvoker.setTestMappingZip(
                        new File(mTestDiscoveryUtil.getTestMappingFilePath()));
            }

            // The result reporting happens in the side effect of the discovery method.
            // TestDiscoveryInvoker will create an output file that follows its caller
            // And by deleting the output file it allows the caller (e.g. BWYN script)
            // to read the discovery result.
            mTestDiscoveryInvoker.discoverTestMappingDependencies();
        }
    }

    /**
     * Determine if a test config is validation test.
     *
     * @param configuration the {@link IConfiguration} that the test args generated.
     * @return True if is validation test config. False if not.
     */
    private static boolean isValidationTestConfig(IConfiguration configuration) {
        List<IRemoteTest> testList = configuration.getTests();
        // Validation test will be the only IRemoteTest in the config
        if (testList.size() != 1) {
            return false;
        }
        if (testList.get(0) instanceof IDiscoverTestClasses) {
            IDiscoverTestClasses test = (IDiscoverTestClasses) testList.get(0);
            return test.getClassNames().equals(VALIDATION_TEST_CLASS_NAMES)
                    || test.getClassNames().equals(TEST_MAPPING_VALIDATION_TEST_CLASS_NAMES);
        }
        return false;
    }

    @VisibleForTesting
    public boolean isValidationTestDiscovered() {
        return isValidationTestDiscovered;
    }
}
