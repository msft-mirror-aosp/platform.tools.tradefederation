/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.proxy.AutomatedReporters;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.DelegatedInvocationExecution;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.RemoteInvocationExecution;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.result.proto.StreamProtoReceiver;
import com.android.tradefed.result.proto.StreamProtoResultReporter;
import com.android.tradefed.service.TradefedFeatureServer;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.IRunUtil.EnvPriority;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.StringEscapeUtils;
import com.android.tradefed.util.SubprocessExceptionParser;
import com.android.tradefed.util.SubprocessTestResultsParser;
import com.android.tradefed.util.SystemUtil;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.UniqueMultiMap;

import org.junit.Assert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link IRemoteTest} for running tests against a separate TF installation.
 *
 * <p>Launches an external java process to run the tests. Used for running the TF unit or functional
 * tests continuously.
 */
public abstract class SubprocessTfLauncher
        implements IBuildReceiver, IInvocationContextReceiver, IRemoteTest, IConfigurationReceiver {

    /** The tag that will be passed to the TF subprocess to differentiate it */
    public static final String SUBPROCESS_TAG_NAME = "subprocess";

    public static final String PARENT_PROC_TAG_NAME = "parentprocess";
    /** Env. variable that affects adb selection. */
    public static final String ANDROID_SERIAL_VAR = "ANDROID_SERIAL";

    @Option(name = "max-run-time", description =
            "The maximum time to allow for a TF test run.", isTimeVal = true)
    private long mMaxTfRunTime = 20 * 60 * 1000;

    @Option(name = "remote-debug", description =
            "Start the TF java process in remote debug mode.")
    private boolean mRemoteDebug = false;

    @Option(name = "config-name", description = "The config that runs the TF tests")
    private String mConfigName;

    @Option(
            name = "local-sharding-mode",
            description =
                    "If sharding is requested, allow the launcher to run with local sharding.")
    private boolean mLocalShardingMode = false;

    @Option(name = "use-event-streaming", description = "Use a socket to receive results as they"
            + "arrived instead of using a temporary file and parsing at the end.")
    private boolean mEventStreaming = true;

    @Option(
            name = "use-proto-reporting",
            description = "Use a proto result reporter for the results from the subprocess.")
    private boolean mUseProtoReporting = true;

    @Option(name = "sub-global-config", description = "The global config name to pass to the"
            + "sub process, can be local or from jar resources. Be careful of conflicts with "
            + "parent process.")
    private String mGlobalConfig = null;

    @Option(
            name = "inject-invocation-data",
            description = "Pass the invocation-data to the subprocess if enabled.")
    private boolean mInjectInvocationData = true;

    @Option(name = "ignore-test-log", description = "Only rely on logAssociation for logs.")
    private boolean mIgnoreTestLog = true;

    @Option(
        name = "disable-stderr-test",
        description = "Whether or not to disable the stderr validation check."
    )
    private boolean mDisableStderrTest = false;

    @Option(
        name = "disable-add-opens",
        description = "Whether or not to add the java add-opens flags"
    )
    private boolean mDisableJavaOpens = false;

    @Option(name = "add-opens", description = "Whether or not to add the java add-opens flags")
    private Set<String> mAddOpens =
            new LinkedHashSet<>(
                    Arrays.asList(
                            "java.base/java.nio",
                            "java.base/sun.reflect.annotation",
                            "java.base/java.io"));

    // Represents all the args to be passed to the sub process
    @Option(name = "sub-params", description = "Parameters to feed the subprocess.")
    private List<String> mSubParams = new ArrayList<String>();

    // Temp global configuration filtered from the parent process.
    private String mFilteredGlobalConfig = null;

    private static final List<String> TRADEFED_JARS =
            new ArrayList<>(
                    Arrays.asList(
                            // Loganalysis
                            "loganalysis.jar",
                            "loganalysis-tests.jar",
                            // Aosp Tf jars
                            "tradefed.jar",
                            "tradefed-tests.jar",
                            // AVD util test jar
                            "^tradefed-avd-util-tests.jar",
                            // libs
                            "tools-common-prebuilt.jar",
                            // jar in older branches
                            "tf-prod-tests.jar",
                            "tf-prod-metatests.jar",
                            // Aosp contrib jars
                            "tradefed-contrib.jar",
                            "tf-contrib-tests.jar",
                            // Google Tf jars
                            "google-tf-prod-tests.jar",
                            "google-tf-prod-metatests.jar",
                            "google-tradefed.jar",
                            "google-tradefed-tests.jar",
                            // Google contrib jars
                            "google-tradefed-contrib.jar",
                            // Older jar required for coverage tests
                            "jack-jacoco-reporter.jar",
                            "emmalib.jar"));

    /** Timeout to wait for the events received from subprocess to finish being processed.*/
    private static final long EVENT_THREAD_JOIN_TIMEOUT_MS = 30 * 1000;

    protected IRunUtil mRunUtil =  new RunUtil();

    protected IBuildInfo mBuildInfo = null;
    // Temp directory to run the TF process.
    protected File mTmpDir = null;
    // List of command line arguments to run the TF process.
    protected List<String> mCmdArgs = null;
    // The absolute path to the build's root directory.
    protected String mRootDir = null;
    protected IConfiguration mConfig;
    private IInvocationContext mContext;

    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }

    protected void setProtoReporting(boolean protoReporting) {
        mUseProtoReporting = protoReporting;
    }

    /**
     * Set use-event-streaming.
     *
     * Exposed for unit testing.
     */
    protected void setEventStreaming(boolean eventStreaming) {
        mEventStreaming = eventStreaming;
    }

    /**
     * Set IRunUtil.
     *
     * Exposed for unit testing.
     */
    protected void setRunUtil(IRunUtil runUtil) {
        mRunUtil = runUtil;
    }

    /** Returns the {@link IRunUtil} that will be used for the subprocess command. */
    protected IRunUtil getRunUtil() {
        return mRunUtil;
    }

    /**
     * Setup before running the test.
     */
    protected void preRun() {
        Assert.assertNotNull(mBuildInfo);
        Assert.assertNotNull(mConfigName);
        IFolderBuildInfo tfBuild = (IFolderBuildInfo) mBuildInfo;
        File rootDirFile = tfBuild.getRootDir();
        mRootDir = rootDirFile.getAbsolutePath();
        String jarClasspath = "";
        List<String> paths = new ArrayList<>();
        for (String jar : TRADEFED_JARS) {
            File f = FileUtil.findFile(rootDirFile, jar);
            if (f != null && f.exists()) {
                paths.add(f.getAbsolutePath());
            }
        }
        jarClasspath = String.join(":", paths);

        mCmdArgs = new ArrayList<String>();
        mCmdArgs.add(getJava());

        try {
            mTmpDir = FileUtil.createTempDir("subprocess-" + tfBuild.getBuildId());
            mCmdArgs.add(String.format("-Djava.io.tmpdir=%s", mTmpDir.getAbsolutePath()));
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }

        addJavaArguments(mCmdArgs);

        if (mRemoteDebug) {
            mCmdArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=10088");
        }
        // This prevent the illegal reflective access warnings by allowing some packages.
        if (!mDisableJavaOpens) {
            for (String modulePackage : mAddOpens) {
                mCmdArgs.add("--add-opens=" + modulePackage + "=ALL-UNNAMED");
            }
        }
        mCmdArgs.add("-cp");

        mCmdArgs.add(jarClasspath);
        mCmdArgs.add("com.android.tradefed.command.CommandRunner");
        mCmdArgs.add(mConfigName);

        Integer shardCount = mConfig.getCommandOptions().getShardCount();
        if (mLocalShardingMode && shardCount != null & shardCount > 1) {
            mCmdArgs.add("--shard-count");
            mCmdArgs.add(Integer.toString(shardCount));
        }

        if (!mSubParams.isEmpty()) {
            mCmdArgs.addAll(StringEscapeUtils.paramsToArgs(mSubParams));
        }

        // clear the TF_GLOBAL_CONFIG env, so another tradefed will not reuse the global config file
        mRunUtil.unsetEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE);
        mRunUtil.unsetEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE);
        mRunUtil.unsetEnvVariable(ANDROID_SERIAL_VAR);
        mRunUtil.unsetEnvVariable(DelegatedInvocationExecution.DELEGATED_MODE_VAR);
        for (String variable : AutomatedReporters.REPORTER_MAPPING) {
            mRunUtil.unsetEnvVariable(variable);
        }
        // Handle feature server
        getRunUtil().unsetEnvVariable(RemoteInvocationExecution.START_FEATURE_SERVER);
        getRunUtil().unsetEnvVariable(TradefedFeatureServer.TF_SERVICE_PORT);
        getRunUtil().setEnvVariablePriority(EnvPriority.SET);
        getRunUtil()
                .setEnvVariable(
                        TradefedFeatureServer.TF_SERVICE_PORT,
                        Integer.toString(TradefedFeatureServer.getPort()));

        if (mGlobalConfig == null) {
            // If the global configuration is not set in option, create a filtered global
            // configuration for subprocess to use.
            try {
                File filteredGlobalConfig =
                        GlobalConfiguration.getInstance().cloneConfigWithFilter();
                mFilteredGlobalConfig = filteredGlobalConfig.getAbsolutePath();
                mGlobalConfig = mFilteredGlobalConfig;
            } catch (IOException e) {
                CLog.e("Failed to create filtered global configuration");
                CLog.e(e);
            }
        }
        if (mGlobalConfig != null) {
            // We allow overriding this global config and then set it for the subprocess.
            mRunUtil.setEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE, mGlobalConfig);
        }
    }

    /**
     * Allow to add extra java parameters to the subprocess invocation.
     *
     * @param args the current list of arguments to which we need to add the extra ones.
     */
    protected void addJavaArguments(List<String> args) {}

    /**
     * Actions to take after the TF test is finished.
     *
     * @param listener the original {@link ITestInvocationListener} where to report results.
     * @param exception True if exception was raised inside the test.
     * @param elapsedTime the time taken to run the tests.
     */
    protected void postRun(ITestInvocationListener listener, boolean exception, long elapsedTime) {}

    /** Pipe to the subprocess the invocation-data so that it can use them if needed. */
    private void addInvocationData() {
        if (!mInjectInvocationData) {
            return;
        }
        UniqueMultiMap<String, String> data = mConfig.getCommandOptions().getInvocationData();
        for (String key : data.keySet()) {
            for (String value : data.get(key)) {
                mCmdArgs.add("--" + CommandOptions.INVOCATION_DATA);
                mCmdArgs.add(key);
                mCmdArgs.add(value);
            }
        }
        // Finally add one last more to tag the subprocess
        mCmdArgs.add("--" + CommandOptions.INVOCATION_DATA);
        mCmdArgs.add(SUBPROCESS_TAG_NAME);
        mCmdArgs.add("true");
        // Tag the parent invocation
        mBuildInfo.addBuildAttribute(PARENT_PROC_TAG_NAME, "true");
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        preRun();
        addInvocationData();

        File stdoutFile = null;
        File stderrFile = null;
        File eventFile = null;
        SubprocessTestResultsParser eventParser = null;
        StreamProtoReceiver protoReceiver = null;
        FileOutputStream stdout = null;
        FileOutputStream stderr = null;

        boolean exception = false;
        long startTime = 0L;
        long elapsedTime = -1L;
        try {
            stdoutFile = FileUtil.createTempFile("stdout_subprocess_", ".log");
            stderrFile = FileUtil.createTempFile("stderr_subprocess_", ".log");
            stderr = new FileOutputStream(stderrFile);
            stdout = new FileOutputStream(stdoutFile);
            if (mUseProtoReporting) {
                // Skip merging properties to avoid contaminating metrics with unit tests
                protoReceiver =
                        new StreamProtoReceiver(
                                listener, mContext, false, false, true, "subprocess-", false);
                mCmdArgs.add("--" + StreamProtoResultReporter.PROTO_REPORT_PORT_OPTION);
                mCmdArgs.add(Integer.toString(protoReceiver.getSocketServerPort()));
            } else {
                eventParser = new SubprocessTestResultsParser(listener, mEventStreaming, mContext);
                if (mEventStreaming) {
                    mCmdArgs.add("--subprocess-report-port");
                    mCmdArgs.add(Integer.toString(eventParser.getSocketServerPort()));
                } else {
                    eventFile = FileUtil.createTempFile("event_subprocess_", ".log");
                    mCmdArgs.add("--subprocess-report-file");
                    mCmdArgs.add(eventFile.getAbsolutePath());
                }
                eventParser.setIgnoreTestLog(mIgnoreTestLog);
            }
            startTime = System.currentTimeMillis();
            CommandResult result = mRunUtil.runTimedCmd(mMaxTfRunTime, stdout,
                    stderr, mCmdArgs.toArray(new String[0]));

            if (eventParser != null) {
                if (eventParser.getStartTime() != null) {
                    startTime = eventParser.getStartTime();
                }
                elapsedTime = System.currentTimeMillis() - startTime;
                // We possibly allow for a little more time if the thread is still processing
                // events.
                if (!eventParser.joinReceiver(EVENT_THREAD_JOIN_TIMEOUT_MS)) {
                    elapsedTime = -1L;
                    throw new RuntimeException(
                            String.format(
                                    "Event receiver thread did not complete:" + "\n%s",
                                    FileUtil.readStringFromFile(stderrFile)));
                }
            } else if (protoReceiver != null) {
                if (!protoReceiver.joinReceiver(EVENT_THREAD_JOIN_TIMEOUT_MS)) {
                    elapsedTime = -1L;
                    throw new RuntimeException(
                            String.format(
                                    "Event receiver thread did not complete:" + "\n%s",
                                    FileUtil.readStringFromFile(stderrFile)));
                }
                protoReceiver.completeModuleEvents();
            }
            if (result.getStatus().equals(CommandStatus.SUCCESS)) {
                CLog.d("Successfully ran TF tests for build %s", mBuildInfo.getBuildId());
                testCleanStdErr(stderrFile, listener);
            } else {
                CLog.w("Failed ran TF tests for build %s, status %s",
                        mBuildInfo.getBuildId(), result.getStatus());
                CLog.v(
                        "TF tests output:\nstdout:\n%s\nstderr:\n%s",
                        result.getStdout(), result.getStderr());
                exception = true;
                String errMessage = null;
                if (result.getStatus().equals(CommandStatus.TIMED_OUT)) {
                    errMessage = String.format("Timeout after %s",
                            TimeUtil.formatElapsedTime(mMaxTfRunTime));
                    throw new HarnessRuntimeException(
                            String.format(
                                    "%s Tests subprocess failed due to:\n%s\n",
                                    mConfigName, errMessage),
                            InfraErrorIdentifier.INVOCATION_TIMEOUT);
                } else if (eventParser != null && !eventParser.reportedInvocationFailed()) {
                    SubprocessExceptionParser.handleStderrException(result);
                }
            }
        } catch (IOException e) {
            exception = true;
            throw new RuntimeException(e);
        } finally {
            StreamUtil.close(stdout);
            StreamUtil.close(stderr);
            logAndCleanFile(stdoutFile, listener);
            logAndCleanFile(stderrFile, listener);
            if (eventFile != null) {
                eventParser.parseFile(eventFile);
                logAndCleanFile(eventFile, listener);
            }
            StreamUtil.close(eventParser);
            StreamUtil.close(protoReceiver);

            if (mGlobalConfig != null && new File(mGlobalConfig).exists()) {
                logAndCleanFile(new File(mGlobalConfig), listener);
            }

            postRun(listener, exception, elapsedTime);

            if (mTmpDir != null) {
                FileUtil.recursiveDelete(mTmpDir);
            }

            if (mFilteredGlobalConfig != null) {
                FileUtil.deleteFile(new File(mFilteredGlobalConfig));
            }
        }
    }

    /**
     * Log the content of given file to listener, then remove the file.
     *
     * @param fileToExport the {@link File} pointing to the file to log.
     * @param listener the {@link ITestInvocationListener} where to report the test.
     */
    private void logAndCleanFile(File fileToExport, ITestInvocationListener listener) {
        if (fileToExport == null) {
            return;
        }

        try (FileInputStreamSource inputStream = new FileInputStreamSource(fileToExport, true)) {
            listener.testLog(fileToExport.getName(), LogDataType.TEXT, inputStream);
        } catch (RuntimeException e) {
            CLog.e(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    /**
     * Extra test to ensure no abnormal logging is made to stderr when all the tests pass.
     *
     * @param stdErrFile the stderr log file of the subprocess.
     * @param listener the {@link ITestInvocationListener} where to report the test.
     */
    private void testCleanStdErr(File stdErrFile, ITestInvocationListener listener)
            throws IOException {
        if (mDisableStderrTest) {
            return;
        }
        listener.testRunStarted("StdErr", 1);
        TestDescription tid = new TestDescription("stderr-test", "checkIsEmpty");
        listener.testStarted(tid);
        if (!FileUtil.readStringFromFile(stdErrFile).isEmpty()) {
            String trace =
                    String.format(
                            "Found some output in stderr:\n%s",
                            FileUtil.readStringFromFile(stdErrFile));
            listener.testFailed(tid, FailureDescription.create(trace));
        }
        listener.testEnded(tid, new HashMap<String, Metric>());
        listener.testRunEnded(0, new HashMap<String, Metric>());
    }

    protected String getJava() {
        return SystemUtil.getRunningJavaBinaryPath().getAbsolutePath();
    }
}
