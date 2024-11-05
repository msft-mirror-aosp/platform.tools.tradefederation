/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tradefed.result.ddmlib;

import com.android.ddmlib.IDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an instrumented Android test using the adb command and AndroidTestOrchestrator. See <a
 * href="https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner#use-android">AndroidX-Orchestrator
 * </a> documentation for more details
 */
public class AndroidTestOrchestratorRemoteTestRunner extends RemoteAndroidTestRunner {
    private static final String SERVICES_APK_PACKAGE = "androidx.test.services";
    private static final String SHELL_MAIN_CLASS = "androidx.test.services.shellexecutor.ShellMain";
    private static final String ORCHESTRATOR_PACKAGE = "androidx.test.orchestrator";
    private static final String ORCHESTRATOR_CLASS =
            "androidx.test.orchestrator.AndroidTestOrchestrator";
    public static final String ORCHESTRATOR_INSTRUMENTATION_PROXY_KEY =
            "orchestratorInstrumentationArgs";
    public static final String ORCHESTRATOR_TARGET_INSTRUMENTATION_KEY = "targetInstrumentation";

    public AndroidTestOrchestratorRemoteTestRunner(
            String packageName, String runName, IDevice device) {
        super(packageName, runName, device);
    }

    @Override
    public String getAmInstrumentCommand() {
        List<String> adbArgs = new ArrayList<>();

        adbArgs.add(String.format("CLASSPATH=$(pm path %s)", SERVICES_APK_PACKAGE));
        adbArgs.add(String.format("app_process / %s", SHELL_MAIN_CLASS));

        adbArgs.add("am");
        adbArgs.add("instrument");
        adbArgs.add("-w");
        adbArgs.add("-r");
        adbArgs.add(
                String.format(
                        "-e %s %s", ORCHESTRATOR_TARGET_INSTRUMENTATION_KEY, getRunnerPath()));
        if (!getRunOptions().isEmpty()) {
            adbArgs.add(
                    String.format(
                            "-e %s \"%s\"",
                            ORCHESTRATOR_INSTRUMENTATION_PROXY_KEY, getRunOptions()));
        }
        adbArgs.add(getArgsCommand());

        adbArgs.add(String.format("%s/%s", ORCHESTRATOR_PACKAGE, ORCHESTRATOR_CLASS));
        return String.join(" ", adbArgs);
    }
}
