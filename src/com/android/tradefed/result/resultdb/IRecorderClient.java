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

/**
 * Interface for communicating with ResultDB recorder backend. The interface contains methods to
 * create and update invocations and upload test results.
 */
public interface IRecorderClient {

    public Invocation createInvocation(CreateInvocationRequest request);

    public Invocation updateInvocation(UpdateInvocationRequest request);

    public Invocation finalizeInvocation(String invocationId);

    public void uploadTestResult(String invocationId, TestResult result);
}
