/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.CommandRunner.ExitCode;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.device.cloud.ManagedRemoteDevice;
import com.android.tradefed.device.cloud.NestedRemoteDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.guice.InvocationScope;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.sandbox.ParentSandboxInvocationExecution;
import com.android.tradefed.invoker.sandbox.SandboxedInvocationExecution;
import com.android.tradefed.invoker.shard.ShardBuildCloner;
import com.android.tradefed.log.BaseLeveledLogOutput;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.log.StdoutLogger;
import com.android.tradefed.postprocessor.IPostProcessor;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.ResultAndLogForwarder;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.ResultAggregator;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFailedToBootError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PrettyPrintDelimiter;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

/**
 * Default implementation of {@link ITestInvocation}.
 * <p/>
 * Loads major objects based on {@link IConfiguration}
 *   - retrieves build
 *   - prepares target
 *   - runs tests
 *   - reports results
 */
public class TestInvocation implements ITestInvocation {

    /** Key of the command line args attributes */
    public static final String COMMAND_ARGS_KEY = "command_line_args";

    /**
     * Format of the key in {@link IBuildInfo} to log the battery level for each step of the
     * invocation. (Setup, test, tear down).
     */
    private static final String BATTERY_ATTRIBUTE_FORMAT_KEY = "%s-battery-%s";

    public static final String TRADEFED_LOG_NAME = "host_log";
    public static final String TRADEFED_END_HOST_LOG = "end_host_log";
    /** Suffix used on host_log for the part before sharding occurs. */
    static final String BEFORE_SHARDING_SUFFIX = "_before_sharding";
    static final String DEVICE_LOG_NAME_PREFIX = "device_logcat_";
    static final String EMULATOR_LOG_NAME_PREFIX = "emulator_log_";
    static final String BUILD_ERROR_BUGREPORT_NAME = "build_error_bugreport";
    static final String DEVICE_UNRESPONSIVE_BUGREPORT_NAME = "device_unresponsive_bugreport";
    static final String INVOCATION_ENDED_BUGREPORT_NAME = "invocation_ended_bugreport";
    static final String TARGET_SETUP_ERROR_BUGREPORT_NAME = "target_setup_error_bugreport";
    static final String BATT_TAG = "[battery level]";

    public enum Stage {
        ERROR("error"),
        SETUP("setup"),
        TEST("test"),
        TEARDOWN("teardown");

        private final String mName;

        Stage(String name) {
            mName = name;
        }

        public String getName() {
            return mName;
        }
    }

    /** The different mode an invocation can run into. */
    public enum RunMode {
        REGULAR,
        PARENT_SANDBOX,
        SANDBOX,
        REMOTE_INVOCATION,
    }

    private String mStatus = "(not invoked)";
    private String mStopCause = null;
    private Long mStopRequestTime = null;
    private boolean mTestStarted = false;

    /**
     * A {@link ResultForwarder} for forwarding resumed invocations.
     * <p/>
     * It filters the invocationStarted event for the resumed invocation, and sums the invocation
     * elapsed time
     */
    private static class ResumeResultForwarder extends ResultForwarder {

        long mCurrentElapsedTime;

        /**
         * @param listeners
         */
        public ResumeResultForwarder(List<ITestInvocationListener> listeners,
                long currentElapsedTime) {
            super(listeners);
            mCurrentElapsedTime = currentElapsedTime;
        }

        @Override
        public void invocationStarted(IInvocationContext context) {
            // ignore
        }

        @Override
        public void invocationEnded(long newElapsedTime) {
            super.invocationEnded(mCurrentElapsedTime + newElapsedTime);
        }
    }

    /**
     * Display a log message informing the user of a invocation being started.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void logStartInvocation(IInvocationContext context, IConfiguration config) {
        String shardSuffix = "";
        if (config.getCommandOptions().getShardIndex() != null) {
            shardSuffix =
                    String.format(
                            " (shard %d of %d)",
                            config.getCommandOptions().getShardIndex() + 1,
                            config.getCommandOptions().getShardCount());
        }
        StringBuilder buildInfos = new StringBuilder();
        StringBuilder msg = new StringBuilder("Starting invocation for '");
        msg.append(context.getTestTag());
        msg.append("' with ");
        for (Entry<ITestDevice, IBuildInfo> entry : context.getDeviceBuildMap().entrySet()) {
            msg.append("'[ ");
            msg.append(entry.getValue().toString());
            buildInfos.append(entry.getValue().toString());
            msg.append(" on device '");
            msg.append(entry.getKey().getSerialNumber());
            msg.append("'] ");
        }
        msg.append(shardSuffix);
        CLog.logAndDisplay(LogLevel.INFO, msg.toString());
        mStatus = String.format("running %s on build(s) '%s'", context.getTestTag(),
                buildInfos.toString()) + shardSuffix;
    }

    /**
     * Performs the invocation
     *
     * @param config the {@link IConfiguration}
     * @param testInfo the {@link TestInformation} to use for the invocation.
     */
    private void performInvocation(
            IConfiguration config,
            TestInformation testInfo,
            IInvocationExecution invocationPath,
            IRescheduler rescheduler,
            ITestInvocationListener listener,
            boolean devicePreSetupDone)
            throws Throwable {
        ReportHostLog reportThread = new ReportHostLog(listener, config);
        Runtime.getRuntime().addShutdownHook(reportThread);
        boolean resumed = false;
        String bugreportName = null;
        long startTime = System.currentTimeMillis();
        long elapsedTime = -1;
        Throwable exception = null;
        Throwable tearDownException = null;
        ITestDevice badDevice = null;
        IInvocationContext context = testInfo.getContext();

        // Ensure that no unexpected attributes are added afterward
        ((InvocationContext) context).lockAttributes();
        try {
            logDeviceBatteryLevel(context, "initial");
            // Run the preInvocationSetup on devices.
            if (!devicePreSetupDone) {
                if (!config.getCommandOptions().shouldUseSandboxing()) {
                    invocationPath.runDevicePreInvocationSetup(context, config, listener);
                }
            }
            // Then run the regular setup and run
            prepareAndRun(config, testInfo, invocationPath, listener);
        } catch (BuildError e) {
            exception = e;
            CLog.w("Build failed on device '%s'. Reason: %s", e.getDeviceDescriptor(),
                    e.toString());
            bugreportName = BUILD_ERROR_BUGREPORT_NAME;
            if (e.getDeviceDescriptor() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceDescriptor().getSerial());
            }
            if (e instanceof DeviceFailedToBootError) {
                if (badDevice == null) {
                    context.setRecoveryModeForAllDevices(RecoveryMode.NONE);
                } else {
                    badDevice.setRecoveryMode(RecoveryMode.NONE);
                }
            }
            reportFailure(e, listener, config, context, invocationPath);
        } catch (TargetSetupError e) {
            exception = e;
            CLog.e("Caught exception while running invocation");
            CLog.e(e);
            bugreportName = TARGET_SETUP_ERROR_BUGREPORT_NAME;
            if (e.getDeviceDescriptor() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceDescriptor().getSerial());
            }
            reportFailure(e, listener, config, context, invocationPath);
        } catch (DeviceNotAvailableException e) {
            exception = e;
            // log a warning here so its captured before reportLogs is called
            CLog.w("Invocation did not complete due to device %s becoming not available. " +
                    "Reason: %s", e.getSerial(), e.getMessage());
            badDevice = context.getDeviceBySerial(e.getSerial());
            if ((e instanceof DeviceUnresponsiveException) && badDevice != null
                    && TestDeviceState.ONLINE.equals(badDevice.getDeviceState())) {
                // under certain cases it might still be possible to grab a bugreport
                bugreportName = DEVICE_UNRESPONSIVE_BUGREPORT_NAME;
            }
            resumed = resume(config, context, rescheduler, System.currentTimeMillis() - startTime);
            if (!resumed) {
                reportFailure(e, listener, config, context, invocationPath);
            } else {
                CLog.i("Rescheduled failed invocation for resume");
            }
            // Upon reaching here after an exception, it is safe to assume that recovery
            // has already been attempted so we disable it to avoid re-entry during clean up.
            if (badDevice != null) {
                badDevice.setRecoveryMode(RecoveryMode.NONE);
            }
            throw e;
        } catch (RunInterruptedException e) {
            CLog.w("Invocation interrupted");
            reportFailure(e, listener, config, context, invocationPath);
        } catch (AssertionError e) {
            exception = e;
            CLog.e("Caught AssertionError while running invocation: %s", e.toString());
            CLog.e(e);
            reportFailure(e, listener, config, context, invocationPath);
        } catch (Throwable t) {
            exception = t;
            // log a warning here so its captured before reportLogs is called
            CLog.e("Unexpected exception when running invocation: %s", t.toString());
            CLog.e(t);
            reportFailure(t, listener, config, context, invocationPath);
            throw t;
        } finally {
            // Only capture logcat for TEST if we started the test phase.
            if (mTestStarted) {
                for (ITestDevice device : context.getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.TEST);
                }
            }
            getRunUtil().allowInterrupt(false);
            if (config.getCommandOptions().takeBugreportOnInvocationEnded() ||
                    config.getCommandOptions().takeBugreportzOnInvocationEnded()) {
                if (bugreportName != null) {
                    CLog.i("Bugreport to be taken for failure instead of invocation ended.");
                } else {
                    bugreportName = INVOCATION_ENDED_BUGREPORT_NAME;
                }
            }
            if (bugreportName != null) {
                if (badDevice == null) {
                    for (ITestDevice device : context.getDevices()) {
                        takeBugreport(device, listener, bugreportName);
                    }
                } else {
                    // If we have identified a faulty device only take the bugreport on it.
                    takeBugreport(badDevice, listener, bugreportName);
                }
            }
            // Save the device executeShellCommand logs
            logExecuteShellCommand(context.getDevices(), listener);

            mStatus = "tearing down";
            try {
                invocationPath.doTeardown(testInfo, config, listener, exception);
            } catch (Throwable e) {
                tearDownException = e;
                CLog.e("Exception when tearing down invocation: %s", tearDownException.toString());
                CLog.e(tearDownException);
                if (exception == null) {
                    // only report when the exception is new during tear down
                    reportFailure(
                            tearDownException,
                            listener,
                            config,
                            context,
                            invocationPath);
                }
            }
            mStatus = "done running tests";
            try {
                // Clean up host.
                invocationPath.doCleanUp(context, config, exception);
                for (ITestDevice device : context.getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.TEARDOWN);
                }
                if (mStopCause != null) {
                    String message =
                            String.format(
                                    "Invocation was interrupted due to: %s, results will be "
                                            + "affected.",
                                    mStopCause);
                    listener.invocationFailed(new RuntimeException(message));
                    PrettyPrintDelimiter.printStageDelimiter(message);
                    if (mStopRequestTime != null) {
                        // This is not 100% perfect since result reporting can still run a bit
                        // longer, but this is our last opportunity to report it.
                        long latency = System.currentTimeMillis() - mStopRequestTime;
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.SHUTDOWN_HARD_LATENCY, latency);
                    }
                }
                reportHostLog(listener, config);
                // If host_log is reported, remove the hook
                Runtime.getRuntime().removeShutdownHook(reportThread);

                elapsedTime = System.currentTimeMillis() - startTime;
                if (!resumed) {
                    // Init a log for the end of the host_log.
                    ILeveledLogOutput endHostLog = config.getLogOutput();
                    endHostLog.init();
                    getLogRegistry().registerLogger(endHostLog);
                    PrettyPrintDelimiter.printStageDelimiter("===== Result Reporters =====");
                    try {
                        // Copy the invocation metrics to the context
                        ((InvocationContext) context).logInvocationMetrics();
                        listener.invocationEnded(elapsedTime);
                    } finally {
                        InvocationMetricLogger.clearInvocationMetrics();
                        endHostLog.closeLog();
                        getLogRegistry().unregisterLogger();
                    }
                }
            } finally {
                invocationPath.cleanUpBuilds(context, config);
            }
        }
        if (tearDownException != null) {
            // this means a DNAE or RTE has happened during teardown, need to throw
            // if there was a preceding RTE or DNAE stored in 'exception', it would have already
            // been thrown before exiting the previous try...catch...finally block
            throw tearDownException;
        }
    }

    /** Do setup and run the tests */
    private void prepareAndRun(
            IConfiguration config,
            TestInformation testInfo,
            IInvocationExecution invocationPath,
            ITestInvocationListener listener)
            throws Throwable {
        getRunUtil().allowInterrupt(true);
        logDeviceBatteryLevel(testInfo.getContext(), "initial -> setup");
        // TODO: Use TestInformation in setup
        invocationPath.doSetup(testInfo, config, listener);
        logDeviceBatteryLevel(testInfo.getContext(), "setup -> test");
        mTestStarted = true;
        invocationPath.runTests(testInfo, config, listener);
        logDeviceBatteryLevel(testInfo.getContext(), "after test");
    }

    /**
     * Starts the invocation.
     * <p/>
     * Starts logging, and informs listeners that invocation has been started.
     *
     * @param config
     * @param context
     */
    private void startInvocation(IConfiguration config, IInvocationContext context,
            ITestInvocationListener listener) {
        logStartInvocation(context, config);
        listener.invocationStarted(context);
    }

    /**
     * Attempt to reschedule the failed invocation to resume where it left off.
     * <p/>
     * @see IResumableTest
     *
     * @param config
     * @return <code>true</code> if invocation was resumed successfully
     */
    private boolean resume(IConfiguration config, IInvocationContext context,
            IRescheduler rescheduler, long elapsedTime) {
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof IResumableTest) {
                IResumableTest resumeTest = (IResumableTest)test;
                if (resumeTest.isResumable()) {
                    // resume this config if any test is resumable
                    IConfiguration resumeConfig = config.clone();
                    // reuse the same build for the resumed invocation
                    ShardBuildCloner.cloneBuildInfos(resumeConfig, resumeConfig, context);

                    // create a result forwarder, to prevent sending two invocationStarted events
                    resumeConfig.setTestInvocationListener(new ResumeResultForwarder(
                            config.getTestInvocationListeners(), elapsedTime));
                    resumeConfig.setLogOutput(config.getLogOutput().clone());
                    resumeConfig.setCommandOptions(config.getCommandOptions().clone());
                    boolean canReschedule = rescheduler.scheduleConfig(resumeConfig);
                    if (!canReschedule) {
                        CLog.i("Cannot reschedule resumed config for build. Cleaning up build.");
                        for (String deviceName : context.getDeviceConfigNames()) {
                            resumeConfig.getDeviceConfigByName(deviceName).getBuildProvider()
                                    .cleanUp(context.getBuildInfo(deviceName));
                        }
                    }
                    // FIXME: is it a bug to return from here, when we may not have completed the
                    // FIXME: config.getTests iteration?
                    return canReschedule;
                }
            }
        }
        return false;
    }

    private void reportFailure(
            Throwable exception,
            ITestInvocationListener listener,
            IConfiguration config,
            IInvocationContext context,
            IInvocationExecution invocationPath) {
        // Always report the failure
        listener.invocationFailed(exception);
        // Reset the build (if necessary)
        // TODO: Remove the "Reschedule" part
        invocationPath.resetBuildAndReschedule(exception, listener, config, context);
    }

    private void reportHostLog(ITestInvocationListener listener, IConfiguration config) {
        reportHostLog(listener, config, TRADEFED_LOG_NAME);
    }

    private void reportHostLog(
            ITestInvocationListener listener, IConfiguration config, String name) {
        ILeveledLogOutput logger = config.getLogOutput();
        try (InputStreamSource globalLogSource = logger.getLog()) {
            if (globalLogSource != null) {
                if (config.getCommandOptions().getHostLogSuffix() != null) {
                    name += config.getCommandOptions().getHostLogSuffix();
                }
                listener.testLog(name, LogDataType.HOST_LOG, globalLogSource);
            } else {
                // Only print the non-logging if we are not a stdout logger
                if (!(logger instanceof StdoutLogger)) {
                    CLog.i("Skip logging %s to a file with logger '%s'", name, logger);
                }
            }
        }
        // once tradefed log is reported, all further log calls for this invocation can get lost
        // unregister logger so future log calls get directed to the tradefed global log
        getLogRegistry().unregisterLogger();
        logger.closeLog();
    }

    private void takeBugreport(
            ITestDevice device, ITestInvocationListener listener, String bugreportName) {
        if (device == null) {
            return;
        }
        if (device.getIDevice() instanceof StubDevice) {
            return;
        }
        // logBugreport will report a regular bugreport if bugreportz is not supported.
        boolean res =
                device.logBugreport(
                        String.format("%s_%s", bugreportName, device.getSerialNumber()), listener);
        if (!res) {
            CLog.w("Error when collecting bugreport for device '%s'", device.getSerialNumber());
        }
    }

    /**
     * Gets the {@link ILogRegistry} to use.
     * <p/>
     * Exposed for unit testing.
     */
    ILogRegistry getLogRegistry() {
        return LogRegistry.getLogRegistry();
    }

    /**
     * Utility method to fetch the default {@link IRunUtil} singleton
     * <p />
     * Exposed for unit testing.
     */
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    @Override
    public String toString() {
        return mStatus;
    }

    /**
     * Log the battery level of each device in the invocation.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param event a {@link String} describing the context of the logging (initial, setup, etc.).
     */
    @VisibleForTesting
    void logDeviceBatteryLevel(IInvocationContext context, String event) {
        for (ITestDevice testDevice : context.getDevices()) {
            if (testDevice == null) {
                continue;
            }
            if (testDevice.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (testDevice instanceof RemoteAndroidVirtualDevice
                    || testDevice instanceof NestedRemoteDevice) {
                // Vritual devices have a fake battery there is no point in logging it.
                continue;
            }
            Integer batteryLevel = testDevice.getBattery();
            if (batteryLevel == null) {
                CLog.v("Failed to get battery level for %s", testDevice.getSerialNumber());
                continue;
            }
            CLog.v("%s - %s - %d%%", BATT_TAG, event, batteryLevel);
            context.getBuildInfo(testDevice)
                    .addBuildAttribute(
                            String.format(
                                    BATTERY_ATTRIBUTE_FORMAT_KEY,
                                    testDevice.getSerialNumber(),
                                    event),
                            batteryLevel.toString());
        }
    }

    /**
     * Invoke {@link IInvocationExecution#fetchBuild(TestInformation, IConfiguration, IRescheduler,
     * ITestInvocationListener)} and handles the output as well as failures.
     *
     * @param testInfo the {@link TestInformation} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param rescheduler the {@link IRescheduler}, for rescheduling portions of the invocation for
     *     execution on another resource(s)
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @param invocationPath the {@link IInvocationExecution} driving the invocation.
     * @return True if we successfully downloaded the build, false otherwise.
     * @throws DeviceNotAvailableException
     */
    private boolean invokeFetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath)
            throws DeviceNotAvailableException {
        Exception buildException = null;
        boolean res = false;
        try {
            res = invocationPath.fetchBuild(testInfo, config, rescheduler, listener);
            if (res) {
                // Successful fetch of build.
                return true;
            }
            // In case of build not found issues.
            mStatus = "(no build to test)";
            // Set the exit code to error
            buildException = new BuildRetrievalError("No build found to test.");
            setExitCode(ExitCode.NO_BUILD, buildException);
        } catch (BuildRetrievalError e) {
            buildException = e;
        }
        // Report an empty invocation, so this error is sent to listeners
        startInvocation(config, testInfo.getContext(), listener);
        // Don't want to use #reportFailure, since that will call buildNotTested
        listener.invocationFailed(buildException);
        for (ITestDevice device : testInfo.getContext().getDevices()) {
            invocationPath.reportLogs(device, listener, Stage.ERROR);
        }
        reportHostLog(listener, config);
        listener.invocationEnded(0L);
        return false;
    }

    /**
     * Invoke {@link IConfiguration#resolveDynamicOptions()} to resolve the dynamic files.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param rescheduler the {@link IRescheduler}, for rescheduling portions of the invocation for
     *     execution on another resource(s)
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @param invocationPath the {@link IInvocationExecution} driving the invocation.
     * @param mode The current {@link RunMode} of the invocation.
     * @return True if we successfully downloaded the build, false otherwise.
     */
    private boolean invokeRemoteDynamic(
            IInvocationContext context,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath,
            RunMode mode) {
        try {
            // Don't resolve for remote invocation, wait until we are inside the remote.
            if (!RunMode.REMOTE_INVOCATION.equals(mode)) {
                config.resolveDynamicOptions();
            }
            return true;
        } catch (RuntimeException | BuildRetrievalError | ConfigurationException e) {
            // In case of build not found issues.
            mStatus = "(failed dynamic download)";
            // Set the exit code to error
            setExitCode(ExitCode.NO_BUILD, e);

            // We don't have a reporting buildInfo at this point
            IBuildInfo info = new BuildInfo();
            context.addDeviceBuildInfo(context.getDeviceConfigNames().get(0), info);

            // Report an empty invocation, so this error is sent to listeners
            startInvocation(config, context, listener);
            // Don't want to use #reportFailure, since that will call buildNotTested
            listener.invocationFailed(e);
            for (ITestDevice device : context.getDevices()) {
                invocationPath.reportLogs(device, listener, Stage.ERROR);
            }
            reportHostLog(listener, config);
            listener.invocationEnded(0L);
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invoke(
            IInvocationContext context,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener... extraListeners)
            throws DeviceNotAvailableException, Throwable {
        // Create the TestInformation for the invocation
        // TODO: Use invocation-id in the workfolder name
        File mWorkFolder = FileUtil.createTempDir("tradefed-invocation-workfolder");
        TestInformation info =
                TestInformation.newBuilder()
                        .setInvocationContext(context)
                        .setDependenciesFolder(mWorkFolder)
                        .build();

        List<ITestInvocationListener> allListeners =
                new ArrayList<>(config.getTestInvocationListeners().size() + extraListeners.length);
        allListeners.addAll(config.getTestInvocationListeners());
        allListeners.addAll(Arrays.asList(extraListeners));
        ITestInvocationListener listener = null;

        // Auto retry feature
        IRetryDecision decision = config.getRetryDecision();
        ResultAggregator aggregator = null;
        decision.setInvocationContext(context);
        if (decision.isAutoRetryEnabled()
                && decision.getMaxRetryCount() > 1
                && !RetryStrategy.NO_RETRY.equals(decision.getRetryStrategy())) {
            CLog.d("Auto-retry enabled, using the ResultAggregator to handle multiple retries.");
            aggregator = new ResultAggregator(allListeners, decision.getRetryStrategy());
            allListeners = Arrays.asList(aggregator);
        }

        if (!config.getPostProcessors().isEmpty()) {
            ITestInvocationListener forwarder = new ResultAndLogForwarder(allListeners);
            // Post-processors are the first layer around the final reporters.
            for (IPostProcessor postProcessor : config.getPostProcessors()) {
                if (postProcessor.isDisabled()) {
                    CLog.d("%s has been disabled. skipping.", postProcessor);
                } else {
                    forwarder = postProcessor.init(forwarder);
                }
            }
            listener = new LogSaverResultForwarder(config.getLogSaver(), Arrays.asList(forwarder));
        } else {
            listener = new LogSaverResultForwarder(config.getLogSaver(), allListeners);
        }

        RunMode mode = RunMode.REGULAR;
        if (config.getConfigurationDescription().shouldUseSandbox()) {
            mode = RunMode.SANDBOX;
        }
        if (config.getCommandOptions().shouldUseSandboxing()) {
            mode = RunMode.PARENT_SANDBOX;
        }
        if (context.getDevices().get(0) instanceof ManagedRemoteDevice) {
            mode = RunMode.REMOTE_INVOCATION;
        }
        IInvocationExecution invocationPath = createInvocationExec(mode);
        updateInvocationContext(context, config);

        // Create the Guice scope
        InvocationScope scope = getInvocationScope();
        scope.enter();
        // Seed our TF objects to the Guice scope
        scope.seed(IRescheduler.class, rescheduler);
        scope.seedConfiguration(config);
        try {
            ILeveledLogOutput leveledLogOutput = config.getLogOutput();
            leveledLogOutput.init();
            if (leveledLogOutput instanceof BaseLeveledLogOutput) {
                ((BaseLeveledLogOutput) leveledLogOutput).initFilters(config);
            }
            getLogRegistry().registerLogger(leveledLogOutput);
            mStatus = "resolving dynamic options";
            boolean resolverSuccess =
                    invokeRemoteDynamic(
                            context, config, rescheduler, listener, invocationPath, mode);
            if (!resolverSuccess) {
                return;
            }

            mStatus = "fetching build";
            for (String deviceName : context.getDeviceConfigNames()) {
                context.getDevice(deviceName).clearLastConnectedWifiNetwork();
                context.getDevice(deviceName)
                        .setOptions(config.getDeviceConfigByName(deviceName).getDeviceOptions());
                if (config.getDeviceConfigByName(deviceName)
                        .getDeviceOptions()
                        .isLogcatCaptureEnabled()) {
                    if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                        context.getDevice(deviceName).startLogcat();
                    }
                }
            }

            String cmdLineArgs = config.getCommandLine();
            if (cmdLineArgs != null) {
                CLog.i("Invocation was started with cmd: %s", cmdLineArgs);
            }

            long start = System.currentTimeMillis();
            boolean providerSuccess =
                    invokeFetchBuild(info, config, rescheduler, listener, invocationPath);
            long fetchBuildDuration = System.currentTimeMillis() - start;
            context.addInvocationTimingMetric(IInvocationContext.TimingEvent.FETCH_BUILD,
                    fetchBuildDuration);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.FETCH_BUILD, fetchBuildDuration);
            CLog.d("Fetch build duration: %s", TimeUtil.formatElapsedTime(fetchBuildDuration));
            if (!providerSuccess) {
                return;
            }

            boolean deviceInit = false;
            // If the top level invocation has --use-sandbox do not shard there. It will shard in
            // the child invocation.
            if (RunMode.REGULAR.equals(mode) || RunMode.SANDBOX.equals(mode)) {
                mStatus = "sharding";

                // TODO: Handle local sharding and special devices
                Integer shardCount = config.getCommandOptions().getShardCount();
                Integer shardIndex = config.getCommandOptions().getShardIndex();
                // Special Handling in case of sharding within the same invocation (in-place): Some
                // devices (Remote devices for example) require extra preparation step to be
                // available, but sharding requires the device to be available in some cases. So
                // we call the device setup early to meet all the requirements.
                if (shardCount != null && shardIndex != null) {
                    deviceInit = true;
                    startInvocation(config, context, listener);
                    try {
                        invocationPath.runDevicePreInvocationSetup(context, config, listener);
                    } catch (DeviceNotAvailableException | TargetSetupError e) {
                        CLog.e(e);
                        setExitCode(ExitCode.THROWABLE_EXCEPTION, e);
                        try {
                            invocationPath.runDevicePostInvocationTearDown(context, config, e);
                        } finally {
                            listener.invocationFailed(e);
                            // Reports the logs
                            for (ITestDevice device : context.getDevices()) {
                                invocationPath.reportLogs(device, listener, Stage.ERROR);
                            }
                            reportHostLog(listener, config);
                            listener.invocationEnded(0L);
                        }
                        return;
                    }
                }

                boolean sharding = invocationPath.shardConfig(config, info, rescheduler, listener);
                if (sharding) {
                    CLog.i(
                            "Invocation for %s has been sharded, rescheduling",
                            context.getSerials());
                    // Log the chunk of parent host_log before sharding
                    reportHostLog(listener, config, TRADEFED_LOG_NAME + BEFORE_SHARDING_SUFFIX);
                    config.getLogSaver().invocationEnded(0L);
                    if (aggregator != null) {
                        // The host_log is not available yet to reporters that don't support
                        // granular results, so forward it.
                        aggregator.forwardAggregatedInvocationLogs();
                    }
                    return;
                }
            }
            // Once we have all the information we can start the invocation.
            if (!deviceInit) {
                startInvocation(config, context, listener);
            }
            if (config.getTests() == null || config.getTests().isEmpty()) {
                CLog.e("No tests to run");
                if (deviceInit) {
                    // If we did an early setup, do the tear down.
                    invocationPath.runDevicePostInvocationTearDown(context, config, null);
                }
                listener.invocationEnded(0L);
                return;
            }

            performInvocation(config, info, invocationPath, rescheduler, listener, deviceInit);
            setExitCode(ExitCode.NO_ERROR, null);
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            scope.exit();
            // Ensure build infos are always cleaned up at the end of invocation.
            invocationPath.cleanUpBuilds(context, config);

            // ensure we always deregister the logger
            for (String deviceName : context.getDeviceConfigNames()) {
                if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                    context.getDevice(deviceName).stopLogcat();
                }
            }
            // save remaining logs contents to global log
            getLogRegistry().dumpToGlobalLog(config.getLogOutput());
            // Ensure log is unregistered and closed
            getLogRegistry().unregisterLogger();
            config.getLogOutput().closeLog();
            config.cleanConfigurationData();
            // Delete the invocation work directory at the end
            FileUtil.recursiveDelete(info.dependenciesFolder());
            // Delete all the execution files
            for (File f : info.executionFiles().getAll().values()) {
                FileUtil.recursiveDelete(f);
            }
        }
    }

    /** Returns the current {@link InvocationScope}. */
    @VisibleForTesting
    InvocationScope getInvocationScope() {
        return InvocationScope.getDefault();
    }

    /**
     * Helper to set the exit code. Exposed for testing.
     */
    protected void setExitCode(ExitCode code, Throwable stack) {
        GlobalConfiguration.getInstance().getCommandScheduler()
                .setLastInvocationExitCode(code, stack);
    }

    public static String getDeviceLogName(Stage stage) {
        return DEVICE_LOG_NAME_PREFIX + stage.getName();
    }

    public static String getEmulatorLogName(Stage stage) {
        return EMULATOR_LOG_NAME_PREFIX + stage.getName();
    }

    @Override
    public void notifyInvocationStopped(String message) {
        mStopCause = message;
        if (mStopRequestTime != null) {
            mStopRequestTime = System.currentTimeMillis();
        }
    }

    /**
     * Create the invocation path that should be followed.
     *
     * @param mode The mode we are currently running as.
     * @return The {@link IInvocationExecution} describing the invocation.
     */
    public IInvocationExecution createInvocationExec(RunMode mode) {
        switch (mode) {
            case PARENT_SANDBOX:
                return new ParentSandboxInvocationExecution();
            case SANDBOX:
                return new SandboxedInvocationExecution();
            case REMOTE_INVOCATION:
                return new RemoteInvocationExecution();
            default:
                return new InvocationExecution();
        }
    }

    /** Prints a delimiter for a given Stage of the invocation. */
    public static void printStageDelimiter(Stage phase, boolean end) {
        String startEnd = end ? "ENDING" : "STARTING";
        String message = String.format("===== %s PHASE %s =====", phase, startEnd);
        PrettyPrintDelimiter.printStageDelimiter(message);
    }

    private void logExecuteShellCommand(List<ITestDevice> devices, ITestLogger logger) {
        for (ITestDevice device : devices) {
            if (!(device instanceof NativeDevice)) {
                return;
            }
            File log = ((NativeDevice) device).getExecuteShellCommandLog();
            if (log == null || !log.exists()) {
                return;
            }
            if (log.length() == 0) {
                CLog.d("executeShellCommandLog file was empty, skip logging.");
                return;
            }
            try (InputStreamSource source = new FileInputStreamSource(log)) {
                logger.testLog(
                        String.format("executeShellCommandLog_%s", device.getSerialNumber()),
                        LogDataType.TEXT,
                        source);
            }
        }
    }

    /**
     * Update the {@link IInvocationContext} with additional info from the {@link IConfiguration}.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void updateInvocationContext(IInvocationContext context, IConfiguration config) {
        if (config.getCommandLine() != null) {
            context.addInvocationAttribute(
                    TestInvocation.COMMAND_ARGS_KEY, config.getCommandLine());
        }
        if (config.getCommandOptions().getShardCount() != null) {
            context.addInvocationAttribute(
                    "shard_count", config.getCommandOptions().getShardCount().toString());
        }
        if (config.getCommandOptions().getShardIndex() != null) {
            context.addInvocationAttribute(
                    "shard_index", config.getCommandOptions().getShardIndex().toString());
        }
        context.setTestTag(getTestTag(config));
    }

    /** Helper to create the test tag from the configuration. */
    private String getTestTag(IConfiguration config) {
        String testTag = config.getCommandOptions().getTestTag();
        if (config.getCommandOptions().getTestTagSuffix() != null) {
            testTag =
                    String.format("%s-%s", testTag, config.getCommandOptions().getTestTagSuffix());
        }
        return testTag;
    }

    /** Helper Thread that ensures host_log is reported in case of killed JVM */
    private class ReportHostLog extends Thread {

        private ITestInvocationListener mListener;
        private IConfiguration mConfiguration;

        public ReportHostLog(ITestInvocationListener listener, IConfiguration config) {
            mListener = listener;
            mConfiguration = config;
        }

        @Override
        public void run() {
            // Report all the logs that always be reported anyway.
            reportHostLog(mListener, mConfiguration);
        }
    }
}
