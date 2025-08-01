/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.TestAppInstallSetup;
import com.android.tradefed.testtype.suite.params.InstantAppHandler;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ListInstrumentationParser;
import com.android.tradefed.util.ResourceUtil;

import com.google.common.annotations.VisibleForTesting;

import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A Test that runs an instrumentation test package on given device using the
 * android.support.test.runner.AndroidJUnitRunner.
 */
@OptionClass(alias = "android-junit")
public class AndroidJUnitTest extends InstrumentationTest
        implements IRuntimeHintProvider,
                ITestFileFilterReceiver,
                ITestFilterReceiver,
                ITestAnnotationFilterReceiver,
                IShardableTest {

    /** instrumentation test runner argument key used for including a class/test */
    private static final String INCLUDE_CLASS_INST_ARGS_KEY = "class";
    /** instrumentation test runner argument key used for excluding a class/test */
    private static final String EXCLUDE_CLASS_INST_ARGS_KEY = "notClass";
    /** instrumentation test runner argument key used for including a package */
    private static final String INCLUDE_PACKAGE_INST_ARGS_KEY = "package";
    /** instrumentation test runner argument key used for excluding a package */
    private static final String EXCLUDE_PACKAGE_INST_ARGS_KEY = "notPackage";
    /** instrumentation test runner argument key used for including a test regex */
    private static final String INCLUDE_REGEX_INST_ARGS_KEY = "tests_regex";
    /** instrumentation test runner argument key used for adding annotation filter */
    private static final String ANNOTATION_INST_ARGS_KEY = "annotation";
    /** instrumentation test runner argument key used for adding notAnnotation filter */
    private static final String NOT_ANNOTATION_INST_ARGS_KEY = "notAnnotation";
    /** instrumentation test runner argument used for adding testFile filter */
    private static final String TEST_FILE_INST_ARGS_KEY = "testFile";
    /** instrumentation test runner argument used for adding notTestFile filter */
    private static final String NOT_TEST_FILE_INST_ARGS_KEY = "notTestFile";
    /** instrumentation test runner argument used to specify the shardIndex of the test */
    private static final String SHARD_INDEX_INST_ARGS_KEY = "shardIndex";
    /** instrumentation test runner argument used to specify the total number of shards */
    private static final String NUM_SHARD_INST_ARGS_KEY = "numShards";
    /**
     * instrumentation test runner argument used to enable the new {@link RunListener} order on
     * device side.
     */
    public static final String NEW_RUN_LISTENER_ORDER_KEY = "newRunListenerMode";

    public static final String USE_TEST_STORAGE_SERVICE = "useTestStorageService";

    /** Options from the collector side helper library. */
    public static final String INCLUDE_COLLECTOR_FILTER_KEY = "include-filter-group";

    public static final String EXCLUDE_COLLECTOR_FILTER_KEY = "exclude-filter-group";

    private static final String INCLUDE_FILE = "includes.txt";
    private static final String EXCLUDE_FILE = "excludes.txt";

    @Option(name = "runtime-hint",
            isTimeVal=true,
            description="The hint about the test's runtime.")
    private long mRuntimeHint = 60000;// 1 minute

    @Option(
            name = "include-filter",
            description = "The include filters of the test name to run.",
            requiredForRerun = true)
    private Set<String> mIncludeFilters = new LinkedHashSet<>();

    @Option(
            name = "exclude-filter",
            description = "The exclude filters of the test name to run.",
            requiredForRerun = true)
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    @Option(
            name = "include-annotation",
            description = "The annotation class name of the test name to run, can be repeated",
            requiredForRerun = true)
    private Set<String> mIncludeAnnotation = new LinkedHashSet<>();

    @Option(
            name = "exclude-annotation",
            description = "The notAnnotation class name of the test name to run, can be repeated",
            requiredForRerun = true)
    private Set<String> mExcludeAnnotation = new LinkedHashSet<>();

    @Option(name = "test-filter-dir",
            description="The device directory path to which the test filtering files are pushed")
    private String mTestFilterDir = "/data/local/tmp/ajur";

    @Option(
            name = "test-storage-dir",
            description = "The device directory path where test storage read files.")
    private String mTestStorageInternalDir = "/sdcard/googletest/test_runfiles";

    @Option(
            name = "use-test-storage",
            description =
                    "If set to true, we will push filters to the test storage instead of disk.")
    private boolean mUseTestStorage = true;

    @Option(
            name = "ajur-max-shard",
            description =
                    "The maximum number of shard we want to allow the AJUR test to shard into")
    private Integer mMaxShard = 4;

    @Option(
            name = "device-listeners",
            description =
                    "Specify device side instrumentation listeners to be added for the run. "
                            + "Can be repeated. Note that while the ordering here is followed for "
                            + "now, future versions of AndroidJUnitRunner might not preserve the "
                            + "listener ordering.")
    private Set<String> mExtraDeviceListeners = new LinkedHashSet<>();

    @Option(
        name = "use-new-run-listener-order",
        description = "Enables the new RunListener Order for AJUR."
    )
    // Default to true as it is harmless if not supported.
    private boolean mNewRunListenerOrderMode = true;

    private File mInternalIncludeTestFile = null;
    private File mInternalExcludeTestFile = null;
    private String mDeviceIncludeFile = null;
    private String mDeviceExcludeFile = null;
    private int mTotalShards = 0;
    private int mShardIndex = 0;
    // Flag to avoid re-sharding a test that already was.
    private boolean mIsSharded = false;

    public AndroidJUnitTest() {
        super();
        setEnforceFormat(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRuntimeHint() {
        return mRuntimeHint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    /**
     * {@inheritDoc}
     */
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
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void setIncludeTestFile(File testFile) {
        mInternalIncludeTestFile = testFile;
    }

    /** {@inheritDoc} */
    @Override
    public File getIncludeTestFile() {
        return mInternalIncludeTestFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setExcludeTestFile(File testFile) {
        mInternalExcludeTestFile = testFile;
    }

    /** {@inheritDoc} */
    @Override
    public File getExcludeTestFile() {
        return mInternalExcludeTestFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIncludeAnnotation(String annotation) {
        mIncludeAnnotation.add(annotation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAllIncludeAnnotation(Set<String> annotations) {
        mIncludeAnnotation.addAll(annotations);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addExcludeAnnotation(String excludeAnnotation) {
        mExcludeAnnotation.add(excludeAnnotation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAllExcludeAnnotation(Set<String> excludeAnnotations) {
        mExcludeAnnotation.addAll(excludeAnnotations);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getIncludeAnnotations() {
        return mIncludeAnnotation;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getExcludeAnnotations() {
        return mExcludeAnnotation;
    }

    /** {@inheritDoc} */
    @Override
    public void clearIncludeAnnotations() {
        mIncludeAnnotation.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearExcludeAnnotations() {
        mExcludeAnnotation.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        if (getDevice() == null) {
            throw new IllegalArgumentException("Device has not been set");
        }
        if (mUseTestStorage) {
            // Check if we are a parameterized module
            List<String> params =
                    getConfiguration()
                            .getConfigurationDescription()
                            .getMetaData(ConfigurationDescriptor.ACTIVE_PARAMETER_KEY);
            if (params != null && params.contains(InstantAppHandler.INSTANT_APP_ID)) {
                mUseTestStorage = false;
                CLog.d("Disable test storage on instant app module.");
            } else if (isTestRunningOnSdkSandbox(testInfo)) {
                // SDK sandboxes don't have access to the test ContentProvider.
                mUseTestStorage = false;
                CLog.d("Disable test storage for SDK sandbox instrumentation tests.");
            } else {
                mUseTestStorage = getDevice().checkApiLevelAgainstNextRelease(34);
                if (!mUseTestStorage) {
                    CLog.d("Disabled test storage as it's not supported on that branch.");
                }
            }
        }

        boolean pushedFile = false;
        try (CloseableTraceScope filter = new CloseableTraceScope("push_filter_files")) {
            // if mInternalIncludeTestFile is set, perform filtering with this file
            if (mInternalIncludeTestFile != null && mInternalIncludeTestFile.length() > 0) {
                mDeviceIncludeFile = mTestFilterDir.replaceAll("/$", "") + "/" + INCLUDE_FILE;
                pushTestFile(mInternalIncludeTestFile, mDeviceIncludeFile, listener, false);
                if (mUseTestStorage) {
                    pushTestFile(
                            mInternalIncludeTestFile,
                            mTestStorageInternalDir + mDeviceIncludeFile,
                            listener,
                            true);
                }
                pushedFile = true;
                // If an explicit include file filter is provided, do not use the package
                setTestPackageName(null);
            }

            // if mInternalExcludeTestFile is set, perform filtering with this file
            if (mInternalExcludeTestFile != null && mInternalExcludeTestFile.length() > 0) {
                mDeviceExcludeFile = mTestFilterDir.replaceAll("/$", "") + "/" + EXCLUDE_FILE;
                pushTestFile(mInternalExcludeTestFile, mDeviceExcludeFile, listener, false);
                if (mUseTestStorage) {
                    pushTestFile(
                            mInternalExcludeTestFile,
                            mTestStorageInternalDir + mDeviceExcludeFile,
                            listener,
                            true);
                }
                pushedFile = true;
            }
        }
        TestAppInstallSetup serviceInstaller = null;
        if (mUseTestStorage) {
            File testServices = null;
            try (CloseableTraceScope serviceInstall =
                    new CloseableTraceScope("install_service_apk")) {
                testServices = FileUtil.createTempFile("services", ".apk");
                boolean extracted =
                        ResourceUtil.extractResourceAsFile(
                                "/test-services-normalized.apk", testServices);
                if (extracted) {
                    serviceInstaller = new TestAppInstallSetup();
                    // Service apk needs force-queryable
                    serviceInstaller.setForceQueryable(true);
                    serviceInstaller.addTestFile(testServices);
                    if (testInfo != null
                            && testInfo.properties().containsKey(RUN_TESTS_AS_USER_KEY)) {
                        serviceInstaller.setUserId(
                                Integer.parseInt(testInfo.properties().get(RUN_TESTS_AS_USER_KEY)));
                    }
                    serviceInstaller.setUp(testInfo);
                    // Turn off battery optimization for androidx.test.services
                    CommandResult dumpsys =
                            getDevice()
                                    .executeShellV2Command(
                                            "dumpsys deviceidle whitelist +androidx.test.services");
                    CLog.d("stdout: %s\nstderr: %s", dumpsys.getStdout(), dumpsys.getStderr());
                } else {
                    throw new IOException("Failed to extract test-services.apk");
                }
            } catch (IOException | TargetSetupError | BuildError e) {
                CLog.e(e);
                mUseTestStorage = false;
            } finally {
                FileUtil.deleteFile(testServices);
            }
        }
        if (mTotalShards > 0 && !isShardable() && mShardIndex != 0) {
            // If not shardable, only first shard can run.
            CLog.i("%s is not shardable.", getRunnerName());
            return;
        }
        super.run(testInfo, listener);
        if (serviceInstaller != null) {
            try (CloseableTraceScope serviceTeardown =
                    new CloseableTraceScope("service_teardown")) {
                serviceInstaller.tearDown(testInfo, null);
            }
        }
        if (pushedFile) {
            // Remove the directory where the files where pushed
            removeTestFilterDir();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setRunnerArgs(IRemoteAndroidTestRunner runner) {
        super.setRunnerArgs(runner);

        // if mIncludeTestFile is set, perform filtering with this file
        if (mDeviceIncludeFile != null) {
            runner.addInstrumentationArg(TEST_FILE_INST_ARGS_KEY, mDeviceIncludeFile);
        }

        // if mExcludeTestFile is set, perform filtering with this file
        if (mDeviceExcludeFile != null) {
            runner.addInstrumentationArg(NOT_TEST_FILE_INST_ARGS_KEY, mDeviceExcludeFile);
        }

        // Split filters into class, notClass, package and notPackage
        List<String> classArg = new ArrayList<String>();
        List<String> notClassArg = new ArrayList<String>();
        List<String> packageArg = new ArrayList<String>();
        List<String> notPackageArg = new ArrayList<String>();
        List<String> regexArg = new ArrayList<String>();
        for (String test : mIncludeFilters) {
            if (isRegex(test)) {
                regexArg.add(test);
            } else if (isClassOrMethod(test)) {
                classArg.add(test);
            } else {
                packageArg.add(test);
            }
        }
        for (String test : mExcludeFilters) {
            // tests_regex doesn't support exclude-filter. Therefore, only check if the filter is
            // for class/method or package.
            if (isClassOrMethod(test)) {
                notClassArg.add(test);
            } else {
                notPackageArg.add(test);
            }
        }
        if (!regexArg.isEmpty()
                && (!classArg.isEmpty()
                        || !notClassArg.isEmpty()
                        || !packageArg.isEmpty()
                        || !notPackageArg.isEmpty())) {
            StringBuilder sb = new StringBuilder();
            if (!classArg.isEmpty()) {
                sb.append("classArg: " + classArg);
            }
            if (!notClassArg.isEmpty()) {
                sb.append("notClassArg: " + notClassArg);
            }
            if (!packageArg.isEmpty()) {
                sb.append("packageArg: " + packageArg);
            }
            if (!notPackageArg.isEmpty()) {
                sb.append("notPackageArg: " + notPackageArg);
            }
            throw new IllegalArgumentException(
                    String.format(
                            "Mixed filter types found. AndroidJUnitTest does not support mixing"
                                    + " both regex [%s] and class/method/package filters: [%s]",
                            regexArg, sb.toString()));
        }
        if (!classArg.isEmpty()) {
            runner.addInstrumentationArg(INCLUDE_CLASS_INST_ARGS_KEY,
                    ArrayUtil.join(",", classArg));
        }
        if (!notClassArg.isEmpty()) {
            runner.addInstrumentationArg(EXCLUDE_CLASS_INST_ARGS_KEY,
                    ArrayUtil.join(",", notClassArg));
        }
        if (!packageArg.isEmpty()) {
            runner.addInstrumentationArg(INCLUDE_PACKAGE_INST_ARGS_KEY,
                    ArrayUtil.join(",", packageArg));
        }
        if (!notPackageArg.isEmpty()) {
            runner.addInstrumentationArg(EXCLUDE_PACKAGE_INST_ARGS_KEY,
                    ArrayUtil.join(",", notPackageArg));
        }
        if (!regexArg.isEmpty()) {
            String regexFilter;
            if (regexArg.size() == 1) {
                regexFilter = regexArg.get(0);
            } else {
                Collections.sort(regexArg);
                regexFilter = "\"(" + ArrayUtil.join("|", regexArg) + ")\"";
            }
            runner.addInstrumentationArg(INCLUDE_REGEX_INST_ARGS_KEY, regexFilter);
        }
        if (!mIncludeAnnotation.isEmpty()) {
            runner.addInstrumentationArg(ANNOTATION_INST_ARGS_KEY,
                    ArrayUtil.join(",", mIncludeAnnotation));
        }
        if (!mExcludeAnnotation.isEmpty()) {
            runner.addInstrumentationArg(NOT_ANNOTATION_INST_ARGS_KEY,
                    ArrayUtil.join(",", mExcludeAnnotation));
        }
        if (mTotalShards > 0 && isShardable()) {
            runner.addInstrumentationArg(SHARD_INDEX_INST_ARGS_KEY, Integer.toString(mShardIndex));
            runner.addInstrumentationArg(NUM_SHARD_INST_ARGS_KEY, Integer.toString(mTotalShards));
        }
        if (mNewRunListenerOrderMode) {
            runner.addInstrumentationArg(
                    NEW_RUN_LISTENER_ORDER_KEY, Boolean.toString(mNewRunListenerOrderMode));
        }
        if (mUseTestStorage) {
            runner.addInstrumentationArg(
                    USE_TEST_STORAGE_SERVICE, Boolean.toString(mUseTestStorage));
        }
        // Add the listeners received from Options
        addDeviceListeners(mExtraDeviceListeners);
    }

    /**
     * Push the testFile to the requested destination. This should only be called for a non-null
     * testFile
     *
     * @param testFile file to be pushed from the host to the device.
     * @param destination the path on the device to which testFile is pushed
     * @param listener {@link ITestInvocationListener} to report failures.
     */
    private void pushTestFile(
            File testFile, String destination, ITestInvocationListener listener, boolean skipLog)
            throws DeviceNotAvailableException {
        if (!testFile.canRead() || !testFile.isFile()) {
            String message = String.format("Cannot read test file %s", testFile.getAbsolutePath());
            reportEarlyFailure(listener, message);
            throw new IllegalArgumentException(message);
        }
        ITestDevice device = getDevice();
        try {
            CLog.d("Attempting to push filters to %s", destination);
            boolean filterDirExists = device.doesFileExist(mTestFilterDir);
            if (!device.pushFile(testFile, destination, true)) {
                String message =
                        String.format(
                                "Failed to push file %s to %s for %s in pushTestFile",
                                testFile.getAbsolutePath(), destination, device.getSerialNumber());
                reportEarlyFailure(listener, message);
                throw new RuntimeException(message);
            }
            // in case the folder was created as 'root' we make is usable.
            if (!filterDirExists) {
                device.executeShellCommand(
                        String.format("chown -R shell:shell %s", mTestFilterDir));
                boolean filterExists = device.doesFileExist(destination);
                if (!filterExists) {
                    CLog.e("Filter '%s' wasn't found on device after pushing.", destination);
                }
            }
        } catch (DeviceNotAvailableException e) {
            reportEarlyFailure(listener, e.getMessage());
            throw e;
        }
        if (skipLog) {
            return;
        }
        try (FileInputStreamSource source = new FileInputStreamSource(testFile)) {
            listener.testLog("filter-" + testFile.getName(), LogDataType.TEXT, source);
        }
    }

    private void removeTestFilterDir() throws DeviceNotAvailableException {
        getDevice().deleteFile(mTestFilterDir);
    }

    private void reportEarlyFailure(ITestInvocationListener listener, String errorMessage) {
        listener.testRunStarted("AndroidJUnitTest_setupError", 0);
        FailureDescription failure = FailureDescription.create(errorMessage);
        failure.setFailureStatus(FailureStatus.INFRA_FAILURE);
        listener.testRunFailed(failure);
        listener.testRunEnded(0, new HashMap<String, Metric>());
    }

    /**
     * Return if a string is the name of a Class or a Method.
     */
    @VisibleForTesting
    public boolean isClassOrMethod(String filter) {
        if (filter.contains("#")) {
            return true;
        }
        String[] parts = filter.split("\\.");
        if (parts.length > 0) {
            // FIXME Assume java package names starts with lowercase and class names start with
            // uppercase.
            // Return true iff the first character of the last word is uppercase
            // com.android.foobar.Test
            return Character.isUpperCase(parts[parts.length - 1].charAt(0));
        }
        return false;
    }

    /** Return if a string is a regex for filter. */
    @VisibleForTesting
    public boolean isRegex(String filter) {
        if (isParameterizedTest(filter)) {
            return false;
        }

        // If filter contains any special regex character, return true.
        // Throw RuntimeException if the regex is invalid.
        if (Pattern.matches(".*[\\?\\*\\^\\$\\(\\)\\[\\]\\{\\}\\|\\\\].*", filter)) {
            try {
                Pattern.compile(filter);
            } catch (PatternSyntaxException e) {
                CLog.e("Filter %s is not a valid regular expression string.", filter);
                throw new RuntimeException(e);
            }
            return true;
        }

        return false;
    }

    /** Return if a string is a parameterized test. */
    @VisibleForTesting
    public boolean isParameterizedTest(String filter) {
        // If filter contains '#', '[', ']' and must ends with ']'. Only numbers, a-Z, -, _,
        // [, ], (, ), and . are allowed between [].
        if (Pattern.matches(".*#.*\\[[0-9a-zA-Z,\\-_.\\[\\]\\(\\)]*\\]$", filter)) {
            CLog.i("Filter %s is a parameterized string.", filter);
            return true;
        }
        return false;
    }

    /**
     * Helper to return if the runner is one that support sharding.
     */
    private boolean isShardable() {
        // Edge toward shardable if no explicit runner specified. The runner will be determined
        // later and if not shardable only the first shard will run.
        if (getRunnerName() == null) {
            return true;
        }
        return ListInstrumentationParser.SHARDABLE_RUNNERS.contains(getRunnerName());
    }

    /** {@inheritDoc} */
    @Override
    public Collection<IRemoteTest> split(int shardCount) {
        if (!isShardable()) {
            return null;
        }
        if (mMaxShard != null) {
            shardCount = Math.min(shardCount, mMaxShard);
        }
        if (!mIsSharded && shardCount > 1) {
            mIsSharded = true;
            Collection<IRemoteTest> shards = new ArrayList<>(shardCount);
            for (int index = 0; index < shardCount; index++) {
                shards.add(getTestShard(shardCount, index));
            }
            return shards;
        }
        return null;
    }

    private IRemoteTest getTestShard(int shardCount, int shardIndex) {
        AndroidJUnitTest shard;
        // ensure we handle runners that extend AndroidJUnitRunner
        try {
            shard = this.getClass().getDeclaredConstructor().newInstance();
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        try {
            OptionCopier.copyOptions(this, shard);
        } catch (ConfigurationException e) {
            CLog.e("Failed to copy instrumentation options: %s", e.getMessage());
        }
        shard.mShardIndex = shardIndex;
        shard.mTotalShards = shardCount;
        shard.mIsSharded = true;
        shard.setAbi(getAbi());
        shard.mInternalExcludeTestFile = mInternalExcludeTestFile;
        shard.mInternalIncludeTestFile = mInternalIncludeTestFile;
        // We approximate the runtime of each shard to be equal since we can't know.
        shard.mRuntimeHint = mRuntimeHint / shardCount;
        return shard;
    }
}
