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
import com.android.tradefed.log.LogUtil.CLog;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.base.Strings;
import com.google.common.io.CountingOutputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** A client implementing the {@code Read} method of the {@code ByteStream} gRPC service. */
public class ByteStreamDownloader {

    private final String mInstanceName;
    private final Channel mChannel;
    private final CallCredentials mCallCredentials;
    private final Duration mCallTimeout;

    public ByteStreamDownloader(
            String instanceName,
            Channel channel,
            CallCredentials callCredentials,
            Duration callTimeout) {
        checkArgument(callTimeout.getSeconds() > 0, "callTimeout must be gt 0.");
        checkArgument(!Strings.isNullOrEmpty(instanceName), "instanceName must be specified.");
        mInstanceName = instanceName;
        mChannel = channel;
        mCallCredentials = callCredentials;
        mCallTimeout = callTimeout;
    }

    /**
     * Downloads a BLOB by the remote {@code ByteStream} service.
     *
     * @param digest the digest of the BLOB to download.
     * @param out the {@link OutputStream} where the BLOB is downloaded.
     */
    public ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
        if (digest.getSizeBytes() == 0) {
            return Futures.immediateVoidFuture();
        }

        return Futures.catchingAsync(
                Futures.transformAsync(
                        read(digest, new CountingOutputStream(out)),
                        bytesRead -> {
                            if (bytesRead != digest.getSizeBytes()) {
                                return Futures.immediateFailedFuture(
                                        new IOException(
                                                String.format(
                                                        "Read incomplete: read size %d for %d total"
                                                                + " - %s",
                                                        bytesRead,
                                                        digest.getSizeBytes(),
                                                        getResourceName(digest))));
                            }
                            return Futures.immediateVoidFuture();
                        },
                        MoreExecutors.directExecutor()),
                StatusRuntimeException.class,
                (e) -> Futures.immediateFailedFuture(new IOException(e)),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Long> read(Digest digest, CountingOutputStream out) {
        SettableFuture<Long> future = SettableFuture.create();
        bsAsyncStub()
                .read(
                        ReadRequest.newBuilder()
                                .setResourceName(getResourceName(digest))
                                .setReadOffset(out.getCount())
                                .build(),
                        new StreamObserver<ReadResponse>() {
                            @Override
                            public void onNext(ReadResponse readResponse) {
                                try {
                                    readResponse.getData().writeTo(out);
                                } catch (IOException e) {
                                    future.setException(e);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                if (out.getCount() == digest.getSizeBytes()) {
                                    // If the file was fully downloaded, it doesn't matter if there
                                    // was an error at the end of the stream.
                                    CLog.w("ignoring error because file was fully received: %s", t);
                                    onCompleted();
                                    return;
                                }

                                future.setException(t);
                            }

                            @Override
                            public void onCompleted() {
                                try {
                                    out.flush();
                                } catch (IOException e) {
                                    future.setException(e);
                                } catch (RuntimeException e) {
                                    CLog.e("Unexpected exception: %s", e);
                                    future.setException(e);
                                }
                                future.set(out.getCount());
                            }
                        });

        return future;
    }

    private ByteStreamStub bsAsyncStub() {
        return ByteStreamGrpc.newStub(mChannel)
                .withCallCredentials(mCallCredentials)
                .withDeadlineAfter(mCallTimeout.getSeconds(), TimeUnit.SECONDS);
    }

    private String getResourceName(Digest digest) {
        return String.format(
                "%s/blobs/%s/%d", mInstanceName, digest.getHash(), digest.getSizeBytes());
    }
}
