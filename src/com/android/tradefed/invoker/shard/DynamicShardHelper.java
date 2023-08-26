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

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.ITestSuite;

import com.google.common.base.Strings;
import com.google.internal.android.engprod.v1.ProvideTestTargetRequest;
import com.google.internal.android.engprod.v1.SerializedTestTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Sharding strategy to allow work remote work queueing between multiple TF instances */
public class DynamicShardHelper extends ShardHelper {

    /** {@inheritDoc} */
    @Override
    public boolean shardConfig(
            IConfiguration config,
            TestInformation testInfo,
            IRescheduler rescheduler,
            ITestLogger logger) {
        Integer shardCount = config.getCommandOptions().getShardCount();
        Integer shardIndex = config.getCommandOptions().getShardIndex();

        if (shardIndex == null) {
            return super.shardConfig(config, testInfo, rescheduler, logger);
        }
        if (shardCount == null) {
            throw new HarnessRuntimeException(
                    "shard-count is null while shard-index is " + shardIndex,
                    InfraErrorIdentifier.INTERNAL_CONFIG_ERROR);
        }
        // No sharding needed if shard-count=1
        if (shardCount == 1) {
            return false;
        }

        // Initialize Dynamic Sharding client
        IDynamicShardingClient client = getClient();

        String invocationId = testInfo.getContext().getAttribute("invocation_id");
        String attemptId = testInfo.getContext().getAttribute("attempt_index");

        // If we don't have sufficient information to properly key the pool, then fall
        // back to strict sharding.
        if (Strings.isNullOrEmpty(invocationId) || Strings.isNullOrEmpty(attemptId)) {
            CLog.d("No invocation_id specified, falling back to strict sharding.");
            return super.shardConfig(config, testInfo, rescheduler, logger);
        }

        String poolId = String.format("invocation-%s-attempt-%s", invocationId, attemptId);

        List<ITestSuite> allModules = getAllModules(config, testInfo);

        Map<String, ITestSuite> moduleMapping = new HashMap<>();
        for (ITestSuite test : allModules) {
            moduleMapping.put(test.getDirectModule().getId(), test);
        }

        // if we're shard 0 populate the pool with the list of tests
        try {
            // Populate the pool
            List<SerializedTestTarget> targetsToUpload =
                    moduleMapping.keySet().stream()
                            .map(x -> SerializedTestTarget.newBuilder().setTargetName(x).build())
                            .collect(Collectors.toList());
            CLog.d("Uploading to pool %s test targets: %s", poolId, targetsToUpload);
            ProvideTestTargetRequest request =
                    ProvideTestTargetRequest.newBuilder()
                            .setReferencePoolId(poolId)
                            .addAllTestTargets(targetsToUpload)
                            .build();
            client.provideTestTarget(request);
        } catch (StatusRuntimeException e) {
            // If it is just the ALREADY_EXISTS error, that's ok; it just means
            // that one of the other shards got to it before this one.
            if (Status.fromThrowable(e).getCode() != Status.Code.ALREADY_EXISTS) {
                // rethrow if it isn't the error we were expecting.
                throw e;
            }
            // will only reach this point if the error code is ALREADY_EXISTS
            CLog.v("Another shard has already seeded the pool '%s'.", poolId);
        }

        // if we're any shard, create a test pool poller that polls the sharding server
        ITestsPool pool = RemoteDynamicPool.newInstance(client, poolId, moduleMapping);

        // For now this should disable the reporting of not executed tests since each
        // poller can only decrement this by 1 and this mode only uses one poller per
        // cluster command.
        // At some point we should probably have some way for pollers to register and
        // deregister from a pool in order to be able to tell how many pollers are still
        // listening to a pool.
        CountDownLatch tracker = new CountDownLatch(2);
        TestsPoolPoller poller = new TestsPoolPoller(pool, tracker);

        // set our main test to be the test pool poller.
        config.setTest(poller);

        // We cannot shuffle to get better average results
        return false;
    }

    private IDynamicShardingClient getClient() {
        ServiceLoader<IDynamicShardingClient> serviceLoader =
                ServiceLoader.load(IDynamicShardingClient.class);
        for (IDynamicShardingClient client : serviceLoader) {
            // return the first (and should be only) implementation of this feature
            return client;
        }
        throw new HarnessRuntimeException(
                "Failed to load dynamic sharding client implementation (missing from service"
                        + " loader?)",
                InfraErrorIdentifier.CLASS_NOT_FOUND);
    }

    private List<ITestSuite> getAllModules(IConfiguration config, TestInformation testInfo) {
        List<ITestSuite> allTests = new ArrayList<>();
        for (IRemoteTest test : config.getTests()) {
            if (test instanceof ITestSuite) {
                allTests.addAll(
                        ((ITestSuite) test)
                                .split(1000000, testInfo).stream()
                                        .map(x -> (ITestSuite) x)
                                        .collect(Collectors.toList()));
            } else {
                throw new HarnessRuntimeException(
                        "Test not an instance of ITestSuite, cannot execute this using dynamic"
                                + " sharding.",
                        InfraErrorIdentifier.INTERNAL_CONFIG_ERROR);
            }
        }
        return allTests;
    }
}
