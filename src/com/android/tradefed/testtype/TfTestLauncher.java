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

import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.HprofAllocSiteParser;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SystemUtil.EnvVariable;
import com.android.tradefed.util.TarUtil;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A {@link IRemoteTest} for running unit or functional tests against a separate TF installation.
 * <p/>
 * Launches an external java process to run the tests. Used for running the TF unit or
 * functional tests continuously.
 */
public class TfTestLauncher extends SubprocessTfLauncher {

    @Option(name = "jacoco-code-coverage", description = "Enable jacoco code coverage on the java "
            + "sub process. Run will be slightly slower because of the overhead.")
    private boolean mEnableCoverage = false;

    @Option(name = "include-coverage", description = "Patterns to include in the code coverage.")
    private Set<String> mIncludeCoverage = new LinkedHashSet<>();

    @Option(name = "exclude-coverage", description = "Patterns to exclude in the code coverage.")
    private Set<String> mExcludeCoverage = new LinkedHashSet<>();

    @Option(
        name = "hprof-heap-memory",
        description =
                "Enable hprof agent while running the java"
                        + "sub process. Run will be slightly slower because of the overhead."
    )
    private boolean mEnableHprof = false;

    @Option(name = "ant-config-res", description = "The name of the ant resource configuration to "
            + "transform the results in readable format.")
    private String mAntConfigResource = "/jacoco/ant-tf-coverage.xml";

    @Option(name = "sub-branch", description = "The branch to be provided to the sub invocation, "
            + "if null, the branch in build info will be used.")
    private String mSubBranch = null;

    @Option(name = "sub-build-flavor", description = "The build flavor to be provided to the "
            + "sub invocation, if null, the build flavor in build info will be used.")
    private String mSubBuildFlavor = null;

    @Option(name = "sub-build-id", description = "The build id that the sub invocation will try "
            + "to use in case where it needs its own device.")
    private String mSubBuildId = null;

    @Option(name = "use-virtual-device", description =
            "Flag if the subprocess is going to need to instantiate a virtual device to run.")
    private boolean mUseVirtualDevice = false;

    @Option(name = "sub-apk-path", description = "The name of all the Apks that needs to be "
            + "installed by the subprocess invocation. Apk need to be inside the downloaded zip. "
            + "Can be repeated.")
    private List<String> mSubApkPath = new ArrayList<String>();

    @Option(name = "skip-temp-dir-check", description = "Whether or not to skip temp dir check.")
    private boolean mSkipTmpDirCheck = false;

    // The regex pattern of temp files to be found in the temporary dir of the subprocess.
    // Any file not matching the patterns, or multiple files in the temporary dir match the same
    // pattern, is considered as test failure.
    private static final String[] EXPECTED_TMP_FILE_PATTERNS = {
        "inv_.*", "tradefed_global_log_.*", "lc_cache", "stage-android-build-api",
    };
    // A destination file where the hprof report will be put.
    private File mHprofFile = null;

    /** {@inheritDoc} */
    @Override
    protected void addJavaArguments(List<String> args) {
        super.addJavaArguments(args);
        try {
            if (mEnableHprof) {
                mHprofFile = FileUtil.createTempFile("java.hprof", ".txt");
                // verbose=n to avoid dump in stderr
                // cutoff the min value we look at.
                String hprofAgent =
                        String.format(
                                "-agentlib:hprof=heap=sites,cutoff=0.01,depth=16,verbose=n,file=%s",
                                mHprofFile.getAbsolutePath());
                args.add(hprofAgent);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void preRun() {
        super.preRun();

        if (!mUseVirtualDevice) {
            mCmdArgs.add("-n");
        } else {
            // if it needs a device we also enable more logs
            mCmdArgs.add("--log-level");
            mCmdArgs.add("VERBOSE");
            mCmdArgs.add("--log-level-display");
            mCmdArgs.add("VERBOSE");
        }
        mCmdArgs.add("--test-tag");
        mCmdArgs.add(mBuildInfo.getTestTag());
        mCmdArgs.add("--build-id");
        if (mSubBuildId != null) {
            mCmdArgs.add(mSubBuildId);
        } else {
            mCmdArgs.add(mBuildInfo.getBuildId());
        }
        mCmdArgs.add("--branch");
        if (mSubBranch != null) {
            mCmdArgs.add(mSubBranch);
        } else if (mBuildInfo.getBuildBranch() != null) {
            mCmdArgs.add(mBuildInfo.getBuildBranch());
        } else {
            throw new RuntimeException("Branch option is required for the sub invocation.");
        }
        mCmdArgs.add("--build-flavor");
        if (mSubBuildFlavor != null) {
            mCmdArgs.add(mSubBuildFlavor);
        } else if (mBuildInfo.getBuildFlavor() != null) {
            mCmdArgs.add(mBuildInfo.getBuildFlavor());
        } else {
            throw new RuntimeException("Build flavor option is required for the sub invocation.");
        }

        for (String apk : mSubApkPath) {
            mCmdArgs.add("--apk-path");
            String apkPath =
                    String.format(
                            "%s%s%s",
                            ((IFolderBuildInfo) mBuildInfo).getRootDir().getAbsolutePath(),
                            File.separator,
                            apk);
            mCmdArgs.add(apkPath);
        }
        // Unset potential build environment to ensure they do not affect the unit tests
        getRunUtil().unsetEnvVariable(EnvVariable.ANDROID_HOST_OUT_TESTCASES.name());
        getRunUtil().unsetEnvVariable(EnvVariable.ANDROID_TARGET_OUT_TESTCASES.name());
    }

    /** {@inheritDoc} */
    @Override
    protected void postRun(ITestInvocationListener listener, boolean exception, long elapsedTime) {
        super.postRun(listener, exception, elapsedTime);
        reportMetrics(elapsedTime, listener);
        if (mEnableHprof) {
            logHprofResults(mHprofFile, listener);
        }

        if (mTmpDir != null) {
            testTmpDirClean(mTmpDir, listener);
        }
        cleanTmpFile();
    }

    @VisibleForTesting
    void cleanTmpFile() {
        FileUtil.deleteFile(mHprofFile);
    }

    /**
     * Report an elapsed-time metric to keep track of it.
     *
     * @param elapsedTime time it took the subprocess to run.
     * @param listener the {@link ITestInvocationListener} where to report the metric.
     */
    private void reportMetrics(long elapsedTime, ITestInvocationListener listener) {
        if (elapsedTime == -1L) {
            return;
        }
        listener.testRunStarted("elapsed-time", 1);
        TestDescription tid = new TestDescription("elapsed-time", "run-elapsed-time");
        listener.testStarted(tid);
        HashMap<String, Metric> runMetrics = new HashMap<>();
        runMetrics.put(
                "elapsed-time", TfMetricProtoUtil.stringToMetric(Long.toString(elapsedTime)));
        listener.testEnded(tid, runMetrics);
        listener.testRunEnded(0L, runMetrics);
    }

    /**
     * Extra test to ensure no files are created by the unit tests in the subprocess and not
     * cleaned.
     *
     * @param tmpDir the temporary dir of the subprocess.
     * @param listener the {@link ITestInvocationListener} where to report the test.
     */
    @VisibleForTesting
    protected void testTmpDirClean(File tmpDir, ITestInvocationListener listener) {
        if (mSkipTmpDirCheck) {
            return;
        }
        listener.testRunStarted("temporaryFiles", 1);
        TestDescription tid = new TestDescription("temporary-files", "testIfClean");
        listener.testStarted(tid);
        String[] listFiles = tmpDir.list();
        List<String> unmatchedFiles = new ArrayList<String>();
        List<String> patterns = new ArrayList<String>(Arrays.asList(EXPECTED_TMP_FILE_PATTERNS));
        patterns.add(mBuildInfo.getBuildBranch());
        for (String file : Arrays.asList(listFiles)) {
            boolean matchFound = false;
            for (String pattern : patterns) {
                if (Pattern.matches(pattern, file)) {
                    patterns.remove(pattern);
                    matchFound = true;
                    break;
                }
            }
            if (!matchFound) {
                unmatchedFiles.add(file);
            }
        }
        if (unmatchedFiles.size() > 0) {
            String trace =
                    String.format(
                            "Found '%d' unexpected temporary files: %s.\nOnly "
                                    + "expected files are: %s. And each should appears only once.",
                            unmatchedFiles.size(), unmatchedFiles, patterns);
            listener.testFailed(tid, FailureDescription.create(trace));
        }
        listener.testEnded(tid, new HashMap<String, Metric>());
        listener.testRunEnded(0, new HashMap<String, Metric>());
    }

    /**
     * Helper to log and report as metric the hprof data.
     *
     * @param hprofFile file containing the Hprof report
     * @param listener the {@link ITestInvocationListener} where to report the test.
     */
    private void logHprofResults(File hprofFile, ITestInvocationListener listener) {
        if (hprofFile == null) {
            CLog.w("Hprof file was null. Skipping parsing.");
            return;
        }
        if (!hprofFile.exists()) {
            CLog.w("Hprof file %s was not found. Skipping parsing.", hprofFile.getAbsolutePath());
            return;
        }
        InputStreamSource memory = null;
        File tmpGzip = null;
        try {
            tmpGzip = TarUtil.gzip(hprofFile);
            memory = new FileInputStreamSource(tmpGzip);
            listener.testLog("hprof", LogDataType.GZIP, memory);
        } catch (IOException e) {
            CLog.e(e);
            return;
        } finally {
            StreamUtil.cancel(memory);
            FileUtil.deleteFile(tmpGzip);
        }
        HprofAllocSiteParser parser = new HprofAllocSiteParser();
        try {
            Map<String, String> results = parser.parse(hprofFile);
            if (results.isEmpty()) {
                CLog.d("No allocation site found from hprof file");
                return;
            }
            listener.testRunStarted("hprofAllocSites", 1);
            TestDescription tid = new TestDescription("hprof", "allocationSites");
            listener.testStarted(tid);
            listener.testEnded(tid, TfMetricProtoUtil.upgradeConvert(results));
            listener.testRunEnded(0, new HashMap<String, Metric>());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
