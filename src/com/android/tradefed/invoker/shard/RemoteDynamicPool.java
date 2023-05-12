/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.invoker.shard;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.shard.token.ITokenRequest;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.ITestSuite;

import com.google.internal.android.engprod.v1.RequestTestTargetRequest;
import com.google.internal.android.engprod.v1.RequestTestTargetResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Implementation of a pool of remote work queued tests */
public class RemoteDynamicPool implements ITestsPool {
    private IDynamicShardingClient mClient;
    private Map<String, ITestSuite> mModuleMapping;
    private String mPoolId;
    private List<IRemoteTest> mQueuedTests;

    public static RemoteDynamicPool newInstance(
            IDynamicShardingClient client, String poolId, Map<String, ITestSuite> moduleMapping) {
        return new RemoteDynamicPool(client, poolId, moduleMapping);
    }

    private RemoteDynamicPool(
            IDynamicShardingClient client, String poolId, Map<String, ITestSuite> moduleMapping) {
        mClient = client;
        mModuleMapping = moduleMapping;
    }

    @Override
    public IRemoteTest poll(TestInformation info, boolean reportNotExecuted) {
        if (mQueuedTests.isEmpty()) {
            RequestTestTargetRequest request =
                    RequestTestTargetRequest.newBuilder().setReferencePoolId(mPoolId).build();
            RequestTestTargetResponse response = mClient.requestTestTarget(request);
            CLog.v(String.format("Received test targets: %s", response.getTestTargetsList()));
            mQueuedTests.addAll(
                    response.getTestTargetsList().stream()
                            .map(x -> mModuleMapping.get(x.getTargetName()))
                            .collect(Collectors.toList()));
            if (mQueuedTests.isEmpty()) {
                return null;
            } else {
                return mQueuedTests.remove(mQueuedTests.size() - 1);
            }
        } else {
            return mQueuedTests.remove(mQueuedTests.size() - 1);
        }
    }

    @Override
    public ITokenRequest pollRejectedTokenModule() {
        return null;
    }
}
