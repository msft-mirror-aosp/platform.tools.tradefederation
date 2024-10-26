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
import com.android.tradefed.invoker.proto.InvocationContext.Context;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.result.proto.TestRecordProto.TestStatus;
import com.android.tradefed.util.proto.TestRecordProtoUtil;

import com.google.common.base.Strings;
import com.google.protobuf.Any;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A result reporter meant to report only the module level results. No re-entry is supported in this
 * module. The intent of this reporter is primarily for caching at module level.
 */
public class ModuleProtoResultReporter extends FileProtoResultReporter {

    public static final String INVOCATION_ID_KEY = "invocation_id";
    private boolean mStopCache = false;
    private String mInvocationId = null;
    private boolean mGranularResults = false;

    public ModuleProtoResultReporter() {
        setPeriodicWriting(false);
        setDelimitedOutput(false);
    }

    public ModuleProtoResultReporter(
            IInvocationContext mainInvocationContext, boolean granularResults) {
        this();
        copyAttributes(mainInvocationContext);
        mGranularResults = granularResults;
    }

    @Override
    protected void beforeModuleStart() {
        IInvocationContext stubContext = new InvocationContext();
        if (mInvocationId != null) {
            CLog.d("Copying property into module results: %s", mInvocationId);
            stubContext.addInvocationAttribute(INVOCATION_ID_KEY, mInvocationId);
        }
        invocationStarted(stubContext);
    }

    @Override
    protected void afterModuleEnd() {
        invocationEnded(0);
    }

    @Override
    public void processTestCaseEnded(TestRecord testCaseRecord) {
        if (mGranularResults) {
            super.processTestCaseEnded(testCaseRecord);
        }
        if (testCaseRecord.getStatus().equals(TestStatus.FAIL)) {
            mStopCache = true;
        }
    }

    @Override
    public void processTestRunEnded(TestRecord runRecord, boolean moduleInProgress) {
        if (mGranularResults) {
            super.processTestRunEnded(runRecord, moduleInProgress);
        }
        if (runRecord.hasDebugInfo()) {
            mStopCache = true;
        }
    }

    @Override
    public void processTestModuleEnd(TestRecord moduleRecord) {
        super.processTestModuleEnd(moduleRecord);
        if (moduleRecord.hasSkipReason()) {
            mStopCache = true;
        }
    }

    public boolean stopCaching() {
        return mStopCache;
    }

    private void copyAttributes(IInvocationContext mainContext) {
        String invocationId = mainContext.getAttribute(INVOCATION_ID_KEY);
        if (!Strings.isNullOrEmpty(invocationId)) {
            mInvocationId = invocationId;
        }
    }

    /** Parsing util to extract metadata we might have transferred */
    public static Map<String, String> parseResultsMetadata(File protoResults) {
        if (protoResults == null) {
            CLog.w("Proto result file is null, cannot parse it.");
            return new HashMap<>();
        }
        try {
            TestRecord record = TestRecordProtoUtil.readFromFile(protoResults, false);
            Any anyDescription = record.getDescription();
            if (!anyDescription.is(Context.class)) {
                throw new RuntimeException("Expected Any description of type Context");
            }
            IInvocationContext receivedContext =
                    InvocationContext.fromProto(anyDescription.unpack(Context.class));
            Map<String, String> receivedAttributes = receivedContext.getAttributes().getUniqueMap();
            CLog.d("Attributes received from cached results: %s", receivedAttributes);
            return receivedAttributes;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
        }
        return new HashMap<>();
    }
}
