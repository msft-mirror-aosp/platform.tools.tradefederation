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

import com.android.tradefed.config.ArgsOptionParser;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StringEscapeUtils;
import com.android.tradefed.util.SystemUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A class for test launcher to call the TradeFed jar that packaged in the test suite to discover
 * test modules.
 *
 * <p>TestDiscoveryInvoker will take {@link IConfiguration} and the test root directory from the
 * launch control provider to make the launch control provider to invoke the workflow to use the
 * config to query the packaged TradeFed jar file in the test suite root directory to retrieve test
 * module names.
 */
public class TestDiscoveryInvoker {

    private final IConfiguration mConfiguration;
    private final File mRootDir;
    public static final String TRADEFED_OBSERVATORY_ENTRY_PATH =
            TestDiscoveryExecutor.class.getName();
    public static final String TEST_DEPENDENCIES_LIST_KEY = "TestDependencies";

    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    public TestDiscoveryInvoker(IConfiguration config, File rootDir) {
        mConfiguration = config;
        mRootDir = rootDir;
    }

    /**
     * Retrieve a list of test module names by using Tradefed Observatory.
     *
     * @return A list of test module names.
     * @throws IOException
     * @throws JSONException
     * @throws ConfigurationException
     */
    public List<String> discoverTestModuleNames()
            throws IOException, JSONException, ConfigurationException {
        List<String> testModuleNames = new ArrayList<>();
        // Build the classpath base on test root directory which should contain all the jars
        String classPath = buildClasspath(mRootDir);
        // Build command line args to query the tradefed.jar in the root directory
        List<String> args = buildJavaCmdForXtsDiscovery(classPath);
        String[] subprocessArgs = args.toArray(new String[args.size()]);
        CommandResult res = getRunUtil().runTimedCmd(20000, subprocessArgs);
        if (res.getExitCode() != 0 || !res.getStatus().equals(CommandStatus.SUCCESS)) {
            CLog.e(
                    "Tradefed observatory error, unable to discovery test module names. command"
                            + " used: %s error: %s",
                    Joiner.on(" ").join(subprocessArgs), res.getStderr());
            return testModuleNames;
        }
        String stdout = res.getStdout();
        CLog.i(String.format("Tradefed Observatory returned in stdout: %s", stdout));
        testModuleNames.addAll(parseTestModules(stdout));
        return testModuleNames;
    }

    /**
     * Build java cmd for invoking a subprocess to discover test module names.
     *
     * @return A list of java command args.
     * @throws ConfigurationException
     */
    private List<String> buildJavaCmdForXtsDiscovery(String classpath)
            throws ConfigurationException {
        List<String> fullCommandLineArgs =
                new ArrayList<String>(
                        Arrays.asList(
                                QuotationAwareTokenizer.tokenizeLine(
                                        mConfiguration.getCommandLine())));
        // first arg is config name
        final String testLauncherConfigName = fullCommandLineArgs.remove(0);

        final ConfigurationCtsParserSettings ctsParserSettings =
                new ConfigurationCtsParserSettings();
        ArgsOptionParser ctsOptionParser = null;
        ctsOptionParser = new ArgsOptionParser(ctsParserSettings);

        // Parse to collect all values of --cts-params as well config name
        ctsOptionParser.parseBestEffort(fullCommandLineArgs, true);

        List<String> ctsParams = ctsParserSettings.mCtsParams;
        String configName = ctsParserSettings.mConfigName;

        if (configName == null) {
            throw new ConfigurationException(
                    String.format(
                            "Failed to extract config-name from parent test command options,"
                                    + " unable to build args to invoke tradefed observatory. Parent"
                                    + " test command options is: %s",
                            fullCommandLineArgs));
        }
        List<String> args = new ArrayList<>();
        args.add(SystemUtil.getRunningJavaBinaryPath().getAbsolutePath());

        args.add("-cp");
        args.add(classpath);

        // Cts V2 requires CTS_ROOT to be set or VTS_ROOT for vts run
        args.add(
                String.format(
                        "-D%s=%s", ctsParserSettings.mRootdirVar, mRootDir.getAbsolutePath()));

        args.add(TRADEFED_OBSERVATORY_ENTRY_PATH);
        args.add(configName);

        // Tokenize args to be passed to CtsTest/XtsTest
        args.addAll(StringEscapeUtils.paramsToArgs(ctsParams));

        return args;
    }

    /**
     * Build the classpath string based on jars in the test root directory's tools folder.
     *
     * @return A string of classpaths.
     * @throws IOException
     */
    private String buildClasspath(File ctsRoot) throws IOException {
        List<File> classpathList = new ArrayList<>();

        if (!ctsRoot.exists()) {
            throw new FileNotFoundException("Couldn't find the build directory: " + ctsRoot);
        }

        // Safe to assume single dir from extracted zip
        if (ctsRoot.list().length != 1) {
            throw new RuntimeException(
                    "List of sub directory does not contain only one item "
                            + "current list is:"
                            + Arrays.toString(ctsRoot.list()));
        }
        String mainDirName = ctsRoot.list()[0];
        // Jar files from the downloaded cts/xts
        File jarCtsPath = new File(new File(ctsRoot, mainDirName), "tools");
        if (jarCtsPath.listFiles().length == 0) {
            throw new FileNotFoundException(
                    String.format(
                            "Could not find any files under %s", jarCtsPath.getAbsolutePath()));
        }
        for (File toolsFile : jarCtsPath.listFiles()) {
            if (toolsFile.getName().endsWith(".jar")) {
                classpathList.add(toolsFile);
            }
        }
        Collections.sort(classpathList);

        return Joiner.on(":").join(classpathList);
    }

    /**
     * Parse test module names from the tradefed observatory's output JSON string.
     *
     * @return A list of test module names.
     * @throws JSONException
     */
    private List<String> parseTestModules(String discoveryOutput) throws JSONException {
        JSONObject jsonObject = new JSONObject(discoveryOutput);
        List<String> testModules = new ArrayList<>();
        JSONArray jsonArray = jsonObject.getJSONArray(TEST_DEPENDENCIES_LIST_KEY);
        for (int i = 0; i < jsonArray.length(); i++) {
            testModules.add(jsonArray.getString(i));
        }
        return testModules;
    }
}
