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

import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateInvocationRequest;
import com.android.tradefed.log.LogUtil.CLog;

/** ResultDB recorder client that uploads test results to ResultDB. */
public class Client implements IRecorderClient {

    private Client() {}

    @Override
    public Invocation createInvocation(CreateInvocationRequest request) {
        CLog.i("Creating invocation: %s", request.toString());
        return request.getInvocation();
    }

    @Override
    public Invocation updateInvocation(UpdateInvocationRequest request) {
        // TODO: Call grpc client.
        CLog.i("Updating invocation: %s", request.toString());
        return request.getInvocation();
    }

    @Override
    public Invocation finalizeInvocation(String invocationId) {
        // TODO: Call grpc client.
        CLog.i("Finalize invocation: %s", invocationId);
        return Invocation.getDefaultInstance();
    }

    @Override
    public void uploadTestResult(String invocationId, TestResult result) {
        // TODO: Implement this method.
        CLog.i("schedule upload test result: %s", result);
    }
}
