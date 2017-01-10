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

import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import org.junit.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Python tests written with the unittest library.
 */
@OptionClass(alias = "python-unit")
public class PythonUnitTestRunner implements IRemoteTest, IBuildReceiver {

    @Option(name = "pythonpath", description = "directories to add to the PYTHONPATH")
    private List<File> mPathDirs = new ArrayList<>();

    @Option(name = "pytest", description = "names of python modules containing the test cases")
    private List<String> mTests = new ArrayList<>();

    @Option(name = "python-unittest-options",
            description = "option string to be passed to the unittest module")
    private String mUnitTestOpts;

    @Option(name = "min-python-version", description = "minimum required python version")
    private String mMinPyVersion = "2.7.0";

    @Option(name = "python-binary", description = "python binary to use (optional)")
    private String mPythonBin;

    @Option(name = "test-timeout", description = "maximum amount of time tests are allowed to run",
            isTimeVal = true)
    private long TEST_TIMEOUT = 1000 * 60 * 5;

    private String mPythonPath;
    private IBuildInfo mBuildInfo;

    private static final String PYTHONPATH = "PYTHONPATH";
    private static final String VERSION_REGEX = "(?:(\\d+)\\.)?(?:(\\d+)\\.)?(\\*|\\d+)$";

    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        setPythonPath();
        if (mPythonBin == null) {
            mPythonBin = getPythonBinary();
        }
        IRunUtil runUtil = new RunUtil();
        runUtil.setEnvVariable(PYTHONPATH, mPythonPath);
        for (String module : mTests) {
            doRunTest(listener, runUtil, module);
        }
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    String getMinPythonVersion() {
        return mMinPyVersion;
    }

    void setMinPythonVersion(String version) {
        mMinPyVersion = version;
    }

    private String getPythonBinary() {
        IRunUtil runUtil = RunUtil.getDefault();
        CommandResult c = runUtil.runTimedCmd(1000, "which", "python");
        String pythonBin = c.getStdout().trim();
        if (pythonBin.length() == 0) {
            throw new RuntimeException("Could not find python binary on host machine");
        }
        c = runUtil.runTimedCmd(1000, pythonBin, "--version");
        // python --version prints to stderr
        CLog.i("Found python version: %s", c.getStderr());
        checkPythonVersion(c);
        return pythonBin;
    }

    private void setPythonPath() {
        StringBuilder sb = new StringBuilder();
        sb.append(System.getenv(PYTHONPATH));
        for (File pathdir : mPathDirs) {
            if (!pathdir.isDirectory()) {
                CLog.w("Not adding file %s to PYTHONPATH: expecting directory",
                        pathdir.getAbsolutePath());
            }
            sb.append(":");
            sb.append(pathdir.getAbsolutePath());
        }
        if (mBuildInfo.getFile(PYTHONPATH) != null) {
            sb.append(":");
            sb.append(mBuildInfo.getFile(PYTHONPATH).getAbsolutePath());
        }
        mPythonPath = sb.toString();
    }

    protected void checkPythonVersion(CommandResult c) {
        Matcher minVersionParts = Pattern.compile(VERSION_REGEX).matcher(mMinPyVersion);
        Matcher versionParts = Pattern.compile(VERSION_REGEX).matcher(c.getStderr());

        Assert.assertTrue(minVersionParts.find());
        int major = Integer.parseInt(minVersionParts.group(1));
        int minor = Integer.parseInt(minVersionParts.group(2));
        int revision = Integer.parseInt(minVersionParts.group(3));

        Assert.assertTrue(versionParts.find());
        int foundMajor = Integer.parseInt(versionParts.group(1));
        int foundMinor = Integer.parseInt(versionParts.group(2));
        int foundRevision = Integer.parseInt(versionParts.group(3));

        Assert.assertTrue(foundMajor >= major);
        if (!(foundMajor > major)) {
            Assert.assertTrue(foundMinor >= minor);
            if (!(foundMinor > minor)) {
                Assert.assertTrue(foundRevision >= revision);
            }
        }
    }

    private void doRunTest(ITestRunListener listener, IRunUtil runUtil, String pyModule) {
        String[] baseOpts = {mPythonBin, "-m", "unittest", "-v"};
        String[] testModule = {pyModule};
        String[] cmd;
        if (mUnitTestOpts != null) {
            cmd = ArrayUtil.buildArray(baseOpts, mUnitTestOpts.split(" "), testModule);
        } else {
            cmd = ArrayUtil.buildArray(baseOpts, testModule);
        }
        CommandResult c = runUtil.runTimedCmd(TEST_TIMEOUT, cmd);

        if (c.getStatus() != CommandStatus.SUCCESS) {
            CLog.e("Python process failed");
            CLog.e("Stderr: %s", c.getStderr());
            CLog.e("Stdout: %s", c.getStdout());
            throw new RuntimeException("Failed to run python unit test");
        }
        CLog.i("Parsing test result: %s", c.getStderr());
        MultiLineReceiver parser = new PythonUnitTestResultParser(
                ArrayUtil.list(listener), pyModule);
        parser.processNewLines(c.getStderr().split("\n"));
    }

}