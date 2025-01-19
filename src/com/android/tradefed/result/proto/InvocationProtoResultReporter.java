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
package com.android.tradefed.result.proto;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.FailureDescription;

/** Reporters to store invocation level caching information and its list of modules */
public class InvocationProtoResultReporter extends ModuleProtoResultReporter {

    public InvocationProtoResultReporter() {
        super();
    }

    public InvocationProtoResultReporter(
            IInvocationContext mainInvocationContext, boolean granularResults) {
        super(mainInvocationContext, granularResults);
    }

    @Override
    public void invocationStarted(IInvocationContext context) {
        IInvocationContext stubContext = createCachedContext();
        super.invocationStarted(stubContext);
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        reportStopCaching();
    }

    @Override
    public void invocationFailed(Throwable cause) {
        reportStopCaching();
    }

    @Override
    protected void beforeModuleStart() {
        // Override parent implementation
    }
}
