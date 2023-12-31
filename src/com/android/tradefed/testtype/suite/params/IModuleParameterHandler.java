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
package com.android.tradefed.testtype.suite.params;

import com.android.tradefed.config.IConfiguration;

/** The interface for parameters of suites modules. */
public interface IModuleParameterHandler {

    /** Returns the name the parameterized module will be identified as. */
    public String getParameterIdentifier();

    /**
     * Adds to {@link IConfiguration} with the parameter specific needs. For example, insert or
     * remove target preparers from configuration.
     *
     * @param moduleConfiguration the {@link IConfiguration} of the module
     */
    default void addParameterSpecificConfig(IConfiguration moduleConfiguration) {}

    /**
     * Apply to the module {@link IConfiguration} the parameter specific module setup. For example,
     * this could be extra options for the preparers or the tests.
     */
    public void applySetup(IConfiguration moduleConfiguration);
}
