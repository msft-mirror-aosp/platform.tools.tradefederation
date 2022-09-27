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
package com.android.tradefed.invoker.sandbox;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceSelectionOptions;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.InvocationExecution;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.TestInvocation.Stage;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.sandbox.SandboxInvocationRunner;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.QuotationAwareTokenizer;
import com.android.tradefed.util.RunUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Version of {@link InvocationExecution} for the parent invocation special actions when running a
 * sandbox.
 */
public class ParentSandboxInvocationExecution extends InvocationExecution {

    private SandboxSetupThread setupThread;
    private TestInformation mTestInfo;

    @Override
    public boolean fetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException, BuildRetrievalError {
        mTestInfo = testInfo;
        if (!testInfo.getContext().getBuildInfos().isEmpty()) {
            CLog.d(
                    "Context already contains builds: %s. Skipping download as we are in "
                            + "sandbox-test-mode.",
                    testInfo.getContext().getBuildInfos());
            return true;
        }
        return super.fetchBuild(testInfo, config, rescheduler, listener);
    }

    /** {@inheritDoc} */
    @Override
    protected List<ITargetPreparer> getTargetPreparersToRun(
            IConfiguration config, String deviceName) {
        return new ArrayList<>();
    }

    /** {@inheritDoc} */
    @Override
    protected List<ITargetPreparer> getLabPreparersToRun(IConfiguration config, String deviceName) {
        List<ITargetPreparer> preparersToRun = new ArrayList<>();
        preparersToRun.addAll(config.getDeviceConfigByName(deviceName).getLabPreparers());
        return preparersToRun;
    }

    @Override
    public void doSetup(TestInformation testInfo, IConfiguration config, ITestLogger listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        // TODO address the situation where multi-target preparers are configured
        // (they will be run by both the parent and sandbox if configured)
        super.doSetup(testInfo, config, listener);
    }

    @Override
    public void doTeardown(
            TestInformation testInfo,
            IConfiguration config,
            ITestLogger logger,
            Throwable exception)
            throws Throwable {
        // TODO address the situation where multi-target preparers are configured
        // (they will be run by both the parent and sandbox if configured)
        super.doTeardown(testInfo, config, logger, exception);
    }

    @Override
    public void doCleanUp(IInvocationContext context, IConfiguration config, Throwable exception) {
        super.doCleanUp(context, config, exception);
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePreInvocationSetup(
            IInvocationContext context, IConfiguration config, ITestLogger logger)
            throws DeviceNotAvailableException, TargetSetupError {
        if (shouldRunDeviceSpecificSetup(config)) {
            if (getSandboxOptions(config).shouldParallelSetup()) {
                setupThread =
                        new SandboxSetupThread(mTestInfo, config, (ITestInvocationListener) logger);
                setupThread.start();
            }
            super.runDevicePreInvocationSetup(context, config, logger);
            String commandLine = config.getCommandLine();
            for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
                if (deviceConfig.getDeviceRequirements().gceDeviceRequested()) {
                    // Turn off the gce-device option and force the serial instead to use the
                    // started virtual device.
                    String deviceName = (config.getDeviceConfig().size() > 1) ?
                            String.format("{%s}", deviceConfig.getDeviceName()) : "";
                    commandLine +=
                            String.format(
                                    " --%sno-gce-device --%sserial %s",
                                    deviceName,
                                    deviceName,
                                    context.getDevice(deviceConfig.getDeviceName())
                                            .getSerialNumber());
                    // If we are using the device-type selector, override it
                    if (DeviceSelectionOptions.DeviceRequestedType.GCE_DEVICE.equals(
                            ((DeviceSelectionOptions) deviceConfig.getDeviceRequirements())
                                    .getDeviceTypeRequested())) {
                        commandLine +=
                                String.format(
                                        " --%sdevice-type %s",
                                        deviceName,
                                        DeviceSelectionOptions.DeviceRequestedType.EXISTING_DEVICE
                                                .name());
                    }
                }
            }
            config.setCommandLine(QuotationAwareTokenizer.tokenizeLine(commandLine, false));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePostInvocationTearDown(
            IInvocationContext context, IConfiguration config, Throwable exception) {
        if (shouldRunDeviceSpecificSetup(config)) {
            super.runDevicePostInvocationTearDown(context, config, exception);
        }
    }

    @Override
    public void runTests(
            TestInformation info, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        try (CloseableTraceScope ignore = new CloseableTraceScope("prepareAndRunSandbox")) {
            prepareAndRunSandbox(info, config, listener);
        }
    }

    @Override
    public void reportLogs(ITestDevice device, ITestLogger logger, Stage stage) {
        // If it's not a major error we do not report it if no setup or teardown ran.
        if (!Stage.ERROR.equals(stage)) {
            return;
        }
        super.reportLogs(device, logger, stage);
    }

    /** Returns the {@link IConfigurationFactory} used to created configurations. */
    @VisibleForTesting
    protected IConfigurationFactory getFactory() {
        return ConfigurationFactory.getInstance();
    }

    @VisibleForTesting
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /** Returns the result status of running the sandbox. */
    @VisibleForTesting
    protected boolean prepareAndRunSandbox(
            TestInformation info, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        // Stop background logcat in parent process during the sandbox
        for (String deviceName : info.getContext().getDeviceConfigNames()) {
            if (!(info.getContext().getDevice(deviceName).getIDevice() instanceof StubDevice)) {
                info.getContext().getDevice(deviceName).stopLogcat();
                CLog.i(
                        "Done stopping logcat for %s",
                        info.getContext().getDevice(deviceName).getSerialNumber());
            }
        }

        if (getSandboxOptions(config).shouldParallelSetup()) {
            long startTime = System.currentTimeMillis();
            try {
                setupThread.join();
            } finally {
                // Only track as overhead the time that setup runs longer
                // than other actions (critical path)
                InvocationMetricLogger.addInvocationPairMetrics(
                        InvocationMetricKey.DYNAMIC_FILE_RESOLVER_PAIR,
                        startTime,
                        System.currentTimeMillis());
            }
            if (setupThread.error != null) {
                CLog.e("An exception occurred during parallel setup.");
                throw setupThread.error;
            }
            return SandboxInvocationRunner.runSandbox(config, listener);
        }
        return SandboxInvocationRunner.prepareAndRun(info, config, listener);
    }

    /**
     * Whether or not to run the device pre invocation setup or not.
     */
    private boolean shouldRunDeviceSpecificSetup(IConfiguration config) {
        SandboxOptions options = getSandboxOptions(config);
        if (options != null && options.startAvdInParent()) {
            return true;
        }
        return false;
    }

    private SandboxOptions getSandboxOptions(IConfiguration config) {
        return (SandboxOptions)
                config.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME);
    }

    private class SandboxSetupThread extends Thread {

        private final TestInformation info;
        private final IConfiguration config;
        private final ITestInvocationListener listener;
        // The error that might be returned by the setup
        public Throwable error;

        public SandboxSetupThread(
                TestInformation info, IConfiguration config, ITestInvocationListener listener) {
            setDaemon(true);
            setName("SandboxSetupThread");
            this.info = info;
            this.config = config;
            this.listener = listener;
        }

        @Override
        public void run() {
            try {
                SandboxInvocationRunner.prepareSandbox(info, config, listener);
            } catch (Throwable e) {
                error = e;
            }
        }
    }
}
