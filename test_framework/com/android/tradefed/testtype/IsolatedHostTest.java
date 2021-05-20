/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.isolation.FilterSpec;
import com.android.tradefed.isolation.JUnitEvent;
import com.android.tradefed.isolation.RunnerMessage;
import com.android.tradefed.isolation.RunnerOp;
import com.android.tradefed.isolation.RunnerReply;
import com.android.tradefed.isolation.TestParameters;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SystemUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Implements a TradeFed runner that uses a subprocess to execute the tests in a low-dependency
 * environment instead of executing them on the main process.
 *
 * <p>This runner assumes that all of the jars configured are in the same test directory and
 * launches the subprocess in that directory. Since it must choose a working directory for the
 * subprocess, and many tests benefit from that directory being the test directory, this was the
 * best compromise available.
 */
@OptionClass(alias = "isolated-host-test")
public class IsolatedHostTest
        implements IRemoteTest,
                IBuildReceiver,
                ITestAnnotationFilterReceiver,
                ITestFilterReceiver,
                ITestCollector,
                IConfigurationReceiver {
    @Option(
            name = "class",
            description =
                    "The JUnit test classes to run, in the format <package>.<class>. eg."
                            + " \"com.android.foo.Bar\". This field can be repeated.",
            importance = Importance.IF_UNSET)
    private Set<String> mClasses = new LinkedHashSet<>();

    @Option(
            name = "jar",
            description = "The jars containing the JUnit test class to run.",
            importance = Importance.IF_UNSET)
    private Set<String> mJars = new HashSet<String>();

    @Option(
            name = "socket-timeout",
            description =
                    "The longest allowable time between messages from the subprocess before "
                            + "assuming that it has malfunctioned or died.",
            importance = Importance.IF_UNSET)
    private int mSocketTimeout = 1 * 60 * 1000;

    @Option(
            name = "include-annotation",
            description = "The set of annotations a test must have to be run.")
    private Set<String> mIncludeAnnotations = new HashSet<>();

    @Option(
            name = "exclude-annotation",
            description =
                    "The set of annotations to exclude tests from running. A test must have "
                            + "none of the annotations in this list to run.")
    private Set<String> mExcludeAnnotations = new HashSet<>();

    @Option(
            name = "java-flags",
            description =
                    "The set of flags to pass to the Java subprocess for complicated test "
                            + "needs.")
    private List<String> mJavaFlags = new ArrayList<>();

    @Option(
            name = "use-robolectric-resources",
            description =
                    "Option to put the Robolectric specific resources directory option on "
                            + "the Java command line.")
    private boolean mRobolectricResources = false;

    @Option(
            name = "exclude-paths",
            description = "The (prefix) paths to exclude from searching in the jars.")
    private Set<String> mExcludePaths =
            new HashSet<>(Arrays.asList("org/junit", "com/google/common/collect/testing/google"));

    @Option(
            name = "java-folder",
            description = "The JDK to be used. If unset, the JDK on $PATH will be used.")
    private File mJdkFolder = null;

    @Option(
            name = "classpath-override",
            description =
                    "[Local Debug Only] Force a classpath (isolation runner dependencies are still"
                            + " added to this classpath)")
    private String mClasspathOverride = null;

    @Option(
            name = "robolectric-android-all-name",
            description =
                    "The android-all resource jar to be used, e.g."
                            + " 'android-all-R-robolectric-r0.jar'")
    private String mAndroidAllName = "android-all-S-robolectric-r0.jar";

    @Option(
            name = TestTimeoutEnforcer.TEST_CASE_TIMEOUT_OPTION,
            description = TestTimeoutEnforcer.TEST_CASE_TIMEOUT_DESCRIPTION)
    private Duration mTestCaseTimeout = Duration.ofSeconds(0L);

    private static final String QUALIFIED_PATH = "/com/android/tradefed/isolation";
    private IBuildInfo mBuildInfo;
    private Set<String> mIncludeFilters = new HashSet<>();
    private Set<String> mExcludeFilters = new HashSet<>();
    private boolean mCollectTestsOnly = false;
    private File mSubprocessLog;
    private File mWorkDir;
    private boolean mReportedFailure = false;
    private IConfiguration mConfiguration;

    private static final String ROOT_DIR = "ROOT_DIR";
    private ServerSocket mServer = null;

    private File mCoverageDestination;
    private File mAgent;
    private File mIsolationJar;

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        mReportedFailure = false;
        mCoverageDestination = null;
        mAgent = null;
        try {
            mServer = new ServerSocket(0);
            mServer.setSoTimeout(mSocketTimeout);

            String classpath = this.compileClassPath();
            List<String> cmdArgs = this.compileCommandArgs(classpath);
            CLog.v(String.join(" ", cmdArgs));
            RunUtil runner = new RunUtil();

            // Note the below chooses a working directory based on the jar that happens to
            // be first in the list of configured jars.  The baked-in assumption is that
            // all configured jars are in the same parent directory, otherwise the behavior
            // here is non-deterministic.
            mWorkDir = findJarDirectory();
            runner.setWorkingDir(mWorkDir);
            CLog.v("Using PWD: %s", mWorkDir.getAbsolutePath());

            mSubprocessLog = FileUtil.createTempFile("subprocess-logs", "");
            runner.setRedirectStderrToStdout(true);

            Process isolationRunner =
                    runner.runCmdInBackground(Redirect.to(mSubprocessLog), cmdArgs);
            CLog.v("Started subprocess.");

            Socket socket = mServer.accept();
            socket.setSoTimeout(mSocketTimeout);
            CLog.v("Connected to subprocess.");

            List<String> testJarAbsPaths = getJarPaths(mJars);

            TestParameters.Builder paramsBuilder =
                    TestParameters.newBuilder()
                            .addAllTestClasses(mClasses)
                            .addAllTestJarAbsPaths(testJarAbsPaths)
                            .addAllExcludePaths(mExcludePaths)
                            .setDryRun(mCollectTestsOnly);

            if (!mIncludeFilters.isEmpty()
                    || !mExcludeFilters.isEmpty()
                    || !mIncludeAnnotations.isEmpty()
                    || !mExcludeAnnotations.isEmpty()) {
                paramsBuilder.setFilter(
                        FilterSpec.newBuilder()
                                .addAllIncludeFilters(mIncludeFilters)
                                .addAllExcludeFilters(mExcludeFilters)
                                .addAllIncludeAnnotations(mIncludeAnnotations)
                                .addAllExcludeAnnotations(mExcludeAnnotations));
            }
            executeTests(socket, listener, paramsBuilder.build());

            RunnerMessage.newBuilder()
                    .setCommand(RunnerOp.RUNNER_OP_STOP)
                    .build()
                    .writeDelimitedTo(socket.getOutputStream());
            // Ensure the subprocess finishes
            isolationRunner.waitFor(1, TimeUnit.MINUTES);
        } catch (IOException | InterruptedException e) {
            if (!mReportedFailure) {
                // Avoid overriding the failure
                FailureDescription failure =
                        FailureDescription.create(
                                StreamUtil.getStackTrace(e), FailureStatus.INFRA_FAILURE);
                listener.testRunFailed(failure);
                listener.testRunEnded(0L, new HashMap<String, Metric>());
            }
        } finally {
            FileUtil.deleteFile(mIsolationJar);
            FileUtil.deleteFile(mAgent);
            mAgent = null;
            if (mCoverageDestination != null && mCoverageDestination.length() > 0) {
                try (FileInputStreamSource source =
                        new FileInputStreamSource(mCoverageDestination, true)) {
                    listener.testLog("coverage", LogDataType.COVERAGE, source);
                }
                mCoverageDestination = null;
            }
        }
    }

    /** Assembles the command arguments to execute the subprocess runner. */
    public List<String> compileCommandArgs(String classpath) {
        List<String> cmdArgs = new ArrayList<>();

        if (mJdkFolder == null) {
            cmdArgs.add(SystemUtil.getRunningJavaBinaryPath().getAbsolutePath());
            CLog.v("Using host java version.");
        } else {
            File javaExec = FileUtil.findFile(mJdkFolder, "java");
            if (javaExec == null) {
                throw new IllegalArgumentException(
                        String.format(
                                "Couldn't find java executable in given JDK folder: %s",
                                mJdkFolder.getAbsolutePath()));
            }
            String javaPath = javaExec.getAbsolutePath();
            cmdArgs.add(javaPath);
            CLog.v("Using java executable at %s", javaPath);
        }
        if (mConfiguration != null && mConfiguration.getCoverageOptions().isCoverageEnabled()) {
            try {
                mCoverageDestination = FileUtil.createTempFile("coverage", ".exec");
                mAgent = extractJacocoAgent();
                String javaAgent =
                        String.format(
                                "-javaagent:%s=destfile=%s",
                                mAgent.getAbsolutePath(), mCoverageDestination.getAbsolutePath());
                cmdArgs.add(javaAgent);
            } catch (IOException e) {
                CLog.e(e);
            }
        }
        cmdArgs.add("-cp");
        cmdArgs.add(classpath);

        cmdArgs.addAll(mJavaFlags);

        if (mRobolectricResources) {
            cmdArgs.addAll(compileRobolectricOptions());
        }

        cmdArgs.addAll(
                List.of(
                        "com.android.tradefed.isolation.IsolationRunner",
                        "-",
                        "--port",
                        Integer.toString(mServer.getLocalPort()),
                        "--address",
                        mServer.getInetAddress().getHostAddress(),
                        "--timeout",
                        Integer.toString(mSocketTimeout)));
        return cmdArgs;
    }

    /**
     * Finds the directory where the first configured jar is located.
     *
     * <p>This is used to determine the correct folder to use for a working directory for the
     * subprocess runner.
     */
    private File findJarDirectory() {
        File testDir = findTestDirectory();
        for (String jar : mJars) {
            File f = FileUtil.findFile(testDir, jar);
            if (f != null && f.exists()) {
                return f.getParentFile();
            }
        }
        return null;
    }

    /**
     * Retrieves the file registered in the build info as the test directory
     *
     * @return a {@link File} object representing the test directory
     */
    private File findTestDirectory() {
        File testsDir = mBuildInfo.getFile(BuildInfoFileKey.HOST_LINKED_DIR);
        if (testsDir != null && testsDir.exists()) {
            return testsDir;
        }
        testsDir = mBuildInfo.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        if (testsDir != null && testsDir.exists()) {
            return testsDir;
        }
        throw new IllegalArgumentException("Test directory not found, cannot proceed");
    }

    /**
     * Creates a classpath for the subprocess that includes the needed jars to run the tests
     *
     * @return a string specifying the colon separated classpath.
     */
    private String compileClassPath() {
        List<String> paths = new ArrayList<>();
        File testDir = findTestDirectory();

        try {
            File isolationJar = getIsolationJar(CurrentInvocation.getWorkFolder());
            paths.add(isolationJar.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (mClasspathOverride != null) {
            paths.add(mClasspathOverride);
        } else {
            if (mRobolectricResources) {
                // This is contingent on the current android-all version.
                File androidAllJar = FileUtil.findFile(testDir, mAndroidAllName);
                if (androidAllJar == null) {
                    throw new RuntimeException(
                            "Could not find android-all jar needed for test execution.");
                }
                paths.add(androidAllJar.getAbsolutePath());
            }

            for (String jar : mJars) {
                File f = FileUtil.findFile(testDir, jar);
                if (f != null && f.exists()) {
                    paths.add(f.getAbsolutePath());
                    String parentPath = f.getParentFile().getAbsolutePath() + "/*";
                    if (!paths.contains(parentPath)) {
                        paths.add(parentPath);
                    }
                }
            }
        }

        String jarClasspath = String.join(java.io.File.pathSeparator, paths);

        return jarClasspath;
    }

    private List<String> compileRobolectricOptions() {
        List<String> options = new ArrayList<>();
        File testDir = findTestDirectory();
        String dependencyDir =
                "-Drobolectric.dependency.dir=" + testDir.getAbsolutePath() + "/android-all/";

        options.add(dependencyDir);
        options.add("-Drobolectric.offline=true");
        options.add("-Drobolectric.logging=stdout");
        options.add("-Drobolectric.resourcesMode=binary");

        // TODO(murj) hide these options behind a debug option
        // options.add("-Drobolectric.logging.enabled=true");
        // options.add("-Xdebug");
        // options.add("-Xrunjdwp:transport=dt_socket,address=8600,server=y,suspend=y");

        return options;
    }

    /**
     * Runs the tests by talking to the subprocess assuming the setup is done.
     *
     * @param socket A socket connected to the subprocess control socket
     * @param listener The TradeFed invocation listener from run()
     * @param params The tests to run and their options
     * @throws IOException
     */
    private void executeTests(
            Socket socket, ITestInvocationListener listener, TestParameters params)
            throws IOException {
        // If needed apply the wrapping listeners like timeout enforcer.
        listener = wrapListener(listener);
        RunnerMessage.newBuilder()
                .setCommand(RunnerOp.RUNNER_OP_RUN_TEST)
                .setParams(params)
                .build()
                .writeDelimitedTo(socket.getOutputStream());

        TestDescription currentTest = null;
        Instant start = Instant.now();

        boolean runStarted = false;
        try {
            mainLoop:
            while (true) {
                try {
                    RunnerReply reply = RunnerReply.parseDelimitedFrom(socket.getInputStream());
                    if (reply == null) {
                        if (currentTest != null) {
                            // Subprocess has hard crashed
                            listener.testFailed(currentTest, "Subprocess died unexpectedly.");
                            listener.testEnded(
                                    currentTest,
                                    System.currentTimeMillis(),
                                    new HashMap<String, Metric>());
                        }
                        // Try collecting the hs_err logs that the JVM dumps when it segfaults.
                        List<File> logFiles =
                                Arrays.stream(mWorkDir.listFiles())
                                        .filter(
                                                f ->
                                                        f.getName().startsWith("hs_err")
                                                                && f.getName().endsWith(".log"))
                                        .collect(Collectors.toList());

                        if (!runStarted) {
                            listener.testRunStarted(this.getClass().getCanonicalName(), 0);
                        }
                        for (File f : logFiles) {
                            try (FileInputStreamSource source =
                                    new FileInputStreamSource(f, true)) {
                                listener.testLog("hs_err_log-VM-crash", LogDataType.TEXT, source);
                            }
                        }
                        mReportedFailure = true;
                        FailureDescription failure =
                                FailureDescription.create(
                                                "The subprocess died unexpectedly.",
                                                FailureStatus.TEST_FAILURE)
                                        .setFullRerun(false);
                        listener.testRunFailed(failure);
                        listener.testRunEnded(0L, new HashMap<String, Metric>());
                        break mainLoop;
                    }
                    switch (reply.getRunnerStatus()) {
                        case RUNNER_STATUS_FINISHED_OK:
                            CLog.v("Received message that runner finished successfully");
                            break mainLoop;
                        case RUNNER_STATUS_FINISHED_ERROR:
                            CLog.e("Received message that runner errored");
                            CLog.e("From Runner: " + reply.getMessage());
                            if (!runStarted) {
                                listener.testRunStarted(this.getClass().getCanonicalName(), 0);
                            }
                            FailureDescription failure =
                                    FailureDescription.create(
                                            reply.getMessage(), FailureStatus.INFRA_FAILURE);
                            listener.testRunFailed(failure);
                            listener.testRunEnded(0L, new HashMap<String, Metric>());
                            break mainLoop;
                        case RUNNER_STATUS_STARTING:
                            CLog.v("Received message that runner is starting");
                            break;
                        default:
                            if (reply.hasTestEvent()) {
                                JUnitEvent event = reply.getTestEvent();
                                TestDescription desc;
                                switch (event.getTopic()) {
                                    case TOPIC_FAILURE:
                                        desc =
                                                new TestDescription(
                                                        event.getClassName(),
                                                        event.getMethodName());
                                        listener.testFailed(desc, event.getMessage());
                                        break;
                                    case TOPIC_ASSUMPTION_FAILURE:
                                        desc =
                                                new TestDescription(
                                                        event.getClassName(),
                                                        event.getMethodName());
                                        listener.testAssumptionFailure(desc, reply.getMessage());
                                        break;
                                    case TOPIC_STARTED:
                                        desc =
                                                new TestDescription(
                                                        event.getClassName(),
                                                        event.getMethodName());
                                        listener.testStarted(desc, event.getStartTime());
                                        currentTest = desc;
                                        break;
                                    case TOPIC_FINISHED:
                                        desc =
                                                new TestDescription(
                                                        event.getClassName(),
                                                        event.getMethodName());
                                        listener.testEnded(
                                                desc,
                                                event.getEndTime(),
                                                new HashMap<String, Metric>());
                                        currentTest = null;
                                        break;
                                    case TOPIC_IGNORED:
                                        desc =
                                                new TestDescription(
                                                        event.getClassName(),
                                                        event.getMethodName());
                                        listener.testIgnored(desc);
                                        break;
                                    case TOPIC_RUN_STARTED:
                                        runStarted = true;
                                        listener.testRunStarted(
                                                event.getClassName(), event.getTestCount());
                                        break;
                                    case TOPIC_RUN_FINISHED:
                                        listener.testRunEnded(
                                                event.getElapsedTime(),
                                                new HashMap<String, Metric>());
                                        break;
                                    default:
                                }
                            }
                    }
                } catch (SocketTimeoutException e) {
                    mReportedFailure = true;
                    FailureDescription failure =
                            FailureDescription.create(
                                    StreamUtil.getStackTrace(e), FailureStatus.INFRA_FAILURE);
                    listener.testRunFailed(failure);
                    listener.testRunEnded(
                            Duration.between(start, Instant.now()).toMillis(),
                            new HashMap<String, Metric>());
                    break mainLoop;
                }
            }
        } finally {
            // This will get associated with the module since it can contains several test runs
            try (FileInputStreamSource source = new FileInputStreamSource(mSubprocessLog, true)) {
                listener.testLog("isolated-java-logs", LogDataType.TEXT, source);
            }
        }
    }

    /**
     * Utility method to searh for absolute paths for JAR files. Largely the same as in the HostTest
     * implementation, but somewhat difficult to extract well due to the various method calls it
     * uses.
     */
    private List<String> getJarPaths(Set<String> jars) throws FileNotFoundException {
        Set<String> output = new HashSet<>();

        for (String jar : jars) {
            File jarFile = getJarFile(jar, mBuildInfo);
            output.add(jarFile.getAbsolutePath());
        }

        return output.stream().collect(Collectors.toList());
    }

    /**
     * Inspect several location where the artifact are usually located for different use cases to
     * find our jar.
     */
    private File getJarFile(String jarName, IBuildInfo buildInfo) throws FileNotFoundException {
        // Check tests dir
        File testDir = buildInfo.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        File jarFile = searchJarFile(testDir, jarName);
        if (jarFile != null) {
            return jarFile;
        }

        // Check ROOT_DIR
        if (buildInfo.getBuildAttributes().get(ROOT_DIR) != null) {
            jarFile =
                    searchJarFile(new File(buildInfo.getBuildAttributes().get(ROOT_DIR)), jarName);
        }
        if (jarFile != null) {
            return jarFile;
        }
        throw new FileNotFoundException(String.format("Could not find jar: %s", jarName));
    }

    /** Looks for a jar file given a place to start and a filename. */
    private File searchJarFile(File baseSearchFile, String jarName) {
        if (baseSearchFile != null && baseSearchFile.isDirectory()) {
            File jarFile = FileUtil.findFile(baseSearchFile, jarName);
            if (jarFile != null && jarFile.isFile()) {
                return jarFile;
            }
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void setBuild(IBuildInfo build) {
        mBuildInfo = build;
    }

    /** {@inheritDoc} */
    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
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
    public void setCollectTestsOnly(boolean shouldCollectTest) {
        mCollectTestsOnly = shouldCollectTest;
    }

    /** {@inheritDoc} */
    @Override
    public void addIncludeAnnotation(String annotation) {
        mIncludeAnnotations.add(annotation);
    }

    /** {@inheritDoc} */
    @Override
    public void addExcludeAnnotation(String notAnnotation) {
        mExcludeAnnotations.add(notAnnotation);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllIncludeAnnotation(Set<String> annotations) {
        mIncludeAnnotations.addAll(annotations);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllExcludeAnnotation(Set<String> notAnnotations) {
        mExcludeAnnotations.addAll(notAnnotations);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getIncludeAnnotations() {
        return mIncludeAnnotations;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getExcludeAnnotations() {
        return mExcludeAnnotations;
    }

    /** {@inheritDoc} */
    @Override
    public void clearIncludeAnnotations() {
        mIncludeAnnotations.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearExcludeAnnotations() {
        mExcludeAnnotations.clear();
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    /**
     * Copied over from HostTest to mimic its unit test harnessing.
     *
     * <p>Inspect several location where the artifact are usually located for different use cases to
     * find our jar.
     */
    @VisibleForTesting
    protected File getJarFile(String jarName, TestInformation testInfo)
            throws FileNotFoundException {
        return testInfo.getDependencyFile(jarName, /* target first*/ false);
    }

    @VisibleForTesting
    protected void setServer(ServerSocket server) {
        mServer = server;
    }

    private ITestInvocationListener wrapListener(ITestInvocationListener listener) {
        if (mTestCaseTimeout.toMillis() > 0L) {
            listener =
                    new TestTimeoutEnforcer(
                            mTestCaseTimeout.toMillis(), TimeUnit.MILLISECONDS, listener);
        }
        return listener;
    }

    /** Returns a {@link File} pointing to the jacoco args jar file extracted from the resources. */
    private File extractJacocoAgent() throws IOException {
        String jacocoAgentRes = "/jacoco/jacocoagent.jar";
        InputStream jacocoAgentStream = getClass().getResourceAsStream(jacocoAgentRes);
        if (jacocoAgentStream == null) {
            throw new IOException("Could not find " + jacocoAgentRes);
        }
        File jacocoAgent = FileUtil.createTempFile("jacocoagent", ".jar");
        FileUtil.writeToFile(jacocoAgentStream, jacocoAgent);
        return jacocoAgent;
    }

    private File getIsolationJar(File workDir) throws IOException {
        try (InputStream jarFileStream = getClass().getResourceAsStream("/tradefed-isolation.jar");
                InputStream qualifiedJarStream =
                        getClass()
                                .getResourceAsStream(
                                        QUALIFIED_PATH + "/tradefed-isolation_deploy.jar")) {
            if (jarFileStream == null && qualifiedJarStream == null) {
                throw new RuntimeException("/tradefed-isolation.jar not found.");
            }
            mIsolationJar = FileUtil.createTempFile("tradefed-isolation", ".jar", workDir);
            if (qualifiedJarStream != null) {
                FileUtil.writeToFile(qualifiedJarStream, mIsolationJar);
            } else {
                FileUtil.writeToFile(jarFileStream, mIsolationJar);
            }
            return mIsolationJar;
        }
    }
}
