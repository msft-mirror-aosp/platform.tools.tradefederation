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

import static com.google.common.base.Preconditions.checkArgument;

import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.cache.MerkleTree;
import com.android.tradefed.cache.UploadManifest;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheFutureStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
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
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** A RemoteActionCache implementation that uses gRPC calls to a remote API server. */
public class RemoteCacheClient implements ICacheClient {
    public static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(60);
    private final File mWorkFolder;
    private final String mInstanceName;
    private final ManagedChannel mChannel;
    private final CallCredentials mCallCredentials;
    private final ByteStreamDownloader mDownloader;
    private final ByteStreamUploader mUploader;
    private final int mMaxMissingBlobsDigestsPerMessage;

    public RemoteCacheClient(
            File workFolder,
            String instanceName,
            ManagedChannel channel,
            CallCredentials callCredentials,
            ByteStreamDownloader downloader,
            ByteStreamUploader uploader) {
        checkArgument(
                workFolder.exists() && workFolder.isDirectory(),
                "a work folder must be specified.");
        checkArgument(!Strings.isNullOrEmpty(instanceName), "instanceName must be specified.");
        mWorkFolder = workFolder;
        mInstanceName = instanceName;
        mChannel = channel;
        mCallCredentials = callCredentials;
        mDownloader = downloader;
        mUploader = uploader;
        mMaxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    }

    /** {@inheritDoc} */
    @Override
    public void uploadCache(ExecutableAction action, ExecutableActionResult actionResult)
            throws IOException, InterruptedException {
        MerkleTree input = action.input();
        UploadManifest.Builder manifestBuilder =
                UploadManifest.builder()
                        .addFiles(input.digestToFile())
                        .addBlob(input.rootDigest(), input.root().toByteString())
                        .addBlobs(
                                input.digestToSubdir().entrySet().stream()
                                        .collect(
                                                Collectors.toMap(
                                                        Entry::getKey,
                                                        e -> e.getValue().toByteString())))
                        .addBlob(action.commandDigest(), action.command().toByteString())
                        .addBlob(action.actionDigest(), action.action().toByteString());
        ActionResult.Builder actionResultBuilder =
                ActionResult.newBuilder().setExitCode(actionResult.exitCode());

        if (actionResult.stdOut() != null) {
            Digest stdOutDigest = DigestCalculator.compute(actionResult.stdOut());
            actionResultBuilder.setStdoutDigest(stdOutDigest);
            manifestBuilder.addFile(stdOutDigest, actionResult.stdOut());
        }

        if (actionResult.stdErr() != null) {
            Digest stdErrDigest = DigestCalculator.compute(actionResult.stdErr());
            actionResultBuilder.setStderrDigest(stdErrDigest);
            manifestBuilder.addFile(stdErrDigest, actionResult.stdErr());
        }

        UploadManifest manifest = manifestBuilder.build();

        try (CloseableTraceScope ignored = new CloseableTraceScope("upload blobs")) {
            List<Digest> digests = new ArrayList<>();
            digests.addAll(manifest.digestToFile().keySet());
            digests.addAll(manifest.digestToBlob().keySet());
            ImmutableSet<Digest> missingDigests = getFromFuture(findMissingDigests(digests));

            List<ListenableFuture<Void>> uploads = new ArrayList<>();
            uploads.addAll(
                    manifest.digestToFile().entrySet().stream()
                            .filter(e -> missingDigests.contains(e.getKey()))
                            .map(e -> mUploader.uploadFile(e.getKey(), e.getValue()))
                            .collect(Collectors.toList()));
            uploads.addAll(
                    manifest.digestToBlob().entrySet().stream()
                            .filter(e -> missingDigests.contains(e.getKey()))
                            .map(e -> mUploader.uploadBlob(e.getKey(), e.getValue()))
                            .collect(Collectors.toList()));
            waitForBulkTransfers(uploads);
        }

        try (CloseableTraceScope ignored = new CloseableTraceScope("update action result")) {
            getFromFuture(
                    Futures.catchingAsync(
                            acFutureStub()
                                    .updateActionResult(
                                            UpdateActionResultRequest.newBuilder()
                                                    .setInstanceName(mInstanceName)
                                                    .setDigestFunction(
                                                            DigestCalculator.DIGEST_FUNCTION)
                                                    .setActionDigest(action.actionDigest())
                                                    .setActionResult(actionResultBuilder.build())
                                                    .build()),
                            StatusRuntimeException.class,
                            (sre) -> Futures.immediateFailedFuture(new IOException(sre)),
                            MoreExecutors.directExecutor()));
        }
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
            waitForBulkTransfers(downloads);
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

    private ListenableFuture<ImmutableSet<Digest>> findMissingDigests(Iterable<Digest> digests) {
        if (Iterables.isEmpty(digests)) {
            return Futures.immediateFuture(ImmutableSet.of());
        }
        // Need to potentially split the digests into multiple requests.
        FindMissingBlobsRequest.Builder requestBuilder =
                FindMissingBlobsRequest.newBuilder()
                        .setInstanceName(mInstanceName)
                        .setDigestFunction(DigestCalculator.DIGEST_FUNCTION);
        List<ListenableFuture<FindMissingBlobsResponse>> getMissingDigestCalls = new ArrayList<>();
        for (Digest digest : digests) {
            requestBuilder.addBlobDigests(digest);
            if (requestBuilder.getBlobDigestsCount() == mMaxMissingBlobsDigestsPerMessage) {
                getMissingDigestCalls.add(getMissingDigests(requestBuilder.build()));
                requestBuilder.clearBlobDigests();
            }
        }

        if (requestBuilder.getBlobDigestsCount() > 0) {
            getMissingDigestCalls.add(getMissingDigests(requestBuilder.build()));
        }

        ListenableFuture<ImmutableSet<Digest>> success =
                Futures.whenAllSucceed(getMissingDigestCalls)
                        .call(
                                () -> {
                                    ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
                                    for (ListenableFuture<FindMissingBlobsResponse> callFuture :
                                            getMissingDigestCalls) {
                                        result.addAll(callFuture.get().getMissingBlobDigestsList());
                                    }
                                    return result.build();
                                },
                                MoreExecutors.directExecutor());

        return Futures.catchingAsync(
                success,
                RuntimeException.class,
                (e) ->
                        Futures.immediateFailedFuture(
                                new IOException(
                                        String.format(
                                                "Failed to find missing blobs: %s", e.getMessage()),
                                        e)),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
            FindMissingBlobsRequest request) {
        return casFutureStub().findMissingBlobs(request);
    }

    private ContentAddressableStorageFutureStub casFutureStub() {
        return ContentAddressableStorageGrpc.newFutureStub(mChannel)
                .withCallCredentials(mCallCredentials)
                .withDeadlineAfter(REMOTE_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
    }

    private int computeMaxMissingBlobsDigestsPerMessage() {
        final int overhead =
                FindMissingBlobsRequest.newBuilder()
                        .setInstanceName(mInstanceName)
                        .setDigestFunction(DigestCalculator.DIGEST_FUNCTION)
                        .build()
                        .getSerializedSize();
        final int tagSize =
                FindMissingBlobsRequest.newBuilder()
                                .addBlobDigests(Digest.getDefaultInstance())
                                .build()
                                .getSerializedSize()
                        - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
        // All non-empty digests of SHA256 have the same size.
        final int digestSize =
                DigestCalculator.compute(new byte[] {1}).getSerializedSize() + tagSize;
        // Set the max message size to 1MB that is used by Bazel (The default max message size is
        // 4MB).
        return (1024 * 1024 - overhead) / digestSize;
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

    private static void waitForBulkTransfers(Iterable<? extends ListenableFuture<?>> transfers)
            throws IOException, InterruptedException {
        boolean interrupted = Thread.currentThread().isInterrupted();
        InterruptedException interruptedException = null;
        for (ListenableFuture<?> transfer : transfers) {
            try {
                getFromFuture(transfer);
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
