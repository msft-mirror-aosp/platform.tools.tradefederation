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

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.ExistingBuildProvider;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildProvider;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.device.TestDeviceState;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.ILogRegistry;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.IShardableListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogSaverResultForwarder;
import com.android.tradefed.result.ResultForwarder;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.DeviceFailedToBootError;
import com.android.tradefed.targetprep.IHostCleaner;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.targetprep.multi.IMultiTargetPreparer;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IMultiDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IResumableTest;
import com.android.tradefed.testtype.IRetriableTest;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.IStrictShardableTest;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;

import junit.framework.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

    static final String TRADEFED_LOG_NAME = "host_log";
    static final String DEVICE_LOG_NAME = "device_logcat";
    static final String EMULATOR_LOG_NAME = "emulator_log";
    static final String BUILD_ERROR_BUGREPORT_NAME = "build_error_bugreport";
    static final String DEVICE_UNRESPONSIVE_BUGREPORT_NAME = "device_unresponsive_bugreport";
    static final String INVOCATION_ENDED_BUGREPORT_NAME = "invocation_ended_bugreport";
    static final String TARGET_SETUP_ERROR_BUGREPORT_NAME = "target_setup_error_bugreport";
    static final String BATT_TAG = "[battery level]";

    private String mStatus = "(not invoked)";

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

        @Deprecated
        @Override
        public void invocationStarted(IBuildInfo buildInfo) {
            // ignore
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
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    public void invoke(ITestDevice device, IConfiguration config, IRescheduler rescheduler,
            ITestInvocationListener... extraListeners)
            throws DeviceNotAvailableException, Throwable {
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, device);
        invoke(context, config, rescheduler, extraListeners);
    }

    /**
     * Attempt to shard the configuration into sub-configurations, to be re-scheduled to run on
     * multiple resources in parallel.
     * <p/>
     * If a shard count is greater than 1, it will simply create configs for each shard by setting
     * shard indices and reschedule them.
     * If a shard count is not set,it would fallback to {@link #legacyShardConfig}.
     *
     * @param config the current {@link IConfiguration}.
     * @param context the {@link IInvocationContext} holding the info of the tests.
     * @param rescheduler the {@link IRescheduler}
     * @return true if test was sharded. Otherwise return <code>false</code>
     */
    private boolean shardConfig(IConfiguration config, IInvocationContext context,
            IRescheduler rescheduler) {
        if (config.getCommandOptions().getShardIndex() != null) {
            // The config is already for a single shard.
            return false;
        }

        mStatus = "sharding";
        if (config.getCommandOptions().getShardCount() == null) {
            return legacyShardConfig(config, context, rescheduler);
        }
        // Schedules shard configs.
        int shardCount = config.getCommandOptions().getShardCount();
        for (int i = 0; i < config.getCommandOptions().getShardCount(); i++) {
            IConfiguration shardConfig = null;
            // Create a deep copy of the configuration.
            try {
                shardConfig = getConfigFactory().createConfigurationFromArgs(
                        QuotationAwareTokenizer.tokenizeLine(config.getCommandLine()));
            } catch (ConfigurationException e) {
                // This must not happen.
                throw new RuntimeException("failed to deep copy a configuration", e);
            }

            cloneBuildInfos(config, shardConfig, context);

            shardConfig.getCommandOptions().setShardCount(shardCount);
            shardConfig.getCommandOptions().setShardIndex(i);
            rescheduler.scheduleConfig(shardConfig);
        }
        return true;
    }

    /**
     * Helper to set the Sharded configuration build provider to the {@link ExistingBuildProvider}.
     *
     * @param fromConfig Original configuration
     * @param toConfig cloned configuration recreated from the command line.
     * @param context invocation context
     */
    private void cloneBuildInfos(IConfiguration fromConfig,
            IConfiguration toConfig, IInvocationContext context) {
        for (String deviceName : context.getDeviceConfigNames()) {
            IBuildInfo toBuild =
                    context.getBuildInfo(deviceName).clone();
            try {
                toConfig.getDeviceConfigByName(deviceName)
                        .addSpecificConfig(new ExistingBuildProvider(toBuild,
                        fromConfig.getDeviceConfigByName(deviceName).getBuildProvider()));
            } catch (ConfigurationException e) {
                // Should never happen, no action taken
                CLog.e(e);
            }
        }
    }

    /**
     * Factory method for getting a reference to the {@link IConfigurationFactory}
     *
     * @return the {@link IConfigurationFactory} to use
     */
    protected IConfigurationFactory getConfigFactory() {
        return ConfigurationFactory.getInstance();
    }

    /**
     * Attempt to shard the configuration into sub-configurations, to be re-scheduled to run on
     * multiple resources in parallel.
     * <p/>
     * A successful shard action renders the current config empty, and invocation should not proceed.
     *
     * @see IShardableTest
     * @see IRescheduler
     *
     * @param config the current {@link IConfiguration}.
     * @param context the {@link IInvocationContext} holding the tests information.
     * @param rescheduler the {@link IRescheduler}
     * @return true if test was sharded. Otherwise return <code>false</code>
     */
    private boolean legacyShardConfig(IConfiguration config, IInvocationContext context,
            IRescheduler rescheduler) {
        List<IRemoteTest> shardableTests = new ArrayList<IRemoteTest>();
        boolean isSharded = false;
        for (IRemoteTest test : config.getTests()) {
            isSharded |= shardTest(shardableTests, test);
        }
        if (isSharded) {
            // shard this invocation!

            // create the TestInvocationListener that will collect results from all the shards,
            // and forward them to the original set of listeners (minus any ISharddableListeners)
            // once all shards complete
            ShardMasterResultForwarder resultCollector = new ShardMasterResultForwarder(
                    config.getLogSaver(), buildMasterShardListeners(config), shardableTests.size());

            resultCollector.invocationStarted(context);
            for (IRemoteTest testShard : shardableTests) {
                CLog.i("Rescheduling sharded config...");
                IConfiguration shardConfig = config.clone();
                shardConfig.setTest(testShard);

                cloneBuildInfos(config, shardConfig, context);

                shardConfig.setTestInvocationListeners(
                        buildShardListeners(resultCollector, config.getTestInvocationListeners()));
                shardConfig.setLogOutput(config.getLogOutput().clone());
                shardConfig.setCommandOptions(config.getCommandOptions().clone());
                // use the same {@link ITargetPreparer}, {@link IDeviceRecovery} etc as original
                // config
                rescheduler.scheduleConfig(shardConfig);
            }
            // clean up original builds
            for (String deviceName : context.getDeviceConfigNames()) {
                config.getDeviceConfigByName(deviceName).getBuildProvider()
                        .cleanUp(context.getBuildInfo(deviceName));
            }
            return true;
        }
        return false;
    }

    /**
     * Builds the {@link ITestInvocationListener} listeners that will collect the results from
     * all shards. Currently excludes {@link IShardableListener}s.
     */
    private List<ITestInvocationListener> buildMasterShardListeners(IConfiguration config) {
        List<ITestInvocationListener> newListeners = new ArrayList<ITestInvocationListener>();
        for (ITestInvocationListener l : config.getTestInvocationListeners()) {
            if (!(l instanceof IShardableListener)) {
                newListeners.add(l);
            }
        }
        return newListeners;
    }

    /**
     * Builds the list of {@link ITestInvocationListener}s for each shard.
     * Currently includes any {@link IShardableListener}, plus a single listener that will forward
     * results to the master shard collector.
     */
    private List<ITestInvocationListener> buildShardListeners(
            ITestInvocationListener resultCollector, List<ITestInvocationListener> origListeners) {
        List<ITestInvocationListener> shardListeners = new ArrayList<ITestInvocationListener>();
        for (ITestInvocationListener l : origListeners) {
            if (l instanceof IShardableListener) {
                shardListeners.add(((IShardableListener)l).clone());
            }
        }
        ShardListener origConfigListener = new ShardListener(resultCollector);
        shardListeners.add(origConfigListener);
        return shardListeners;
    }

    /**
     * Attempt to shard given {@link IRemoteTest}.
     *
     * @param shardableTests the list of {@link IRemoteTest}s to add to
     * @param test the {@link Test} to shard
     * @return <code>true</code> if test was sharded
     */
    private boolean shardTest(List<IRemoteTest> shardableTests, IRemoteTest test) {
        boolean isSharded = false;
        if (test instanceof IShardableTest) {
            IShardableTest shardableTest = (IShardableTest)test;
            Collection<IRemoteTest> shards = shardableTest.split();
            if (shards != null) {
                shardableTests.addAll(shards);
                isSharded = true;
            }
        }
        if (!isSharded) {
            shardableTests.add(test);
        }
        return isSharded;
    }

    /**
     * Update the {@link IBuildInfo} with additional info from the {@link IConfiguration}.
     *
     * @param info the {@link IBuildInfo}
     * @param config the {@link IConfiguration}
     */
    private void updateBuild(IBuildInfo info, IConfiguration config) {
        if (config.getCommandLine() != null) {
            // TODO: obfuscate the password if any.
            info.addBuildAttribute("command_line_args", config.getCommandLine());
        }
        if (config.getCommandOptions().getShardCount() != null) {
            info.addBuildAttribute("shard_count",
                    config.getCommandOptions().getShardCount().toString());
        }
        if (config.getCommandOptions().getShardIndex() != null) {
            info.addBuildAttribute("shard_index",
                    config.getCommandOptions().getShardIndex().toString());
        }
        // TODO: update all the configs to only use test-tag from CommandOption and not build
        // providers.
        // When CommandOption is set, it overrides any test-tag from build_providers
        if (!"stub".equals(config.getCommandOptions().getTestTag())) {
            String testTag = config.getCommandOptions().getTestTag();
            if (config.getCommandOptions().getTestTagSuffix() != null) {
                testTag = String.format("%s-%s", testTag,
                        config.getCommandOptions().getTestTagSuffix());
            }
            info.setTestTag(testTag);
        } else if (info.getTestTag() == null || info.getTestTag().isEmpty()) {
            // We ensure that that a default test-tag is always available.
            info.setTestTag("stub");
        } else {
            CLog.w("Using the test-tag from the build_provider. Consider updating your config to"
                    + " have no alias/namespace in front of test-tag.");
        }
    }

    /**
     * Update the {@link IInvocationContext} with additional info from the {@link IConfiguration}.
     *
     * @param context the {@link IInvocationContext}
     * @param config the {@link IConfiguration}
     */
    private void updateInvocationContext(IInvocationContext context, IConfiguration config) {
        // TODO: Once reporting on context is done, only set context attributes
        if (config.getCommandLine() != null) {
            // TODO: obfuscate the password if any.
            context.addInvocationAttribute("command_line_args", config.getCommandLine());
        }
        if (config.getCommandOptions().getShardCount() != null) {
            context.addInvocationAttribute("shard_count",
                    config.getCommandOptions().getShardCount().toString());
        }
        if (config.getCommandOptions().getShardIndex() != null) {
            context.addInvocationAttribute("shard_index",
                    config.getCommandOptions().getShardIndex().toString());
        }
        String testTag = config.getCommandOptions().getTestTag();
        if (config.getCommandOptions().getTestTagSuffix() != null) {
            testTag = String.format("%s-%s", testTag,
                    config.getCommandOptions().getTestTagSuffix());
        }
        context.setTestTag(testTag);
    }

    /**
     * Updates the {@link IConfiguration} to run a single shard if a shard index is set.
     *
     * @see IStrictShardableTest
     *
     * @param config the {@link IConfiguration}.
     */
    private void updateConfigIfSharded(IConfiguration config) {
        if (config.getCommandOptions().getShardIndex() == null) {
            return;
        }

        int shardCount = config.getCommandOptions().getShardCount();
        int shardIndex = config.getCommandOptions().getShardIndex();
        List<IRemoteTest> testShards = new ArrayList<IRemoteTest>();
        for (IRemoteTest test : config.getTests()) {
            if (!(test instanceof IStrictShardableTest)) {
                CLog.w("%s is not shardable; the whole test will run in shard 0",
                        test.getClass().getName());
                if (shardIndex == 0) {
                    testShards.add(test);
                }
                continue;
            }
            IRemoteTest testShard = ((IStrictShardableTest) test).getTestShard(shardCount,
                    shardIndex);
            testShards.add(testShard);
        }
        config.setTests(testShards);
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
            shardSuffix = String.format(" (shard %d of %d)",
                    config.getCommandOptions().getShardIndex(),
                    config.getCommandOptions().getShardCount());
        }
        StringBuilder buildInfos = new StringBuilder();
        StringBuilder msg = new StringBuilder("Starting invocation for '");
        msg.append(context.getTestTag());
        msg.append("' with ");
        for (Entry<ITestDevice, IBuildInfo> entry : context.getDeviceBuildMap().entrySet()) {
            buildInfos.append("[").append(getBuildDescription(entry.getValue())).append("]");
            if (!IBuildInfo.UNKNOWN_BUILD_ID.equals(entry.getValue().getBuildId())) {
                msg.append("[build ");
                msg.append(getBuildDescription(entry.getValue()));
            }
            for (String buildAttr : entry.getValue().getBuildAttributes().values()) {
                msg.append(" ");
                msg.append(buildAttr);
            }
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
     * Returns a user-friendly description of the build
     */
    private String getBuildDescription(IBuildInfo info) {
        return String.format("'%s'", buildSpacedString(info.getBuildBranch(),
                info.getBuildFlavor(), info.getBuildId()));
    }

    /**
     * Helper method for adding space delimited sequence of strings. Will ignore null segments
     */
    private String buildSpacedString(String... segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (s != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /**
     * Performs the invocation
     *
     * @param config the {@link IConfiguration}
     * @param context the {@link IInvocationContext} to use.
     */
    private void performInvocation(IConfiguration config, IInvocationContext context,
            IRescheduler rescheduler, ITestInvocationListener listener) throws Throwable {

        boolean resumed = false;
        String bugreportName = null;
        long startTime = System.currentTimeMillis();
        long elapsedTime = -1;
        Throwable exception = null;
        Throwable tearDownException = null;
        ITestDevice badDevice = null;

        startInvocation(config, context, listener);
        try {
            logDeviceBatteryLevel(context, "initial");
            prepareAndRun(config, context, listener);
        } catch (BuildError e) {
            exception = e;
            CLog.w("Build failed on device '%s'. Reason: %s", e.getDeviceDescriptor(),
                    e.toString());
            bugreportName = BUILD_ERROR_BUGREPORT_NAME;
            badDevice = context.getDeviceBySerial(e.getDeviceDescriptor().getSerial());
            if (e instanceof DeviceFailedToBootError) {
                if (badDevice == null) {
                    context.setRecoveryModeForAllDevices(RecoveryMode.NONE);
                } else {
                    badDevice.setRecoveryMode(RecoveryMode.NONE);
                }
            }
            reportFailure(e, listener, config, context, rescheduler);
        } catch (TargetSetupError e) {
            exception = e;
            CLog.e("Caught exception while running invocation");
            CLog.e(e);
            bugreportName = TARGET_SETUP_ERROR_BUGREPORT_NAME;
            badDevice = context.getDeviceBySerial(e.getDeviceDescriptor().getSerial());
            reportFailure(e, listener, config, context, rescheduler);
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
                reportFailure(e, listener, config, context, rescheduler);
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
            reportFailure(e, listener, config, context, rescheduler);
        } catch (AssertionError e) {
            exception = e;
            CLog.e("Caught AssertionError while running invocation: %s", e.toString());
            CLog.e(e);
            reportFailure(e, listener, config, context, rescheduler);
        } catch (Throwable t) {
            exception = t;
            // log a warning here so its captured before reportLogs is called
            CLog.e("Unexpected exception when running invocation: %s", t.toString());
            CLog.e(t);
            reportFailure(t, listener, config, context, rescheduler);
            throw t;
        } finally {
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
                        takeBugreport(device, listener, bugreportName,
                                config.getCommandOptions().takeBugreportzOnInvocationEnded());
                    }
                } else {
                    // If we have identified a faulty device only take the bugreport on it.
                    takeBugreport(badDevice, listener, bugreportName,
                            config.getCommandOptions().takeBugreportzOnInvocationEnded());
                }
            }
            mStatus = "tearing down";
            try {
                doTeardown(config, context, exception);
            } catch (Throwable e) {
                tearDownException = e;
                CLog.e("Exception when tearing down invocation: %s", tearDownException.toString());
                CLog.e(tearDownException);
                if (exception == null) {
                    // only report when the exception is new during tear down
                    reportFailure(tearDownException, listener, config, context, rescheduler);
                }
            }
            mStatus = "done running tests";
            try {
                // Clean up host.
                doCleanUp(config, context, exception);
                for (ITestDevice device : context.getDevices()) {
                    reportLogs(device, listener);
                }
                reportHostLog(listener, config.getLogOutput());
                elapsedTime = System.currentTimeMillis() - startTime;
                if (!resumed) {
                    listener.invocationEnded(elapsedTime);
                }
            } finally {
                for (String deviceName : context.getDeviceConfigNames()) {
                    config.getDeviceConfigByName(deviceName).getBuildProvider()
                            .cleanUp(context.getBuildInfo(deviceName));
                }
            }
        }
        if (tearDownException != null) {
            // this means a DNAE or RTE has happened during teardown, need to throw
            // if there was a preceding RTE or DNAE stored in 'exception', it would have already
            // been thrown before exiting the previous try...catch...finally block
            throw tearDownException;
        }
    }

    /**
     * Do setup, run the tests, then call tearDown
     */
    private void prepareAndRun(IConfiguration config, IInvocationContext context,
            ITestInvocationListener listener) throws Throwable {
        getRunUtil().allowInterrupt(true);
        logDeviceBatteryLevel(context, "initial -> setup");
        doSetup(config, context, listener);
        logDeviceBatteryLevel(context, "setup -> test");
        runTests(context, config, listener);
        logDeviceBatteryLevel(context, "after test");
    }

    private void doSetup(IConfiguration config, IInvocationContext context,
            final ITestInvocationListener listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        // TODO: evaluate doing device setup in parallel
        for (String deviceName : context.getDeviceConfigNames()) {
            ITestDevice device = context.getDevice(deviceName);
            CLog.d("Starting setup for device: '%s'", device.getSerialNumber());
            if (device instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) context.getDevice(deviceName))
                        .setTestLogger(listener);
            }
            device.preInvocationSetup(context.getBuildInfo(deviceName));

            for (ITargetPreparer preparer : config.getDeviceConfigByName(deviceName)
                    .getTargetPreparers()) {
                if (preparer instanceof ITestLoggerReceiver) {
                    ((ITestLoggerReceiver) preparer).setTestLogger(listener);
                }
                CLog.d("starting preparer '%s' on device: '%s'", preparer,
                        device.getSerialNumber());
                preparer.setUp(device, context.getBuildInfo(deviceName));
                CLog.d("done with preparer '%s' on device: '%s'", preparer,
                        device.getSerialNumber());
            }
            CLog.d("Done with setup of device: '%s'", device.getSerialNumber());
        }
        // After all the individual setup, make the multi-devices setup
        for (IMultiTargetPreparer multipreparer : config.getMultiTargetPreparers()) {
            if (multipreparer instanceof ITestLoggerReceiver) {
                ((ITestLoggerReceiver) multipreparer).setTestLogger(listener);
            }
            CLog.d("Starting multi target preparer '%s'", multipreparer);
            multipreparer.setUp(context.getDeviceBuildMap());
            CLog.d("done with multi target preparer '%s'", multipreparer);
        }
    }

    private void doTeardown(IConfiguration config, IInvocationContext context,
            Throwable exception) throws Throwable {
        Throwable throwable = null;

        List<IMultiTargetPreparer> multiPreparers = config.getMultiTargetPreparers();
        ListIterator<IMultiTargetPreparer> iterator =
                multiPreparers.listIterator(multiPreparers.size());
        while (iterator.hasPrevious()) {
            IMultiTargetPreparer multipreparer = iterator.previous();
            CLog.d("Starting multi target tearDown '%s'", multipreparer);
            multipreparer.tearDown(context.getDeviceBuildMap(), throwable);
            CLog.d("Done with multi target tearDown '%s'", multipreparer);
        }

        // Clear wifi settings, to prevent wifi errors from interfering with teardown process.
        for (String deviceName : context.getDeviceConfigNames()) {
            ITestDevice device = context.getDevice(deviceName);
            device.clearLastConnectedWifiNetwork();
            List<ITargetPreparer> preparers =
                    config.getDeviceConfigByName(deviceName).getTargetPreparers();
            ListIterator<ITargetPreparer> itr = preparers.listIterator(preparers.size());
            while (itr.hasPrevious()) {
                ITargetPreparer preparer = itr.previous();
                if(preparer instanceof ITargetCleaner) {
                    ITargetCleaner cleaner = (ITargetCleaner) preparer;
                    if (cleaner != null) {
                        try {
                            CLog.d("starting tearDown '%s' on device: '%s'", preparer,
                                    device.getSerialNumber());
                            cleaner.tearDown(device, context.getBuildInfo(deviceName), exception);
                            CLog.d("done with tearDown '%s' on device: '%s'", preparer,
                                    device.getSerialNumber());
                        } catch (Throwable e) {
                            // We catch it and rethrow later to allow each targetprep to be attempted.
                            // Only the last one will be thrown but all should be logged.
                            CLog.e("Deferring throw for: %s", e);
                            throwable = e;
                        }
                    }
                }
            }
            // Extra tear down step for the device
            device.postInvocationTearDown();
        }

        if (throwable != null) {
            throw throwable;
        }
    }

    private void doCleanUp(IConfiguration config, IInvocationContext context,
            Throwable exception) {
        for (String deviceName : context.getDeviceConfigNames()) {
            List<ITargetPreparer> preparers =
                    config.getDeviceConfigByName(deviceName).getTargetPreparers();
            ListIterator<ITargetPreparer> itr = preparers.listIterator(preparers.size());
            while (itr.hasPrevious()) {
                ITargetPreparer preparer = itr.previous();
                if (preparer instanceof IHostCleaner) {
                    IHostCleaner cleaner = (IHostCleaner) preparer;
                    if (cleaner != null) {
                        cleaner.cleanUp(context.getBuildInfo(deviceName), exception);
                    }
                }
            }
        }
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
                    cloneBuildInfos(resumeConfig, resumeConfig, context);

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

    private void reportFailure(Throwable exception, ITestInvocationListener listener,
            IConfiguration config, IInvocationContext context, IRescheduler rescheduler) {
        listener.invocationFailed(exception);
        if (!(exception instanceof BuildError) && !(exception.getCause() instanceof BuildError)) {
            for (String deviceName : context.getDeviceConfigNames()) {
                config.getDeviceConfigByName(deviceName).getBuildProvider()
                        .buildNotTested(context.getBuildInfo(deviceName));
            }
            rescheduleTest(config, rescheduler);
        }
    }

    private void rescheduleTest(IConfiguration config, IRescheduler rescheduler) {
        for (IRemoteTest test : config.getTests()) {
            if (!config.getCommandOptions().isLoopMode() && test instanceof IRetriableTest &&
                    ((IRetriableTest) test).isRetriable()) {
                rescheduler.rescheduleCommand();
                return;
            }
        }
    }

    private void reportLogs(ITestDevice device, ITestInvocationListener listener) {
        InputStreamSource logcatSource = null;
        InputStreamSource emulatorOutput = null;
        if (device != null) {
            logcatSource = device.getLogcat();
            if (device.getIDevice() != null && device.getIDevice().isEmulator()) {
                emulatorOutput = device.getEmulatorOutput();
            }
        }
        if (logcatSource != null) {
            listener.testLog(DEVICE_LOG_NAME, LogDataType.LOGCAT, logcatSource);
        }
        if (emulatorOutput != null) {
            listener.testLog(EMULATOR_LOG_NAME, LogDataType.TEXT, emulatorOutput);
        }
        // Clean up after our ISSen
        if (logcatSource != null) {
            logcatSource.cancel();
        }
        if (emulatorOutput != null) {
            emulatorOutput.cancel();
        }

    }

    private void reportHostLog(ITestInvocationListener listener, ILeveledLogOutput logger) {
        InputStreamSource globalLogSource = logger.getLog();
        listener.testLog(TRADEFED_LOG_NAME, LogDataType.TEXT, globalLogSource);
        globalLogSource.cancel();
    }

    private void takeBugreport(ITestDevice device, ITestInvocationListener listener,
            String bugreportName, boolean useBugreportz) {
        if (device == null) {
            return;
        }
        if (device.getIDevice() instanceof StubDevice) {
            return;
        }
        if (useBugreportz) {
            // logBugreport will report a regular bugreport if bugreportz is not supported.
            device.logBugreport(String.format("%s_%s", bugreportName, device.getSerialNumber()),
                    listener);
        } else {
            InputStreamSource bugreport = device.getBugreport();
            try {
                if (bugreport != null) {
                    listener.testLog(String.format("%s_%s", bugreportName,
                            device.getSerialNumber()), LogDataType.BUGREPORT, bugreport);
                } else {
                    CLog.w("Error when collecting bugreport for device '%s'",
                            device.getSerialNumber());
                }
            } finally {
                StreamUtil.cancel(bugreport);
            }
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

    /**
     * Runs the test.
     *
     * @param context the {@link IInvocationContext} to run tests on
     * @param config the {@link IConfiguration} to run
     * @param listener the {@link ITestInvocationListener} of test results
     * @throws DeviceNotAvailableException
     */
    private void runTests(IInvocationContext context, IConfiguration config,
            ITestInvocationListener listener) throws DeviceNotAvailableException {
        for (IRemoteTest test : config.getTests()) {
            // For compatibility of those receivers, they are assumed to be single device alloc.
            if (test instanceof IDeviceTest) {
                ((IDeviceTest)test).setDevice(context.getDevices().get(0));
            }
            if (test instanceof IBuildReceiver) {
                ((IBuildReceiver)test).setBuild(context.getBuildInfo(
                        context.getDevices().get(0)));
            }

            // TODO: consider adding receivers for only the list of ITestDevice and IBuildInfo.
            if (test instanceof IMultiDeviceTest) {
                ((IMultiDeviceTest)test).setDeviceInfos(context.getDeviceBuildMap());
            }
            if (test instanceof IInvocationContextReceiver) {
                ((IInvocationContextReceiver)test).setInvocationContext(context);
            }
            test.run(listener);
        }
    }

    @Override
    public String toString() {
        return mStatus;
    }

    private void logDeviceBatteryLevel(IInvocationContext context, String event) {
        for (ITestDevice testDevice : context.getDevices()) {
            if (testDevice == null) {
                return;
            }
            IDevice device = testDevice.getIDevice();
            if (device == null) {
                return;
            }
            try {
                CLog.v("%s - %s - %d%%", BATT_TAG, event,
                        device.getBattery(500, TimeUnit.MILLISECONDS).get());
                return;
            } catch (InterruptedException | ExecutionException e) {
                // fall through
            }

            CLog.v("Failed to get battery level");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke(
            IInvocationContext context, IConfiguration config, IRescheduler rescheduler,
            ITestInvocationListener... extraListeners)
                    throws DeviceNotAvailableException, Throwable {
        List<ITestInvocationListener> allListeners = new ArrayList<ITestInvocationListener>(
                config.getTestInvocationListeners().size() + extraListeners.length);
        allListeners.addAll(config.getTestInvocationListeners());
        allListeners.addAll(Arrays.asList(extraListeners));
        ITestInvocationListener listener = new LogSaverResultForwarder(config.getLogSaver(),
                allListeners);
        String currentDeviceName = null;
        try {
            mStatus = "fetching build";
            config.getLogOutput().init();
            getLogRegistry().registerLogger(config.getLogOutput());
            for (String deviceName : context.getDeviceConfigNames()) {
                context.getDevice(deviceName).clearLastConnectedWifiNetwork();
                context.getDevice(deviceName).setOptions(
                        config.getDeviceConfigByName(deviceName).getDeviceOptions());
                if (config.getDeviceConfigByName(deviceName).getDeviceOptions()
                        .isLogcatCaptureEnabled()) {
                    context.getDevice(deviceName).startLogcat();
                }
            }

            String cmdLineArgs = config.getCommandLine();
            if (cmdLineArgs != null) {
                CLog.i("Invocation was started with cmd: %s", cmdLineArgs);
            }
            // TODO: evaluate fetching build in parallel
            for (String deviceName : context.getDeviceConfigNames()) {
                currentDeviceName = deviceName;
                IBuildInfo info = null;
                ITestDevice device = context.getDevice(deviceName);
                IDeviceConfiguration deviceConfig = config.getDeviceConfigByName(deviceName);

                if (deviceConfig.getBuildProvider() instanceof IDeviceBuildProvider) {
                    info = ((IDeviceBuildProvider)deviceConfig.getBuildProvider()).getBuild(device);
                } else {
                    info = deviceConfig.getBuildProvider().getBuild();
                }
                if (info != null) {
                    info.setDeviceSerial(device.getSerialNumber());
                    context.addDeviceBuildInfo(deviceName, info);
                    device.setRecovery(deviceConfig.getDeviceRecovery());
                } else {
                    mStatus = "(no build to test)";
                    CLog.i("No build to test for device: %s", device.getSerialNumber());
                    rescheduleTest(config, rescheduler);
                    // save current log contents to global log
                    getLogRegistry().dumpToGlobalLog(config.getLogOutput());
                    // Set the exit code to error
                    GlobalConfiguration.getInstance().getCommandScheduler()
                            .setLastInvocationExitCode(1);
                    return;
                }
                // TODO: remove build update when reporting is done on context
                updateBuild(info, config);
            }
            updateInvocationContext(context, config);
            if (shardConfig(config, context, rescheduler)) {
                CLog.i("Invocation for %s has been sharded, rescheduling",
                        context.getSerials().toString());
            } else {
                updateConfigIfSharded(config);
                if (config.getTests() == null || config.getTests().isEmpty()) {
                    CLog.e("No tests to run");
                } else {
                    performInvocation(config, context, rescheduler, listener);
                }
            }
        } catch (BuildRetrievalError e) {
            CLog.e(e);
            if (currentDeviceName != null) {
                context.addDeviceBuildInfo(currentDeviceName, e.getBuildInfo());
            }
            // report an empty invocation, so this error is sent to listeners
            startInvocation(config, context, listener);
            // don't want to use #reportFailure, since that will call buildNotTested
            listener.invocationFailed(e);
            for (ITestDevice device : context.getDevices()) {
                reportLogs(device, listener);
            }
            reportHostLog(listener, config.getLogOutput());
            listener.invocationEnded(0);
            return;
        } catch (IOException e) {
            CLog.e(e);
        } finally {
            // ensure we always deregister the logger
            for (String deviceName : context.getDeviceConfigNames()) {
                context.getDevice(deviceName).stopLogcat();
            }
            getLogRegistry().unregisterLogger();
            config.getLogOutput().closeLog();
        }
    }
}
