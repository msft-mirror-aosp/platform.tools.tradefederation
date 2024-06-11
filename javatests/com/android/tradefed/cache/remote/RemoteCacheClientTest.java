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

package com.android.tradefed.cache.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheImplBase;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.util.FileUtil;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Tests for {@link RemoteCacheClient}. */
@RunWith(JUnit4.class)
public class RemoteCacheClientTest {
    private final String mFakeServerName = "fake server for " + getClass();
    private final MutableHandlerRegistry mServiceRegistry = new MutableHandlerRegistry();
    private ManagedChannel mChannel;
    private Server mFakeServer;
    private File mInput;

    @Before
    public final void setUp() throws Exception {
        mFakeServer =
                InProcessServerBuilder.forName(mFakeServerName)
                        .fallbackHandlerRegistry(mServiceRegistry)
                        .directExecutor()
                        .build()
                        .start();
        mChannel = InProcessChannelBuilder.forName(mFakeServerName).directExecutor().build();
        mInput = FileUtil.createTempDir("input-dir");
    }

    @After
    public void tearDown() throws Exception {
        mChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        mFakeServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        FileUtil.recursiveDelete(mInput);
    }

    @Test
    public void lookupCache_works() throws IOException, InterruptedException {
        ExecutableAction notFoundAction =
                ExecutableAction.create(
                        mInput, Arrays.asList("not", "found", "command"), new HashMap<>(), 100L);
        ExecutableAction cachedAction =
                ExecutableAction.create(
                        mInput, Arrays.asList("found", "command"), new HashMap<>(), 100L);
        int exitCode = 0;
        mServiceRegistry.addService(
                new ActionCacheImplBase() {
                    @Override
                    public void getActionResult(
                            GetActionResultRequest request,
                            StreamObserver<ActionResult> responseObserver) {
                        if (request.getActionDigest().equals(cachedAction.actionDigest())) {
                            responseObserver.onNext(
                                    ActionResult.newBuilder().setExitCode(exitCode).build());
                            responseObserver.onCompleted();
                            return;
                        }
                        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
                    }
                });
        RemoteCacheClient client = newClient();

        ExecutableActionResult notFoundResult = client.lookupCache(notFoundAction);
        ExecutableActionResult cachedResult = client.lookupCache(cachedAction);

        assertNull(notFoundResult);
        assertEquals(0, cachedResult.exitCode());
    }

    private RemoteCacheClient newClient() {
        return new RemoteCacheClient("test instance", mChannel, null);
    }
}
