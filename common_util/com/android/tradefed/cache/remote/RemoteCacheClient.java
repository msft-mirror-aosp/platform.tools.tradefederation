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

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheFutureStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** A RemoteActionCache implementation that uses gRPC calls to a remote API server. */
public class RemoteCacheClient implements ICacheClient {
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(60);
    private final String mInstanceName;
    private final ManagedChannel mChannel;
    private final CallCredentials mCallCredentials;

    public RemoteCacheClient(
            String instanceName, ManagedChannel channel, CallCredentials callCredentials) {
        mInstanceName = instanceName;
        mChannel = channel;
        mCallCredentials = callCredentials;
    }

    /** {@inheritDoc} */
    @Override
    public void uploadCache(ExecutableAction action, ExecutableActionResult actionResult) {
        throw new UnsupportedOperationException("Not implemented feature.");
    }

    /** {@inheritDoc} */
    @Override
    public ExecutableActionResult lookupCache(ExecutableAction action)
            throws IOException, InterruptedException {
        ActionResult actionResult = null;
        try (CloseableTraceScope ignored = new CloseableTraceScope("getActionResult")) {
            actionResult =
                    getFromFuture(
                            Futures.catchingAsync(
                                    acFutureStub()
                                            .getActionResult(
                                                    GetActionResultRequest.newBuilder()
                                                            .setInstanceName(mInstanceName)
                                                            .setDigestFunction(
                                                                    DigestCalculator
                                                                            .DIGEST_FUNCTION)
                                                            .setActionDigest(action.actionDigest())
                                                            .setInlineStderr(false)
                                                            .setInlineStdout(false)
                                                            .build()),
                                    StatusRuntimeException.class,
                                    (sre) ->
                                            sre.getStatus().getCode() == Code.NOT_FOUND
                                                    // Return null to indicate that it was a cache
                                                    // miss.
                                                    ? Futures.immediateFuture(null)
                                                    : Futures.immediateFailedFuture(
                                                            new IOException(sre)),
                                    MoreExecutors.directExecutor()));
        }

        if (actionResult == null) {
            return null;
        }
        // TODO(b/338141320): Download the stdout&stderr and add them into ExecutableActionResult.
        return ExecutableActionResult.create(actionResult.getExitCode(), null, null);
    }

    private ActionCacheFutureStub acFutureStub() {
        ActionCacheFutureStub stub =
                ActionCacheGrpc.newFutureStub(mChannel)
                        .withDeadlineAfter(REMOTE_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        return mCallCredentials != null ? stub.withCallCredentials(mCallCredentials) : stub;
    }

    private static <T> T getFromFuture(ListenableFuture<T> f)
            throws IOException, InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                f.cancel(true);
                throw (InterruptedException) cause;
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(cause);
        } catch (InterruptedException e) {
            f.cancel(true);
            throw e;
        }
    }
}
