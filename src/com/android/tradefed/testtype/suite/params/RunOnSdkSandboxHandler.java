/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tradefed.testtype.suite.params;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.RunOnSdkSandboxTargetPreparer;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Handler for {@link ModuleParameters#RUN_ON_SDK_SANDBOX}. */
public class RunOnSdkSandboxHandler implements IModuleParameterHandler {

    /** {@inheritDoc} */
    @Override
    public String getParameterIdentifier() {
        return "run-on-sdk-sandbox";
    }

    /** {@inheritDoc} */
    @Override
    public void addParameterSpecificConfig(IConfiguration moduleConfiguration) {
        for (IDeviceConfiguration deviceConfig : moduleConfiguration.getDeviceConfig()) {
            List<ITargetPreparer> preparers = deviceConfig.getTargetPreparers();
            preparers.add(new RunOnSdkSandboxTargetPreparer());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        // The SDK sandbox is at least as restrictive as instant apps. Therefore we only run tests
        // marked for @AppModeInstant and exclude tests for @FullAppMode.
        for (IRemoteTest test : moduleConfiguration.getTests()) {
            if (test instanceof ITestAnnotationFilterReceiver) {
                ITestAnnotationFilterReceiver filterTest = (ITestAnnotationFilterReceiver) test;
                // Retrieve the current set of excludeAnnotations to maintain for after the
                // clearing/reset of the annotations.
                Set<String> excludeAnnotations = new HashSet<>(filterTest.getExcludeAnnotations());
                // Remove any global filter on AppModeInstant so instant mode tests can run.
                excludeAnnotations.remove("android.platform.test.annotations.AppModeInstant");
                // Prevent full mode tests from running.
                excludeAnnotations.add("android.platform.test.annotations.AppModeFull");
                // Reset the annotations of the tests
                filterTest.clearExcludeAnnotations();
                filterTest.addAllExcludeAnnotation(excludeAnnotations);
            }
        }
    }
}
