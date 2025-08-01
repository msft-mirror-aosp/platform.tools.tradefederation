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
import com.android.tradefed.build.CommandLineBuildInfoBuilder;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.clearcut.ClearcutClient;
import com.android.tradefed.command.CommandRunner.ExitCode;
import com.android.tradefed.command.CommandScheduler;
import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.command.ICommandScheduler.IScheduledInvocationListener;
import com.android.tradefed.config.ArgsOptionParser;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.filter.OptionFetcher;
import com.android.tradefed.config.proxy.AutomatedReporters;
import com.android.tradefed.config.proxy.TradefedDelegator;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.dependencies.ExternalDependency;
import com.android.tradefed.dependencies.IExternalDependency;
import com.android.tradefed.device.DeviceManager;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.FreeDeviceState;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.device.RemoteAvdIDevice;
import com.android.tradefed.device.SnapuserdWaitPhase;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.StubLocalAndroidVirtualDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.device.cloud.ManagedRemoteDevice;
import com.android.tradefed.device.cloud.NestedRemoteDevice;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.device.internal.DeviceReleaseReporter;
import com.android.tradefed.error.HarnessException;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.error.IHarnessException;
import com.android.tradefed.invoker.InvocationCacheHelper.CacheInvocationResultDescriptor;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.logger.TfObjectTracker;
import com.android.tradefed.invoker.sandbox.ParentSandboxInvocationExecution;
import com.android.tradefed.invoker.sandbox.SandboxedInvocationExecution;
import com.android.tradefed.invoker.shard.LastShardDetector;
import com.android.tradefed.invoker.shard.ShardHelper;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.BaseLeveledLogOutput;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.log.StdoutLogger;
import com.android.tradefed.postprocessor.IPostProcessor;
import com.android.tradefed.result.ActionInProgress;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.EventsLoggerListener;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.ReportPassedTests;
import com.android.tradefed.result.ResultAndLogForwarder;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.result.proto.InvocationProtoResultReporter;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.ResultAggregator;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.service.TradefedFeatureServer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFailedToBootError;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.ITestInformationReceiver;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IDisableable;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PrettyPrintDelimiter;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SystemUtil;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

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
    public static final String TRADEFED_INVOC_COMPLETE_HOST_LOG = "invoc_complete_host_log";
    private static final String TRADEFED_DELEGATED_LOG_NAME = "delegated_parent_log";
    public static final String TRADEFED_CONFIG_NAME = "tradefed-expanded-config";
    /** Suffix used on host_log for the part before sharding occurs. */
    static final String BEFORE_SHARDING_SUFFIX = "_before_sharding";
    static final String DEVICE_LOG_NAME_PREFIX = "device_logcat_";
    static final String EMULATOR_LOG_NAME_PREFIX = "emulator_log_";
    static final String BUILD_ERROR_BUGREPORT_NAME = "build_error_bugreport";
    static final String DEVICE_UNRESPONSIVE_BUGREPORT_NAME = "device_unresponsive_bugreport";
    static final String INVOCATION_ENDED_BUGREPORT_NAME = "invocation_ended_bugreport";
    static final String TARGET_SETUP_ERROR_BUGREPORT_NAME = "target_setup_error_bugreport";
    static final String BATT_TAG = "[battery level]";
    static final String RECOVERY_LOG_DEVICE_PATH = "/tmp/recovery.log";
    public static final String INVOCATION_EXTERNAL_DEPENDENCIES =
            "invocation-external-dependencies";
    public static final long AVAILABILITY_CHECK_TIMEOUT = 180000L; // 3 minutes
    static final String GOOGLE_USB_VENDOR_ID = "0x18d1";

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
        DELEGATED_INVOCATION
    }

    private String mStatus = "(not invoked)";
    private String mStopCause = null;
    private ErrorIdentifier mStopErrorId = null;
    private Long mStopRequestTime = null;
    private Long mSoftStopRequestTime = null;
    private boolean mShutdownBeforeTest = false;
    private boolean mTestStarted = false;
    private boolean mTestDone = false;
    private boolean mForcedStopRequestedAfterTest = false;
    private boolean mIsRemoteInvocation = false;

    private boolean mInvocationFailed = false;
    private boolean mDelegatedInvocation = false;
    private List<IScheduledInvocationListener> mSchedulerListeners = new ArrayList<>();
    private DeviceUnavailableMonitor mUnavailableMonitor = new DeviceUnavailableMonitor();
    private ConditionFailureMonitor mConditionalFailureMonitor = new ConditionFailureMonitor();
    private InvocationProtoResultReporter mInvocationProtoResultReporter = null;
    private ExitCode mExitCode = ExitCode.NO_ERROR;
    private Throwable mExitStack = null;
    private EventsLoggerListener mEventsLogger = null;
    private ClearcutClient mClient = null;

    private List<ExtendedFile> mParallelDynamicDownloads = new ArrayList<>();
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
            ITestInvocationListener listener,
            boolean devicePreSetupDone)
            throws Throwable {
        ReportHostLog reportThread = new ReportHostLog(listener, config);
        Runtime.getRuntime().addShutdownHook(reportThread);
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
                invocationPath.runDevicePreInvocationSetup(context, config, listener);
            }
            // Then run the regular setup and run
            prepareAndRun(config, testInfo, invocationPath, listener);
        } catch (BuildError e) {
            exception = e;
            CLog.w("BuildError on device '%s'. Reason: %s", e.getDeviceSerial(), e.toString());
            bugreportName = BUILD_ERROR_BUGREPORT_NAME;
            if (e.getDeviceSerial() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceSerial());
            }
            if (e instanceof DeviceFailedToBootError) {
                if (badDevice == null) {
                    context.setRecoveryModeForAllDevices(RecoveryMode.NONE);
                } else {
                    badDevice.setRecoveryMode(RecoveryMode.NONE);
                }
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
        } catch (TargetSetupError e) {
            exception = e;
            CLog.e("Caught exception while running invocation");
            CLog.e(e);
            // We let parent process capture the bugreport
            if (!isSubprocess(config)) {
                bugreportName = TARGET_SETUP_ERROR_BUGREPORT_NAME;
            }
            if (e.getDeviceSerial() != null) {
                badDevice = context.getDeviceBySerial(e.getDeviceSerial());
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
        } catch (DeviceNotAvailableException e) {
            exception = e;
            // log a warning here so its captured before reportLogs is called
            CLog.w(
                    "Invocation did not complete due to device %s becoming not available. "
                            + "Reason: %s",
                    e.getSerial(), e.toString());
            badDevice = context.getDeviceBySerial(e.getSerial());
            if ((e instanceof DeviceUnresponsiveException) && badDevice != null
                    && TestDeviceState.ONLINE.equals(badDevice.getDeviceState())) {
                // We let parent process capture the bugreport
                if (!isSubprocess(config)) {
                    // under certain cases it might still be possible to grab a bugreport
                    bugreportName = DEVICE_UNRESPONSIVE_BUGREPORT_NAME;
                }
            }
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
            // Upon reaching here after an exception, it is safe to assume that recovery
            // has already been attempted so we disable it to avoid re-entry during clean up.
            if (badDevice != null) {
                badDevice.setRecoveryMode(RecoveryMode.NONE);
            }
            throw e;
        } catch (RunInterruptedException e) {
            exception = e;
            CLog.w("Invocation interrupted");
            CLog.e(e);
            // if a stop cause was set, the interruption is most likely due to the invocation being
            // cancelled
            if (mStopCause == null) {
                reportFailure(createFailureFromException(e, FailureStatus.UNSET), listener);
            }
        } catch (AssertionError e) {
            exception = e;
            CLog.e("Caught AssertionError while running invocation: %s", e.toString());
            CLog.e(e);
            reportFailure(createFailureFromException(e, FailureStatus.UNSET), listener);
        } catch (Throwable t) {
            exception = t;
            // log a warning here so its captured before reportLogs is called
            CLog.e("Unexpected exception when running invocation: %s", t.toString());
            CLog.e(t);
            if (mStopCause == null) {
                reportFailure(createFailureFromException(t, FailureStatus.UNSET), listener);
                throw t;
            }
        } finally {
            mTestDone = true;
            long bugreportStartTime = System.currentTimeMillis();
            // Only capture logcat for TEST if we started the test phase.
            if (mTestStarted) {
                for (ITestDevice device : context.getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.TEST);
                }
            }
            if (mConditionalFailureMonitor.hasRunFailures()) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.HAS_ANY_RUN_FAILURES, "true");
            }
            CurrentInvocation.setActionInProgress(ActionInProgress.TEAR_DOWN);
            getRunUtil().allowInterrupt(false);
            if (!mDelegatedInvocation) {
                if (config.getCommandOptions().takeBugreportOnInvocationEnded()
                        || config.getCommandOptions().takeBugreportzOnInvocationEnded()) {
                    if (bugreportName != null) {
                        CLog.i("Bugreport to be taken for failure instead of invocation ended.");
                    } else {
                        bugreportName = INVOCATION_ENDED_BUGREPORT_NAME;
                    }
                }
                if (exception == null && !SystemUtil.isLocalMode()) {
                    exception = mUnavailableMonitor.getUnavailableException();
                    if (exception != null) {
                        CLog.e("Found a test level only device unavailable exception:");
                        CLog.e(exception);
                    }
                    if (exception == null) {
                        try (CloseableTraceScope ignore =
                                new CloseableTraceScope("responsiveness_check")) {
                            exception = bareMinimumResponsiveness(context.getDevices());
                        }
                    }
                    if (exception != null) {
                        bugreportName = null;
                    }
                }

                // reset bugreportName to null if shouldSkipBugreportError(exception) == true
                bugreportName = shouldSkipBugreportError(exception) ? null : bugreportName;
                if (bugreportName != null) {
                    try (CloseableTraceScope _ignore =
                            new CloseableTraceScope(InvocationMetricKey.bugreport.name())) {
                        if (context.getDevices().size() == 1 || badDevice != null) {
                            ITestDevice collectBugreport = badDevice;
                            if (collectBugreport == null) {
                                collectBugreport = context.getDevices().get(0);
                            }
                            // If we have identified a faulty device only take the bugreport on it.
                            takeBugreport(
                                    collectBugreport,
                                    listener,
                                    config.getCommandOptions(),
                                    bugreportName);
                        } else if (context.getDevices().size() > 1) {
                            ParallelDeviceExecutor<Boolean> executor =
                                    new ParallelDeviceExecutor<>(context.getDevices().size());
                            List<Callable<Boolean>> callableTasks = new ArrayList<>();
                            final String reportName = bugreportName;
                            for (ITestDevice device : context.getDevices()) {
                                Callable<Boolean> callableTask =
                                        () -> {
                                            CLog.d(
                                                    "Start taking bugreport on '%s'",
                                                    device.getSerialNumber());
                                            takeBugreport(
                                                    device,
                                                    listener,
                                                    config.getCommandOptions(),
                                                    reportName);
                                            return true;
                                        };
                                callableTasks.add(callableTask);
                            }
                            // Capture the bugreports best effort, ignore the results.
                            executor.invokeAll(callableTasks, 5, TimeUnit.MINUTES);
                        }
                    }
                }
                reportRecoveryLogs(context.getDevices(), listener);
            }
            try (CloseableTraceScope ignore = new CloseableTraceScope("logExecuteShellCommand")) {
                // Save the device executeShellCommand logs
                logExecuteShellCommand(context.getDevices(), listener);
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(InvocationMetricKey.check_device_availability.name())) {
                if (SystemUtil.isLocalMode()) {
                    CLog.d("Skipping check for device availability for local run.");
                } else if (exception == null) {
                    CLog.d("Checking that devices are online.");
                    exception = checkDevicesAvailable(context.getDevices(), listener);
                } else {
                    CLog.d("Skip online check as an exception was already reported: %s", exception);
                }
                // Report bugreport and various check as part of teardown
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEARDOWN_PAIR,
                        bugreportStartTime,
                        System.currentTimeMillis());
                mStatus = "tearing down";
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(InvocationMetricKey.test_teardown.name())) {
                invocationPath.doTeardown(testInfo, config, listener, exception);
            } catch (Throwable e) {
                tearDownException = e;
                CLog.e("Exception when tearing down invocation: %s", tearDownException.toString());
                CLog.e(tearDownException);
                if (exception == null) {
                    // only report when the exception is new during tear down
                    reportFailure(
                            createFailureFromException(
                                    tearDownException, FailureStatus.INFRA_FAILURE),
                            listener);
                }
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(InvocationMetricKey.log_and_release_device.name())) {
                // Capture last logcat before releasing the device.
                for (ITestDevice device : context.getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.TEARDOWN);
                }
                mStatus = "done running tests";
                CurrentInvocation.setActionInProgress(ActionInProgress.FREE_RESOURCES);

                // Ensure we always deregister the logger
                for (String deviceName : context.getDeviceConfigNames()) {
                    if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                        context.getDevice(deviceName).stopLogcat();
                        CLog.i(
                                "Done stopping logcat for %s",
                                context.getDevice(deviceName).getSerialNumber());
                    }
                }

                if (config.getCommandOptions().earlyDeviceRelease()) {
                    Map<ITestDevice, FreeDeviceState> devicesStates =
                            handleAndLogReleaseState(context, exception, tearDownException);
                    context.markReleasedEarly();
                    for (IScheduledInvocationListener scheduleListener : mSchedulerListeners) {
                        scheduleListener.releaseDevices(context, devicesStates);
                    }
                }
                // Log count of allocated devices for test accounting
                addInvocationMetric(
                        InvocationMetricKey.DEVICE_COUNT, context.getNumDevicesAllocated());
                // Track the timestamp when we are done with devices
                addInvocationMetric(
                        InvocationMetricKey.DEVICE_DONE_TIMESTAMP, System.currentTimeMillis());
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(InvocationMetricKey.test_cleanup.name())) {
                // Clean up host.
                invocationPath.doCleanUp(context, config, exception);
                waitForSnapuserd(
                        testInfo, config, SnapuserdWaitPhase.BLOCK_BEFORE_RELEASING, false);
                if (mSoftStopRequestTime != null) { // soft stop occurred
                    long latency = System.currentTimeMillis() - mSoftStopRequestTime;
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.SHUTDOWN_LATENCY, latency);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.SHUTDOWN_BEFORE_TEST,
                            Boolean.toString(mShutdownBeforeTest));
                    if (mShutdownBeforeTest) {
                        String message =
                                String.format("Notified of soft shut down. Did not run tests");
                        FailureDescription failure =
                                FailureDescription.create(message)
                                        .setErrorIdentifier(
                                                InfraErrorIdentifier
                                                        .TRADEFED_SKIPPED_TESTS_DURING_SHUTDOWN)
                                        .setCause(
                                                new HarnessRuntimeException(
                                                        message,
                                                        InfraErrorIdentifier
                                                                .TRADEFED_SKIPPED_TESTS_DURING_SHUTDOWN));
                        // report failure so that command can be un-leased
                        reportFailure(failure, listener);
                    }
                }
                if (mStopCause != null) { // Forced stop occurred
                    if (mForcedStopRequestedAfterTest) {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.SHUTDOWN_AFTER_TEST, "true");
                        CLog.d(
                                "Forced shutdown occurred after test phase execution. It shouldn't"
                                        + " have impact on test results.");
                    } else {
                        String message =
                                String.format(
                                        "Invocation was interrupted due to: %s%s",
                                        mStopCause,
                                        mShutdownBeforeTest
                                                ? ". Tests were not run."
                                                : ", results will be affected");
                        if (mStopErrorId == null) {
                            mStopErrorId = InfraErrorIdentifier.INVOCATION_CANCELLED;
                        }
                        // if invocation is stopped and tests were not run, report invocation
                        // failure with correct error identifier so that command can be
                        // un-leased
                        if (mShutdownBeforeTest) {
                            mStopErrorId =
                                    InfraErrorIdentifier.TRADEFED_SKIPPED_TESTS_DURING_SHUTDOWN;
                        }
                        FailureDescription failure =
                                FailureDescription.create(message)
                                        .setErrorIdentifier(mStopErrorId)
                                        .setCause(
                                                new HarnessRuntimeException(message, mStopErrorId));
                        reportFailure(failure, listener);
                        PrettyPrintDelimiter.printStageDelimiter(message);
                    }
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

                // Measure teardown disk usage before clean up
                Long size = measureWorkFolderSize(config, testInfo);
                if (size != null) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.TEAR_DOWN_DISK_USAGE, size);
                }
                elapsedTime = System.currentTimeMillis() - startTime;
                reportInvocationEnded(config, context, listener, elapsedTime);
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
        long startTimeNano = System.nanoTime();
        try {
            getRunUtil().allowInterrupt(true);
            logDeviceBatteryLevel(testInfo.getContext(), "initial -> setup");
            CurrentInvocation.setActionInProgress(ActionInProgress.SETUP);
            invocationPath.doSetup(testInfo, config, listener);
            // Don't run tests if notified of soft/forced shutdown
            if (mSoftStopRequestTime != null || mStopRequestTime != null) {
                if (System.getenv("IS_CLOUD_ATE") == null) {
                    // Throw an exception so that it can be reported as an invocation failure
                    // and command can be un-leased
                    throw new RunInterruptedException(
                            "Notified of shut down. Will not run tests",
                            InfraErrorIdentifier.TRADEFED_SKIPPED_TESTS_DURING_SHUTDOWN);
                } else {
                    CLog.d(
                            "Notified of shut down. Will still run tests and respect grace period"
                                + " in CI for shutting down.");
                }
            }
            logDeviceBatteryLevel(testInfo.getContext(), "setup -> test");
            mTestStarted = true;
            CurrentInvocation.setActionInProgress(ActionInProgress.TEST);
            waitForSnapuserd(
                    testInfo, config, SnapuserdWaitPhase.BLOCK_BEFORE_TEST, isSubprocess(config));
            invocationPath.runTests(testInfo, config, listener);
        } finally {
            if (mClient != null) {
                mClient.notifyTestRunFinished(startTimeNano);
            }
        }
        logDeviceBatteryLevel(testInfo.getContext(), "after test");
        CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
    }

    /**
     * Starts the invocation.
     *
     * <p>Starts logging, and informs listeners that invocation has been started.
     *
     * @param config
     * @param context
     */
    private void startInvocation(
            IConfiguration config,
            IInvocationContext context,
            ITestInvocationListener listener,
            RunMode mode,
            boolean parentShard) {
        logStartInvocation(context, config);
        listener.invocationStarted(context);
        logExpandedConfiguration(config, listener, mode, parentShard);
    }

    private void startInvocation(
            IConfiguration config, IInvocationContext context, ITestInvocationListener listener) {
        startInvocation(config, context, listener, null, false);
    }

    /** Report the exception failure as an invocation failure. */
    private void reportFailure(FailureDescription failure, ITestInvocationListener listener) {
        if (mInvocationFailed) {
            CLog.e("An invocation failure was already reported, ignoring %s", failure);
            return;
        }
        // Always report the failure
        listener.invocationFailed(failure);
        mInvocationFailed = true;
    }

    /**
     * Create a {@link FailureDescription} from an invocation exception.
     *
     * @param exception The exception to convert
     * @param defaultStatus The status to use by default if the exception is not a {@link
     *     IHarnessException}.
     */
    public static FailureDescription createFailureFromException(
            Throwable exception, FailureStatus defaultStatus) {
        ErrorIdentifier id = null;
        if (exception instanceof IHarnessException) {
            id = ((IHarnessException) exception).getErrorId();
        }
        String message = exception.getMessage();
        if (message == null) {
            message = "No error message";
        }
        FailureDescription failure =
                CurrentInvocation.createFailure(message, id).setCause(exception);
        if (id == null) {
            failure.setFailureStatus(defaultStatus);
        }
        return failure;
    }

    private void reportHostLog(ITestInvocationListener listener, IConfiguration config) {
        String name = TRADEFED_LOG_NAME;
        if (mDelegatedInvocation) {
            name = TRADEFED_DELEGATED_LOG_NAME;
        }
        reportHostLog(listener, config, name);
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
            ITestDevice device,
            ITestInvocationListener listener,
            ICommandOptions options,
            String bugreportName) {
        if (device == null) {
            return;
        }
        if (device.getIDevice() instanceof StubDevice) {
            return;
        }
        if (!TestDeviceState.ONLINE.equals(device.getDeviceState())) {
            CLog.d("Skipping bugreportz on %s. Device is offline.", device.getSerialNumber());
            return;
        }
        // logBugreport will report a regular bugreport if bugreportz is not supported.
        RecoveryMode recovery = device.getRecoveryMode();
        try {
            device.setRecoveryMode(RecoveryMode.NONE);
            if (!options.isConditionalBugreportDisabled()
                    && !mConditionalFailureMonitor.hasFailures()) {
                device.logAnrs(listener);
            } else {
                boolean res =
                        device.logBugreport(
                                String.format("%s_%s", bugreportName, device.getSerialNumber()),
                                listener);
                if (!res) {
                    CLog.w(
                            "Error when collecting bugreport for device '%s'",
                            device.getSerialNumber());
                }
            }
        } catch (DeviceNotAvailableException | RuntimeException e) {
            CLog.e("Harness Exception while collecting bugreport");
            CLog.e(e);
        } finally {
            device.setRecoveryMode(recovery);
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
        if (SystemUtil.isLocalMode()) {
            CLog.d("Skipping battery level log for local invocation on event: %s.", event);
            return;
        }
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
            try (CloseableTraceScope ignored = new CloseableTraceScope("log_battery")) {
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
    }

    /**
     * Log the invocation configuration as one large XML detailing all settings in use.
     *
     * @param config the {@link IConfiguration} of this test run
     * @param listener the {@link ITestLogger} with which to register the log
     */
    private void logExpandedConfiguration(
            IConfiguration config, ITestLogger listener, RunMode mode, boolean parentShard) {
        boolean isShard = config.getConfigurationDescription().getShardIndex() != null;
        if (isShard && !parentShard) {
            // Bail out of logging the config if this is a local shard since it is problematic
            // and redundant anyway.
            CLog.d("Skipping expanded config log for shard.");
            return;
        }
        try (StringWriter configXmlWriter = new StringWriter();
                PrintWriter wrapperWriter = new PrintWriter(configXmlWriter)) {
            config.dumpXml(wrapperWriter, new ArrayList<String>(), true, false);
            wrapperWriter.flush();
            // Specified UTF-8 encoding for an abundance of caution, but its possible we could want
            // something else in the future
            byte[] configXmlByteArray = configXmlWriter.toString().getBytes("UTF-8");
            try (InputStreamSource source = new ByteArrayInputStreamSource(configXmlByteArray)) {
                String prefix = "";
                if (mode != null) {
                    switch (mode) {
                        case PARENT_SANDBOX:
                            prefix = "parent-sandbox-";
                            break;
                        case SANDBOX:
                            prefix = "child-sandbox-";
                            break;
                        case DELEGATED_INVOCATION:
                            prefix = "parent-delegate-";
                            break;
                        case REMOTE_INVOCATION:
                            // Fallthrough
                        default:
                            prefix = "";
                    }
                }
                String configOutputName = String.format("%s%s", prefix, TRADEFED_CONFIG_NAME);
                listener.testLog(configOutputName, LogDataType.HARNESS_CONFIG, source);
            }
        } catch (IOException e) {
            CLog.e(e);
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
     */
    private boolean invokeFetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath)
            throws Exception {
        CurrentInvocation.setActionInProgress(ActionInProgress.FETCHING_ARTIFACTS);
        Exception buildException = null;
        boolean res = false;
        try {
            res = invocationPath.fetchBuild(testInfo, config, rescheduler, listener);
            if (res) {
                try (CloseableTraceScope ignored =
                        new CloseableTraceScope("wait_for_dynamic_download")) {
                    for (ExtendedFile file : mParallelDynamicDownloads) {
                        CLog.d("Wait for %s to finish downloading", file);
                        file.waitForDownload();
                    }
                }
                // Successful fetch of build.
                CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
                return true;
            }
            // In case of build not found issues.
            mStatus = "(no build to test)";
            // Set the exit code to error
            buildException =
                    new BuildRetrievalError(
                            "No build found to test.", InfraErrorIdentifier.ARTIFACT_NOT_FOUND);
        } catch (BuildRetrievalError | RuntimeException | DeviceNotAvailableException e) {
            buildException = e;
        }
        for (ExtendedFile file : mParallelDynamicDownloads) {
            file.cancelDownload();
        }
        setExitCode(ExitCode.NO_BUILD, buildException);
        // If somehow we don't have builds
        if (testInfo.getContext().getBuildInfos().isEmpty()) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.BACKFILL_BUILD_INFO, "true");
            IBuildInfo info = backFillBuildInfoForReporting(config.getCommandLine());
            testInfo.getContext()
                    .addDeviceBuildInfo(testInfo.getContext().getDeviceConfigNames().get(0), info);
        }
        // Report an empty invocation, so this error is sent to listeners
        startInvocation(config, testInfo.getContext(), listener);
        reportFailure(
                createFailureFromException(buildException, FailureStatus.INFRA_FAILURE), listener);
        for (ITestDevice device : testInfo.getContext().getDevices()) {
            invocationPath.reportLogs(device, listener, Stage.ERROR);
        }
        reportHostLog(listener, config);
        reportInvocationEnded(config, testInfo.getContext(), listener, 0L);
        CLog.e(buildException);
        // We rethrow so it's caught in CommandScheduler and properly release
        // the device
        throw buildException;
    }

    /**
     * Invoke {@link IConfiguration#resolveDynamicOptions(DynamicRemoteFileResolver)} to resolve the
     * dynamic files.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @param invocationPath the {@link IInvocationExecution} driving the invocation.
     * @param mode The current {@link RunMode} of the invocation.
     * @return True if we successfully downloaded the build, false otherwise.
     */
    private boolean invokeRemoteDynamic(
            IInvocationContext context,
            IConfiguration config,
            ITestInvocationListener listener,
            IInvocationExecution invocationPath,
            RunMode mode)
            throws BuildRetrievalError, ConfigurationException {
        DynamicRemoteFileResolver resolver =
                new DynamicRemoteFileResolver(true /* allow parallelization */);
        try {
            CurrentInvocation.setActionInProgress(ActionInProgress.FETCHING_ARTIFACTS);
            resolver.setDevice(context.getDevices().get(0));
            resolver.addExtraArgs(config.getCommandOptions().getDynamicDownloadArgs());
            config.resolveDynamicOptions(resolver);
            mParallelDynamicDownloads.addAll(resolver.getParallelDownloads());
            CurrentInvocation.setActionInProgress(ActionInProgress.UNSET);
            return true;
        } catch (RuntimeException | BuildRetrievalError | ConfigurationException e) {
            // Cancel running downloads
            for (ExtendedFile file : resolver.getParallelDownloads()) {
                file.cancelDownload();
            }
            // We don't have a reporting buildInfo at this point
            IBuildInfo info = backFillBuildInfoForReporting(config.getCommandLine());

            // In case of build not found issues.
            mStatus = "(failed dynamic download)";
            // Set the exit code to error
            setExitCode(ExitCode.NO_BUILD, e);
            context.addDeviceBuildInfo(context.getDeviceConfigNames().get(0), info);

            // Report an empty invocation, so this error is sent to listeners
            startInvocation(config, context, listener);
            reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
            for (ITestDevice device : context.getDevices()) {
                invocationPath.reportLogs(device, listener, Stage.ERROR);
            }
            reportHostLog(listener, config);
            reportInvocationEnded(config, context, listener, 0L);
            // We rethrow so it's caught in CommandScheduler and properly release
            // the device
            throw e;
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
        RunMode mode = RunMode.REGULAR;
        ITestInvocationListener listener = null;
        TestInformation info = null;
        ResultAggregator aggregator = null;
        CleanUpInvocationFiles cleanUpThread = null;
        try (CloseableTraceScope ignore =
                new CloseableTraceScope(InvocationMetricKey.invocation_warm_up.name())) {
            if (!config.getInopOptions().isEmpty()) {
                context.addInvocationAttribute(
                        "inop-options", Joiner.on(",").join(config.getInopOptions()));
            }
            // Carry the reference of the server so it can be used within the same process.
            if (config.getConfigurationDescription()
                    .getAllMetaData()
                    .getUniqueMap()
                    .containsKey(TradefedFeatureServer.SERVER_REFERENCE)) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.SERVER_REFERENCE,
                        config.getConfigurationDescription()
                                .getAllMetaData()
                                .getUniqueMap()
                                .get(TradefedFeatureServer.SERVER_REFERENCE));
            }
            // Only log invocation_start in parent
            boolean isCurrentlySubprocess = isSubprocess(config);
            if (!isCurrentlySubprocess) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.INVOCATION_START, System.currentTimeMillis());
            } else {
                CLog.d("Fetching options from parent.");
                // Get options from the parent process
                try (OptionFetcher fetchOptions = new OptionFetcher()) {
                    fetchOptions.fetchParentOptions(config);
                }
            }
            // Handle the automated reporting
            applyAutomatedReporters(config);

            if (config.getCommandOptions().delegatedEarlyDeviceRelease()
                    && System.getenv(DelegatedInvocationExecution.DELEGATED_MODE_VAR) != null) {
                // If in a subprocess, add the early device release feature as a listener.
                mSchedulerListeners.add(new DeviceReleaseReporter());
            }

            for (ITestInvocationListener extra : extraListeners) {
                if (extra instanceof IScheduledInvocationListener) {
                    mSchedulerListeners.add((IScheduledInvocationListener) extra);
                }
            }
            // Create the TestInformation for the invocation
            // TODO: Use invocation-id in the workfolder name
            Object sharedInfoObject =
                    config.getConfigurationObject(ShardHelper.SHARED_TEST_INFORMATION);
            TestInformation sharedTestInfo = null;
            if (sharedInfoObject != null) {
                sharedTestInfo = (TestInformation) sharedInfoObject;
                // During sharding we share everything except the invocation context & properties
                info = TestInformation.createModuleTestInfo(sharedTestInfo, context);
            }
            if (info == null) {
                File mWorkFolder = FileUtil.createTempDir("tf-workfolder");
                info =
                        TestInformation.newBuilder()
                                .setInvocationContext(context)
                                .setDependenciesFolder(mWorkFolder)
                                .build();
            }
            // Register the test info to the configuration to be usable.
            config.setConfigurationObject(TradefedFeatureServer.TEST_INFORMATION_OBJECT, info);
            CurrentInvocation.addInvocationInfo(
                    InvocationInfo.WORK_FOLDER, info.dependenciesFolder());

            cleanUpThread = new CleanUpInvocationFiles(info, config);
            Runtime.getRuntime().addShutdownHook(cleanUpThread);
            registerExecutionFiles(info.executionFiles());

            List<ITestInvocationListener> allListeners = new ArrayList<>();
            // If it's not a subprocess, report the passed tests.
            ReportPassedTests reportPass = null;
            if (config.getConfigurationObject(TradefedDelegator.DELEGATE_OBJECT) == null
                    && config.getCommandOptions().reportPassedTests()
                    && !isSubprocess(config)) {
                reportPass = new ReportPassedTests();
                reportPass.setConfiguration(config);
                allListeners.add(reportPass);
            }
            List<ITestInvocationListener> resultReporters =
                    new ArrayList<ITestInvocationListener>(config.getTestInvocationListeners());
            boolean disableReporter =
                    resultReporters.removeIf(
                            l -> ((l instanceof IDisableable) && ((IDisableable) l).isDisabled()));
            if (disableReporter) {
                CLog.d("Some reporters are disabled and won't be used.");
            }
            allListeners.addAll(resultReporters);
            allListeners.addAll(Arrays.asList(extraListeners));
            allListeners.add(mUnavailableMonitor);
            allListeners.add(mConditionalFailureMonitor);
            if (config.getCommandOptions().shouldUploadInvocationCacheResults()) {
                mInvocationProtoResultReporter =
                        new InvocationProtoResultReporter(info.getContext(), false);
                File outputFile = FileUtil.createTempFile("invocation-results-cache", ".pb");
                mInvocationProtoResultReporter.setOutputFile(outputFile);
                allListeners.add(mInvocationProtoResultReporter);
            }

            // Auto retry feature
            IRetryDecision decision = config.getRetryDecision();
            decision.setInvocationContext(context);
            if (decision instanceof ITestInformationReceiver) {
                ((ITestInformationReceiver) decision).setTestInformation(info);
            }
            updateInvocationContext(context, config);
            CurrentInvocation.setInvocationContext(context);
            config.getLogSaver().init(context);
            // We don't need the aggregator in the subprocess because the parent will take care of
            // it.
            if (!config.getCommandOptions()
                    .getInvocationData()
                    .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)) {
                if (decision.isAutoRetryEnabled()
                        && decision.getMaxRetryCount() > 1
                        && !RetryStrategy.NO_RETRY.equals(decision.getRetryStrategy())) {
                    CLog.d(
                            "Auto-retry enabled, using the ResultAggregator to handle multiple"
                                    + " retries.");
                    aggregator = new ResultAggregator(allListeners, decision.getRetryStrategy());
                    aggregator.setUpdatedReporting(decision.useUpdatedReporting());
                    allListeners = Arrays.asList(aggregator);
                } else {
                    mEventsLogger = new EventsLoggerListener("all-events");
                    allListeners.add(mEventsLogger);
                }
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
                listener =
                        new LogSaverResultForwarder(
                                config.getLogSaver(), Arrays.asList(forwarder), config);
            } else {
                listener = new LogSaverResultForwarder(config.getLogSaver(), allListeners, config);
            }
            if (reportPass != null) {
                reportPass.setLogger(listener);
            }

            if (config.getConfigurationDescription().shouldUseSandbox()) {
                mode = RunMode.SANDBOX;
            }
            if (config.getCommandOptions().shouldUseSandboxing()) {
                mode = RunMode.PARENT_SANDBOX;
            }
            if (context.getDevices().get(0) instanceof ManagedRemoteDevice) {
                mode = RunMode.REMOTE_INVOCATION;
            }
            if (config.getConfigurationObject(TradefedDelegator.DELEGATE_OBJECT) != null) {
                mDelegatedInvocation = true;
                mode = RunMode.DELEGATED_INVOCATION;
            }
        }
        IInvocationExecution invocationPath = createInvocationExec(mode);

        boolean sharding = false;
        try {
            ILeveledLogOutput leveledLogOutput = config.getLogOutput();
            leveledLogOutput.init();
            if (leveledLogOutput instanceof BaseLeveledLogOutput) {
                ((BaseLeveledLogOutput) leveledLogOutput).initFilters(config);
            }
            getLogRegistry().registerLogger(leveledLogOutput);

            // Only in parent fetch demotion information
            config.getSkipManager().setup(config, context);

            mStatus = "resolving dynamic options";
            long startDynamic = System.currentTimeMillis();
            boolean resolverSuccess = false;
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(InvocationMetricKey.dynamic_download.name())) {
                resolverSuccess =
                        invokeRemoteDynamic(context, config, listener, invocationPath, mode);
            } finally {
                // Do not report the pair for subprocess as it would be part
                // of a test specific setup instead.
                if (!isSubprocess(config)) {
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.DYNAMIC_FILE_RESOLVER_PAIR,
                            startDynamic,
                            System.currentTimeMillis());
                }
            }
            if (!resolverSuccess) {
                return;
            }

            mStatus = "fetching build";
            String cmdLineArgs = config.getCommandLine();
            if (cmdLineArgs != null) {
                CLog.i("Invocation was started with cmd: %s", cmdLineArgs);
            }

            long start = System.currentTimeMillis();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.FETCH_BUILD_START, start);
            boolean providerSuccess = false;
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(InvocationMetricKey.fetch_artifact.name())) {
                providerSuccess =
                        invokeFetchBuild(info, config, rescheduler, listener, invocationPath);
            } finally {
                long end = System.currentTimeMillis();
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.FETCH_BUILD_END, end);
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.FETCH_BUILD_PAIR, start, end);
                long fetchBuildDuration = end - start;
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.FETCH_BUILD, fetchBuildDuration);
                CLog.d("Fetch build duration: %s", TimeUtil.formatElapsedTime(fetchBuildDuration));
            }
            if (!providerSuccess) {
                return;
            }
            // Skip invocation can only happen in the parent process and not in the parent
            // delegator.
            if (!config.getCommandOptions()
                            .getInvocationData()
                            .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME)
                    && !RunMode.DELEGATED_INVOCATION.equals(mode)) {
                boolean skipInvocation = config.getSkipManager().shouldSkipInvocation(info, config);
                String skipReason = config.getSkipManager().getInvocationSkipReason();
                if (!skipInvocation) {
                    if (config.getCommandOptions().getRemoteCacheInstanceName() != null
                            && config.getCommandOptions().shouldUploadInvocationCacheResults()) {
                        CacheInvocationResultDescriptor descriptor =
                                InvocationCacheHelper.lookupInvocationResults(config, info);
                        if (descriptor != null && descriptor.isCacheHit()) {
                            skipReason = descriptor.getDetails();
                            if (InvocationContext.isPresubmit(context)
                                    && config.getCommandOptions()
                                            .reportInvocationCacheResultsInPresubmit()) {
                                skipInvocation = true;
                            }
                        }
                    }
                }
                if (skipInvocation) {
                    CLog.d("Skipping invocation early.");
                    startInvocation(config, info.getContext(), listener);
                    // Backfill accounting metrics with zeros
                    long timestamp = System.currentTimeMillis();
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.TEST_SETUP_PAIR, timestamp, timestamp);
                    InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.SETUP, 0);
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.SETUP_PAIR, timestamp, timestamp);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.SETUP_START, timestamp);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.SETUP_END, timestamp);
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.TEST_PAIR, timestamp, timestamp);
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.TEARDOWN_PAIR, timestamp, timestamp);
                    InvocationMetricLogger.addInvocationPairMetrics(
                            InvocationMetricKey.TEST_TEARDOWN_PAIR, timestamp, timestamp);
                    listener.invocationSkipped(new SkipReason(skipReason, ""));
                    reportModuleSkip(config, listener);
                    reportHostLog(listener, config);
                    reportInvocationEnded(config, info.getContext(), listener, 0L);
                    return;
                }
            }
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(InvocationMetricKey.start_logcat.name())) {
                for (String deviceName : context.getDeviceConfigNames()) {
                    context.getDevice(deviceName).clearLastConnectedWifiNetwork();
                    // TODO: Report invocation error if setOptions() fails
                    context.getDevice(deviceName)
                            .setOptions(
                                    config.getDeviceConfigByName(deviceName).getDeviceOptions());
                    if (config.getDeviceConfigByName(deviceName)
                            .getDeviceOptions()
                            .isLogcatCaptureEnabled()) {
                        if (!(context.getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                            context.getDevice(deviceName).startLogcat();
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Report an empty invocation, so this error is sent to listeners
                startInvocation(config, info.getContext(), listener);
                reportFailure(createFailureFromException(e, FailureStatus.INFRA_FAILURE), listener);
                for (ITestDevice device : info.getContext().getDevices()) {
                    invocationPath.reportLogs(device, listener, Stage.ERROR);
                }
                reportHostLog(listener, config);
                reportInvocationEnded(config, info.getContext(), listener, 0L);
                return;
            }

            // Apply global filters before sharding so they are taken into account.
            config.getGlobalFilters()
                    .setUpFilters(config, config.getSkipManager().getDemotedTests().keySet());
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
                boolean startInvocationCalled = false;
                if (shardCount != null && shardIndex != null) {
                    try (CloseableTraceScope ignored =
                            new CloseableTraceScope(
                                    InvocationMetricKey.pre_sharding_required_setup.name())) {
                        deviceInit = true;
                        startInvocation(config, context, listener);
                        startInvocationCalled = true;
                        invocationPath.runDevicePreInvocationSetup(context, config, listener);
                    } catch (DeviceNotAvailableException | TargetSetupError e) {
                        CLog.e(e);
                        FailureDescription failure = FailureDescription.create(e.getMessage());
                        failure.setCause(e).setFailureStatus(FailureStatus.INFRA_FAILURE);
                        if (e instanceof DeviceNotAvailableException) {
                            setExitCode(ExitCode.DEVICE_UNAVAILABLE, e);
                        } else {
                            setExitCode(ExitCode.THROWABLE_EXCEPTION, e);
                        }
                        try {
                            invocationPath.runDevicePostInvocationTearDown(context, config, e);
                        } finally {
                            reportFailure(
                                    createFailureFromException(e, FailureStatus.INFRA_FAILURE),
                                    listener);
                            // Reports the logs
                            for (ITestDevice device : context.getDevices()) {
                                invocationPath.reportLogs(device, listener, Stage.ERROR);
                            }
                            reportHostLog(listener, config);
                            reportInvocationEnded(config, context, listener, 0L);
                        }
                        return;
                    }
                }

                try (CloseableTraceScope ignored =
                        new CloseableTraceScope(InvocationMetricKey.sharding.name())) {
                    sharding = invocationPath.shardConfig(config, info, rescheduler, listener);
                } catch (RuntimeException unexpected) {
                    CLog.e("Exception during sharding.");
                    CLog.e(unexpected);
                    if (deviceInit) {
                        // If we did an early setup, do the tear down.
                        invocationPath.runDevicePostInvocationTearDown(context, config, unexpected);
                    }
                    // Call the reporting to get debugging infos.
                    if (!startInvocationCalled) {
                        startInvocation(config, context, listener);
                    }
                    reportFailure(
                            createFailureFromException(unexpected, FailureStatus.INFRA_FAILURE)
                                    .setActionInProgress(ActionInProgress.TEST),
                            listener);
                    reportHostLog(listener, config);
                    listener.invocationEnded(0L);
                    return;
                }
                if (sharding) {
                    CLog.i(
                            "Invocation for %s has been sharded, rescheduling",
                            context.getSerials());
                    // Log the chunk of parent host_log before sharding
                    reportHostLog(listener, config, TRADEFED_LOG_NAME + BEFORE_SHARDING_SUFFIX);
                    logExpandedConfiguration(config, listener, mode, true);
                    config.getLogSaver().invocationEnded(0L);
                    if (aggregator != null) {
                        // The host_log is not available yet to reporters that don't support
                        // granular results, so forward it.
                        aggregator.forwardAggregatedInvocationLogs();
                        aggregator.cleanEventsFiles();
                    }
                    return;
                }
            }
            // Once we have all the information we can start the invocation.
            if (!deviceInit) {
                try (CloseableTraceScope s = new CloseableTraceScope("startInvocation")) {
                    startInvocation(config, context, listener);
                }
            }
            if (!RunMode.DELEGATED_INVOCATION.equals(mode)
                    && (config.getTests() == null || config.getTests().isEmpty())) {
                CLog.e("No tests to run");
                if (deviceInit) {
                    // If we did an early setup, do the tear down.
                    invocationPath.runDevicePostInvocationTearDown(context, config, null);
                }
                if (mEventsLogger != null) {
                    logEventsFile(mEventsLogger.getLoggedEvents(), listener);
                }
                listener.invocationEnded(0L);
                return;
            }

            performInvocation(config, info, invocationPath, listener, deviceInit);
            setExitCode(ExitCode.NO_ERROR, null);
            if (mInvocationProtoResultReporter != null
                    && !mInvocationProtoResultReporter.stopCaching()) {
                InvocationCacheHelper.uploadInvocationResults(
                        config, mInvocationProtoResultReporter.getOutputFile(), info);
            }
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            if (mInvocationProtoResultReporter != null) {
                FileUtil.deleteFile(mInvocationProtoResultReporter.getOutputFile());
            }
            TfObjectTracker.clearTracking();
            CurrentInvocation.clearInvocationInfos();
            config.getSkipManager().clearManager();
            // Ensure build infos are always cleaned up at the end of invocation.
            CLog.i("Cleaning up builds");
            invocationPath.cleanUpBuilds(context, config);
            if (!sharding) {
                // If we are the parent shard, we do not delete the test information
                deleteInvocationFiles(info, config);
            }

            if (!config.getCommandOptions().reportInvocationComplete()) {
                // save remaining logs contents to global log
                getLogRegistry().dumpToGlobalLog(config.getLogOutput());
                // Ensure log is unregistered and closed
                getLogRegistry().unregisterLogger();
                config.getLogOutput().closeLog();
            }

            config.cleanConfigurationData();
            if (cleanUpThread != null) {
                Runtime.getRuntime().removeShutdownHook(cleanUpThread);
            }
        }
    }

    @VisibleForTesting
    public void registerExecutionFiles(ExecutionFiles executionFiles) {
        CurrentInvocation.registerExecutionFiles(executionFiles);
    }

    /**
     * Helper to set the exit code. Exposed for testing.
     */
    protected void setExitCode(ExitCode code, Throwable stack) {
        mExitCode = code;
        mExitStack = stack;
    }

    protected void addInvocationMetric(InvocationMetricKey key, long value) {
        InvocationMetricLogger.addInvocationMetrics(key, value);
    }

    protected void addInvocationMetric(InvocationMetricKey key, String value) {
        InvocationMetricLogger.addInvocationMetrics(key, value);
    }

    public static String getDeviceLogName(Stage stage) {
        return DEVICE_LOG_NAME_PREFIX + stage.getName();
    }

    public static String getEmulatorLogName(Stage stage) {
        return EMULATOR_LOG_NAME_PREFIX + stage.getName();
    }

    @Override
    public void notifyInvocationForceStopped(String message, ErrorIdentifier errorId) {
        mStopCause = message;
        mStopErrorId = errorId;
        if (mStopRequestTime == null) {
            mStopRequestTime = System.currentTimeMillis();
            mForcedStopRequestedAfterTest = mTestDone;
            // If test isn't started yet, we know we can stop
            mShutdownBeforeTest = !mTestStarted;
        }
    }

    @Override
    public void notifyInvocationStopped(String message) {
        if (mSoftStopRequestTime == null) {
            mSoftStopRequestTime = System.currentTimeMillis();
            // If test isn't started yet, we know we can stop
            mShutdownBeforeTest = !mTestStarted;
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
                mIsRemoteInvocation = true;
                return new RemoteInvocationExecution();
            case DELEGATED_INVOCATION:
                return new DelegatedInvocationExecution();
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

    @VisibleForTesting
    protected void applyAutomatedReporters(IConfiguration config) {
        AutomatedReporters autoReport = new AutomatedReporters();
        autoReport.applyAutomatedReporters(config);
    }

    private void logExecuteShellCommand(List<ITestDevice> devices, ITestLogger logger) {
        for (ITestDevice device : devices) {
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (!(device instanceof NativeDevice)) {
                continue;
            }
            File log = ((NativeDevice) device).getExecuteShellCommandLog();
            if (log == null || !log.exists()) {
                continue;
            }
            if (log.length() == 0) {
                CLog.d("executeShellCommandLog file was empty, skip logging.");
                continue;
            }
            try (InputStreamSource source = new FileInputStreamSource(log)) {
                logger.testLog(
                        String.format("executeShellCommandLog_%s", device.getSerialNumber()),
                        LogDataType.TEXT,
                        source);
            }
        }
    }

    private void logEventsFile(File eventsLog, ITestLogger logger) {
        if (eventsLog != null && eventsLog.length() > 0) {
            try (FileInputStreamSource source = new FileInputStreamSource(eventsLog, true)) {
                logger.testLog("event-logs", LogDataType.TF_EVENTS, source);
            }
        }
        FileUtil.deleteFile(eventsLog);
    }

    /**
     * Update the {@link IInvocationContext} with additional info from the {@link IConfiguration}.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void updateInvocationContext(IInvocationContext context, IConfiguration config) {
        context.setTestTag(getTestTag(config));
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            // Avoid relogging the properties in a subprocess
            return;
        }
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
        // add Invocation level external dependencies
        Set<ExternalDependency> externalDependencies = new LinkedHashSet<>();
        for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
            for (Object obj : deviceConfig.getAllObjects()) {
                if (obj instanceof IExternalDependency) {
                    externalDependencies.addAll(((IExternalDependency) obj).getDependencies());
                }
            }
        }
        if (!externalDependencies.isEmpty()) {
            List<String> dependencyClassNames =
                    externalDependencies.stream()
                            .map(dependency -> dependency.getClass().getName())
                            .collect(Collectors.toList());
            context.addInvocationAttribute(
                    INVOCATION_EXTERNAL_DEPENDENCIES, String.join(", ", dependencyClassNames));
        }
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

    /**
     * Delete the invocation files if this is the last shard for local sharding or if we are not in
     * a local sharding situation.
     */
    private void deleteInvocationFiles(TestInformation testInfo, IConfiguration config) {
        Object obj = config.getConfigurationObject(ShardHelper.LAST_SHARD_DETECTOR);
        if (obj != null) {
            LastShardDetector lastShardDetector = (LastShardDetector) obj;
            if (!lastShardDetector.isLastShardDone()) {
                return;
            }
        }
        // Delete the invocation work directory at the end
        FileUtil.recursiveDelete(testInfo.dependenciesFolder());
        // Delete all the execution files
        testInfo.executionFiles().clearFiles();
    }

    private Map<ITestDevice, FreeDeviceState> handleAndLogReleaseState(
            IInvocationContext context, Throwable exception, Throwable tearDownException) {
        if (exception == null && tearDownException != null) {
            exception = tearDownException;
        } else if (tearDownException instanceof DeviceNotAvailableException) {
            // Use what we consider a later & higher priority error
            exception = tearDownException;
        }
        // Capture the FreeDeviceState of the primary device
        Map<ITestDevice, FreeDeviceState> devicesStates =
                CommandScheduler.createReleaseMap(context, exception);
        if (devicesStates.size() >= 1) {
            addInvocationMetric(
                    InvocationMetricKey.DEVICE_RELEASE_STATE,
                    devicesStates.values().iterator().next().toString());
        }
        int countPhysicalLost = 0;
        int countVirtualLost = 0;
        for (Entry<ITestDevice, FreeDeviceState> fds : devicesStates.entrySet()) {
            // TODO: Rely on the FailureStatus for lost devices instead
            if ((fds.getKey().getIDevice() instanceof RemoteAvdIDevice
                            || fds.getKey().getIDevice() instanceof StubLocalAndroidVirtualDevice)
                    && exception instanceof DeviceNotAvailableException) {
                countVirtualLost++;
                continue;
            }
            if (fds.getKey().getIDevice() instanceof StubDevice) {
                continue;
            }
            if (FreeDeviceState.UNAVAILABLE.equals(fds.getValue())
                    || FreeDeviceState.UNRESPONSIVE.equals(fds.getValue())) {
                // Remote devices are not seen as stub, but are still virtual devices
                if (fds.getKey() instanceof RemoteAndroidDevice
                        || fds.getKey() instanceof NestedRemoteDevice) {
                    countVirtualLost++;
                } else {
                    countPhysicalLost++;
                }
            }
        }
        if (countPhysicalLost > 0) {
            addInvocationMetric(InvocationMetricKey.DEVICE_LOST_DETECTED, countPhysicalLost);
            if (GlobalConfiguration.getDeviceManagerInstance() instanceof DeviceManager) {
                String adbOutput =
                        ((DeviceManager) GlobalConfiguration.getDeviceManagerInstance())
                                .executeGlobalAdbCommand("devices");
                CLog.e("'adb devices' output:\n%s", adbOutput);

                CommandResult fastbootResult =
                        getRunUtil()
                                .runTimedCmdSilently(
                                        60000L,
                                        GlobalConfiguration.getDeviceManagerInstance()
                                                .getFastbootPath(),
                                        "devices");
                CLog.d("'fastboot devices' output:\n%s", fastbootResult.getStdout());

                CommandResult lsusbResult =
                        getRunUtil()
                                .runTimedCmdSilently(
                                        60000L, "lsusb", "-d", GOOGLE_USB_VENDOR_ID + ":");
                CLog.d("'lsusb -d %s:' output:\n%s", GOOGLE_USB_VENDOR_ID, lsusbResult.getStdout());
            }
        } else if (countVirtualLost > 0) {
            CLog.e("Counting as virtual_device_lost.");
            addInvocationMetric(InvocationMetricKey.VIRTUAL_DEVICE_LOST_DETECTED, countVirtualLost);
        }
        return devicesStates;
    }

    private void reportRecoveryLogs(List<ITestDevice> devices, ITestInvocationListener listener) {
        for (ITestDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (device.getDeviceState() != TestDeviceState.RECOVERY) {
                continue;
            }
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.ATTEMPT_RECOVERY_LOG_COUNT, 1);
            RecoveryMode mode = device.getRecoveryMode();
            try {
                device.setRecoveryMode(RecoveryMode.NONE);
                // We need root to access the recovery logs so attempt to set it
                String output = device.executeAdbCommand("root");
                CLog.d("adb recovery root output: %s", output);
                File recovery_log = device.pullFile(RECOVERY_LOG_DEVICE_PATH);
                if (recovery_log != null) {
                    try (FileInputStreamSource fis = new FileInputStreamSource(recovery_log)) {
                        listener.testLog(
                                String.format("recovery_log_%s.txt", device.getSerialNumber()),
                                LogDataType.RECOVERY_MODE_LOG,
                                fis);
                    }
                }
                File trustyLog = device.pullFile("/dev/trusty-log0");
                if (trustyLog != null) {
                    try (FileInputStreamSource fis = new FileInputStreamSource(trustyLog)) {
                        listener.testLog(
                                String.format("trusty-log0_%s.txt", device.getSerialNumber()),
                                LogDataType.RECOVERY_MODE_LOG,
                                fis);
                    }
                }
                File lastKmsg = device.pullFile("/sys/fs/pstore/console-ramoops-0");
                if (lastKmsg != null) {
                    try (FileInputStreamSource fis = new FileInputStreamSource(lastKmsg)) {
                        listener.testLog(
                                String.format("recovery_mode_last_kmsg_%s.txt",
                                device.getSerialNumber()),
                                LogDataType.RECOVERY_MODE_LOG,
                                fis);
                    }
                }
            } catch (DeviceNotAvailableException e) {
                CLog.i("Device unavailable, can't pull recovery.log");
            } finally {
                device.setRecoveryMode(mode);
            }
        }
    }

    private void reportInvocationEnded(
            IConfiguration config,
            IInvocationContext context,
            ITestInvocationListener listener,
            long elapsedTime) {
        // Only log Invocation ended in parent
        if (mIsRemoteInvocation || !isSubprocess(config)) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.INVOCATION_END, System.currentTimeMillis());
        }
        // Init a log for the end of the host_log.
        ILeveledLogOutput endHostLog = config.getLogOutput();
        try {
            endHostLog.init();
            getLogRegistry().registerLogger(endHostLog);
        } catch (IOException e) {
            CLog.e(e);
            endHostLog = null;
        }

        PrettyPrintDelimiter.printStageDelimiter("===== Result Reporters =====");
        try {
            // Copy the invocation metrics to the context
            ((InvocationContext) context).logInvocationMetrics();
            if (mEventsLogger != null) {
                logEventsFile(mEventsLogger.getLoggedEvents(), listener);
            }
            listener.invocationEnded(elapsedTime);
        } finally {
            InvocationMetricLogger.clearInvocationMetrics();
            if (endHostLog != null) {
                endHostLog.closeLog();
                getLogRegistry().unregisterLogger();
            }
        }
        if (!config.getCommandOptions().reportInvocationComplete()) {
            return;
        }
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            config.getCommandOptions().setReportInvocationComplete(false);
            return;
        }
        // Re-init for invocationComplete logs
        try {
            endHostLog.init();
            getLogRegistry().registerLogger(endHostLog);
        } catch (IOException e) {
            CLog.e(e);
            config.getCommandOptions().setReportInvocationComplete(false);
        }
    }

    private DeviceNotAvailableException bareMinimumResponsiveness(List<ITestDevice> devices) {
        for (ITestDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (device.isStateBootloaderOrFastbootd()) {
                return null;
            }
            if (TestDeviceState.RECOVERY.equals(device.getDeviceState())) {
                return null;
            }
            RecoveryMode current = device.getRecoveryMode();
            device.setRecoveryMode(RecoveryMode.NONE);
            CLog.d("Testing minimum responsiveness.");
            try {
                if (device instanceof NativeDevice) {
                    ((NativeDevice) device).invalidatePropertyCache();
                }
                device.waitForDeviceOnline(60000L);
                device.getApiLevel();
            } catch (DeviceNotAvailableException e) {
                return e;
            } finally {
                device.setRecoveryMode(current);
            }
        }
        return null;
    }

    /**
     * If no previous exception occurred, report if the device is not available anymore after tests
     * finish running.
     */
    private DeviceNotAvailableException checkDevicesAvailable(
            List<ITestDevice> devices, ITestInvocationListener listener) {
        DeviceNotAvailableException dnae = null;
        for (ITestDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            if (device.isStateBootloaderOrFastbootd()) {
                dnae =
                        new DeviceNotAvailableException(
                                "Device was left in fastboot state after tests",
                                device.getSerialNumber(),
                                DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
                reportFailure(
                        createFailureFromException(dnae, FailureStatus.INFRA_FAILURE), listener);
                continue;
            }
            if (TestDeviceState.RECOVERY.equals(device.getDeviceState())) {
                dnae =
                        new DeviceNotAvailableException(
                                "Device was left in recovery state after tests",
                                device.getSerialNumber(),
                                DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
                reportFailure(
                        createFailureFromException(dnae, FailureStatus.INFRA_FAILURE), listener);
                continue;
            }
            RecoveryMode current = device.getRecoveryMode();
            device.setRecoveryMode(RecoveryMode.NONE);
            try {
                // Cap availability check at 3 minutes instead of the device
                // configured one because this is not tied to a reboot, we just
                // need the device to be still online & reporting.
                boolean available = device.waitForDeviceAvailable(AVAILABILITY_CHECK_TIMEOUT);
                if (!available) {
                    throw new DeviceNotAvailableException(
                            String.format(
                                    "Device %s failed availability check after running tests.",
                                    device.getSerialNumber()),
                            device.getSerialNumber(),
                            DeviceErrorIdentifier.DEVICE_UNAVAILABLE);
                }
            } catch (DeviceNotAvailableException e) {
                String msg =
                        String.format("Device was left offline after tests: %s", e.getMessage());
                DeviceNotAvailableException wrap =
                        new DeviceNotAvailableException(msg, e, e.getSerial(), e.getErrorId());
                reportFailure(
                        createFailureFromException(wrap, FailureStatus.INFRA_FAILURE), listener);
                dnae = e;
            } finally {
                device.setRecoveryMode(current);
            }
        }
        return dnae;
    }

    private void reportModuleSkip(IConfiguration config, ITestInvocationListener listener) {
        if (!config.getSkipManager().reportInvocationSkippedModule()) {
            return;
        }
        // Make a heuristic determination of ABI.
        String abi = "arm64";
        if (config.getDeviceConfig().get(0).getDeviceRequirements().nullDeviceRequested()
                || config.getDeviceConfig().get(0).getDeviceRequirements().gceDeviceRequested()) {
            abi = "x86_64";
        }
        String buildTarget =
                config.getCommandOptions()
                        .getInvocationData()
                        .getUniqueMap()
                        .get("test_result.build_target");
        if (!Strings.isNullOrEmpty(buildTarget) && buildTarget.contains("cf_arm64")) {
            abi = "arm64";
        }

        for (String moduleName : config.getSkipManager().getUnchangedModules()) {
            IInvocationContext moduleContext = new InvocationContext();
            ConfigurationDescriptor configDescriptor = new ConfigurationDescriptor();
            configDescriptor.setModuleName(moduleName);

            moduleContext.setConfigurationDescriptor(configDescriptor);
            moduleContext.addInvocationAttribute(ModuleDefinition.MODULE_ABI, abi);
            moduleContext.addInvocationAttribute(ModuleDefinition.MODULE_NAME, moduleName);
            moduleContext.addInvocationAttribute(
                    ModuleDefinition.MODULE_ID, abi + " " + moduleName);
            moduleContext.addInvocationAttribute(
                    ModuleDefinition.MODULE_SKIPPED,
                    config.getSkipManager().getInvocationSkipReason());
            moduleContext.addInvocationAttribute(
                    ModuleDefinition.SPARSE_MODULE,
                    "true");
            listener.testModuleStarted(moduleContext);
            listener.testModuleEnded();
        }
    }

    /**
     * Helper that use the command line to backfill a {@link IBuildInfo} for reporting in case of
     * download failure.
     */
    public static IBuildInfo backFillBuildInfoForReporting(String commandLine) {
        IBuildInfo info = new BuildInfo();
        CommandLineBuildInfoBuilder builder = new CommandLineBuildInfoBuilder();
        try {
            List<String> command =
                    new ArrayList<>(
                            Arrays.asList(
                                    QuotationAwareTokenizer.tokenizeLine(commandLine, false)));
            command.remove(0);
            ArgsOptionParser parser = new ArgsOptionParser(builder);
            parser.parseBestEffort(command, true);
            info = builder.createBuild();
        } catch (ConfigurationException ignore) {
            CLog.e(ignore);
        }
        return info;
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

    /** Helper Thread to ensure invocation files are deleted in case of killed JVM */
    private class CleanUpInvocationFiles extends Thread {

        private TestInformation mTestInfo;
        private IConfiguration mConfig;

        public CleanUpInvocationFiles(TestInformation currentInfo, IConfiguration config) {
            mTestInfo = currentInfo;
            mConfig = config;
        }

        @Override
        public void run() {
            deleteInvocationFiles(mTestInfo, mConfig);
        }
    }

    /** Measure the size of the work folder. */
    private Long measureWorkFolderSize(IConfiguration config, TestInformation testInfo) {
        if (testInfo == null) {
            return null;
        }
        File workFolder = testInfo.dependenciesFolder();
        CLog.d("Measuring size of %s", workFolder);
        if (workFolder == null || !workFolder.exists()) {
            return null;
        }
        // Only measure in parent process
        if (isSubprocess(config)) {
            CLog.d("Skip measuring size since we are in subprocess");
            return null;
        }

        Object obj = config.getConfigurationObject(ShardHelper.LAST_SHARD_DETECTOR);
        if (obj != null) {
            LastShardDetector lastShardDetector = (LastShardDetector) obj;
            if (!lastShardDetector.isLastShardDone()) {
                return null;
            }
        }
        return FileUtil.sizeOfDirectory(workFolder);
    }

    @Override
    public ExitInformation getExitInfo() {
        ExitInformation info = new ExitInformation();
        info.mExitCode = this.mExitCode;
        info.mStack = this.mExitStack;
        return info;
    }

    @Override
    public void setClearcutClient(ClearcutClient client) {
        mClient = client;
    }

    /** Always complete snapuserd before proceeding into test. */
    private void waitForSnapuserd(
            TestInformation testInfo,
            IConfiguration config,
            SnapuserdWaitPhase currentPhase,
            boolean force)
            throws DeviceNotAvailableException {
        for (ITestDevice device : testInfo.getDevices()) {
            if (device instanceof StubDevice) {
                continue;
            }
            if (force) {
                // Force a notify so we go through a round of detection.
                // This ensures we will commit the snapshot before tests in subprocess
                device.notifySnapuserd(currentPhase);
            }
            device.waitForSnapuserd(currentPhase); // Should be inop if not waiting on any updates.
        }
    }

    /** Returns true if the invocation is currently within a subprocess scope. */
    public static boolean isSubprocess(IConfiguration config) {
        if (System.getenv(DelegatedInvocationExecution.DELEGATED_MODE_VAR) != null) {
            return true;
        }
        return config.getCommandOptions()
                .getInvocationData()
                .containsKey(SubprocessTfLauncher.SUBPROCESS_TAG_NAME);
    }

    /** Helper method that identifies errors when the bugreport should be skipped */
    public static boolean shouldSkipBugreportError(@Nullable Throwable t) {
        if (t == null) {
            return false;
        }

        if (!(t instanceof HarnessException)) {
            return false;
        }

        HarnessException e = (HarnessException) t;

        if (e.getErrorId() == null) {
            // Can't tell, better take a bugreport just in case.
            return false;
        }

        long errorId = e.getErrorId().code();

        // Configuration Errors
        if (errorId >= 505_250 && errorId < 505_300) {
            return true;
        }

        // Artifact Errors
        if (errorId >= 500_501 && errorId < 501_000) {
            return true;
        }

        // Certain General Errors
        if (errorId == 500_501
                || errorId == 500_003
                || errorId == 500_008
                || errorId == 500_009
                || errorId == 500_010
                || errorId == 500_013
                || errorId == 500_014
                || errorId == 500_017) {
            return true;
        }

        return false;
    }
}
