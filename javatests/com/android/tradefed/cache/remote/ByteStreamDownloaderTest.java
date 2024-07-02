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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.Digest;
import com.android.tradefed.cache.DigestCalculator;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Tests for {@link ByteStreamDownloader}. */
@RunWith(JUnit4.class)
public class ByteStreamDownloaderTest {
    private static final String INSTANCE = "test instance";
    private final String mFakeServerName = "fake server for " + getClass();
    private final MutableHandlerRegistry mServiceRegistry = new MutableHandlerRegistry();
    private ManagedChannel mChannel;
    private Server mFakeServer;

    @Before
    public final void setUp() throws Exception {
        mFakeServer =
                InProcessServerBuilder.forName(mFakeServerName)
                        .fallbackHandlerRegistry(mServiceRegistry)
                        .directExecutor()
                        .build()
                        .start();
        mChannel = InProcessChannelBuilder.forName(mFakeServerName).directExecutor().build();
    }

    @After
    public void tearDown() throws Exception {
        mChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        mFakeServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void downloadBlob_works() throws InterruptedException, ExecutionException {
        String blob = "test data";
        Digest digest = DigestCalculator.compute(blob.getBytes(UTF_8));
        String resourceName =
                String.format("%s/blobs/%s/%d", INSTANCE, digest.getHash(), digest.getSizeBytes());
        mServiceRegistry.addService(
                new ByteStreamImplBase() {
                    @Override
                    public void read(
                            ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
                        if (!request.getResourceName().equals(resourceName)) {
                            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
                            return;
                        }
                        responseObserver.onNext(
                                ReadResponse.newBuilder()
                                        .setData(ByteString.copyFromUtf8(blob))
                                        .build());
                        responseObserver.onCompleted();
                    }
                });
        ByteStreamDownloader downloader = newDownloader();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ListenableFuture<Void> notFound =
                downloader.downloadBlob(
                        DigestCalculator.compute("not found blob".getBytes(UTF_8)),
                        new ByteArrayOutputStream());
        ListenableFuture<Void> downloaded = downloader.downloadBlob(digest, out);
        downloaded.get();

        assertThrows(ExecutionException.class, () -> notFound.get());
        assertEquals(out.toString(), blob);
    }

    @Test
    public void downloadBlob_ignore_error_after_fully_downloaded()
            throws InterruptedException, ExecutionException {
        String blob = "test data";
        mServiceRegistry.addService(
                new ByteStreamImplBase() {
                    @Override
                    public void read(
                            ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
                        responseObserver.onNext(
                                ReadResponse.newBuilder()
                                        .setData(ByteString.copyFromUtf8(blob))
                                        .build());
                        responseObserver.onError(Status.FAILED_PRECONDITION.asRuntimeException());
                    }
                });
        ByteStreamDownloader downloader = newDownloader();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ListenableFuture<Void> ignoredError =
                downloader.downloadBlob(DigestCalculator.compute(blob.getBytes(UTF_8)), out);
        ignoredError.get();

        assertEquals(out.toString(), blob);
    }

    private ByteStreamDownloader newDownloader() {
        return new ByteStreamDownloader(INSTANCE, mChannel, null, Duration.ofSeconds(5));
    }
}
