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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import build.bazel.remote.execution.v2.Digest;
import com.android.tradefed.cache.DigestCalculator;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Tests for {@link ByteStreamUploader}. */
@RunWith(JUnit4.class)
public class ByteStreamUploaderTest {
    private static final String INSTANCE = "test instance";
    private final String mFakeServerName = "fake server for " + getClass();
    private final MutableHandlerRegistry mServiceRegistry = new MutableHandlerRegistry();
    private ManagedChannel mChannel;
    private Server mFakeServer;
    private int mChunkSize = 10;

    private static class FakeByteStreamService extends ByteStreamImplBase {
        private long mNextOffset = 0;
        public byte[] receivedData;
        public String receivedResourceName = null;
        public int requestCount = 0;

        private FakeByteStreamService(int bufferSize) {
            this.receivedData = new byte[bufferSize];
        }

        @Override
        public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
                @Override
                public void onNext(WriteRequest writeRequest) {
                    if (mNextOffset == 0) {
                        receivedResourceName = writeRequest.getResourceName();
                    }
                    ByteString data = writeRequest.getData();
                    System.arraycopy(
                            data.toByteArray(), 0, receivedData, (int) mNextOffset, data.size());
                    mNextOffset += data.size();
                    requestCount++;
                }

                @Override
                public void onError(Throwable throwable) {
                    fail("onError should never be called.");
                }

                @Override
                public void onCompleted() {
                    assertEquals(mNextOffset, receivedData.length);
                    streamObserver.onNext(
                            WriteResponse.newBuilder().setCommittedSize(mNextOffset).build());
                    streamObserver.onCompleted();
                }
            };
        }
    }

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
    public void uploadBlob_single_blob_works() throws InterruptedException, ExecutionException {
        int bufferSize = 10;
        byte[] blob = new byte[bufferSize];
        new Random().nextBytes(blob);
        Digest digest = DigestCalculator.compute(blob);
        FakeByteStreamService service = new FakeByteStreamService(bufferSize);
        mServiceRegistry.addService(service);
        mChunkSize = 11;

        newUploader().uploadBlob(digest, ByteString.copyFrom(blob)).get();

        assertArrayEquals(service.receivedData, blob);
        assertTrue(service.receivedResourceName.startsWith(INSTANCE + "/uploads"));
        assertTrue(
                service.receivedResourceName.endsWith(
                        digest.getHash() + "/" + String.valueOf(bufferSize)));
    }

    @Test
    public void uploadBlob_multiple_blobs_works() throws InterruptedException, ExecutionException {
        int bufferSize = 10;
        byte[] blob = new byte[bufferSize];
        new Random().nextBytes(blob);
        FakeByteStreamService service = new FakeByteStreamService(bufferSize);
        mServiceRegistry.addService(service);
        mChunkSize = 3;

        newUploader().uploadBlob(DigestCalculator.compute(blob), ByteString.copyFrom(blob)).get();

        assertArrayEquals(service.receivedData, blob);
        assertEquals(service.requestCount, 4);
    }

    @Test
    public void uploadBlob_incorrect_committed_size_fails()
            throws InterruptedException, ExecutionException {
        byte[] blob = new byte[10];
        new Random().nextBytes(blob);
        mServiceRegistry.addService(
                new ByteStreamImplBase() {
                    @Override
                    public StreamObserver<WriteRequest> write(
                            StreamObserver<WriteResponse> streamObserver) {
                        return new StreamObserver<WriteRequest>() {
                            @Override
                            public void onNext(WriteRequest writeRequest) {}

                            @Override
                            public void onError(Throwable throwable) {
                                fail("onError should never be called.");
                            }

                            @Override
                            public void onCompleted() {
                                streamObserver.onNext(
                                        WriteResponse.newBuilder()
                                                .setCommittedSize(blob.length + 1)
                                                .build());
                                streamObserver.onCompleted();
                            }
                        };
                    }
                });

        ListenableFuture<Void> future =
                newUploader().uploadBlob(DigestCalculator.compute(blob), ByteString.copyFrom(blob));

        assertThrows(ExecutionException.class, () -> future.get());
    }

    @Test
    public void uploadBlob_early_response_should_not_fail()
            throws InterruptedException, ExecutionException {
        byte[] blob = new byte[10];
        new Random().nextBytes(blob);
        mServiceRegistry.addService(
                new ByteStreamImplBase() {
                    @Override
                    public StreamObserver<WriteRequest> write(
                            StreamObserver<WriteResponse> streamObserver) {
                        return new StreamObserver<WriteRequest>() {
                            @Override
                            public void onNext(WriteRequest writeRequest) {
                                // On receiving the chunk, respond with the full size of the
                                // uploaded file immediately without error to indicate that the blob
                                // already exists (per the remote API spec) and close the stream.
                                streamObserver.onNext(
                                        WriteResponse.newBuilder()
                                                .setCommittedSize(blob.length)
                                                .build());
                                streamObserver.onCompleted();
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                fail("onError should never be called.");
                            }

                            @Override
                            public void onCompleted() {}
                        };
                    }
                });

        newUploader().uploadBlob(DigestCalculator.compute(blob), ByteString.copyFrom(blob)).get();
    }

    private ByteStreamUploader newUploader() {
        return new ByteStreamUploader(INSTANCE, mChannel, null, Duration.ofSeconds(5), mChunkSize);
    }
}
