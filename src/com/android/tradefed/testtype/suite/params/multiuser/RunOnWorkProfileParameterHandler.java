/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tradefed.testtype.suite.params.multiuser;

import com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparer;
import com.android.tradefed.testtype.suite.params.IModuleParameterHandler;

import java.util.List;

public class RunOnWorkProfileParameterHandler extends ProfileParameterHandler
        implements IModuleParameterHandler {

    private static final List<String> REQUIRE_RUN_ON_WORK_PROFILE_NAMES = List.of(
            "com.android.bedstead.enterprise.annotations.RequireRunOnWorkProfile",
            "com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile"
    );

    public RunOnWorkProfileParameterHandler() {
        super(REQUIRE_RUN_ON_WORK_PROFILE_NAMES, new RunOnWorkProfileTargetPreparer());
    }

    @Override
    public String getParameterIdentifier() {
        return "run-on-work-profile";
    }
}
