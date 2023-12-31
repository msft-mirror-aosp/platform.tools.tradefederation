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
package com.android.tradefed.testtype.suite.module;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.IInvocationContext;

/** Interface for controlling if a module should be executed or not. */
public interface IModuleController {

    /** Enum describing how the module should be executed. */
    public enum RunStrategy {
        // Run the full module
        RUN,
        // Completely skip the module and report nothing
        FULL_MODULE_BYPASS,
        // Report all tests in the module as SKIPPED
        SKIP_MODULE_TESTCASES
    }

    /**
     * Method to determine if a module should run or not.
     *
     * @param context the {@link IInvocationContext} of the module.
     * @return True if the module should run, false otherwise.
     */
    public RunStrategy shouldRunModule(IInvocationContext context)
            throws DeviceNotAvailableException;
}
