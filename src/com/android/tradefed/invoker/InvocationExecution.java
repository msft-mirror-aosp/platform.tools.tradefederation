/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildInfo.BuildInfoProperties;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.build.IDeviceBuildProvider;
import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.filter.GetPreviousPassedHelper;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.device.cloud.OxygenUtil;
import com.android.tradefed.device.metric.AutoLogCollector;
import com.android.tradefed.device.metric.CollectorHelper;
import com.android.tradefed.device.metric.CountTestCasesCollector;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollectorReceiver;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.TestInvocation.Stage;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.IsolationGrade;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationGroupMetricKey;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.logger.TfObjectTracker;
import com.android.tradefed.invoker.shard.IShardHelper;
import com.android.tradefed.invoker.shard.TestsPoolPoller;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.RetryLogSaverResultForwarder;
import com.android.tradefed.retry.RetryStatistics;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.suite.checker.ISystemStatusCheckerReceiver;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.IHostCleaner;
import com.android.tradefed.targetprep.ILabPreparer;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.testtype.retry.IAutoRetriableTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.PrettyPrintDelimiter;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.SystemUtil;
import com.android.tradefed.util.SystemUtil.EnvVariable;
import com.android.tradefed.util.TimeUtil;
import com.android.tradefed.util.executor.ParallelDeviceExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Class that describes all the invocation steps: build download, target_prep, run tests, clean up.
 * Can be extended to override the default behavior of some steps. Order of the steps is driven by
 * {@link TestInvocation}.
 */
public class InvocationExecution implements IInvocationExecution {

    public static final String ADB_VERSION_KEY = "adb_version";
    public static final String JAVA_VERSION_KEY = "java_version";
    public static final String JAVA_CLASSPATH_KEY = "java_classpath";
    // Track which preparer ran in setup to ensure we don't trigger tearDown if setup was skipped.
    private Set<IMultiTargetPreparer> mTrackMultiPreparers = null;
    private Map<String, Set<ITargetPreparer>> mTrackLabPreparers = null;
    private Map<String, Set<ITargetPreparer>> mTrackTargetPreparers = null;
    // GceManager for multi-device leasing. It's needed for releasing the devices.
    private GceManager mMultiDeviceRequester = null;

    /** Timer to make sure Test Phase does not run for too long. */
    private class TestPhaseMonitor extends TimerTask {

        private TestThread mTestThread;

        public TestPhaseMonitor(TestThread toMonitor) {
            mTestThread = toMonitor;
        }

        @Override
        public void run() {
            if (mTestThread != null) {
                mTestThread.stopTestThread();
            }
        }
    }

    /** A Thread to execute {@link IRemoteTest} */
    private class TestThread extends Thread {
        private TestInformation mTestInfo;
        private ITestInvocationListener mTestListener;
        private IRemoteTest mTest;
        private Throwable lastThrownException;

        public TestThread(
                TestInformation info, ITestInvocationListener listener, IRemoteTest test) {
            mTestInfo = info;
            mTestListener = listener;
            mTest = test;
        }

        @Override
        public void run() {
            try {
                mTest.run(mTestInfo, mTestListener);
            } catch (Exception e) {
                lastThrownException = e;
            }
        }

        public Throwable getLastThrownException() {
            return lastThrownException;
        }

        public void stopTestThread() {
            this.interrupt();
            mTestInfo.notifyTimeout();
            // record this interrupt as an exception so that TestInvocation thread can throw this.
            lastThrownException =
                    new RunInterruptedException(
                            "Test Phase Timeout Reached.",
                            TestErrorIdentifier.TEST_PHASE_TIMED_OUT);
        }
    }

    @Override
    public boolean fetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException, BuildRetrievalError {
        String currentDeviceName = null;
        IBuildInfo buildReplicat = null;
        try {
            // TODO: evaluate fetching build in parallel
            for (int i = 0; i < testInfo.getContext().getDeviceConfigNames().size(); i++) {
                currentDeviceName = testInfo.getContext().getDeviceConfigNames().get(i);
                if (buildReplicat != null) {
                    // TODO: evaluate if cloning the build is needed
                    testInfo.getContext().addDeviceBuildInfo(currentDeviceName, buildReplicat);
                    continue;
                }
                IBuildInfo info = null;
                ITestDevice device = testInfo.getContext().getDevice(currentDeviceName);
                IDeviceConfiguration deviceConfig = config.getDeviceConfigByName(currentDeviceName);
                IBuildProvider provider = deviceConfig.getBuildProvider();
                TfObjectTracker.countWithParents(provider.getClass());
                // Inject the context to the provider if it can receive it
                if (provider instanceof IInvocationContextReceiver) {
                    ((IInvocationContextReceiver) provider)
                            .setInvocationContext(testInfo.getContext());
                }
                if (provider instanceof ITestLoggerReceiver) {
                    ((ITestLoggerReceiver) provider).setTestLogger(listener);
                }
                // Get the build
                if (provider instanceof IDeviceBuildProvider) {
                    // Download a device build if the provider can handle it.
                    info = ((IDeviceBuildProvider) provider).getBuild(device);
                } else {
                    info = provider.getBuild();
                }
                if (info != null) {
                    info.setDeviceSerial(device.getSerialNumber());
                    testInfo.getContext().addDeviceBuildInfo(currentDeviceName, info);
                    device.setRecovery(deviceConfig.getDeviceRecovery());
                } else {
                    CLog.logAndDisplay(
                            LogLevel.WARN,
                            "No build found to test for device: %s",
                            device.getSerialNumber());
                    IBuildInfo notFoundStub = new BuildInfo();
                    updateBuild(notFoundStub, config);
                    testInfo.getContext().addDeviceBuildInfo(currentDeviceName, notFoundStub);
                    return false;
                }
                // TODO: remove build update when reporting is done on context
                updateBuild(info, config);
                linkExternalDirs(info, testInfo);

                if (config.getCommandOptions().shouldUseReplicateSetup()) {
                    buildReplicat = info;
                }
            }
        } catch (BuildRetrievalError e) {
            CLog.e(e);
            if (currentDeviceName != null) {
                IBuildInfo errorBuild = e.getBuildInfo();
                updateBuild(errorBuild, config);
                testInfo.getContext().addDeviceBuildInfo(currentDeviceName, errorBuild);
            }
            throw e;
        } catch (RuntimeException re) {
            if (currentDeviceName != null) {
                IBuildInfo errorBuild =
                        TestInvocation.backFillBuildInfoForReporting(config.getCommandLine());
                updateBuild(errorBuild, config);
                testInfo.getContext().addDeviceBuildInfo(currentDeviceName, errorBuild);
            }
            throw re;
        }
        setBinariesVersion(testInfo.getContext());
        copyRemoteFiles(config.getCommandOptions(), testInfo.getBuildInfo());
        return true;
    }

    @Override
    public void cleanUpBuilds(IInvocationContext context, IConfiguration config) {
        // Ensure build infos are always cleaned up at the end of invocation.
        for (String cleanUpDevice : context.getDeviceConfigNames()) {
            if (context.getBuildInfo(cleanUpDevice) != null) {
                try {
                    config.getDeviceConfigByName(cleanUpDevice)
                            .getBuildProvider()
                            .cleanUp(context.getBuildInfo(cleanUpDevice));
                } catch (RuntimeException e) {
                    // We catch an simply log exception in cleanUp to avoid missing any final
                    // step of the invocation.
                    CLog.e(e);
                }
            }
        }
    }

    @Override
    public boolean shardConfig(
            IConfiguration config,
            TestInformation testInfo,
            IRescheduler rescheduler,
            ITestLogger logger) {
        IShardHelper helper = createShardHelper();
        CLog.d("IShardHelper selected: %s", helper);
        return helper.shardConfig(config, testInfo, rescheduler, logger);
    }

    /** Create an return the {@link IShardHelper} to be used. */
    @VisibleForTesting
    protected IShardHelper createShardHelper() {
        return GlobalConfiguration.getInstance().getShardingStrategy();
    }

    /**
     * Retrieve a list of target preparers to run on this device.
     *
     * <p>Overridden in sandbox classes to restrict lab preparers from being run inside the sandbox
     * child
     */
    protected List<ITargetPreparer> getTargetPreparersToRun(
            IConfiguration config, String deviceName) {
        List<ITargetPreparer> preparersToRun = new ArrayList<>();
        preparersToRun.addAll(config.getDeviceConfigByName(deviceName).getTargetPreparers());
        return preparersToRun;
    }

    /**
     * Retrieve a list of lab preparers to run on this device.
     *
     * <p>Overridden in sandbox classes to restrict lab preparers from being run inside the sandbox
     * child
     */
    protected List<ITargetPreparer> getLabPreparersToRun(IConfiguration config, String deviceName) {
        List<ITargetPreparer> preparersToRun = new ArrayList<>();
        preparersToRun.addAll(config.getDeviceConfigByName(deviceName).getLabPreparers());
        return preparersToRun;
    }

    @Override
    public void doSetup(TestInformation testInfo, IConfiguration config, final ITestLogger listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        long start = System.currentTimeMillis();
        mTrackLabPreparers = new ConcurrentHashMap<>();
        mTrackTargetPreparers = new ConcurrentHashMap<>();
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.SETUP_START, start);

        for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
            ITestDevice device = testInfo.getContext().getDevice(deviceName);
            CLog.d("Starting setup for device: '%s'", device.getSerialNumber());
            if (device instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) testInfo.getContext().getDevice(deviceName))
                        .setTestLogger(listener);
            }
            mTrackLabPreparers.put(deviceName, new HashSet<>());
            mTrackTargetPreparers.put(deviceName, new HashSet<>());
        }
        try {
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(InvocationMetricKey.pre_multi_preparer.name())) {
                // Before all the individual setup, make the multi-pre-target-preparer devices setup
                runMultiTargetPreparers(
                        config.getMultiPreTargetPreparers(),
                        listener,
                        testInfo,
                        "multi pre target preparer setup");
            } finally {
                long end = System.currentTimeMillis();
                // Pre-multi-preparer are test specific and account toward test setup
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEST_SETUP_PAIR, start, end);
            }
            start = System.currentTimeMillis();
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(InvocationMetricKey.lab_setup.name())) {
                runLabPreparersSetup(testInfo, config, listener);
            } finally {
                long end = System.currentTimeMillis();
                InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.SETUP_END, end);
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.SETUP_PAIR, start, end);
            }
            long startPreparer = System.currentTimeMillis();
            try (CloseableTraceScope ignored =
                    new CloseableTraceScope(InvocationMetricKey.test_setup.name())) {
                runPreparersSetup(testInfo, config, listener);

                // After all the individual setup, make the multi-devices setup
                runMultiTargetPreparers(
                        config.getMultiTargetPreparers(),
                        listener,
                        testInfo,
                        "multi target preparer setup");
                // Collect some info automatically after setup
                collectAutoInfo(config, testInfo);
            } finally {
                // Note: These metrics are handled in a try in case of a kernel reset or device
                // issue.
                // Setup timing metric. It does not include flashing time on boot tests.
                long end = System.currentTimeMillis();
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEST_SETUP_PAIR, startPreparer, end);
                long setupDuration = end - start;
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.SETUP, setupDuration);
                CLog.d("Total setup duration: %s'", TimeUtil.formatElapsedTime(setupDuration));
            }
        } finally {
            // Upload the setup logcat after setup is complete.
            for (ITestDevice device : testInfo.getDevices()) {
                reportLogs(device, listener, Stage.SETUP);
            }
        }
    }

    private void runLabPreparersSetup(
            TestInformation testInfo, IConfiguration config, ITestLogger listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        int index = 0;
        if ((config.getCommandOptions().shouldUseParallelSetup()
                        || config.getCommandOptions().shouldUseReplicateSetup())
                && config.getDeviceConfig().size() > 1) {
            CLog.d("Using parallel setup.");
            ParallelDeviceExecutor<Boolean> executor =
                    new ParallelDeviceExecutor<>(testInfo.getContext().getDevices().size());
            List<Callable<Boolean>> callableTasks = new ArrayList<>();
            for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
                final int deviceIndex = index;
                // Replicate TestInfo
                TestInformation replicated =
                        TestInformation.createModuleTestInfo(testInfo, testInfo.getContext());
                Callable<Boolean> callableTask =
                        () -> {
                            // Lab preparer then target preparer
                            runLabPreparationOnDevice(
                                    replicated,
                                    deviceName,
                                    deviceIndex,
                                    getLabPreparersToRun(config, deviceName),
                                    mTrackLabPreparers.get(deviceName),
                                    listener);
                            return true;
                        };
                callableTasks.add(callableTask);
                index++;
            }
            Duration timeout = config.getCommandOptions().getParallelSetupTimeout();
            executor.invokeAll(callableTasks, timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (executor.hasErrors()) {
                List<Throwable> errors = executor.getErrors();
                // TODO: Handle throwing multi-exceptions, right now throw the first one.
                for (Throwable error : errors) {
                    if (error instanceof TargetSetupError) {
                        throw (TargetSetupError) error;
                    }
                    if (error instanceof BuildError) {
                        throw (BuildError) error;
                    }
                    if (error instanceof DeviceNotAvailableException) {
                        throw (DeviceNotAvailableException) error;
                    }
                    if (error instanceof HarnessRuntimeException) {
                        throw (HarnessRuntimeException) error;
                    }
                    throw new RuntimeException(error);
                }
            }
        } else {
            for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
                // Lab preparer then target preparer
                runLabPreparationOnDevice(
                        testInfo,
                        deviceName,
                        index,
                        getLabPreparersToRun(config, deviceName),
                        mTrackLabPreparers.get(deviceName),
                        listener);
                index++;
            }
        }
    }

    private void runPreparersSetup(
            TestInformation testInfo, IConfiguration config, ITestLogger listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        int index = 0;
        if ((config.getCommandOptions().shouldUseParallelSetup()
                        || config.getCommandOptions().shouldUseReplicateSetup())
                && config.getDeviceConfig().size() > 1) {
            CLog.d("Using parallel setup.");
            ParallelDeviceExecutor<Boolean> executor =
                    new ParallelDeviceExecutor<>(testInfo.getContext().getDevices().size());
            List<Callable<Boolean>> callableTasks = new ArrayList<>();
            for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
                final int deviceIndex = index;
                // Replicate TestInfo
                TestInformation replicated =
                        TestInformation.createModuleTestInfo(testInfo, testInfo.getContext());
                Callable<Boolean> callableTask =
                        () -> {
                            runPreparationOnDevice(
                                    replicated,
                                    deviceName,
                                    deviceIndex,
                                    getTargetPreparersToRun(config, deviceName),
                                    mTrackTargetPreparers.get(deviceName),
                                    listener);
                            return true;
                        };
                callableTasks.add(callableTask);
                index++;
            }
            Duration timeout = config.getCommandOptions().getParallelSetupTimeout();
            executor.invokeAll(callableTasks, timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (executor.hasErrors()) {
                List<Throwable> errors = executor.getErrors();
                // TODO: Handle throwing multi-exceptions, right now throw the first one.
                for (Throwable error : errors) {
                    if (error instanceof TargetSetupError) {
                        throw (TargetSetupError) error;
                    }
                    if (error instanceof BuildError) {
                        throw (BuildError) error;
                    }
                    if (error instanceof DeviceNotAvailableException) {
                        throw (DeviceNotAvailableException) error;
                    }
                    throw new RuntimeException(error);
                }
            }
        } else {
            for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
                runPreparationOnDevice(
                        testInfo,
                        deviceName,
                        index,
                        getTargetPreparersToRun(config, deviceName),
                        mTrackTargetPreparers.get(deviceName),
                        listener);
                index++;
            }
        }
    }

    private void runLabPreparationOnDevice(
            TestInformation testInfo,
            String deviceName,
            int index,
            List<ITargetPreparer> labPreparersToRun,
            Set<ITargetPreparer> trackLabPreparers,
            ITestLogger logger)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getContext().getDevice(deviceName);

        // Run lab preparers on the device
        for (ITargetPreparer preparer : labPreparersToRun) {
            if (preparer.isDisabled()) {
                CLog.d("%s has been disabled. skipping.", preparer);
                continue;
            }
            // Track object invoked as lab_preparer that are not ILabPreparer
            if (!(preparer instanceof ILabPreparer)) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.LAB_PREPARER_NOT_ILAB,
                        preparer.getClass().getCanonicalName());
            }

            TfObjectTracker.countWithParents(preparer.getClass());
            if (preparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) preparer).setTestLogger(logger);
            }

            long startTime = System.currentTimeMillis();
            CLog.d(
                    "starting lab preparer '%s' on device: '%s'",
                    preparer, device.getSerialNumber());
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(preparer.getClass().getSimpleName())) {
                testInfo.setActiveDeviceIndex(index);
                preparer.setUp(testInfo);
            } finally {
                testInfo.setActiveDeviceIndex(0);
                long elapsedTime = System.currentTimeMillis() - startTime;

                CLog.d(
                        "done with lab preparer '%s' on device: '%s' in %s",
                        preparer,
                        device.getSerialNumber(),
                        TimeUtil.formatElapsedTime(elapsedTime));

                InvocationMetricLogger.addInvocationMetrics(
                        InvocationGroupMetricKey.LAB_PREPARER_SETUP_LATENCY,
                        preparer.getClass().getName(),
                        elapsedTime);
            }
            // Track which lab preparers were executed separately from the target preparers
            trackLabPreparers.add(preparer);
        }
    }

    private void runPreparationOnDevice(
            TestInformation testInfo,
            String deviceName,
            int index,
            List<ITargetPreparer> targetPreparersToRun,
            Set<ITargetPreparer> trackPreparers,
            ITestLogger logger)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getContext().getDevice(deviceName);
        for (ITargetPreparer preparer : targetPreparersToRun) {
            if (preparer.isDisabled()) {
                CLog.d("%s has been disabled. skipping.", preparer);
                continue;
            }
            // Track object invoked as target_preparer but is ILabPreparer
            if (preparer instanceof ILabPreparer) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.TARGET_PREPARER_IS_ILAB,
                        preparer.getClass().getCanonicalName());
            }

            TfObjectTracker.countWithParents(preparer.getClass());
            if (preparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) preparer).setTestLogger(logger);
            }

            long startTime = System.currentTimeMillis();
            CLog.d("starting preparer '%s' on device: '%s'", preparer, device.getSerialNumber());
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(preparer.getClass().getSimpleName())) {
                testInfo.setActiveDeviceIndex(index);
                preparer.setUp(testInfo);
            } finally {
                testInfo.setActiveDeviceIndex(0);
            }

            trackPreparers.add(preparer);
            long elapsedTime = System.currentTimeMillis() - startTime;

            CLog.d(
                    "done with preparer '%s' on device: '%s' in %s",
                    preparer, device.getSerialNumber(), TimeUtil.formatElapsedTime(elapsedTime));

            InvocationMetricLogger.addInvocationMetrics(
                    InvocationGroupMetricKey.TARGET_PREPARER_SETUP_LATENCY,
                    preparer.getClass().getName(),
                    elapsedTime);
        }

        CLog.d("Done with setup of device: '%s'", device.getSerialNumber());
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePreInvocationSetup(
            IInvocationContext context, IConfiguration config, ITestLogger logger)
            throws DeviceNotAvailableException, TargetSetupError {
        if (config.getCommandOptions().shouldDisableInvocationSetupAndTeardown()) {
            CLog.i("--disable-invocation-setup-and-teardown, skipping pre-invocation setup.");
            return;
        }
        long start = System.currentTimeMillis();
        customizeDevicePreInvocation(config, context);

        // Multi-device test scenario
        Integer multiDeviceCount = config.getCommandOptions().getMultiDeviceCount();
        boolean allVirtualDevices = true;
        for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
            if (!deviceConfig.getDeviceRequirements().gceDeviceRequested()) {
                allVirtualDevices = false;
                break;
            }
        }
        if (multiDeviceCount != null && multiDeviceCount != 1 && allVirtualDevices) {
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope("runMultiVirtualDevicesPreInvocationSetup")) {
                runMultiVirtualDevicesPreInvocationSetup(context, config, logger);
            } catch (TargetSetupError e) {
                // TODO(b/353826394): Refactor when avd_util wrapping is ready.
                if (context.getDevices().get(0).getOptions().useCvdCF()) {
                    // TODO(b/353649277): Flesh out this section when it's ready.
                    // Basically, the rough processes to pull CF host logs are
                    // 1. establish the CURL connection via LHP or SSH.
                    // 2. Compose CURL command and execute it to pull CF logs.
                } else {
                    OxygenUtil util = new OxygenUtil();
                    util.downloadLaunchFailureLogs(e, logger);
                }
                throw e;
            }
        } else {
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope("device_pre_invocation_setup")) {
                List<String> deviceNames = context.getDeviceConfigNames();
                if (config.getCommandOptions().shouldUseParallelPreInvocationSetup()
                        && deviceNames.size() > 1) {
                    CLog.d("Using parallel preInvocationSetup.");
                    List<Callable<Void>> callableTasks = new ArrayList<>();
                    for (String deviceName : deviceNames) {
                        callableTasks.add(
                                () -> {
                                    runSingleDevicePreInvocationSetup(
                                            deviceName, context, config, logger);
                                    return null;
                                });
                    }
                    // The threads are also controlled by
                    // host_options:concurrent-virtual-device-startup-limit.
                    ParallelDeviceExecutor<Void> executor =
                            new ParallelDeviceExecutor<>(callableTasks.size());
                    executor.invokeAll(
                            callableTasks,
                            config.getCommandOptions()
                                    .getParallelPreInvocationSetupTimeout()
                                    .toMillis(),
                            TimeUnit.MILLISECONDS);
                    // TODO: Handle throwing multi-exceptions, right now throw the first one.
                    for (Throwable error : executor.getErrors()) {
                        if (error instanceof DeviceNotAvailableException) {
                            throw (DeviceNotAvailableException) error;
                        }
                        if (error instanceof TargetSetupError) {
                            throw (TargetSetupError) error;
                        }
                        throw new RuntimeException(error);
                    }
                } else {
                    if (config.getCommandOptions().shouldUseParallelPreInvocationSetup()) {
                        CLog.w("Parallel pre-invocation setup is enabled but device count <= 1.");
                    }
                    for (String deviceName : deviceNames) {
                        runSingleDevicePreInvocationSetup(deviceName, context, config, logger);
                    }
                }
            }
        }
        // Also report device pre invocation into setup
        InvocationMetricLogger.addInvocationPairMetrics(
                InvocationMetricKey.SETUP_PAIR, start, System.currentTimeMillis());
    }

    /**
     * Launch multiple virtual devices together, then invoke the {@link
     * ITestDevice#preInvocationSetup(IBuildInfo)} for each device part of the invocation with
     * setting the GceAvdInfo of the device beforehand.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param logger the {@link ITestLogger} to report logs.
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    private void runMultiVirtualDevicesPreInvocationSetup(
            IInvocationContext context, IConfiguration config, ITestLogger logger)
            throws TargetSetupError, DeviceNotAvailableException {
        // One GceManager is needed to lease the whole device group
        String firstDeviceName = context.getDeviceConfigNames().get(0);
        ITestDevice firstDevice = context.getDevice(firstDeviceName);
        mMultiDeviceRequester =
                new GceManager(
                        firstDevice.getDeviceDescriptor(),
                        firstDevice.getOptions(),
                        context.getBuildInfo(firstDeviceName));

        List<ITestDevice> devices = context.getDevices();
        List<IBuildInfo> buildInfos = context.getBuildInfos();
        // Set logger on all devices first
        for (ITestDevice device : devices) {
            if (device instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) device).setTestLogger(logger);
            }
        }

        // Start multiple devices in a group
        List<GceAvdInfo> gceAvdInfoList =
                mMultiDeviceRequester.startMultiDevicesGce(buildInfos, context.getAttributes());
        for (int i = 0; i < devices.size(); i++) {
            // For each device, do setup with its GceAvdInfo
            CLog.d(
                    "Starting device pre invocation launched device setup with GceAvdInfo %s"
                            + " for : '%s'",
                    gceAvdInfoList.get(i), devices.get(i).getSerialNumber());
            // Use the most common basic interface for device connection setup
            NativeDevice device = (NativeDevice) devices.get(i);

            device.setConnectionAvdInfo(gceAvdInfoList.get(i));
            device.preInvocationSetup(buildInfos.get(i), context.getAttributes());

            // Last device in the group is responsible for releasing the whole device group
            if (i != devices.size() - 1) {
                CLog.d(
                        "Set device %s to skip tear down because only the last device in the"
                                + " device group will be responsible for tearing down the whole"
                                + " device group",
                        device.getSerialNumber());
                device.getOptions().setSkipTearDown(true);
            }
        }
    }

    /**
     * Run preInvocationSetup for one device.
     *
     * @param deviceName the name of the device to be set up.
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param logger the {@link ITestLogger} to report logs.
     * @throws DeviceNotAvailableException
     * @throws TargetSetupError
     */
    private void runSingleDevicePreInvocationSetup(
            String deviceName,
            IInvocationContext context,
            IConfiguration config,
            ITestLogger logger)
            throws DeviceNotAvailableException, TargetSetupError {
        ITestDevice device = context.getDevice(deviceName);
        CLog.d("Starting device pre invocation setup for : '%s'", device.getSerialNumber());
        if (device instanceof ITestLoggerReceiver) {
            ((ITestLoggerReceiver) context.getDevice(deviceName)).setTestLogger(logger);
        }
        IDeviceConfiguration deviceConfig = config.getDeviceConfigByName(deviceName);
        if (deviceConfig != null && deviceConfig.isFake()) {
            CLog.d("Skip preInvocationSetup on fake device %s", device);
        } else {
            device.preInvocationSetup(context.getBuildInfo(deviceName), context.getAttributes());
        }
    }

    /**
     * Give a chance to customize some of the device before preInvocationSetup.
     *
     * @param config The config of the invocation.
     * @param context The current invocation context.
     */
    protected void customizeDevicePreInvocation(IConfiguration config, IInvocationContext context) {
        // Empty by default
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePostInvocationTearDown(
            IInvocationContext context, IConfiguration config, Throwable exception) {
        // Extra tear down step for the device
        if (config.getCommandOptions().shouldDisableInvocationSetupAndTeardown()) {
            CLog.i("--disable-invocation-setup-and-teardown, skipping post-invocation teardown.");
            return;
        }
        // Check if device tear down is needed for multi-device tests.
        boolean shouldTearDown = false;
        for (String deviceName : context.getDeviceConfigNames()) {
            ITestDevice device = context.getDevice(deviceName);
            IDeviceConfiguration deviceConfig = config.getDeviceConfigByName(deviceName);
            if (deviceConfig != null && deviceConfig.isFake()) {
                CLog.d("Skip postInvocationTearDown on fake device %s", device);
                continue;
            }
            // For multi-device tests, only the last device is flagged to be tear down if needed.
            shouldTearDown |= !device.getOptions().shouldSkipTearDown();
            device.postInvocationTearDown(exception);
        }
        if (mMultiDeviceRequester != null && shouldTearDown) {
            mMultiDeviceRequester.shutdownGce();
        }
    }

    /** Runs the {@link IMultiTargetPreparer} specified. */
    private void runMultiTargetPreparers(
            List<IMultiTargetPreparer> multiPreparers,
            ITestLogger logger,
            TestInformation testInfo,
            String description)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        if (mTrackMultiPreparers == null) {
            mTrackMultiPreparers = new HashSet<>();
        }
        for (IMultiTargetPreparer multiPreparer : multiPreparers) {
            // do not call the preparer if it was disabled
            if (multiPreparer.isDisabled()) {
                CLog.d("%s has been disabled. skipping.", multiPreparer);
                continue;
            }
            TfObjectTracker.countWithParents(multiPreparer.getClass());
            if (multiPreparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) multiPreparer).setTestLogger(logger);
            }
            long startTime = System.currentTimeMillis();
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(multiPreparer.getClass().getSimpleName())) {
                CLog.d("Starting %s '%s'", description, multiPreparer);
                multiPreparer.setUp(testInfo);
                mTrackMultiPreparers.add(multiPreparer);
                long elapsedTime = System.currentTimeMillis() - startTime;
                CLog.d(
                        "Done with %s '%s' in %s",
                        description, multiPreparer, TimeUtil.formatElapsedTime(elapsedTime));
            }
        }
    }

    /** Runs the {@link IMultiTargetPreparer} specified tearDown. */
    private Throwable runMultiTargetPreparersTearDown(
            List<IMultiTargetPreparer> multiPreparers,
            TestInformation testInfo,
            ITestLogger logger,
            Throwable throwable,
            String description)
            throws Throwable {
        ListIterator<IMultiTargetPreparer> iterator =
                multiPreparers.listIterator(multiPreparers.size());
        Throwable deferredThrowable = null;

        while (iterator.hasPrevious()) {
            IMultiTargetPreparer multipreparer = iterator.previous();
            if (multipreparer.isDisabled() || multipreparer.isTearDownDisabled()) {
                CLog.d("%s has been disabled. skipping.", multipreparer);
                continue;
            }
            if (mTrackMultiPreparers == null || !mTrackMultiPreparers.contains(multipreparer)) {
                CLog.d("%s didn't run setUp, skipping tearDown.", multipreparer);
                continue;
            }
            if (multipreparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) multipreparer).setTestLogger(logger);
            }
            long startTime = System.currentTimeMillis();
            CLog.d("Starting %s '%s'", description, multipreparer);
            try (CloseableTraceScope ignore =
                    new CloseableTraceScope(multipreparer.getClass().getSimpleName())) {
                multipreparer.tearDown(testInfo, throwable);
            } catch (Throwable t) {
                // We catch it and rethrow later to allow each multi_targetprep to be attempted.
                // Only the first one will be thrown but all should be logged.
                CLog.e("Deferring throw for:");
                CLog.e(t);
                if (deferredThrowable == null) {
                    deferredThrowable = t;
                }
            }
            long elapsedTime = System.currentTimeMillis() - startTime;

            CLog.d(
                    "Done with %s '%s' in %s",
                    description, multipreparer, TimeUtil.formatElapsedTime(elapsedTime));
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationGroupMetricKey.MULTI_TARGET_PREPARER_TEARDOWN_LATENCY,
                    multipreparer.getClass().getName(),
                    elapsedTime);
        }

        return deferredThrowable;
    }

    @Override
    public void doTeardown(
            TestInformation testInfo,
            IConfiguration config,
            ITestLogger logger,
            Throwable exception)
            throws Throwable {
        IInvocationContext context = testInfo.getContext();
        Throwable deferredThrowable;
        long start = System.currentTimeMillis();
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.TEARDOWN_START, start);
        try {
            int deviceIndex = 0;
            try {
                List<IMultiTargetPreparer> multiPreparers = config.getMultiTargetPreparers();
                deferredThrowable =
                        runMultiTargetPreparersTearDown(
                                multiPreparers,
                                testInfo,
                                logger,
                                exception,
                                "multi target preparer teardown");

                for (String deviceName : context.getDeviceConfigNames()) {
                    ITestDevice device = context.getDevice(deviceName);
                    device.clearLastConnectedWifiNetwork();

                    List<ITargetPreparer> targetPreparersToRun =
                            getTargetPreparersToRun(config, deviceName);
                    Throwable firstLocalThrowable =
                            runPreparersTearDown(
                                    testInfo,
                                    device,
                                    deviceName,
                                    deviceIndex,
                                    logger,
                                    exception,
                                    targetPreparersToRun,
                                    mTrackTargetPreparers);
                    if (deferredThrowable == null) {
                        deferredThrowable = firstLocalThrowable;
                    }

                    deviceIndex++;
                }

                if (exception == null) {
                    exception = deferredThrowable;
                }
            } finally {
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEST_TEARDOWN_PAIR, start, System.currentTimeMillis());
            }

            start = System.currentTimeMillis();
            try {
                deviceIndex = 0;
                for (String deviceName : context.getDeviceConfigNames()) {
                    ITestDevice device = context.getDevice(deviceName);
                    List<ITargetPreparer> labPreparersToRun =
                            getLabPreparersToRun(config, deviceName);
                    Throwable secondLocalThrowable =
                            runPreparersTearDown(
                                    testInfo,
                                    device,
                                    deviceName,
                                    deviceIndex,
                                    logger,
                                    exception,
                                    labPreparersToRun,
                                    mTrackLabPreparers);
                    if (deferredThrowable == null) {
                        deferredThrowable = secondLocalThrowable;
                    }

                    deviceIndex++;
                }

                if (exception == null) {
                    exception = deferredThrowable;
                }
                // Extra tear down step for the device
                runDevicePostInvocationTearDown(context, config, exception);

                // After all, run the multi_pre_target_preparer tearDown.
                List<IMultiTargetPreparer> multiPrePreparers = config.getMultiPreTargetPreparers();
                Throwable preTargetTearDownException =
                        runMultiTargetPreparersTearDown(
                                multiPrePreparers,
                                testInfo,
                                logger,
                                exception,
                                "multi pre target preparer teardown");
                if (deferredThrowable == null) {
                    deferredThrowable = preTargetTearDownException;
                }
            } finally {
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEARDOWN_PAIR, start, System.currentTimeMillis());
            }
        } finally {
            // Collect adb logs.
            logHostAdb(config, logger);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.TEARDOWN_END, System.currentTimeMillis());
        }

        if (deferredThrowable != null) {
            throw deferredThrowable;
        }
    }

    protected Throwable runPreparersTearDown(
            TestInformation testInfo,
            ITestDevice device,
            String deviceName,
            int deviceIndex,
            ITestLogger logger,
            Throwable exception,
            List<ITargetPreparer> preparersToRun,
            Map<String, Set<ITargetPreparer>> trackPreparersMap) {
        Throwable deferredThrowable = null;
        ListIterator<ITargetPreparer> itr = preparersToRun.listIterator(preparersToRun.size());
        while (itr.hasPrevious()) {
            ITargetPreparer preparer = itr.previous();
            // do not call the cleaner if it was disabled
            if (preparer.isDisabled() || preparer.isTearDownDisabled()) {
                CLog.d("%s has been disabled. skipping.", preparer);
                continue;
            }
            if (trackPreparersMap == null
                    || !trackPreparersMap.containsKey(deviceName)
                    || !trackPreparersMap.get(deviceName).contains(preparer)) {
                CLog.d("%s didn't run setUp, skipping tearDown.", preparer);
                continue;
            }
            // If setup hit a targetSetupError, the setUp() and setTestLogger might not have
            // run, ensure we still have the logger.
            if (preparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) preparer).setTestLogger(logger);
            }
            long startTime = System.currentTimeMillis();
            try (CloseableTraceScope remoteTest =
                    new CloseableTraceScope(preparer.getClass().getSimpleName())) {
                CLog.d(
                        "starting tearDown '%s' on device: '%s'",
                        preparer, device.getSerialNumber());
                testInfo.setActiveDeviceIndex(deviceIndex);
                Throwable tearDownException = exception;
                // If a previous teardown fail, still notify following ones.
                if (exception == null && deferredThrowable != null) {
                    tearDownException = deferredThrowable;
                }
                preparer.tearDown(testInfo, tearDownException);
            } catch (Throwable e) {
                // We catch it and rethrow later to allow each targetprep to be attempted.
                // Only the first one will be thrown but all should be logged.
                CLog.e("Deferring throw for:");
                CLog.e(e);
                if (deferredThrowable == null) {
                    deferredThrowable = e;
                }
            } finally {
                testInfo.setActiveDeviceIndex(0);
                long elapsedTime = System.currentTimeMillis() - startTime;
                CLog.d(
                        "done with tearDown '%s' on device: '%s' in %s",
                        preparer,
                        device.getSerialNumber(),
                        TimeUtil.formatElapsedTime(elapsedTime));
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationGroupMetricKey.TARGET_PREPARER_TEARDOWN_LATENCY,
                        preparer.getClass().getName(),
                        elapsedTime);
            }
        }
        return deferredThrowable;
    }

    @Override
    public void doCleanUp(IInvocationContext context, IConfiguration config, Throwable exception) {
        for (String deviceName : context.getDeviceConfigNames()) {

            List<ITargetPreparer> targetPreparers = getTargetPreparersToRun(config, deviceName);

            ListIterator<ITargetPreparer> itr =
                    targetPreparers.listIterator(targetPreparers.size());
            while (itr.hasPrevious()) {
                ITargetPreparer preparer = itr.previous();
                if (preparer instanceof IHostCleaner) {
                    IHostCleaner cleaner = (IHostCleaner) preparer;
                    if (preparer.isDisabled() || preparer.isTearDownDisabled()) {
                        CLog.d("%s has been disabled. skipping.", cleaner);
                        continue;
                    }
                    cleaner.cleanUp(context.getBuildInfo(deviceName), exception);
                }
            }

            List<ITargetPreparer> labPreparers = getLabPreparersToRun(config, deviceName);

            // Yes this ends up very redundant to the above stanza, but 8 lines isn't really worth
            // extracting to a helper method.
            itr = labPreparers.listIterator(labPreparers.size());
            while (itr.hasPrevious()) {
                ITargetPreparer preparer = itr.previous();
                if (preparer instanceof IHostCleaner) {
                    IHostCleaner cleaner = (IHostCleaner) preparer;
                    if (preparer.isDisabled() || preparer.isTearDownDisabled()) {
                        CLog.d("%s has been disabled. skipping.", cleaner);
                        continue;
                    }
                    cleaner.cleanUp(context.getBuildInfo(deviceName), exception);
                }
            }
        }
    }

    @Override
    public void runTests(
            TestInformation info, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        Timer testPhaseTimer = new Timer(true);
        long remainingTestPhaseTime =
                GlobalConfiguration.getInstance().getHostOptions().getTestPhaseTimeout();
        boolean testPhaseTimeoutNeeded = remainingTestPhaseTime > 0;
        // Make sure Test Phase timeout is less than or equal to invocation timeout
        long invocationTimeout = config.getCommandOptions().getInvocationTimeout();
        if (testPhaseTimeoutNeeded && invocationTimeout > 0) {
            remainingTestPhaseTime = Math.min(remainingTestPhaseTime, invocationTimeout);
        }

        List<IRemoteTest> remainingTests = new ArrayList<>(config.getTests());
        UnexecutedTestReporterThread reporterThread =
                new UnexecutedTestReporterThread(listener, remainingTests);
        Runtime.getRuntime().addShutdownHook(reporterThread);
        TestInvocation.printStageDelimiter(Stage.TEST, false);
        long start = System.currentTimeMillis();
        try (CloseableTraceScope ignored =
                new CloseableTraceScope(InvocationMetricKey.test_execution.name())) {
            GetPreviousPassedHelper previousPassHelper = new GetPreviousPassedHelper();
            // Add new exclude filters to global filters
            Set<String> previousPassedFilters = previousPassHelper.getPreviousPassedFilters(config);
            // TODO: Ensure global filters are cloned for local sharding
            config.getGlobalFilters().addPreviousPassedTests(previousPassedFilters);
            for (IRemoteTest test : config.getTests()) {
                try (CloseableTraceScope remoteTest =
                        new CloseableTraceScope(test.getClass().getSimpleName())) {
                    TfObjectTracker.countWithParents(test.getClass());
                    // For compatibility of those receivers, they are assumed to be single device
                    // alloc.
                    if (test instanceof IDeviceTest) {
                        ((IDeviceTest) test).setDevice(info.getDevice());
                    }
                    if (test instanceof IBuildReceiver) {
                        ((IBuildReceiver) test).setBuild(info.getBuildInfo());
                    }
                    if (test instanceof ISystemStatusCheckerReceiver) {
                        ((ISystemStatusCheckerReceiver) test)
                                .setSystemStatusChecker(config.getSystemStatusCheckers());
                    }
                    if (test instanceof IInvocationContextReceiver) {
                        ((IInvocationContextReceiver) test).setInvocationContext(info.getContext());
                    }

                    updateAutoCollectors(config);

                    IRetryDecision decision = config.getRetryDecision();
                    // Apply the filters
                    if (test instanceof ITestFilterReceiver) {
                        config.getGlobalFilters().applyFiltersToTest((ITestFilterReceiver) test);
                    } else if (test instanceof BaseTestSuite) {
                        config.getGlobalFilters().applyFiltersToTest((BaseTestSuite) test);
                    }
                    // Handle the no-retry use case
                    if (!decision.isAutoRetryEnabled()
                            || RetryStrategy.NO_RETRY.equals(decision.getRetryStrategy())
                            || test instanceof ITestSuite
                            // Exclude special launcher
                            || test.getClass().getSimpleName().equals("CtsTestLauncher")
                            // TODO: Handle auto-retry in local-sharding for non-suite
                            || test instanceof TestsPoolPoller
                            // If test doesn't support auto-retry
                            || (!(test instanceof ITestFilterReceiver)
                                    && !(test instanceof IAutoRetriableTest)
                                    && !RetryStrategy.ITERATIONS.equals(
                                            decision.getRetryStrategy()))) {
                        try {
                            long timeSpentOnTest =
                                    runTest(
                                            config,
                                            info,
                                            listener,
                                            test,
                                            testPhaseTimer,
                                            remainingTestPhaseTime,
                                            testPhaseTimeoutNeeded);
                            remainingTestPhaseTime -= timeSpentOnTest;
                        } finally {
                            CurrentInvocation.setRunIsolation(IsolationGrade.NOT_ISOLATED);
                            CurrentInvocation.setModuleIsolation(IsolationGrade.NOT_ISOLATED);
                            // Clean the suite internals once done
                            if (test instanceof BaseTestSuite) {
                                ((BaseTestSuite) test).cleanUpSuiteSetup();
                            }
                        }
                        remainingTests.remove(test);
                        continue;
                    }
                    CLog.d("Using RetryLogSaverResultForwarder to forward results.");
                    ModuleListener mainGranularRunListener =
                            new ModuleListener(null, info.getContext());
                    RetryLogSaverResultForwarder runListener =
                            initializeListeners(config, listener, mainGranularRunListener);
                    mainGranularRunListener.setAttemptIsolation(
                            CurrentInvocation.runCurrentIsolation());
                    try {
                        long timeSpentOnTest =
                                runTest(
                                        config,
                                        info,
                                        runListener,
                                        test,
                                        testPhaseTimer,
                                        remainingTestPhaseTime,
                                        testPhaseTimeoutNeeded);
                        remainingTestPhaseTime -= timeSpentOnTest;
                    } finally {
                        CurrentInvocation.setRunIsolation(IsolationGrade.NOT_ISOLATED);
                        CurrentInvocation.setModuleIsolation(IsolationGrade.NOT_ISOLATED);
                    }
                    remainingTests.remove(test);
                    runListener.incrementAttempt();

                    // Avoid entering the loop if no retry to be done.
                    if (!decision.shouldRetry(
                            test, 0, mainGranularRunListener.getTestRunForAttempts(0))) {
                        continue;
                    }
                    // Avoid rechecking the shouldRetry below the first time as it could retrigger
                    // reboot.
                    boolean firstCheck = true;
                    long startTime = System.currentTimeMillis();
                    try {
                        PrettyPrintDelimiter.printStageDelimiter("Starting auto-retry");
                        for (int attemptNumber = 1;
                                attemptNumber < decision.getMaxRetryCount();
                                attemptNumber++) {
                            if (!firstCheck) {
                                boolean retry =
                                        decision.shouldRetry(
                                                test,
                                                attemptNumber - 1,
                                                mainGranularRunListener.getTestRunForAttempts(
                                                        attemptNumber - 1));
                                if (!retry) {
                                    continue;
                                }
                            }
                            firstCheck = false;
                            CLog.d("auto-retry attempt number '%s'", attemptNumber);
                            mainGranularRunListener.setAttemptIsolation(
                                    CurrentInvocation.runCurrentIsolation());
                            try {
                                // Run the tests again
                                long timeSpent =
                                        runTest(
                                                config,
                                                info,
                                                runListener,
                                                test,
                                                testPhaseTimer,
                                                remainingTestPhaseTime,
                                                testPhaseTimeoutNeeded);
                                remainingTestPhaseTime -= timeSpent;
                            } finally {
                                CurrentInvocation.setRunIsolation(IsolationGrade.NOT_ISOLATED);
                                CurrentInvocation.setModuleIsolation(IsolationGrade.NOT_ISOLATED);
                            }
                            runListener.incrementAttempt();
                        }
                        // Feed the last attempt if we reached here.
                        decision.addLastAttempt(
                                mainGranularRunListener.getTestRunForAttempts(
                                        decision.getMaxRetryCount() - 1));
                    } finally {
                        RetryStatistics retryStats = decision.getRetryStatistics();
                        // Track how long we spend in retry
                        retryStats.mRetryTime = System.currentTimeMillis() - startTime;
                        addRetryTime(retryStats.mRetryTime);
                    }
                }
            }
        } finally {
            testPhaseTimer.cancel();
            TestInvocation.printStageDelimiter(Stage.TEST, true);
            // TODO: Look if this can be improved to DeviceNotAvailableException too.
            try {
                Runtime.getRuntime().removeShutdownHook(reporterThread);
            } catch (IllegalStateException e) {
                // Ignore as it would throw only if JVM shutdown is in progress.
            }
            // Only log if it was no already logged to keep the value closest to execution
            if (!InvocationMetricLogger.getInvocationMetrics()
                    .containsKey(InvocationMetricKey.TEST_PAIR.toString())) {
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.TEST_PAIR, start, System.currentTimeMillis());
            }
        }
    }

    @Override
    public void reportLogs(ITestDevice device, ITestLogger listener, Stage stage) {
        if (device == null) {
            return;
        }
        IDevice idevice = device.getIDevice();
        try (InputStreamSource logcatSource = device.getLogcat()) {
            device.clearLogcat();
            if (logcatSource != null && logcatSource.size() > 0L) {
                String name =
                        String.format(
                                "%s_%s",
                                TestInvocation.getDeviceLogName(stage), device.getSerialNumber());
                listener.testLog(name, LogDataType.LOGCAT, logcatSource);
            }
        }
        // Emulator logs
        if (idevice != null && idevice.isEmulator()) {
            try (InputStreamSource emulatorOutput = device.getEmulatorOutput()) {
                // TODO: Clear the emulator log
                String name = TestInvocation.getEmulatorLogName(stage);
                listener.testLog(name, LogDataType.TEXT, emulatorOutput);
            }
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

    /** Handle setting the test tag on the build info. */
    protected void setTestTag(IBuildInfo info, IConfiguration config) {
        // When CommandOption is set, it overrides any test-tag from build_providers
        if (!"stub".equals(config.getCommandOptions().getTestTag())) {
            info.setTestTag(getTestTag(config));
        } else if (Strings.isNullOrEmpty(info.getTestTag())) {
            // We ensure that that a default test-tag is always available.
            info.setTestTag("stub");
        }
    }

    /**
     * Update the {@link IBuildInfo} with additional info from the {@link IConfiguration}.
     *
     * @param info the {@link IBuildInfo}
     * @param config the {@link IConfiguration}
     */
    void updateBuild(IBuildInfo info, IConfiguration config) {
        setTestTag(info, config);
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            // Avoid relogging the properties in a subprocess
            return;
        }
        if (config.getCommandLine() != null) {
            // TODO: obfuscate the password if any.
            info.addBuildAttribute(TestInvocation.COMMAND_ARGS_KEY, config.getCommandLine());
        }
        if (config.getCommandOptions().getShardCount() != null) {
            info.addBuildAttribute(
                    "shard_count", config.getCommandOptions().getShardCount().toString());
        }
        if (config.getCommandOptions().getShardIndex() != null) {
            info.addBuildAttribute(
                    "shard_index", config.getCommandOptions().getShardIndex().toString());
        }
    }

    /**
     * Runs a test and returns the time taken to finish the test.
     *
     * <p>Tests will be run on a separate thread with a timer when test phase level timeout is
     * needed.
     */
    private long runTest(
            IConfiguration config,
            TestInformation info,
            ITestInvocationListener listener,
            IRemoteTest test,
            Timer timer,
            long testPhaseTimeout,
            boolean testPhaseTimeoutNeeded)
            throws DeviceNotAvailableException, Throwable {
        // We clone the collectors for each IRemoteTest to ensure no state conflicts.
        List<IMetricCollector> clonedCollectors = new ArrayList<>();
        // Add automated collectors
        for (AutoLogCollector auto : config.getCommandOptions().getAutoLogCollectors()) {
            clonedCollectors.add(auto.getInstanceForValue());
        }
        // Add the collector from the configuration
        clonedCollectors.addAll(CollectorHelper.cloneCollectors(config.getMetricCollectors()));
        if (test instanceof IMetricCollectorReceiver) {
            ((IMetricCollectorReceiver) test).setMetricCollectors(clonedCollectors);
            // If test can receive collectors then let it handle the how to set them up
            if (testPhaseTimeoutNeeded) {
                return runTestThread(info, listener, test, timer, testPhaseTimeout);
            } else {
                long startTime = System.currentTimeMillis();
                test.run(info, listener);
                return System.currentTimeMillis() - startTime;
            }
        } else {
            // Wrap collectors in each other and collection will be sequential, do this in the
            // loop to ensure they are always initialized against the right context.
            ITestInvocationListener listenerWithCollectors = listener;
            if (config.getCommandOptions().reportTestCaseCount()) {
                CountTestCasesCollector counter = new CountTestCasesCollector(test);
                clonedCollectors.add(counter);
            }
            for (IMetricCollector collector : clonedCollectors) {
                if (collector.isDisabled()) {
                    CLog.d("%s has been disabled. Skipping.", collector);
                } else {
                    if (collector instanceof IConfigurationReceiver) {
                        ((IConfigurationReceiver) collector).setConfiguration(config);
                    }
                    listenerWithCollectors =
                            collector.init(info.getContext(), listenerWithCollectors);
                    TfObjectTracker.countWithParents(collector.getClass());
                }
            }
            if (testPhaseTimeoutNeeded) {
                return runTestThread(info, listenerWithCollectors, test, timer, testPhaseTimeout);
            } else {
                long startTime = System.currentTimeMillis();
                test.run(info, listenerWithCollectors);
                return System.currentTimeMillis() - startTime;
            }
        }
    }

    /** Runs a test in a separate thread and returns the time spent on running the test. */
    private long runTestThread(
            TestInformation info,
            ITestInvocationListener listener,
            IRemoteTest test,
            Timer timer,
            long testPhaseTimeout)
            throws Throwable {
        if (testPhaseTimeout <= 0) {
            // throw run interrupted exception so that it can be handled the same way as TestThreads
            // when timeout is reached.
            throw new RunInterruptedException(
                    "Test Phase Timeout Reached.", TestErrorIdentifier.TEST_PHASE_TIMED_OUT);
        }
        TestThread testThread = new TestThread(info, listener, test);
        TestPhaseMonitor testPhaseMonitor = new TestPhaseMonitor(testThread);
        timer.schedule(testPhaseMonitor, testPhaseTimeout);
        long startTime = System.currentTimeMillis();
        testThread.start();
        try {
            testThread.join();
        } catch (InterruptedException e) {
            CLog.e(e);
        } finally {
            testPhaseMonitor.cancel();
            long timeSpent = System.currentTimeMillis() - startTime;
            if (testThread.getLastThrownException() != null) {
                throw testThread.getLastThrownException();
            }
            return timeSpent;
        }
    }

    private RetryLogSaverResultForwarder initializeListeners(
            IConfiguration config,
            ITestInvocationListener mainListener,
            ITestInvocationListener mainGranularLevelListener) {
        List<ITestInvocationListener> currentTestListeners = new ArrayList<>();
        currentTestListeners.add(mainGranularLevelListener);
        currentTestListeners.add(mainListener);
        return new RetryLogSaverResultForwarder(
                config.getLogSaver(), currentTestListeners, config) {
            @Override
            public void testLog(
                    String dataName, LogDataType dataType, InputStreamSource dataStream) {
                // We know for sure that the sub-listeners are LogSaverResultForwarder
                // so we delegate to them to save and generate the logAssociation.
                testLogForward(dataName, dataType, dataStream);
            }
        };
    }

    private void addRetryTime(long retryTimeMs) {
        // InvocationMetricLogger automatically adds the auto retry time.
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.AUTO_RETRY_TIME, retryTimeMs);
    }

    protected void linkExternalDirs(IBuildInfo info, TestInformation testInfo) {
        if (info.getProperties().contains(BuildInfoProperties.DO_NOT_LINK_TESTS_DIR)) {
            CLog.d("Skip linking external directory as FileProperty was set.");
            return;
        }
        // Load environment tests dir.
        if (info instanceof IDeviceBuildInfo) {
            // TODO: Use tests directory from TestInformation instead.
            File testsDir = ((IDeviceBuildInfo) info).getTestsDir();
            if (testsDir != null && testsDir.exists()) {
                if (testInfo.executionFiles().get(FilesKey.TARGET_TESTS_DIRECTORY) == null) {
                    File targetTestCases =
                            handleLinkingExternalDirs(
                                    (IDeviceBuildInfo) info,
                                    testsDir,
                                    EnvVariable.ANDROID_TARGET_OUT_TESTCASES,
                                    BuildInfoFileKey.TARGET_LINKED_DIR.getFileKey());
                    if (targetTestCases != null) {
                        testInfo.executionFiles()
                                .put(FilesKey.TARGET_TESTS_DIRECTORY, targetTestCases, true);
                    }
                }
                if (testInfo.executionFiles().get(FilesKey.HOST_TESTS_DIRECTORY) == null) {
                    File hostTestCases =
                            handleLinkingExternalDirs(
                                    (IDeviceBuildInfo) info,
                                    testsDir,
                                    EnvVariable.ANDROID_HOST_OUT_TESTCASES,
                                    BuildInfoFileKey.HOST_LINKED_DIR.getFileKey());
                    if (hostTestCases != null) {
                        testInfo.executionFiles()
                                .put(FilesKey.HOST_TESTS_DIRECTORY, hostTestCases, true);
                    }
                }
            }
        }
    }

    private File handleLinkingExternalDirs(
            IDeviceBuildInfo info, File testsDir, EnvVariable var, String baseName) {
        File externalDir = getExternalTestCasesDirs(var);
        if (externalDir == null) {
            String path = SystemUtil.ENV_VARIABLE_PATHS_IN_TESTS_DIR.get(var);
            File varDir = FileUtil.getFileForPath(testsDir, path);
            if (varDir.exists()) {
                // If we found a dir already in the tests dir we keep track of it
                info.setFile(
                        baseName,
                        varDir,
                        /** version */
                        "v1");
                return varDir;
            }
            return null;
        }
        try {
            // Avoid conflict by creating a randomized name for the arriving symlink file.
            File subDir = FileUtil.createTempDir(baseName, testsDir);
            subDir.delete();
            FileUtil.symlinkFile(externalDir, subDir);
            // Tag the dir in the build info to be possibly cleaned.
            info.setFile(
                    baseName,
                    subDir,
                    /** version */
                    "v1");
            // Ensure we always delete the linking, no matter how the JVM exits.
            subDir.deleteOnExit();
            return subDir;
        } catch (IOException e) {
            CLog.e("Failed to load external test dir %s. Ignoring it.", externalDir);
            CLog.e(e);
        }
        return null;
    }

    private void setBinariesVersion(IInvocationContext context) {
        String version = getAdbVersion();
        if (version != null) {
            context.addInvocationAttribute(ADB_VERSION_KEY, version);
        }
        String javaVersion = System.getProperty("java.version");
        if (javaVersion != null && !javaVersion.isEmpty()) {
            context.addInvocationAttribute(JAVA_VERSION_KEY, javaVersion);
        }
        String javaClasspath = System.getProperty("java.class.path");
        if (javaClasspath != null && !javaClasspath.isEmpty()) {
            context.addInvocationAttribute(JAVA_CLASSPATH_KEY, javaClasspath);
        }
    }

    private void copyRemoteFiles(ICommandOptions options, IBuildInfo info) {
        for (String remoteFile : options.getRemoteFiles()) {
            info.setFile(
                    IBuildInfo.REMOTE_FILE_PREFIX,
                    new File(remoteFile),
                    IBuildInfo.REMOTE_FILE_VERSION);
        }
    }

    /** Convert the legacy *-on-failure options to the new auto-collect. */
    private void updateAutoCollectors(IConfiguration config) {
        if (config.getCommandOptions().captureScreenshotOnFailure()) {
            config.getCommandOptions()
                    .getAutoLogCollectors()
                    .add(AutoLogCollector.SCREENSHOT_ON_FAILURE);
        }
        if (config.getCommandOptions().captureLogcatOnFailure()) {
            config.getCommandOptions()
                    .getAutoLogCollectors()
                    .add(AutoLogCollector.LOGCAT_ON_FAILURE);
        }
    }

    /** Collect the logs from $TMPDIR/adb.$UID.log. */
    @VisibleForTesting
    protected void logHostAdb(IConfiguration config, ITestLogger logger) {
        if (SystemUtil.isLocalMode()) {
            // Skip logging host adb locally
            return;
        }
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            // Avoid relogging the adb log in a subprocess
            return;
        }
        String tmpDir = "/tmp";
        if (System.getenv("TMPDIR") != null) {
            tmpDir = System.getenv("TMPDIR");
        }
        CommandResult uidRes =
                RunUtil.getDefault()
                        .runTimedCmd(60000, "id", "-u", System.getProperty("user.name"));
        if (!CommandStatus.SUCCESS.equals(uidRes.getStatus())) {
            CLog.e("Failed to collect UID for adb logs: %s", uidRes.getStderr());
            return;
        }
        String uid = uidRes.getStdout().trim();
        File adbLog = new File(tmpDir, String.format("adb.%s.log", uid));
        if (!adbLog.exists()) {
            CLog.i("Did not find adb log file: %s, upload skipped.", adbLog);
            return;
        }
        CommandResult truncAdb =
                RunUtil.getDefault()
                        .runTimedCmd(60000, "tail", "-c", "10MB", adbLog.getAbsolutePath());
        if (!CommandStatus.SUCCESS.equals(truncAdb.getStatus())) {
            CLog.e("Failed to truncate the adb log: %s\n%s", adbLog, truncAdb.getStderr());
            return;
        }
        try (InputStreamSource source =
                new ByteArrayInputStreamSource(truncAdb.getStdout().getBytes())) {
            logger.testLog("host_adb_log", LogDataType.ADB_HOST_LOG, source);
        }
    }

    /** Returns the external directory coming from the environment. */
    @VisibleForTesting
    File getExternalTestCasesDirs(EnvVariable envVar) {
        return SystemUtil.getExternalTestCasesDir(envVar);
    }

    /** Returns the adb version in use for the invocation. */
    protected String getAdbVersion() {
        return GlobalConfiguration.getDeviceManagerInstance().getAdbVersion();
    }

    /** Collect automatically some information on the primary device under test. */
    protected void collectAutoInfo(IConfiguration config, TestInformation info)
            throws DeviceNotAvailableException {
        if (SystemUtil.isLocalMode()) {
            // Avoid collecting for local modes since data collected in this method is used
            // in CI only.
            return;
        }
        if (config.getCommandOptions().getInvocationData().containsKey("subprocess")) {
            // Avoid logging in the subprocess
            return;
        }
        ITestDevice device = info.getDevice();
        if (device.getIDevice() instanceof StubDevice) {
            return;
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("collect_device_info")) {
            CommandResult kernelInfoResult = device.executeShellV2Command("uname -a");
            if (kernelInfoResult != null
                    && CommandStatus.SUCCESS.equals(kernelInfoResult.getStatus())) {
                CLog.i(
                        "Device %s kernel information: '%s'",
                        device.getSerialNumber(), kernelInfoResult.getStdout().trim());
                info.getBuildInfo()
                        .addBuildAttribute(
                                "device_kernel_info", kernelInfoResult.getStdout().trim());
            }
            String system_img_info = device.getProperty("ro.system.build.fingerprint");
            if (system_img_info != null) {
                CLog.i(
                        "Device %s system image build information: '%s'",
                        device.getSerialNumber(), system_img_info);
                info.getBuildInfo().addBuildAttribute("system_img_info", system_img_info);
            }
            String vendor_img_info = device.getProperty("ro.vendor.build.fingerprint");
            if (vendor_img_info != null) {
                CLog.i(
                        "Device %s vendor image build information: '%s'",
                        device.getSerialNumber(), vendor_img_info);
                info.getBuildInfo().addBuildAttribute("vendor_img_info", vendor_img_info);
            }
        }
    }
}
