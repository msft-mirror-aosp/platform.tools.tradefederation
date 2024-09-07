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
package com.android.tradefed.result.proto;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.result.proto.TestRecordProto.TestStatus;

/**
 * A result reporter meant to report only the module level results. No re-entry is supported in this
 * module.
 */
public class ModuleProtoResultReporter extends FileProtoResultReporter {

    private boolean mHasFailures = false;

    public ModuleProtoResultReporter() {
        setPeriodicWriting(false);
        setDelimitedOutput(false);
    }

    @Override
    protected void beforeModuleStart() {
        IInvocationContext stubContext = new InvocationContext();
        invocationStarted(stubContext);
    }

    @Override
    protected void afterModuleEnd() {
        invocationEnded(0);
    }

    @Override
    public void processTestCaseEnded(TestRecord testCaseRecord) {
        super.processTestCaseEnded(testCaseRecord);
        if (testCaseRecord.getStatus().equals(TestStatus.FAIL)) {
            mHasFailures = true;
        }
    }

    @Override
    public void processTestRunEnded(TestRecord runRecord, boolean moduleInProgress) {
        super.processTestRunEnded(runRecord, moduleInProgress);
        if (runRecord.hasDebugInfo()) {
            mHasFailures = true;
        }
    }

    public boolean hasFailures() {
        return mHasFailures;
    }
}
