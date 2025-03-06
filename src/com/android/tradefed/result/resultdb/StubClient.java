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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateInvocationRequest;

import java.util.ArrayList;
import java.util.List;

/** Stub implementation of IRecorderClient for testing. */
class StubClient implements IRecorderClient {
    private List<TestResult> mTestResults = new ArrayList<>();
    private Invocation mInvocation;

    public static StubClient create() {
        return new StubClient();
    }

    private StubClient() {}

    @Override
    public Invocation finalizeInvocation(String invocationId) {
        // TODO: implement this method.
        mInvocation = mInvocation.toBuilder().setState(Invocation.State.FINALIZED).build();
        return mInvocation;
    }

    @Override
    public Invocation updateInvocation(UpdateInvocationRequest request) {
        // TODO: implement this method.
        return null;
    }

    @Override
    public void uploadTestResult(TestResult result) {
        mTestResults.add(result);
    }

    public List<TestResult> getTestResults() {
        return mTestResults;
    }

    public Invocation getInvocation() {
        return mInvocation;
    }

    @Override
    public void finalizeTestResults() {
        // Do nothing.
    }
}
