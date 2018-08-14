/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.build.BuildInfoKey;
import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A Test that runs a native test package. */
@OptionClass(alias = "hostgtest")
public class HostGTest extends GTestBase implements IAbiReceiver, IBuildReceiver {

    private IBuildInfo mBuildInfo = null;
    private IAbi mAbi = null;

    @Override
    public void setAbi(IAbi abi) {
        this.mAbi = abi;
    }

    @Override
    public IAbi getAbi() {
        return this.mAbi;
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        this.mBuildInfo = buildInfo;
    }

    /**
     * @param cmd command that want to execute in host
     * @return the {@link CommandResult} of command
     */
    public CommandResult executeHostCommand(String cmd) {
        String[] cmds = cmd.split("\\s+");
        long maxTestTimeMs = getMaxTestTimeMs();
        CommandResult cmdResult = RunUtil.getDefault().runTimedCmd(maxTestTimeMs, cmds);
        if (!CommandStatus.SUCCESS.equals(cmdResult.getStatus())) {
            throw new RuntimeException(
                    String.format("Command run fail cause by %s.", cmdResult.getStderr()));
        }
        return cmdResult;
    }

    /**
     * @param cmd command that want to execute in host
     * @param receiver the result parser
     */
    public void executeHostCommand(String cmd, IShellOutputReceiver receiver)
            throws RuntimeException {
        // TODO Redirect stdout stream, so we could get results as they come.
        CommandResult result = executeHostCommand(cmd);
        if (null != result && CommandStatus.SUCCESS.equals(result.getStatus())) {
            byte[] resultStdout = result.getStdout().getBytes();
            receiver.addOutput(resultStdout, 0, resultStdout.length);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String loadFilter(String binaryOnHost) {
        try {
            CLog.i("Loading filter from file for key: '%s'", getTestFilterKey());
            String filterFileName = String.format("%s%s", binaryOnHost, FILTER_EXTENSION);
            File filterFile = new File(filterFileName);
            if (filterFile.exists()) {
                CommandResult cmdResult =
                        executeHostCommand(String.format("cat %s", filterFileName));
                String content = cmdResult.getStdout();
                if (content != null && !content.isEmpty()) {
                    JSONObject filter = new JSONObject(content);
                    String key = getTestFilterKey();
                    JSONObject filterObject = filter.getJSONObject(key);
                    return filterObject.getString("filter");
                }
                CLog.e("Error with content of the filter file %s: %s", filterFile, content);
            } else {
                CLog.e("Filter file %s not found", filterFile);
            }
        } catch (JSONException e) {
            CLog.e(e);
        }
        return null;
    }

    /**
     * Run the given gtest binary
     *
     * @param resultParser the test run output parser
     * @param fullPath absolute file system path to gtest binary
     * @param flags gtest execution flags
     */
    private void runTest(
            final IShellOutputReceiver resultParser, final String fullPath, final String flags) {
        try {
            for (String cmd : getBeforeTestCmd()) {
                executeHostCommand(cmd);
            }
            String cmd = getGTestCmdLine(fullPath, flags);
            executeHostCommand(cmd, resultParser);
        } finally {
            resultParser.flush();
            for (String cmd : getAfterTestCmd()) {
                executeHostCommand(cmd);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void run(ITestInvocationListener listener)
            throws DeviceNotAvailableException { // DNAE is part of IRemoteTest.
        // Get testcases directory using the key HOST_LINKED_DIR first.
        // If the directory is null, then get testcase directory from getTestDir() since *TS will
        // invoke setTestDir().
        List<File> scanDirs = new ArrayList<>();
        File hostLinkedDir = mBuildInfo.getFile(BuildInfoKey.BuildInfoFileKey.HOST_LINKED_DIR);
        if (hostLinkedDir != null) {
            scanDirs.add(hostLinkedDir);
        }
        File testsDir = ((DeviceBuildInfo) mBuildInfo).getTestsDir();
        if (testsDir != null) {
            scanDirs.add(testsDir);
        }

        String moduleName = getTestModule();
        File gTestFile = null;
        try {
            gTestFile = FileUtil.findFile(moduleName, mAbi, scanDirs.toArray(new File[] {}));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (gTestFile == null) {
            throw new RuntimeException(
                    String.format(
                            "Fail to find native test %s in directory %s.", moduleName, scanDirs));
        }

        if (!gTestFile.canExecute()) {
            throw new RuntimeException(
                    String.format("%s is not executable!", gTestFile.getAbsolutePath()));
        }

        IShellOutputReceiver resultParser = createResultParser(gTestFile.getName(), listener);
        String flags = getAllGTestFlags(gTestFile.getName());
        CLog.i("Running gtest %s %s", gTestFile.getName(), flags);
        String filePath = gTestFile.getAbsolutePath();
        runTest(resultParser, filePath, flags);
    }
}
