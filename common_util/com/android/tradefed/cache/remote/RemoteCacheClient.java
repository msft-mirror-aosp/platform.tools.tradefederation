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
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.util.FileUtil;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** A RemoteActionCache implementation that uses gRPC calls to a remote API server. */
public class RemoteCacheClient implements ICacheClient {
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(60);
    private final File mWorkFolder;
    private final String mInstanceName;
    private final ManagedChannel mChannel;
    private final CallCredentials mCallCredentials;
    private final ByteStreamDownloader mDownloader;

    public RemoteCacheClient(
            File workFolder,
            String instanceName,
            ManagedChannel channel,
            CallCredentials callCredentials,
            ByteStreamDownloader downloader) {
        mWorkFolder = workFolder;
        mInstanceName = instanceName;
        mChannel = channel;
        mCallCredentials = callCredentials;
        mDownloader = downloader;
    }

    /** {@inheritDoc} */
    @Override
    public void uploadCache(ExecutableAction action, ExecutableActionResult actionResult)
            throws IOException, InterruptedException {
        ActionResult.Builder actionResultBuilder =
                ActionResult.newBuilder().setExitCode(actionResult.exitCode());

        if (actionResult.stdOut() != null) {
            actionResultBuilder.setStdoutDigest(DigestCalculator.compute(actionResult.stdOut()));
        }

        if (actionResult.stdErr() != null) {
            actionResultBuilder.setStderrDigest(DigestCalculator.compute(actionResult.stdErr()));
        }

        getFromFuture(
                Futures.catchingAsync(
                        acFutureStub()
                                .updateActionResult(
                                        UpdateActionResultRequest.newBuilder()
                                                .setInstanceName(mInstanceName)
                                                .setDigestFunction(DigestCalculator.DIGEST_FUNCTION)
                                                .setActionDigest(action.actionDigest())
                                                .setActionResult(actionResultBuilder.build())
                                                .build()),
                        StatusRuntimeException.class,
                        (sre) -> Futures.immediateFailedFuture(new IOException(sre)),
                        MoreExecutors.directExecutor()));
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

        File stdout = null;
        OutputStream stdoutStream = null;
        File stderr = null;
        OutputStream stderrStream = null;
        try (CloseableTraceScope ignored = new CloseableTraceScope("download outputs")) {
            List<ListenableFuture<Void>> downloads = new ArrayList<>();
            Digest stdoutDigest = actionResult.getStdoutDigest();
            if (!stdoutDigest.equals(Digest.getDefaultInstance())) {
                stdout =
                        FileUtil.createTempFile(
                                String.format("cached-stdout-%s", stdoutDigest.getHash()),
                                ".txt",
                                mWorkFolder);
                stdoutStream = new FileOutputStream(stdout);
                downloads.add(mDownloader.downloadBlob(stdoutDigest, stdoutStream));
            }
            Digest stderrDigest = actionResult.getStderrDigest();
            if (!actionResult.getStderrDigest().equals(Digest.getDefaultInstance())) {
                stderr =
                        FileUtil.createTempFile(
                                String.format("cached-stderr-%s", stderrDigest.getHash()),
                                ".txt",
                                mWorkFolder);
                stderrStream = new FileOutputStream(stderr);
                downloads.add(mDownloader.downloadBlob(stderrDigest, stderrStream));
            }
            // TODO(b/346606200): Track download metrics.
            waitForDownloads(downloads);
        } finally {
            if (stdoutStream != null) {
                stdoutStream.close();
            }
            if (stderrStream != null) {
                stderrStream.close();
            }
        }
        return ExecutableActionResult.create(actionResult.getExitCode(), stdout, stderr);
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

    private static void waitForDownloads(Iterable<? extends ListenableFuture<?>> downloads)
            throws IOException, InterruptedException {
        boolean interrupted = Thread.currentThread().isInterrupted();
        InterruptedException interruptedException = null;
        for (ListenableFuture<?> download : downloads) {
            try {
                getFromFuture(download);
            } catch (InterruptedException e) {
                interrupted = Thread.interrupted() || interrupted;
                interruptedException = e;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (interruptedException != null) {
            throw interruptedException;
        }
    }
}
