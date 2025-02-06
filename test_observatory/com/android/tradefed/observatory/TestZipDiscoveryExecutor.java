/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.ddmlib.DdmPreferences;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.invoker.tracing.ActiveTrace;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.invoker.tracing.TracingLogger;
import com.android.tradefed.log.Log;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.StdoutLogger;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.sandbox.TradefedSandbox;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.util.FileUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A class for getting test zipsfor a given command line args.
 *
 * <p>TestZipDiscoveryExecutor will consume the command line args and print test zip regexes for the
 * caller to receive and parse it.
 *
 * <p>
 */
public class TestZipDiscoveryExecutor {

    private static TestDiscoveryUtil mTestDiscoveryUtil;

    public TestZipDiscoveryExecutor() {
        mTestDiscoveryUtil = new TestDiscoveryUtil();
    }

    public TestZipDiscoveryExecutor(TestDiscoveryUtil testDiscoveryUtil) {
        mTestDiscoveryUtil = testDiscoveryUtil;
    }

    /**
     * Discover test zips base on command line args.
     *
     * @param args the command line args of the test.
     * @return A JSON string with one test zip regex array.
     */
    public String discoverTestZips(String[] args)
            throws TestDiscoveryException, ConfigurationException, JSONException {
        if (!mTestDiscoveryUtil.isTradefedConfiguration(args)) {
            return nonTradefedDiscovery(args);
        }
        if (mTestDiscoveryUtil.hasOutputResultFile()) {
            DdmPreferences.setLogLevel(Log.LogLevel.VERBOSE.getStringValue());
            Log.setLogOutput(LogRegistry.getLogRegistry());
            StdoutLogger logger = new StdoutLogger();
            logger.setLogLevel(Log.LogLevel.VERBOSE);
            LogRegistry.getLogRegistry().registerLogger(logger);
        }
        boolean reportNoPossibleDiscovery = true;
        // Create IConfiguration base on command line args.
        IConfiguration config = mTestDiscoveryUtil.getConfiguration(args);
        try {
            // Get tests from the configuration.
            List<IRemoteTest> tests = config.getTests();

            // Tests could be empty if input args are corrupted.
            if (tests == null || tests.isEmpty()) {
                throw new TestDiscoveryException(
                        "Tradefed Observatory discovered no tests from the IConfiguration created"
                                + " from command line args.",
                        null,
                        DiscoveryExitCode.ERROR);
            }

            Set<String> testZipRegexSet = new LinkedHashSet<>();
            SandboxOptions sandboxOptions = null;

            // If sandbox is in use, we always need to download the tradefed zip.
            if (config.getCommandOptions().shouldUseSandboxing()
                    || config.getCommandOptions().shouldUseRemoteSandboxMode()) {
                // Report targets for compatibility with build commands names
                testZipRegexSet.add("tradefed.zip");
                testZipRegexSet.add("tradefed-all.zip");
                testZipRegexSet.add("google-tradefed.zip");
                testZipRegexSet.add("google-tradefed-all.zip");
                reportNoPossibleDiscovery = false;
            }

            if (config.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME) != null) {
                sandboxOptions = (SandboxOptions) config.getConfigurationObject(
                        Configuration.SANBOX_OPTIONS_TYPE_NAME);
            }

            // Retrieve the value of option --sandbox-tests-zips
            if (sandboxOptions != null) {
                testZipRegexSet.addAll(sandboxOptions.getTestsZips());
                reportNoPossibleDiscovery = false;
            }

            List<IDeviceConfiguration> list = config.getDeviceConfig();

            if (list != null && list.size() > 0) {
                for (IDeviceConfiguration deviceConfiguration : list) {
                    // Attempt to retrieve test zip filters from the build provider."
                    if (deviceConfiguration.getBuildProvider() instanceof IDiscoverDependencies) {
                        Set<String> testZipFileFilters =
                                ((IDiscoverDependencies) deviceConfiguration.getBuildProvider())
                                        .reportTestZipFileFilter();
                        if (testZipFileFilters != null) {
                            testZipRegexSet.addAll(testZipFileFilters);
                        }
                        reportNoPossibleDiscovery = false;
                    }
                }
            }

            for (IRemoteTest test : tests) {
                // For test mapping suite, match the corresponding test zip by test config name.
                // Suppress the extra target if sandbox is not downloading the default zip.
                if (test instanceof ITestSuite && sandboxOptions != null
                        && sandboxOptions.getTestsZips().isEmpty()
                        && sandboxOptions.downloadDefaultZips()) {
                    testZipRegexSet.addAll(
                            TradefedSandbox.matchSandboxExtraBuildTargetByConfigName(
                                    config.getName()));
                    reportNoPossibleDiscovery = false;
                }
            }

            if (testZipRegexSet.contains(null)) {
                throw new TestDiscoveryException(
                        String.format(
                                "Tradefed Observatory discovered null test zip regex. This is"
                                    + " likely due to a corrupted discovery result. Test config:"
                                    + " %s",
                                config.getName()),
                        null,
                        DiscoveryExitCode.DISCOVERY_RESULTS_CORREPUTED);
            }

            return formatResults(reportNoPossibleDiscovery, testZipRegexSet);
        } finally {
            if (mTestDiscoveryUtil.hasOutputResultFile()) {
                LogRegistry.getLogRegistry().unregisterLogger();
            }
        }
    }

    private String formatResults(boolean reportNoPossibleDiscovery, Set<String> zipRegex)
            throws JSONException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("format_results")) {
            JSONObject j = new JSONObject();
            j.put(TestDiscoveryInvoker.TEST_ZIP_REGEXES_LIST_KEY, new JSONArray(zipRegex));
            if (reportNoPossibleDiscovery) {
                j.put(TestDiscoveryInvoker.NO_POSSIBLE_TEST_DISCOVERY_KEY, "true");
            }
            return j.toString();
        }
    }

    /** Centralize all the logic to handle non-Tradefed command discovery and assumptions. */
    private String nonTradefedDiscovery(String[] args) throws JSONException {
        Set<String> testsZipRegex = new LinkedHashSet<String>();
        // special setup by camera team
        if ("unused".equals(args[0])) {
            for (String arg : args) {
                if (arg.contains("liblyric")) {
                    testsZipRegex.add("camera-hal-tests.zip");
                }
            }
            return formatResults(false, testsZipRegex);
        }
        for (String arg : args) {
            if (arg.contains("haiku")) {
                testsZipRegex.add("haiku-presubmit");
            }
            // TODO(b/382159415): Update to the dedicated mobly zip
            if (arg.contains("mobly")) {
                testsZipRegex.add("general-tests.zip");
            }
        }
        return formatResults(false, testsZipRegex);
    }

    /**
     * A TradeFederation entry point that will use command args to discover test zip information.
     *
     * <p>Intended for use with BWYN in Android CI build optimization.
     *
     * <p>Will only exit with 0 when successfully discovered test zips.
     *
     * <p>Expected arguments: [commands options] (config to run)
     */
    public static void main(String[] args) {
        long pid = ProcessHandle.current().pid();
        long tid = Thread.currentThread().getId();
        ActiveTrace trace = TracingLogger.createActiveTrace(pid, tid);
        trace.startTracing(false);
        DiscoveryExitCode exitCode = DiscoveryExitCode.SUCCESS;
        TestZipDiscoveryExecutor testZipDiscoveryExecutor = new TestZipDiscoveryExecutor();
        try (CloseableTraceScope ignored = new CloseableTraceScope("main_discovery")) {
            String testModules = testZipDiscoveryExecutor.discoverTestZips(args);
            if (mTestDiscoveryUtil.hasOutputResultFile()) {
                FileUtil.writeToFile(
                        testModules, new File(System.getenv(TestDiscoveryInvoker.OUTPUT_FILE)));
            }
            System.out.print(testModules);
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
}
