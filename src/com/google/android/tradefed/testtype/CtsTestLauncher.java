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

package com.google.android.tradefed.testtype;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.config.proxy.AutomatedReporters;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceSelection.BaseDeviceType;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ManagedTestDeviceFactory;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TestDeviceOptions.InstanceType;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollectorReceiver;
import com.android.tradefed.invoker.DelegatedInvocationExecution;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.RemoteInvocationExecution;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.proto.StreamProtoReceiver;
import com.android.tradefed.result.proto.StreamProtoResultReporter;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.service.TradefedFeatureServer;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain;
import com.android.tradefed.testtype.suite.ITestSuite;
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
import com.android.tradefed.util.ZipUtil;

import com.google.android.tradefed.util.ClasspathLauncherUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import org.junit.Assert;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link IRemoteTest} for running tests against a separate CTS installation.
 *
 * <p>Launches an external java process to run the tests. Used for running a specific downloaded
 * version of CTS and its configuration It will use the current version of tradefed that launch it
 * to complete missing jars
 */
public class CtsTestLauncher
        implements IRemoteTest,
                IDeviceTest,
                IBuildReceiver,
                IShardableTest,
                IInvocationContextReceiver,
                IConfigurationReceiver,
                IMetricCollectorReceiver {

    private static final int MIN_CTS_VERSION = 1;
    private static final int MAX_CTS_VERSION = 2;

    /** Timeout to wait for the events received from subprocess to finish being processed. */
    private static final long EVENT_THREAD_JOIN_TIMEOUT_MS = 10 * 60 * 1000;

    private static final String ENABLE_PARAMETERS = "enable-parameterized-modules";

    @Option(
            name = "max-run-time",
            isTimeVal = true,
            description =
                    "the maximum time to allow the CTS runner (sub process) to run,"
                            + "supports complex time format like 1h30m15s")
    private long mMaxTfRunTime = 3600000L;

    @Option(
            name = "config-name",
            description = "the config that runs the TF tests",
            mandatory = true)
    private String mConfigName;

    // Provide the reporter to build the --template:map option
    @Option(
            name = "reporter-template",
            shortName = 't',
            description = "use a specific reporter for the template")
    private String mReporterName = null;

    @Option(
            name = "coverage-reporter-template",
            description = "use a coverage reporter for the template")
    private String mCoverageReporterName = null;

    @Option(
            name = "metadata-reporter-template",
            description = "use a metadata reporter for the template")
    private String mMetadataReporterName = null;

    @Option(
            name = "throw-if-jar-not-found",
            description =
                    "Throw an exception if one of the jar " + "specified by run-jar is not found.")
    private boolean mThrowIfJarNotFound = true;

    // represents all the args to be passed to the sub process
    // example:  --cts-params "--plan CTS-hardware --disable-reboot"
    @Option(name = "cts-params", description = "All the cts parameters to feed the sub process")
    private List<String> mCtsParams = new ArrayList<>();

    @Option(
            name = "cts-version",
            description =
                    "Integer representing the version of cts to use." + "Default is version 2.")
    private int mCtsVersion = MAX_CTS_VERSION;

    @Option(
            name = "rootdir-var",
            description =
                    "Name of the variable to be passed as -D "
                            + "parameter to the java call to specify the root directory.")
    private String mRootdirVar = "CTS_ROOT";

    @Option(
            name = "tests-suite-package",
            description =
                    "The key in the build info where to find the unzipped tests suite artifacts.")
    private String mTestsSuitePackageKey = "android-cts";

    @Option(
            name = "run-as-root",
            description = "If sub process CTS should be triggered with root " + "identity.")
    private boolean mRunAsRoot = true;

    @Option(
            name = "report-subprocess-events",
            description =
                    "If the sub process test events "
                            + "should be mirrored at parent process side.")
    private boolean mReportSubprocessEvents = true;

    @Option(name = "skip-report-test-logs", description = "Only rely on logAssociation for logs.")
    private boolean mSkipReportTestLogs = true;

    @Option(
            name = "need-device",
            description = "flag if the subprocess is going to need an actual device to run.")
    private boolean mNeedDevice = true;

    @Option(
            name = "use-event-streaming",
            description =
                    "Use a socket to receive results as they"
                            + "arrived instead of using a temporary file and parsing at the end.")
    private boolean mEventStreaming = true;

    @Option(
            name = "use-proto-reporting",
            description = "Use a proto result reporter for the results from the subprocess.")
    private boolean mUseProtoReporting = true;

    @Option(
            name = "inject-invocation-data",
            description = "Pass the invocation-data to the subprocess if enabled.")
    private boolean mInjectInvocationData = true;

    @Option(
            name = "skip-build-info",
            description = "Don't use parameters to pass build info to sub process.")
    private boolean mSkipBuildInfo = false;

    @Option(
            name = "multi-devices",
            description = "Whether the subprocess requires multiple devices or not.")
    private boolean mMultiDevice = false;

    @Option(
            name = "inject-global-config",
            description =
                    "Whether or not pass the default global configuration object to the "
                            + "subprocess.")
    private boolean mInjectGlobalConfig = false;

    @Option(
            name = "global-config-filters",
            description =
                    "The filters to clone global configuration. Must be used with "
                            + "--inject-global-config=true.")
    private List<String> mGlobalConfigFilters = new ArrayList<>();

    @Option(
            name = "wait-for-subprocess-events",
            description =
                    "The time to wait for all subprocess events to be parsed after the process "
                            + "finishes.",
            isTimeVal = true)
    private long mWaitForSubprocessEvents = EVENT_THREAD_JOIN_TIMEOUT_MS;

    @Option(
            name = "local-sharding-mode",
            description =
                    "If sharding is requested, allow the launcher to run cts with local sharding.")
    private boolean mLocalShardingMode = false;

    @Option(
            name = ENABLE_PARAMETERS,
            description =
                    "Whether or not to enable parameterized modules. This is a feature flag for"
                            + " work in development.")
    private Boolean mEnableParameter = null;

    // ART jars do not need to be on the classpath
    @Option(
            name = "exclude-file-in-java-classpath",
            description = "The file not to include in the java classpath when running CTS.")
    private List<String> mExcludedFilesInClasspath =
            new ArrayList<>(Arrays.asList("art-run-test.*", "art-gtest-jars.*"));

    @Option(
            name = "top-priority-jar",
            description = "Jars that should be on top of the class path list.")
    private Collection<String> mTopPriorityJar = new LinkedHashSet<>();

    @Option(
            name = "disable-compress",
            description = "Do not compress the logged file in the subprocess")
    private boolean mDisableCompress = true;

    @Option(
            name = "use-bundled-java",
            description = "If it exists, use the bundled java inside the package")
    private boolean mUseBundledJava = true;

    @Option(
            name = "inject-download-flag",
            description = "Feature flag to disable injecting the download flag to subprocess")
    private boolean mInjectSkipDownloadFlag = true;

    private IRunUtil mRunUtil;

    private IInvocationContext mContext = null;

    private IConfiguration mConfig = null;

    private CommandResult mResult = null;

    private IBuildInfo mBuildInfo = null;

    private ITestDevice mTestDevice = null;

    private int mShardCount = -1;

    private int mShardIndex = -1;

    private File mTmpDir;
    private File mHeapDump;
    private File mLlvmProfdataTool;

    private boolean mIsSharded = false;

    private File mFilteredGlobalConfig = null;

    public CtsTestLauncher() {
        super();
    }

    public CtsTestLauncher(int shardCount, int shardIndex) {
        this();
        mShardCount = shardCount;
        mShardIndex = shardIndex;
    }

    /** Returns the current shard-index the launcher is going to start. */
    @VisibleForTesting
    int getShardIndex() {
        return mShardIndex;
    }

    public CommandResult getResult() {
        return mResult;
    }

    public String getConfigName() {
        return mConfigName;
    }

    public void setConfigName(String config) {
        mConfigName = config;
    }

    public void setCtsVersion(int version) {
        mCtsVersion = version;
    }

    protected IRunUtil getRunUtil() {
        if (mRunUtil == null) {
            mRunUtil = new RunUtil();
        }
        return mRunUtil;
    }

    protected void setRunAsRoot(boolean runAsRoot) {
        mRunAsRoot = runAsRoot;
    }

    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }

    @Override
    public void setMetricCollectors(List<IMetricCollector> collectors) {
        // Ignore collector
        CLog.d("Ignoring collectors in CtsTestLauncher.");
    }

    /**
     * Creates the classpath used by the sub process to run CTS
     *
     * @return A String with the full classpath.
     */
    public String buildClasspath() throws IOException {
        List<File> classpathList = new ArrayList<>();

        for (VersionedFile vf : mBuildInfo.getFiles()) {
            if (vf.getFile() == null) {
                continue;
            }
            String fileName = vf.getFile().getName();
            if (fileName.endsWith(".jar")
                    && !ClasspathLauncherUtil.matchExcludedFilesInClasspath(
                            mExcludedFilesInClasspath, fileName)) {
                File tempJar = vf.getFile();
                if (!tempJar.exists()) {
                    throw new FileNotFoundException("Couldn't find the jar file: " + tempJar);
                }
                classpathList.add(tempJar);
            }
        }

        File ctsRoot = getTestsSuiteRootDir();
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

        // Move the top jars to the beginning of the class path list.
        if (mTopPriorityJar.isEmpty()) {
            // Always put tradefed.jar first
            mTopPriorityJar.add("tradefed.jar");
        }
        if (!mTopPriorityJar.isEmpty()) {
            List<File> topJars = new ArrayList<>();
            for (File classpath : classpathList) {
                if (mTopPriorityJar.contains(classpath.getName())) {
                    topJars.add(classpath);
                }
            }
            classpathList.removeAll(topJars);
            classpathList.addAll(0, topJars);
        }

        if (mCtsVersion == 2) {
            // Cts V2 requires an additional path to be added
            File additionalPath = new File(new File(ctsRoot, mainDirName), "testcases");
            if (!additionalPath.exists()) {
                throw new FileNotFoundException("testcases directory not found for cts v2");
            }
            // include all host side jars in the directory
            classpathList.addAll(
                    ClasspathLauncherUtil.getJars(additionalPath, mExcludedFilesInClasspath));
        }

        return Joiner.on(":").join(classpathList);
    }

    /** Sets {@code mExcludedFilesInClasspath}. */
    @VisibleForTesting
    void setExcludedFilesInClasspath(List<String> excludedFilesInClasspath) {
        this.mExcludedFilesInClasspath = excludedFilesInClasspath;
    }

    /** Sets {@code mTopPriorityJar}. */
    @VisibleForTesting
    void setTopPriorityJar(Collection<String> jars) {
        this.mTopPriorityJar = jars;
    }

    /** Create a tmp dir and add it to the java options. */
    @VisibleForTesting
    void createSubprocessTmpDir(List<String> args) {
        try {
            mTmpDir = FileUtil.createTempDir("cts-subprocess-", getWorkDir());
            args.add(String.format("-Djava.io.tmpdir=%s", mTmpDir.getAbsolutePath()));
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
    }

    /** Create a tmp dir and add it to the java options. */
    @VisibleForTesting
    void createHeapDumpTmpDir(List<String> args) {
        try {
            mHeapDump = FileUtil.createTempDir("heap-dump", getWorkDir());
            args.add("-XX:+HeapDumpOnOutOfMemoryError");
            args.add(String.format("-XX:HeapDumpPath=%s", mHeapDump.getAbsolutePath()));
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates the java command line that will be run in a sub process.
     *
     * @param classpath java classpath to the jar files requires for CTS/XTS to run
     * @return An ArrayList<String> with the java command line and parameters.
     */
    public List<String> buildJavaCmd(String classpath) throws DeviceNotAvailableException {
        List<String> args = new ArrayList<>();
        args.add(getJavaPath());
        createHeapDumpTmpDir(args);
        createSubprocessTmpDir(args);
        args.add("-cp");
        args.add(classpath);

        if (mCtsVersion == 2) {
            // Cts V2 requires CTS_ROOT to be set or VTS_ROOT for vts run
            args.add(
                    String.format(
                            "-D%s=%s", mRootdirVar, getTestsSuiteRootDir().getAbsolutePath()));
        }

        args.add("com.android.tradefed.command.CommandRunner");
        args.add(mConfigName);

        if (mReporterName != null) {
            args.add("--template:map");
            args.add("reporters");
            // example: google/template/reporters/cts-google-reporters
            args.add(mReporterName);
        }
        if (mCoverageReporterName != null) {
            args.add("--template:map");
            args.add("coverage-reporter");
            args.add(mCoverageReporterName);
        }
        if (mMetadataReporterName != null) {
            args.add("--template:map");
            args.add("metadata-reporters");
            args.add(mMetadataReporterName);
        }

        // Ensure the sub process is always logging for debug purpose
        // Always set this BEFORE the mCtsParams to ensure it can be overridden.
        args.add("--log-level");
        args.add("VERBOSE");

        args.add("--log-level-display");
        args.add("VERBOSE");

        if (mDisableCompress) {
            args.add("--no-compress-files");
        }

        // Tokenize args to be passed to CtsTest/XtsTest
        if (!mCtsParams.isEmpty()) {
            args.addAll(StringEscapeUtils.paramsToArgs(mCtsParams));
        }

        // This a newer option that CTS before R will not support so control the compatibility
        // from CtsTestLauncher. Those args overrides --cts-params.
        if (!(getDevice().getIDevice() instanceof StubDevice)) {
            if (mEnableParameter != null) {
                if (getDevice().checkApiLevelAgainstNextRelease(29)) {
                    if (mEnableParameter) {
                        args.add("--" + ENABLE_PARAMETERS);
                    } else {
                        args.add("--no-" + ENABLE_PARAMETERS);
                    }
                }
            }
            IRetryDecision retryOptions = mConfig.getRetryDecision();
            if (!RetryStrategy.NO_RETRY.equals(retryOptions.getRetryStrategy())) {
                if (getDevice().checkApiLevelAgainstNextRelease(29)) {
                    args.add("--max-testcase-run-count");
                    args.add(Integer.toString(retryOptions.getMaxRetryCount()));
                    args.add("--retry-strategy");
                    args.add(retryOptions.getRetryStrategy().toString());
                    if (retryOptions.rebootAtLastAttempt()) {
                        args.add("--reboot-at-last-retry");
                    }
                }
            }
        }

        // always match the serial that was picked by the parent
        if (mNeedDevice) {
            // Carry device started as NativeDevice to subprocess
            Set<String> notifyAsNative = new LinkedHashSet<String>();
            for (IDeviceConfiguration deviceConfig : mConfig.getDeviceConfig()) {
                if (BaseDeviceType.NATIVE_DEVICE.equals(
                        deviceConfig.getDeviceRequirements().getBaseDeviceTypeRequested())) {
                    notifyAsNative.add(
                            mContext.getDevice(deviceConfig.getDeviceName()).getSerialNumber());
                }
            }
            for (ITestDevice device : mContext.getDevices()) {
                // If device is directly Native carry it as native
                if (NativeDevice.class.equals(device.getClass())) {
                    notifyAsNative.add(device.getSerialNumber());
                }
            }
            if (!notifyAsNative.isEmpty()) {
                getRunUtil()
                        .setEnvVariable(
                                ManagedTestDeviceFactory.NOTIFY_AS_NATIVE,
                                Joiner.on(",").join(notifyAsNative));
            }
            // If we enabled multi-devices for subprocess and we have multiple devices we pass them
            // all
            if (mMultiDevice && mContext.getDevices().size() > 1) {
                for (ITestDevice device : mContext.getDevices()) {
                    args.add("--serial");
                    args.add(device.getSerialNumber());
                }
            } else {
                if (!mLocalShardingMode) {
                    args.add("--serial");
                    args.add(mTestDevice.getSerialNumber());
                    InstanceType type = mTestDevice.getOptions().getInstanceType();
                    if (InstanceType.CUTTLEFISH.equals(type)
                            || InstanceType.REMOTE_NESTED_AVD.equals(type)) {
                        args.add("--instance-type");
                        args.add(type.toString());
                    }
                }
            }
        } else {
            args.add("-n");
        }

        if (mBuildInfo.getBuildBranch() != null) {
            args.add("--branch");
            args.add(mBuildInfo.getBuildBranch());
        }

        if (!mSkipBuildInfo) {
            if (mBuildInfo.getBuildId() != null) {
                args.add("--build-id");
                args.add(mBuildInfo.getBuildId());
            }

            if (mBuildInfo.getBuildFlavor() != null) {
                args.add("--build-flavor");
                args.add(mBuildInfo.getBuildFlavor());
            }

            if (mBuildInfo.getBuildAttributes().get("build_target") != null) {
                args.add("--build-attribute");
                args.add("build_target=" + mBuildInfo.getBuildAttributes().get("build_target"));
            }
        }

        if (mCtsVersion == 1) {
            // cts/xts install path for cts version 1
            args.add(String.format("--%s-install-path", mConfigName));
            args.add(getTestsSuiteRootDir().getAbsolutePath());
        }

        if (mRunAsRoot) {
            args.add("--enable-root");
        } else {
            args.add("--no-enable-root");
        }

        if (mInjectInvocationData) {
            UniqueMultiMap<String, String> data = mConfig.getCommandOptions().getInvocationData();
            for (String key : data.keySet()) {
                for (String value : data.get(key)) {
                    args.add("--invocation-data");
                    args.add(key);
                    args.add(value);
                }
            }
            // Finally add one last more to tag the subprocess
            args.add("--" + CommandOptions.INVOCATION_DATA);
            args.add(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
            args.add("true");
            // Tag the parent invocation
            mBuildInfo.addBuildAttribute(SubprocessTfLauncher.PARENT_PROC_TAG_NAME, "true");

            for (File remoteFile : mBuildInfo.getRemoteFiles()) {
                args.add("--remote-files");
                args.add(remoteFile.toString());
            }
        }
        if (mInjectSkipDownloadFlag) {
            if (mBuildInfo.getBuildAttributes().get(ITestSuite.SKIP_STAGING_ARTIFACTS) != null) {
                args.add("--" + ITestSuite.SKIP_STAGING_ARTIFACTS);
            }
        }

        if (0 <= mShardCount && 0 <= mShardIndex) {
            args.add("--shard-count");
            args.add(Integer.toString(mShardCount));
            args.add("--shard-index");
            args.add(Integer.toString(mShardIndex));
        }
        if (mLocalShardingMode && mShardCount > 1) {
            args.add("--shard-count");
            args.add(Integer.toString(mShardCount));
        }

        if (mConfig != null
                && mConfig.getCoverageOptions().isCoverageEnabled()
                && mConfig.getCoverageOptions().getCoverageToolchains().contains(Toolchain.CLANG)) {
            if (mConfig.getCoverageOptions().getLlvmProfdataPath() != null) {
                args.add("--llvm-profdata-path");
                args.add(mConfig.getCoverageOptions().getLlvmProfdataPath().toString());
            } else {
                // Extract llvm-profdata.zip from the build and pass it through the
                // --llvm-profdata-path option.
                try {
                    File profileToolZip = mBuildInfo.getFile("llvm-profdata.zip");
                    mLlvmProfdataTool = ZipUtil.extractZipToTemp(profileToolZip, "llvm-profdata");
                    args.add("--llvm-profdata-path");
                    args.add(mLlvmProfdataTool.toPath().toString());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return args;
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        Assert.assertNotNull(mBuildInfo);
        Assert.assertNotNull(mConfigName);
        if (mCtsVersion < MIN_CTS_VERSION || mCtsVersion > MAX_CTS_VERSION) {
            throw new RuntimeException(
                    String.format("Invalid Cts version requested: %s", mCtsVersion));
        }

        String classpath = null;
        try {
            classpath = buildClasspath();
        } catch (IOException e) {
            CLog.e(e);
        }

        List<String> args = buildJavaCmd(classpath);

        getRunUtil().setLinuxInterruptProcess(true);
        // clear the TF_GLOBAL_CONFIG env, so another tradefed won't reuse the global config file
        getRunUtil().unsetEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE);
        getRunUtil().unsetEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE);
        for (String variable : AutomatedReporters.REPORTER_MAPPING) {
            getRunUtil().unsetEnvVariable(variable);
        }
        getRunUtil().unsetEnvVariable(DelegatedInvocationExecution.DELEGATED_MODE_VAR);
        // Handle feature server
        getRunUtil().unsetEnvVariable(RemoteInvocationExecution.START_FEATURE_SERVER);
        getRunUtil().unsetEnvVariable(TradefedFeatureServer.TF_SERVICE_PORT);
        getRunUtil().setEnvVariablePriority(EnvPriority.SET);
        getRunUtil()
                .setEnvVariable(
                        TradefedFeatureServer.TF_SERVICE_PORT,
                        Integer.toString(TradefedFeatureServer.getPort()));
        if (mConfig.getConfigurationDescription()
                        .getMetaData(TradefedFeatureServer.SERVER_REFERENCE)
                != null) {
            mRunUtil.setEnvVariable(
                    TradefedFeatureServer.SERVER_REFERENCE,
                    mConfig.getConfigurationDescription()
                            .getAllMetaData()
                            .getUniqueMap()
                            .get(TradefedFeatureServer.SERVER_REFERENCE));
        }
        // If the global configuration is set, create a filtered global configuration for
        // subprocess to use.
        if (mInjectGlobalConfig) {
            try {
                mFilteredGlobalConfig =
                        GlobalConfiguration.getInstance()
                                .cloneConfigWithFilter(mGlobalConfigFilters.toArray(new String[0]));
                getRunUtil()
                        .setEnvVariable(
                                GlobalConfiguration.GLOBAL_CONFIG_VARIABLE,
                                mFilteredGlobalConfig.getAbsolutePath());
            } catch (IOException e) {
                CLog.e("Failed to create filtered global configuration");
                CLog.e(e);
            }
        }

        File stdoutFile = null;
        File stderrFile = null;
        File eventFile = null;
        SubprocessTestResultsParser eventParser = null;
        StreamProtoReceiver protoReceiver = null;
        try {
            stdoutFile = FileUtil.createTempFile("stdout_subprocess_", ".txt", getWorkDir());
            stderrFile = FileUtil.createTempFile("stderr_subprocess_", ".txt", getWorkDir());
            if (mUseProtoReporting) {
                protoReceiver = new StreamProtoReceiver(listener, mContext, false, false);
                args.add("--" + StreamProtoResultReporter.PROTO_REPORT_PORT_OPTION);
                args.add(Integer.toString(protoReceiver.getSocketServerPort()));
            } else {
                if (mEventStreaming) {
                    eventParser = new SubprocessTestResultsParser(listener, true, mContext);
                    args.add("--subprocess-report-port");
                    args.add(Integer.toString(eventParser.getSocketServerPort()));
                } else if (mReportSubprocessEvents) {
                    eventFile = FileUtil.createTempFile("event_subprocess_", ".log");
                    eventParser = new SubprocessTestResultsParser(listener, false, mContext);
                    args.add("--subprocess-report-file");
                    args.add(eventFile.getAbsolutePath());
                }
                eventParser.setIgnoreTestLog(mSkipReportTestLogs);
            }

            if (!mRunAsRoot) {
                // run unroot then tell sub process to not enable root (if device is rebooted)
                getDevice().disableAdbRoot();
            }
            RuntimeException interruptedException = null;
            try {
                mResult =
                        getRunUtil()
                                .runTimedCmdWithInput(
                                        mMaxTfRunTime,
                                        null,
                                        stdoutFile,
                                        stderrFile,
                                        args.toArray(new String[0]));
            } catch (RuntimeException interrupted) {
                CLog.e("Sandbox runtimedCmd threw an exception");
                CLog.e(interrupted);
                interruptedException = interrupted;
                mResult = new CommandResult(CommandStatus.EXCEPTION);
                mResult.setStdout(StreamUtil.getStackTrace(interrupted));
            }

            boolean failedStatus = false;
            String stderrText;
            try {
                stderrText = FileUtil.readStringFromFile(stderrFile);
            } catch (IOException e) {
                stderrText = "Could not read the stderr output from process.";
            }
            if (!CommandStatus.SUCCESS.equals(mResult.getStatus())) {
                failedStatus = true;
                mResult.setStderr(stderrText);
            }
            boolean joinResult = false;
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(
                            InvocationMetricKey.invocation_events_processing.toString())) {
                if (protoReceiver != null) {
                    joinResult = protoReceiver.joinReceiver(mWaitForSubprocessEvents);
                } else {
                    joinResult = eventParser.joinReceiver(EVENT_THREAD_JOIN_TIMEOUT_MS);
                }
            }
            if (interruptedException != null) {
                throw interruptedException;
            }
            if (!joinResult) {
                if (!failedStatus) {
                    mResult.setStatus(CommandStatus.EXCEPTION);
                }
                mResult.setStderr(
                        String.format("Event receiver thread did not complete.:\n%s", stderrText));
            }

            if (protoReceiver != null && protoReceiver.hasInvocationFailed()) {
                // If an invocation failed has already been reported, skip the logic below to report
                // it again.
                return;
            }
            if (CommandStatus.TIMED_OUT.equals(mResult.getStatus())) {
                mResult.setStderr(
                        String.format(
                                "Timed out after '%s' .%s",
                                TimeUtil.formatElapsedTime(mMaxTfRunTime), mResult.getStderr()));
            }

            if (!CommandStatus.SUCCESS.equals(mResult.getStatus())) {
                CLog.e(
                        "Sandbox finished with status: %s and exit code: %s",
                        mResult.getStatus(), mResult.getExitCode());
                SubprocessExceptionParser.handleStderrException(mResult);
            }
            CLog.i("Successfully ran %s tests for build %s", mConfigName, mBuildInfo.getBuildId());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (protoReceiver != null) {
                protoReceiver.completeModuleEvents();
            }
            if (eventParser != null) {
                eventParser.completeModuleEvents();
            }
            if (mResult != null && mResult.getExitCode() != null) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.SANDBOX_EXIT_CODE, mResult.getExitCode());
            }
            // Parse events first if we weren't streaming.
            if (eventFile != null) {
                eventParser.parseFile(eventFile);
                logAndCleanFile(eventFile, listener);
            }
            // Report and clean the logs
            logAndCleanFile(stdoutFile, listener);
            logAndCleanFile(stderrFile, listener);
            StreamUtil.close(eventParser);
            StreamUtil.close(protoReceiver);
            if (mFilteredGlobalConfig != null) {
                logAndCleanFile(mFilteredGlobalConfig, listener);
            } else {
                CLog.d(
                        "No filtered global config is saved. (\"inject-global-config\" = %s)",
                        mInjectGlobalConfig);
            }
            FileUtil.recursiveDelete(mTmpDir);
            logAndCleanHeapDump(mHeapDump, listener);
            cleanLlvmProfdataTool();
        }
    }

    private void logAndCleanFile(File fileToExport, ITestInvocationListener listener) {
        if (fileToExport != null) {
            FileInputStreamSource stderrInputStream = new FileInputStreamSource(fileToExport);
            listener.testLog(fileToExport.getName(), LogDataType.TEXT, stderrInputStream);
            StreamUtil.cancel(stderrInputStream);
            FileUtil.deleteFile(fileToExport);
        }
    }

    @VisibleForTesting
    void logAndCleanHeapDump(File heapDumpDir, ITestLogger logger) {
        try {
            if (heapDumpDir != null && heapDumpDir.listFiles().length != 0) {
                for (File f : heapDumpDir.listFiles()) {
                    FileInputStreamSource fileInput = new FileInputStreamSource(f);
                    logger.testLog(f.getName(), LogDataType.HPROF, fileInput);
                    StreamUtil.cancel(fileInput);
                }
            }
        } finally {
            FileUtil.recursiveDelete(heapDumpDir);
        }
    }

    @VisibleForTesting
    void cleanLlvmProfdataTool() {
        FileUtil.recursiveDelete(mLlvmProfdataTool);
    }

    /** {@inheritDoc} */
    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public void setDevice(ITestDevice device) {
        mTestDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    private File getTestsSuiteRootDir() {
        // Search the key in all available builds (for multi-devices case)
        for (IBuildInfo info : mContext.getBuildInfos()) {
            if (info.getFile(mTestsSuitePackageKey) != null) {
                return info.getFile(mTestsSuitePackageKey);
            }
        }
        for (IBuildInfo info : mContext.getBuildInfos()) {
            if (info instanceof IFolderBuildInfo) {
                return ((IFolderBuildInfo) info).getRootDir();
            }
        }
        throw new IllegalArgumentException(
                String.format(
                        "Build info did not contain the suite key '%s' nor is a IFolderBuildInfo.",
                        mTestsSuitePackageKey));
    }

    private IRemoteTest getTestShard(int shardCount, int shardIndex) {
        CtsTestLauncher shard = new CtsTestLauncher(shardCount, shardIndex);
        try {
            OptionCopier.copyOptions(this, shard);
            shard.mIsSharded = true;
        } catch (ConfigurationException e) {
            // Bail out rather than run tests with unexpected options
            throw new RuntimeException("failed to copy options", e);
        }
        return shard;
    }

    @Override
    public Collection<IRemoteTest> split(int shardCountHint) {
        // If we are using replicated setup, only use local sharding
        if (mConfig.getCommandOptions().shouldUseReplicateSetup()) {
            mLocalShardingMode = true;
        }
        if (mLocalShardingMode) {
            // In local sharding mode, we let the subprocess do sharding.
            mShardCount = shardCountHint;
            return null;
        }
        if (shardCountHint <= 1 || mIsSharded) {
            // cannot shard or already sharded
            return null;
        }
        mIsSharded = true;
        Collection<IRemoteTest> shards = new ArrayList<>(shardCountHint);
        for (int index = 0; index < shardCountHint; index++) {
            shards.add(getTestShard(shardCountHint, index));
        }
        return shards;
    }

    private File getWorkDir() {
        File workDir = CurrentInvocation.getInfo(InvocationInfo.WORK_FOLDER);
        if (workDir == null || !workDir.exists()) {
            return null;
        }
        return workDir;
    }

    private String getJavaPath() {
        if (mUseBundledJava) {
            File rootDir = getTestsSuiteRootDir();
            File java = new File(rootDir, "jdk/bin/java");
            if (java.exists()) {
                return java.getAbsolutePath();
            }
        }
        return getSystemJava();
    }

    protected String getSystemJava() {
        return SystemUtil.getRunningJavaBinaryPath().getAbsolutePath();
    }
}
