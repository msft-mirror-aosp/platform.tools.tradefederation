/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.testtype.mobly;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.AdbUtils;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PythonVirtualenvHelper;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;

import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Host test meant to run a mobly python binary file from the Android Build system (Soong) */
@OptionClass(alias = "mobly-host")
public class MoblyBinaryHostTest
        implements IRemoteTest, IDeviceTest, IBuildReceiver, ITestFilterReceiver {

    private static final String ANDROID_SERIAL_VAR = "ANDROID_SERIAL";
    private static final String MOBLY_TEST_SUMMARY = "test_summary.yaml";
    private static final String LOCAL_CONFIG_FILENAME = "local_config.yaml";

    // TODO(b/159366744): merge this and next options.
    @Option(
            name = "mobly-par-file-name",
            description = "The binary names inside the build info to run.")
    private Set<String> mBinaryNames = new HashSet<>();

    @Option(
            name = "mobly-binaries",
            description = "The full path to a runnable python binary. Can be repeated.")
    private Set<File> mBinaries = new HashSet<>();

    @Option(
            name = "mobly-test-timeout",
            description = "The timeout limit of a single Mobly test binary.",
            isTimeVal = true)
    private long mTestTimeout = 20 * 1000L;

    @Option(
            name = "inject-android-serial",
            description = "Whether or not to pass an ANDROID_SERIAL variable to the process.")
    private boolean mInjectAndroidSerialVar = true;

    @Option(
            name = "mobly-options",
            description = "Option string to be passed to the binary when running")
    private List<String> mTestOptions = new ArrayList<>();

    @Option(
            name = "mobly-config-file-name",
            description =
                    "Mobly config file name. If set, will append '--config=<config file"
                            + " path>' to the command for running binary.")
    private String mConfigFileName;

    @Option(
            name = "mobly-wildcard-config",
            description =
                    "Use wildcard config. If set and 'mobly-config-file-name' is not set, use"
                            + " wildcard config with all allocted devices.")
    private boolean mWildcardConfig = true;

    @Option(
            name = "test-bed",
            description =
                    "Name of the test bed to run the tests."
                            + "If set, will append '--test_bed=<test bed name>' to the command for "
                            + "running binary.")
    private String mTestBed;

    @Option(name = "mobly-std-log", description = "Print mobly logs to standard outputs")
    private boolean mStdLog = false;

    private ITestDevice mDevice;
    private IBuildInfo mBuildInfo;
    private File mLogDir;
    private TestInformation mTestInfo;
    private IRunUtil mRunUtil;
    private Set<String> mIncludeFilters = new LinkedHashSet<>();
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    /** {@inheritDoc} */
    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void clearIncludeFilters() {
        mIncludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getIncludeFilters() {
        return mIncludeFilters;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getExcludeFilters() {
        return mExcludeFilters;
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public final void run(TestInformation testInfo, ITestInvocationListener listener) {
        mTestInfo = testInfo;
        mBuildInfo = mTestInfo.getBuildInfo();
        mDevice = mTestInfo.getDevice();
        List<File> parFilesList = findParFiles(listener);
        File venvDir = mBuildInfo.getFile("VIRTUAL_ENV");
        if (venvDir != null && venvDir.exists()) {
            PythonVirtualenvHelper.activate(getRunUtil(), venvDir);
        } else {
            CLog.d("No virtualenv configured.");
        }
        for (File parFile : parFilesList) {
            // TODO(b/159365341): add a failure reporting for nonexistent binary.
            if (!parFile.exists()) {
                CLog.d(
                        "ignoring %s which doesn't look like a test file.",
                        parFile.getAbsolutePath());
                continue;
            }
            parFile.setExecutable(true);
            try {
                runSingleParFile(parFile.getAbsolutePath(), parFile.getName(), listener);
            } finally {
                reportLogs(getLogDir(), listener);
            }
        }
    }

    private List<File> findParFiles(ITestInvocationListener listener) {
        // TODO(b/159369297): make naming and log message more "mobly".
        List<File> files = new ArrayList<>();
        for (String binaryName : mBinaryNames) {
            File res = null;
            // search tests dir
            try {
                res = mTestInfo.getDependencyFile(binaryName, /* targetFirst */ false);
                files.add(res);
            } catch (FileNotFoundException e) {
                reportFailure(
                        listener, binaryName, "Couldn't find Mobly test binary " + binaryName);
            }
        }
        files.addAll(mBinaries);
        return files;
    }

    private void runSingleParFile(
            String parFilePath, String runName, ITestInvocationListener listener) {
        if (mInjectAndroidSerialVar) {
            getRunUtil().setEnvVariable(ANDROID_SERIAL_VAR, getDevice().getSerialNumber());
        }
        AdbUtils.updateAdb(mTestInfo, getRunUtil(), getAdbPath());
        String configPath = null;
        if (mConfigFileName != null || mWildcardConfig) {
            try {
                File configFile = null;
                if (mConfigFileName != null) {
                    configFile =
                            mTestInfo.getDependencyFile(mConfigFileName, /* targetFirst */ false);
                }
                configPath = updateTemplateConfigFile(configFile, mWildcardConfig);
            } catch (FileNotFoundException e) {
                reportFailure(
                        listener, runName, "Couldn't find Mobly config file " + mConfigFileName);
                return;
            }
        }
        CommandResult list_result =
                getRunUtil().runTimedCmd(6000, parFilePath, "--", "--list_tests");
        if (!CommandStatus.SUCCESS.equals(list_result.getStatus())) {
            String message;
            if (CommandStatus.TIMED_OUT.equals(list_result.getStatus())) {
                message = "Unable to list tests from the python binary: Timed out";
            } else {
                message =
                        "Unable to list tests from the python binary\nstdout: "
                                + list_result.getStdout()
                                + "\nstderr: "
                                + list_result.getStderr();
            }
            reportFailure(listener, runName, message);
            return;
        }
        // Compute all tests.
        final String[] all_tests =
                Arrays.stream(list_result.getStdout().split(System.lineSeparator()))
                        .filter(line -> !line.startsWith("==========>"))
                        .toArray(String[]::new);
        Stream<String> includedTests = Arrays.stream(all_tests);
        // Process include filters.
        String[] includeFilters =
                getIncludeFilters().stream()
                        .map(filter -> filter.replace("#", "."))
                        .toArray(String[]::new);
        if (includeFilters.length > 0) {
            String invalidIncludeFilters =
                    Arrays.stream(includeFilters)
                            .filter(
                                    filter ->
                                            !Arrays.stream(all_tests)
                                                    .anyMatch(test -> test.startsWith(filter)))
                            .collect(Collectors.joining(", "));
            if (!invalidIncludeFilters.isEmpty()) {
                reportFailure(
                        listener,
                        runName,
                        "Invalid include filters: [" + invalidIncludeFilters + "]");
                return;
            }
            includedTests =
                    includedTests.filter(
                            test ->
                                    Arrays.stream(includeFilters)
                                            .anyMatch(filter -> test.startsWith(filter)));
        }
        // Process exclude filters.
        String[] excludeFilters =
                getExcludeFilters().stream()
                        .map(filter -> filter.replace("#", "."))
                        .toArray(String[]::new);
        if (excludeFilters.length > 0) {
            String invalidExcludeFilters =
                    Arrays.stream(excludeFilters)
                            .filter(
                                    filter ->
                                            !Arrays.stream(all_tests)
                                                    .anyMatch(test -> test.equals(filter)))
                            .collect(Collectors.joining(", "));
            if (!invalidExcludeFilters.isEmpty()) {
                reportFailure(
                        listener,
                        runName,
                        "Invalid exclude filters: [" + invalidExcludeFilters + "]");
                return;
            }
            includedTests =
                    includedTests.filter(
                            test ->
                                    !Arrays.stream(excludeFilters)
                                            .anyMatch(filter -> test.equals(filter)));
        }
        // Collect final filtered tests list.
        List<String> tests = includedTests.collect(Collectors.toList());
        // Start run.
        long startTime = System.currentTimeMillis();
        listener.testRunStarted(runName, tests.size());
        // No test to run, abort early.
        if (tests.isEmpty()) {
            listener.testRunEnded(0, new HashMap<String, String>());
            return;
        }
        // Do not pass tests to command line if all included.
        if (tests.size() == all_tests.length) {
            tests.clear();
        }
        String[] command = buildCommandLineArray(parFilePath, configPath, tests);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<CommandResult> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            if (isStdLogging()) {
                                return getRunUtil()
                                        .runTimedCmd(
                                                getTestTimeout(), System.out, System.err, command);
                            }
                            return getRunUtil().runTimedCmd(getTestTimeout(), command);
                        },
                        executor);
        MoblyYamlResultParser parser = new MoblyYamlResultParser(listener);
        File yamlSummaryFile = null;
        InputStream inputStream = null;
        boolean runFailed = false;
        while (!future.isDone() && yamlSummaryFile == null) {
            yamlSummaryFile = FileUtil.findFile(getLogDir(), MOBLY_TEST_SUMMARY);
            if (yamlSummaryFile != null) {
                try {
                    inputStream = new FileInputStream(yamlSummaryFile);
                } catch (FileNotFoundException ex) {
                    listener.testRunFailed(ex.toString());
                    runFailed = true;
                }
            }
        }
        if (inputStream != null) {
            while (!future.isDone()) processYamlTestResults(inputStream, parser, listener, runName);
            if (!processYamlTestResults(inputStream, parser, listener, runName))
                CLog.e("Did not get a complete summary file from python binary.");
            runFailed = parser.getRunFailed();
            StreamUtil.close(inputStream);
        }
        try {
            CommandResult result = future.get();
            if (!CommandStatus.SUCCESS.equals(result.getStatus()) && !runFailed)
                listener.testRunFailed(result.getStderr());
        } catch (InterruptedException ex) {
            listener.testRunFailed(ex.toString());
        } catch (ExecutionException ex) {
            listener.testRunFailed(ex.toString());
        }
        executor.shutdownNow();
        listener.testRunEnded(
                System.currentTimeMillis() - startTime, new HashMap<String, String>());
    }

    /**
     * Parses Mobly test results and does result reporting.
     *
     * @param inputStream An InputStream object reading in Mobly test result file.
     * @param parser An MoblyYamlResultParser object that processes Mobly test results.
     * @param listener An ITestInvocationListener instance that does various reporting.
     * @param runName str, the name of the Mobly test binary run.
     */
    @VisibleForTesting
    protected boolean processYamlTestResults(
            InputStream inputStream,
            MoblyYamlResultParser parser,
            ITestInvocationListener listener,
            String runName) {
        try {
            return parser.parse(inputStream);
        } catch (MoblyYamlResultHandlerFactory.InvalidResultTypeException
                | IOException
                | IllegalAccessException
                | InstantiationException ex) {
            CLog.e("Failed to parse the result file.\n" + ex);
            return false;
        }
    }

    private String updateTemplateConfigFile(File templateConfig, boolean wildcardConfig)
            throws HarnessRuntimeException {
        InputStream inputStream = null;
        FileWriter fileWriter = null;
        File localConfigFile = new File(getLogDir(), "local_config.yaml");
        try {
            if (templateConfig != null) {
                inputStream = new FileInputStream(templateConfig);
            } else {
                String configString =
                        "TestBeds:\n"
                                + "- Name: TestBed\n"
                                + "  Controllers:\n"
                                + "    AndroidDevice: '*'\n";
                inputStream = new ByteArrayInputStream(configString.getBytes());
            }
            fileWriter = new FileWriter(localConfigFile);
            updateConfigFile(inputStream, fileWriter);
        } catch (IOException ex) {
            throw new RuntimeException("Exception in creating local config file: %s", ex);
        } finally {
            StreamUtil.close(inputStream);
            StreamUtil.close(fileWriter);
        }
        return localConfigFile.getAbsolutePath();
    }

    @VisibleForTesting
    protected void updateConfigFile(InputStream configInputStream, Writer writer)
            throws HarnessRuntimeException {
        Yaml yaml = new Yaml();
        Map<String, Object> configMap = (Map<String, Object>) yaml.load(configInputStream);
        CLog.d("Loaded yaml config: \n%s", configMap);
        List<Object> testBedList = (List<Object>) configMap.get("TestBeds");
        Map<String, Object> targetTb = null;
        if (getTestBed() == null) {
            targetTb = (Map<String, Object>) testBedList.get(0);
        } else {
            for (Object tb : testBedList) {
                Map<String, Object> tbMap = (Map<String, Object>) tb;
                String tbName = (String) tbMap.get("Name");
                if (tbName.equalsIgnoreCase(getTestBed())) {
                    targetTb = tbMap;
                    break;
                }
            }
        }
        if (targetTb == null) {
            throw new HarnessRuntimeException(
                    String.format("Fail to find specified test bed: %s.", getTestBed()),
                    TestErrorIdentifier.UNEXPECTED_MOBLY_BEHAVIOR);
        }

        // Inject serial for devices
        List<ITestDevice> devices = getTestInfo().getDevices();
        Map<String, Object> controllerMap = (Map<String, Object>) targetTb.get("Controllers");
        Object androidDeviceValue = controllerMap.get("AndroidDevice");
        List<Object> androidDeviceList = null;
        if (androidDeviceValue instanceof List) {
            androidDeviceList = (List<Object>) controllerMap.get("AndroidDevice");
            if (devices.size() != androidDeviceList.size()) {
                throw new HarnessRuntimeException(
                        String.format(
                                "Device count mismatch (configured: %s vs allocated: %s)",
                                androidDeviceList.size(), devices.size()),
                        TestErrorIdentifier.UNEXPECTED_MOBLY_BEHAVIOR);
            }

            for (int index = 0; index < devices.size(); index++) {
                Map<String, Object> deviceMap = (Map<String, Object>) androidDeviceList.get(index);
                deviceMap.put("serial", devices.get(index).getSerialNumber());
            }
        } else if ("*".equals(androidDeviceValue)) {
            // Auto-find Android devices - add explicit device list with serials
            androidDeviceList = new ArrayList<>();
            controllerMap.put("AndroidDevice", androidDeviceList);
            for (int index = 0; index < devices.size(); index++) {
                Map<String, String> deviceMap = new HashMap<>();
                androidDeviceList.add(deviceMap);
                deviceMap.put("serial", devices.get(index).getSerialNumber());
            }
        } else if (androidDeviceValue == null) {
            CLog.d("No Android device provided.");
        } else {
            throw new HarnessRuntimeException(
                    String.format("Unsupported value for AndroidDevice: %s", androidDeviceValue),
                    TestErrorIdentifier.UNEXPECTED_MOBLY_BEHAVIOR);
        }

        // Inject log path
        Map<String, Object> paramsMap = (Map<String, Object>) configMap.get("MoblyParams");
        if (paramsMap == null) {
            paramsMap = new HashMap<>();
            configMap.put("MoblyParams", paramsMap);
        }
        paramsMap.put("LogPath", getLogDirAbsolutePath());

        yaml.dump(configMap, writer);
    }

    private File getLogDir() {
        if (mLogDir == null) {
            try {
                mLogDir = FileUtil.createTempDir("host_tmp_mobly");
            } catch (IOException ex) {
                CLog.e("Failed to create temp dir with prefix host_tmp_mobly: %s", ex);
            }
            CLog.d("Mobly log path: %s", mLogDir.getAbsolutePath());
        }
        return mLogDir;
    }

    private void reportFailure(
            ITestInvocationListener listener, String runName, String errorMessage) {
        listener.testRunStarted(runName, 0);
        FailureDescription description =
                FailureDescription.create(errorMessage, FailureStatus.TEST_FAILURE);
        listener.testRunFailed(description);
        listener.testRunEnded(0L, new HashMap<String, Metric>());
    }

    private Set<String> cleanFilters(List<String> filters) {
        Set<String> new_filters = new LinkedHashSet<String>();
        for (String filter : filters) {
            new_filters.add(filter.replace("#", "."));
        }
        return new_filters;
    }

    @VisibleForTesting
    protected String getLogDirAbsolutePath() {
        return getLogDir().getAbsolutePath();
    }

    @VisibleForTesting
    protected File getLogDirFile() {
        return mLogDir;
    }

    @VisibleForTesting
    String getTestBed() {
        return mTestBed;
    }

    @VisibleForTesting
    boolean isStdLogging() {
        return mStdLog;
    }

    @VisibleForTesting
    TestInformation getTestInfo() {
        return mTestInfo;
    }

    @VisibleForTesting
    protected String[] buildCommandLineArray(String filePath, String configPath) {
        return buildCommandLineArray(filePath, configPath, new ArrayList<>());
    }

    protected String[] buildCommandLineArray(
            String filePath, String configPath, List<String> tests) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(filePath);
        // TODO(b/166468397): some test binaries are actually a wrapper of Mobly runner and need --
        //  to separate Python options.
        commandLine.add("--");
        if (configPath != null) {
            commandLine.add("--config=" + configPath);
        }
        if (getTestBed() != null) {
            commandLine.add("--test_bed=" + getTestBed());
        }
        for (ITestDevice device : getTestInfo().getDevices()) {
            commandLine.add("--device_serial=" + device.getSerialNumber());
        }
        commandLine.add("--log_path=" + getLogDirAbsolutePath());
        if (!tests.isEmpty()) {
            commandLine.add("--tests");
            commandLine.addAll(cleanFilters(tests));
        }
        // Add all the other options
        commandLine.addAll(getTestOptions());
        return commandLine.toArray(new String[0]);
    }

    @VisibleForTesting
    protected void reportLogs(File logDir, ITestInvocationListener listener) {
        for (File subFile : logDir.listFiles()) {
            if (subFile.isDirectory()) {
                reportLogs(subFile, listener);
            } else {
                if (!subFile.exists()) {
                    continue;
                }
                try (InputStreamSource dataStream = new FileInputStreamSource(subFile, true)) {
                    String cleanName = subFile.getName().replace(",", "_");
                    LogDataType type = LogDataType.TEXT;
                    if (cleanName.contains("logcat")) {
                        type = LogDataType.LOGCAT;
                    }
                    listener.testLog(cleanName, type, dataStream);
                }
            }
        }
        FileUtil.recursiveDelete(logDir);
        // reset log dir to be recreated for retries
        mLogDir = null;
    }

    @VisibleForTesting
    List<String> getTestOptions() {
        return mTestOptions;
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        if (mRunUtil == null) {
            mRunUtil = new RunUtil();
        }
        return mRunUtil;
    }

    @VisibleForTesting
    String getAdbPath() {
        return GlobalConfiguration.getDeviceManagerInstance().getAdbPath();
    }

    @VisibleForTesting
    long getTestTimeout() {
        return mTestTimeout;
    }
}
