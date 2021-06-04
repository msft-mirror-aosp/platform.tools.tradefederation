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
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationFactory;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.GceManager;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.InvocationExecution;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.TestInvocation.Stage;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.sandbox.SandboxInvocationRunner;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Version of {@link InvocationExecution} for the parent invocation special actions when running a
 * sandbox.
 */
public class ParentSandboxInvocationExecution extends InvocationExecution {

    @Override
    public boolean fetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException, BuildRetrievalError {
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
    protected List<ITargetPreparer> getPreparersToRun(IConfiguration config, String deviceName) {
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

    @Override
    public void runTests(
            TestInformation info, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        // If the invocation is sandboxed run as a sandbox instead.
        boolean success = false;
        try {
            success = prepareAndRunSandbox(info, config, listener);
        } finally {
            if (!success) {
                String instanceName = null;
                boolean cleaned = false;
                for (IBuildInfo build : info.getContext().getBuildInfos()) {
                    if (build.getBuildAttributes().get(GceManager.GCE_INSTANCE_NAME_KEY) != null) {
                        instanceName =
                                build.getBuildAttributes().get(GceManager.GCE_INSTANCE_NAME_KEY);
                    }
                    if (build.getBuildAttributes().get(GceManager.GCE_INSTANCE_CLEANED_KEY)
                            != null) {
                        cleaned = true;
                    }
                }
                if (instanceName != null && !cleaned) {
                    // TODO: Handle other devices if needed.
                    TestDeviceOptions options = config.getDeviceConfig().get(0).getDeviceOptions();
                    CLog.w("Instance was not cleaned in sandbox subprocess, cleaning it now.");
                    boolean res = GceManager.AcloudShutdown(options, getRunUtil(), instanceName);
                    if (res) {
                        info.getBuildInfo()
                                .addBuildAttribute(GceManager.GCE_INSTANCE_CLEANED_KEY, "true");
                    }
                }
            }
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
        return SandboxInvocationRunner.prepareAndRun(info, config, listener);
    }

}
