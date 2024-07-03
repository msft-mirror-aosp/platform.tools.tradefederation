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

import build.bazel.remote.execution.v2.Digest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/** A client implementing the {@code Write} method of the {@code ByteStream} gRPC service. */
public class ByteStreamUploader {

    // Uses 16KB as the default chunk size that is also used by Bazel.
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 16;
    private final String mInstanceName;
    private final Channel mChannel;
    private final CallCredentials mCallCredentials;
    private final Duration mCallTimeout;
    private final int mChunkSize;

    public ByteStreamUploader(
            String instanceName,
            Channel channel,
            CallCredentials callCredentials,
            Duration callTimeout) {
        this(instanceName, channel, callCredentials, callTimeout, DEFAULT_CHUNK_SIZE);
    }

    @VisibleForTesting
    ByteStreamUploader(
            String instanceName,
            Channel channel,
            CallCredentials callCredentials,
            Duration callTimeout,
            int chunkSize) {
        checkArgument(callTimeout.getSeconds() > 0, "callTimeout must be greater than 0.");
        checkArgument(!Strings.isNullOrEmpty(instanceName), "instanceName must be specified.");
        mInstanceName = instanceName;
        mChannel = channel;
        mCallCredentials = callCredentials;
        mCallTimeout = callTimeout;
        mChunkSize = chunkSize;
    }

    /**
     * Uploads a BLOB by the remote {@code ByteStream} service.
     *
     * @param digest the digest of the BLOB to upload.
     * @param blob the BLOB to upload.
     */
    public ListenableFuture<Void> uploadBlob(Digest digest, ByteString blob) {
        return uploadBlob(digest, new Chunker(blob.newInput(), digest.getSizeBytes(), mChunkSize));
    }

    /**
     * Uploads a file by the remote {@code ByteStream} service.
     *
     * @param digest the digest of the file to upload.
     * @param file the file to upload.
     */
    public ListenableFuture<Void> uploadFile(Digest digest, File file) {
        try {
            return uploadBlob(
                    digest,
                    new Chunker(new FileInputStream(file), digest.getSizeBytes(), mChunkSize));
        } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    private ListenableFuture<Void> uploadBlob(Digest digest, Chunker chunker) {
        String resourceName = getResourceName(digest);
        return Futures.catchingAsync(
                Futures.transformAsync(
                        write(resourceName, chunker),
                        committedSize ->
                                committedSize == digest.getSizeBytes()
                                        ? Futures.immediateVoidFuture()
                                        : Futures.immediateFailedFuture(
                                                new IOException(
                                                        String.format(
                                                                "write incomplete: committed_size"
                                                                        + " %d for %d total - %s",
                                                                committedSize,
                                                                digest.getSizeBytes(),
                                                                resourceName))),
                        MoreExecutors.directExecutor()),
                StatusRuntimeException.class,
                (sre) ->
                        sre.getStatus().getCode() == Code.ALREADY_EXISTS
                                ? Futures.immediateVoidFuture()
                                : Futures.immediateFailedFuture(
                                        new IOException(
                                                String.format(
                                                        "Error while uploading artifact with digest"
                                                                + " '%s/%s'",
                                                        digest.getHash(), digest.getSizeBytes()),
                                                sre)),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Long> write(String resourceName, Chunker chunker) {
        SettableFuture<Long> uploadResult = SettableFuture.create();
        bsAsyncStub().write(new Writer(resourceName, uploadResult, chunker));
        return uploadResult;
    }

    /** A writer used to stream the BLOB to the remote service and handle the response. */
    private static final class Writer
            implements ClientResponseObserver<WriteRequest, WriteResponse>, Runnable {
        private final String mResourceName;
        private final SettableFuture<Long> mUploadResult;
        private final Chunker mChunker;
        private ClientCallStreamObserver<WriteRequest> mRequestObserver;
        private long mCommittedSize = -1;
        private boolean mFirstRequest = true;
        private boolean mFinishedWriting;

        private Writer(String resourceName, SettableFuture<Long> uploadResult, Chunker chunker) {
            mResourceName = resourceName;
            mUploadResult = uploadResult;
            mChunker = chunker;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<WriteRequest> requestObserver) {
            mRequestObserver = requestObserver;
            mUploadResult.addListener(
                    () -> {
                        if (mUploadResult.isCancelled()) {
                            mRequestObserver.cancel("cancelled by user", null);
                        }
                    },
                    MoreExecutors.directExecutor());
            mRequestObserver.setOnReadyHandler(this);
        }

        @Override
        public void run() {
            while (mRequestObserver.isReady()) {
                WriteRequest.Builder request = WriteRequest.newBuilder();
                if (mFirstRequest) {
                    // Resource name only needs to be set on the first write for each file.
                    request.setResourceName(mResourceName);
                    mFirstRequest = false;
                }
                Chunker.Chunk chunk;
                try {
                    chunk = mChunker.next();
                } catch (IOException e) {
                    mRequestObserver.cancel("Failed to read next chunk.", e);
                    return;
                }
                boolean isLastChunk = !mChunker.hasNext();
                mRequestObserver.onNext(
                        request.setData(chunk.getData())
                                .setWriteOffset(chunk.getOffset())
                                .setFinishWrite(isLastChunk)
                                .build());
                if (isLastChunk) {
                    mRequestObserver.onCompleted();
                    mFinishedWriting = true;
                }
            }
        }

        @Override
        public void onNext(WriteResponse response) {
            mCommittedSize = response.getCommittedSize();
        }

        @Override
        public void onError(Throwable t) {
            mUploadResult.setException(t);
        }

        @Override
        public void onCompleted() {
            // Server completed successfully before we finished writing all the data, meaning the
            // blob already exists.
            if (mFinishedWriting) {
                mRequestObserver.cancel("server has returned early", null);
            }
            mUploadResult.set(mCommittedSize);
        }
    }

    private ByteStreamStub bsAsyncStub() {
        return ByteStreamGrpc.newStub(mChannel)
                .withCallCredentials(mCallCredentials)
                .withDeadlineAfter(mCallTimeout.getSeconds(), TimeUnit.SECONDS);
    }

    private String getResourceName(Digest digest) {
        return String.format(
                "%s/uploads/%s/blobs/%s/%d",
                mInstanceName, UUID.randomUUID(), digest.getHash(), digest.getSizeBytes());
    }
}
