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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.build.VersionedFile;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ManagedTestDeviceFactory;
import com.android.tradefed.invoker.ExecutionFiles;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.InvocationExecution;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.sandbox.SandboxOptions;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.IInvocationContextReceiver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Special sandbox execution of the invocation: This is the InvocationExection for when we are
 * inside the sandbox running the command. The build should already be available in the context.
 */
public class SandboxedInvocationExecution extends InvocationExecution {

    /** {@inheritDoc} */
    @Override
    public boolean fetchBuild(
            TestInformation testInfo,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException, BuildRetrievalError {
        // If the invocation is currently sandboxed, builds have already been downloaded.
        CLog.d("Skipping download in the sandbox.");
        if (!config.getConfigurationDescription().shouldUseSandbox()) {
            throw new RuntimeException(
                    "We should only skip download if we are a sandbox. Something went very wrong.");
        }
        // Even if we don't call them directly here, ensure they receive their dependencies for the
        // buildNotTested callback.
        for (String deviceName : testInfo.getContext().getDeviceConfigNames()) {
            IDeviceConfiguration deviceConfig = config.getDeviceConfigByName(deviceName);
            IBuildProvider provider = deviceConfig.getBuildProvider();
            // Inject the context to the provider if it can receive it
            if (provider instanceof IInvocationContextReceiver) {
                ((IInvocationContextReceiver) provider).setInvocationContext(testInfo.getContext());
            }
        }

        // Still set the test-tag on build infos for proper reporting
        for (IBuildInfo info : testInfo.getContext().getBuildInfos()) {
            setTestTag(info, config);
        }
        backFillTestInformation(testInfo, testInfo.getBuildInfo());
        return true;
    }

    @Override
    public void cleanUpBuilds(IInvocationContext context, IConfiguration config) {
        // Don't clean the build info in subprocess. Let the parents do it.
    }

    /** {@inheritDoc} */
    @Override
    protected List<ITargetPreparer> getTargetPreparersToRun(
            IConfiguration config, String deviceName) {
        List<ITargetPreparer> preparersToRun = new ArrayList<>();
        preparersToRun.addAll(config.getDeviceConfigByName(deviceName).getTargetPreparers());
        return preparersToRun;
    }

    /** {@inheritDoc} */
    @Override
    protected List<ITargetPreparer> getLabPreparersToRun(IConfiguration config, String deviceName) {
        return new ArrayList<>();
    }

    /**
     * In order for sandbox to work without currently receiving the parent TestInformation back-fill
     * some information to find artifacts properly.
     */
    private void backFillTestInformation(TestInformation testInfo, IBuildInfo primaryBuild) {
        ExecutionFiles execFiles = testInfo.executionFiles();
        if (execFiles.get(FilesKey.TESTS_DIRECTORY) == null) {
            File testsDir = primaryBuild.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
            if (testsDir != null && testsDir.exists()) {
                execFiles.put(FilesKey.TESTS_DIRECTORY, testsDir, true);
            }
        }
        if (execFiles.get(FilesKey.TARGET_TESTS_DIRECTORY) == null) {
            File targetDir = primaryBuild.getFile(BuildInfoFileKey.TARGET_LINKED_DIR);
            if (targetDir != null && targetDir.exists()) {
                execFiles.put(FilesKey.TARGET_TESTS_DIRECTORY, targetDir, true);
            }
        }
        if (execFiles.get(FilesKey.HOST_TESTS_DIRECTORY) == null) {
            File hostDir = primaryBuild.getFile(BuildInfoFileKey.HOST_LINKED_DIR);
            if (hostDir != null && hostDir.exists()) {
                execFiles.put(FilesKey.HOST_TESTS_DIRECTORY, hostDir, true);
            }
        }
        // Link the remaining buildInfo files.
        for (String key : primaryBuild.getVersionedFileKeys()) {
            VersionedFile versionedFile = primaryBuild.getVersionedFile(key);
            if (versionedFile != null
                    && versionedFile.getFile().exists()
                    && !execFiles.containsKey(key)) {
                execFiles.put(key, versionedFile.getFile());
            }
        }
    }

    @Override
    protected void logHostAdb(IConfiguration config, ITestLogger logger) {
        // Do nothing, the parent sandbox will log it.
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePreInvocationSetup(
            IInvocationContext context, IConfiguration config, ITestLogger logger)
            throws DeviceNotAvailableException, TargetSetupError {
        if (shouldRunDeviceSpecificSetup(config)) {
            super.runDevicePreInvocationSetup(context, config, logger);
        } else {
            CLog.d("Skipping runDevicePreInvocationSetup it ran in parent sandbox.");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void runDevicePostInvocationTearDown(
            IInvocationContext context, IConfiguration config, Throwable exception) {
        if (shouldRunDeviceSpecificSetup(config)) {
            super.runDevicePostInvocationTearDown(context, config, exception);
        } else {
            CLog.d("Skipping runDevicePostInvocationTearDown it ran in parent sandbox.");
        }
    }

    /**
     * Do not run the pre invocation setup for the device if the parent handled it.
     */
    private boolean shouldRunDeviceSpecificSetup(IConfiguration config) {
        if (System.getenv(ManagedTestDeviceFactory.NOTIFY_AS_NATIVE) != null) {
            CLog.d("Usage of native device detected: running device preSetup for connection.");
            return true;
        }
        SandboxOptions options =
                (SandboxOptions)
                        config.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME);
        if (options != null && options.startAvdInParent()) {
            // If it ran in parents, don't run it again.
            return false;
        }
        return true;
    }
}
