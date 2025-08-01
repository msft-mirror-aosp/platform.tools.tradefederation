/*
 * Copyright (C) 2025 The Android Open Source Project
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

/**
 * Only run tests if the device under test is SDK version 36 or above.
 *
 * <p>Use by adding this line to your AndroidTest.xml:
 *
 * <pre><code>&lt;object type="module_controller"
 * class="com.android.tradefed.testtype.suite.module.Sdk36ModuleController" /&gt;</code></pre>
 */
public class Sdk36ModuleController extends MinSdkModuleController {
    public Sdk36ModuleController() {
        super(36);
    }
}
