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
package com.android.tradefed.invoker;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.StubBuildProvider;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.command.CommandRunner;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceSelectionOptions;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.device.cloud.LaunchCvdHelper;
import com.android.tradefed.device.cloud.ManagedRemoteDevice;
import com.android.tradefed.device.cloud.MultiUserSetupUtil;
import com.android.tradefed.device.cloud.RemoteFileUtil;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.proto.FileProtoResultReporter;
import com.android.tradefed.result.proto.ProtoResultParser;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.proto.TestRecordProtoUtil;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Implementation of {@link InvocationExecution} that drives a remote execution. */
public class RemoteInvocationExecution extends InvocationExecution {

    public static final long PUSH_TF_TIMEOUT = 150000L;
    public static final long PULL_RESULT_TIMEOUT = 180000L;
    public static final long REMOTE_PROCESS_RUNNING_WAIT = 15000L;
    public static final long LAUNCH_EXTRA_DEVICE = 2 * 60 * 1000L;
    public static final long NEW_USER_TIMEOUT = 5 * 60 * 1000L;

    public static final String REMOTE_USER_DIR = "/home/{$USER}/";
    public static final String PROTO_RESULT_NAME = "output.pb";
    public static final String STDOUT_FILE = "screen-VM_tradefed-stdout.txt";
    public static final String STDERR_FILE = "screen-VM_tradefed-stderr.txt";
    public static final String REMOTE_CONFIG = "configuration";
    public static final String GLOBAL_REMOTE_CONFIG = "global-remote-configuration";

    private static final int MAX_CONNECTION_REFUSED_COUNT = 3;
    private static final int MAX_PUSH_TF_ATTEMPTS = 3;

    private String mRemoteTradefedDir = null;
    private String mRemoteFinalResult = null;
    private String mRemoteAdbPath = null;

    @Override
    public boolean fetchBuild(
            IInvocationContext context,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException, BuildRetrievalError {
        // TODO: handle multiple devices/build config
        updateInvocationContext(context, config);
        StubBuildProvider stubProvider = new StubBuildProvider();

        String deviceName = config.getDeviceConfig().get(0).getDeviceName();
        OptionCopier.copyOptionsNoThrow(
                config.getDeviceConfig().get(0).getBuildProvider(), stubProvider);

        IBuildInfo info = stubProvider.getBuild();
        if (info == null) {
            return false;
        }
        context.addDeviceBuildInfo(deviceName, info);
        updateBuild(info, config);
        return true;
    }

    @Override
    public void runTests(
            IInvocationContext context, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        ManagedRemoteDevice device = (ManagedRemoteDevice) context.getDevices().get(0);
        GceAvdInfo info = device.getRemoteAvdInfo();

        // Run remote TF (new tests?)
        IRunUtil runUtil = new RunUtil();

        TestDeviceOptions options = device.getOptions();
        String mainRemoteDir = getRemoteMainDir(options);
        // Handle sharding
        if (config.getCommandOptions().getShardCount() != null
                && config.getCommandOptions().getShardIndex() == null) {
            if (config.getCommandOptions().getShardCount() > 1) {
                // For each device after the first one we need to start a new device.
                for (int i = 2; i < config.getCommandOptions().getShardCount() + 1; i++) {
                    String username = String.format("vsoc-0%s", i);
                    CommandResult userSetup =
                            MultiUserSetupUtil.prepareRemoteUser(
                                    username, info, options, runUtil, NEW_USER_TIMEOUT);
                    if (userSetup != null) {
                        String errorMsg =
                                String.format("Failed to setup user: %s", userSetup.getStderr());
                        CLog.e(errorMsg);
                        listener.invocationFailed(new RuntimeException(errorMsg));
                        return;
                    }

                    CommandResult homeDirSetup =
                            MultiUserSetupUtil.prepareRemoteHomeDir(
                                    options.getInstanceUser(),
                                    username,
                                    info,
                                    options,
                                    runUtil,
                                    NEW_USER_TIMEOUT);
                    if (homeDirSetup != null) {
                        String errorMsg =
                                String.format(
                                        "Failed to setup home dir: %s", homeDirSetup.getStderr());
                        CLog.e(errorMsg);
                        listener.invocationFailed(new RuntimeException(errorMsg));
                        return;
                    }

                    List<String> startCommand =
                            LaunchCvdHelper.createSimpleDeviceCommand(username, true);
                    CommandResult startDeviceRes =
                            GceManager.remoteSshCommandExecution(
                                    info,
                                    options,
                                    runUtil,
                                    LAUNCH_EXTRA_DEVICE,
                                    Joiner.on(" ").join(startCommand));
                    if (!CommandStatus.SUCCESS.equals(startDeviceRes.getStatus())) {
                        String errorMsg =
                                String.format(
                                        "Failed to start %s: %s",
                                        username, startDeviceRes.getStderr());
                        CLog.e(errorMsg);
                        listener.invocationFailed(new RuntimeException(errorMsg));
                        return;
                    }
                }
            }
        }

        mRemoteAdbPath = String.format("/home/%s/bin/adb", options.getInstanceUser());

        String tfPath = System.getProperty("TF_JAR_DIR");
        if (tfPath == null) {
            listener.invocationFailed(new RuntimeException("Failed to find $TF_JAR_DIR."));
            return;
        }
        File currentTf = new File(tfPath).getAbsoluteFile();
        if (tfPath.equals(".")) {
            currentTf = new File("").getAbsoluteFile();
        }
        mRemoteTradefedDir = mainRemoteDir + "tradefed/";
        CommandResult createRemoteDir =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 120000L, "mkdir", "-p", mRemoteTradefedDir);
        if (!CommandStatus.SUCCESS.equals(createRemoteDir.getStatus())) {
            listener.invocationFailed(new RuntimeException("Failed to create remote dir."));
            return;
        }

        // Push Tradefed to the remote
        int attempt = 0;
        boolean result = false;
        while (!result && attempt < MAX_PUSH_TF_ATTEMPTS) {
            result =
                    RemoteFileUtil.pushFileToRemote(
                            info,
                            options,
                            Arrays.asList("-r"),
                            runUtil,
                            PUSH_TF_TIMEOUT,
                            mRemoteTradefedDir,
                            currentTf);
            attempt++;
        }
        if (!result) {
            CLog.e("Failed to push Tradefed.");
            listener.invocationFailed(new RuntimeException("Failed to push Tradefed."));
            return;
        }

        mRemoteTradefedDir = mRemoteTradefedDir + currentTf.getName() + "/";
        CommandResult listRemoteDir =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 120000L, "ls", "-l", mRemoteTradefedDir);
        CLog.d("stdout: %s", listRemoteDir.getStdout());
        CLog.d("stderr: %s", listRemoteDir.getStderr());
        mRemoteFinalResult = mRemoteTradefedDir + PROTO_RESULT_NAME;

        // Setup the remote reporting to a proto file
        FileProtoResultReporter reporter = new FileProtoResultReporter();
        reporter.setFileOutput(new File(mRemoteFinalResult));
        config.setTestInvocationListener(reporter);

        for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
            deviceConfig.getDeviceRequirements().setSerial();
            if (deviceConfig.getDeviceRequirements() instanceof DeviceSelectionOptions) {
                ((DeviceSelectionOptions) deviceConfig.getDeviceRequirements())
                        .setDeviceTypeRequested(null);
            }
        }

        File configFile = FileUtil.createTempFile(config.getName(), ".xml");
        File globalConfig = null;
        config.dumpXml(new PrintWriter(configFile));
        try {
            try (InputStreamSource source = new FileInputStreamSource(configFile)) {
                listener.testLog(REMOTE_CONFIG, LogDataType.XML, source);
            }
            CLog.d("Pushing Tradefed XML configuration to remote.");
            boolean resultPush =
                    RemoteFileUtil.pushFileToRemote(
                            info,
                            options,
                            null,
                            runUtil,
                            PUSH_TF_TIMEOUT,
                            mRemoteTradefedDir,
                            configFile);
            if (!resultPush) {
                CLog.e("Failed to push Tradefed Configuration.");
                listener.invocationFailed(
                        new RuntimeException("Failed to push Tradefed Configuration."));
                return;
            }

            String[] whitelistConfigs =
                    new String[] {
                        GlobalConfiguration.SCHEDULER_TYPE_NAME,
                        GlobalConfiguration.HOST_OPTIONS_TYPE_NAME,
                        "android-build"
                    };
            try {
                globalConfig =
                        GlobalConfiguration.getInstance()
                                .cloneConfigWithFilter(new HashSet<>(), whitelistConfigs);
            } catch (IOException e) {
                listener.invocationFailed(e);
                return;
            }
            try (InputStreamSource source = new FileInputStreamSource(globalConfig)) {
                listener.testLog(GLOBAL_REMOTE_CONFIG, LogDataType.XML, source);
            }
            // Push the global configuration
            boolean resultPushGlobal =
                    RemoteFileUtil.pushFileToRemote(
                            info,
                            options,
                            null,
                            runUtil,
                            PUSH_TF_TIMEOUT,
                            mRemoteTradefedDir,
                            globalConfig);
            if (!resultPushGlobal) {
                CLog.e("Failed to push Tradefed Global Configuration.");
                listener.invocationFailed(
                        new RuntimeException("Failed to push Tradefed Global Configuration."));
                return;
            }

            resetAdb(info, options, runUtil);
            runRemote(listener, configFile, info, options, runUtil, config, globalConfig);
            collectAdbLogs(info, options, runUtil, listener);
        } finally {
            FileUtil.recursiveDelete(configFile);
            FileUtil.recursiveDelete(globalConfig);
        }
    }

    @Override
    public void doSetup(
            IInvocationContext context, IConfiguration config, ITestInvocationListener listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        // Skip
    }

    @Override
    public void doTeardown(IInvocationContext context, IConfiguration config, Throwable exception)
            throws Throwable {
        // Only run device post invocation teardown
        super.runDevicePostInvocationTearDown(context, config);
    }

    @Override
    public void doCleanUp(IInvocationContext context, IConfiguration config, Throwable exception) {
        // Skip
    }

    @Override
    String getAdbVersion() {
        // Do not report the adb version from the parent, the remote child will remote its own.
        return null;
    }

    private void runRemote(
            ITestInvocationListener currentInvocationListener,
            File configFile,
            GceAvdInfo info,
            TestDeviceOptions options,
            IRunUtil runUtil,
            IConfiguration config,
            File globalConfig)
            throws InvalidProtocolBufferException, IOException {
        List<String> remoteTfCommand = new ArrayList<>();
        remoteTfCommand.add("pushd");
        remoteTfCommand.add(mRemoteTradefedDir + ";");
        remoteTfCommand.add(String.format("PATH=%s:$PATH", new File(mRemoteAdbPath).getParent()));
        remoteTfCommand.add("screen -dmSU tradefed sh -c");

        StringBuilder tfCmdBuilder =
                new StringBuilder("TF_GLOBAL_CONFIG=" + globalConfig.getName());
        tfCmdBuilder.append(" ENTRY_CLASS=" + CommandRunner.class.getCanonicalName());
        tfCmdBuilder.append(" ./tradefed.sh " + mRemoteTradefedDir + configFile.getName());
        if (config.getCommandOptions().shouldUseRemoteSandboxMode()) {
            tfCmdBuilder.append(" --" + CommandOptions.USE_SANDBOX);
        }
        tfCmdBuilder.append(" > " + STDOUT_FILE + " 2> " + STDERR_FILE);
        remoteTfCommand.add("\"" + tfCmdBuilder.toString() + "\"");
        // Kick off the actual remote run
        CommandResult resultRemoteExecution =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 0L, remoteTfCommand.toArray(new String[0]));
        if (!CommandStatus.SUCCESS.equals(resultRemoteExecution.getStatus())) {
            CLog.e("Error running the remote command: %s", resultRemoteExecution.getStdout());
            currentInvocationListener.invocationFailed(
                    new RuntimeException(resultRemoteExecution.getStderr()));
            return;
        }
        // Sleep a bit to let the process start
        RunUtil.getDefault().sleep(10000L);

        // Monitor the remote invocation to ensure it's completing. Block until timeout or stops
        // running.
        boolean stillRunning =
                isStillRunning(
                        currentInvocationListener, configFile, info, options, runUtil, config);

        File resultFile = null;
        if (!stillRunning) {
            resultFile =
                    RemoteFileUtil.fetchRemoteFile(
                            info, options, runUtil, PULL_RESULT_TIMEOUT, mRemoteFinalResult);
            if (resultFile == null) {
                currentInvocationListener.invocationFailed(
                        new RuntimeException(
                                String.format(
                                        "Could not find remote result file at %s",
                                        mRemoteFinalResult)));
            } else {
                CLog.d("Fetched remote result file!");
            }
        }

        // Fetch the logs
        File stdoutFile =
                RemoteFileUtil.fetchRemoteFile(
                        info,
                        options,
                        runUtil,
                        PULL_RESULT_TIMEOUT,
                        mRemoteTradefedDir + STDOUT_FILE);
        if (stdoutFile != null) {
            try (InputStreamSource source = new FileInputStreamSource(stdoutFile, true)) {
                currentInvocationListener.testLog(STDOUT_FILE, LogDataType.TEXT, source);
            }
        }

        File stderrFile =
                RemoteFileUtil.fetchRemoteFile(
                        info,
                        options,
                        runUtil,
                        PULL_RESULT_TIMEOUT,
                        mRemoteTradefedDir + STDERR_FILE);
        if (stderrFile != null) {
            try (InputStreamSource source = new FileInputStreamSource(stderrFile, true)) {
                currentInvocationListener.testLog(STDERR_FILE, LogDataType.TEXT, source);
            }
        }

        if (resultFile != null) {
            // Report result to listener.
            ProtoResultParser parser =
                    new ProtoResultParser(currentInvocationListener, false, "remote-");
            parser.processFinalizedProto(TestRecordProtoUtil.readFromFile(resultFile));
        }
    }

    private boolean isStillRunning(
            ITestInvocationListener currentInvocationListener,
            File configFile,
            GceAvdInfo info,
            TestDeviceOptions options,
            IRunUtil runUtil,
            IConfiguration config) {
        long maxTimeout = config.getCommandOptions().getInvocationTimeout();
        Long endTime = null;
        if (maxTimeout > 0L) {
            endTime = System.currentTimeMillis() + maxTimeout;
        }
        boolean stillRunning = true;
        int errorConnectCount = 0;
        while (stillRunning) {
            CommandResult psRes =
                    GceManager.remoteSshCommandExecution(
                            info,
                            options,
                            runUtil,
                            120000L,
                            "ps",
                            "-ef",
                            "| grep",
                            CommandRunner.class.getCanonicalName());
            if (!CommandStatus.SUCCESS.equals(psRes.getStatus())) {
                errorConnectCount++;
                // If we get several connection errors in a row, give up.
                if (errorConnectCount > MAX_CONNECTION_REFUSED_COUNT) {
                    CLog.e("Failed to connect to the remote to check running status.");
                    return false;
                }
            } else {
                // Reset the error count
                errorConnectCount = 0;
                CLog.d("ps -ef: stdout: %s\nstderr: %s\n", psRes.getStdout(), psRes.getStderr());
                stillRunning = psRes.getStdout().contains(configFile.getName());
                CLog.d("still running: %s", stillRunning);
                if (endTime != null && System.currentTimeMillis() > endTime) {
                    currentInvocationListener.invocationFailed(
                            new RuntimeException(
                                    String.format(
                                            "Remote invocation timeout after %s",
                                            TimeUtil.formatElapsedTime(maxTimeout))));
                    break;
                }
            }
            if (stillRunning) {
                RunUtil.getDefault().sleep(REMOTE_PROCESS_RUNNING_WAIT);
            }
        }
        return stillRunning;
    }

    /** Returns the main remote working directory. */
    private String getRemoteMainDir(TestDeviceOptions options) {
        return REMOTE_USER_DIR.replace("{$USER}", options.getInstanceUser());
    }

    /**
     * Sometimes remote adb version is a bit weird and is not running properly the first time. Try
     * it out once to ensure it starts.
     */
    private void resetAdb(GceAvdInfo info, TestDeviceOptions options, IRunUtil runUtil) {
        CommandResult probAdb =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 120000L, mRemoteAdbPath, "devices");
        CLog.d("remote adb prob: %s", probAdb.getStdout());
        CLog.d("%s", probAdb.getStderr());

        CommandResult versionAdb =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 120000L, mRemoteAdbPath, "version");
        CLog.d("version adb: %s", versionAdb.getStdout());
        CLog.d("%s", versionAdb.getStderr());
    }

    /**
     * Remote invocation relies on the adb of the remote, so always collect its logs to make sure we
     * can debug it appropriately.
     */
    private void collectAdbLogs(
            GceAvdInfo info, TestDeviceOptions options, IRunUtil runUtil, ITestLogger logger) {
        CommandResult tmpDirFolder =
                GceManager.remoteSshCommandExecution(
                        info, options, runUtil, 120000L, "bash -c \"echo \\$TMPDIR\"");
        String folder = tmpDirFolder.getStdout().trim();
        CLog.d("Remote TMPDIR folder is: %s", folder);
        if (Strings.isNullOrEmpty(folder)) {
            // If TMPDIR is not set, default to /tmp/ location.
            folder = "/tmp";
        }
        CommandResult uid =
                GceManager.remoteSshCommandExecution(
                        info, options, new RunUtil(), 120000L, "bash -c \"echo \\$UID\"");
        String uidString = uid.getStdout().trim();
        CLog.d("Remote $UID for adb is: %s", uidString);

        GceManager.logNestedRemoteFile(
                logger,
                info,
                options,
                runUtil,
                folder + "/adb." + uidString + ".log",
                LogDataType.TEXT,
                "full_adb.log");
    }
}
