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
import static org.junit.Assert.assertNull;

import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheImplBase;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.MerkleTreeTest;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Tests for {@link RemoteCacheClient}. */
@RunWith(JUnit4.class)
public class RemoteCacheClientTest {
    private static final String INSTANCE = "test instance";
    private final String mFakeServerName = "fake server for " + getClass();
    private final MutableHandlerRegistry mServiceRegistry = new MutableHandlerRegistry();
    private ManagedChannel mChannel;
    private Server mFakeServer;
    private File mInput;
    private File mWorkFolder;

    private static class FakeByteStreamDownloader extends ByteStreamDownloader {
        private final Map<Digest, String> mData;

        public FakeByteStreamDownloader() {
            this(new HashMap<>());
        }

        public FakeByteStreamDownloader(Map<Digest, String> data) {
            super(INSTANCE, null, null, Duration.ofSeconds(5));
            mData = data;
        }

        @Override
        public ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
            try {
                if (digest.getSizeBytes() == 0) {
                    out.close();
                    return Futures.immediateVoidFuture();
                }
                if (!mData.containsKey(digest)) {
                    out.close();
                    return Futures.immediateFailedFuture(new IOException("Blob not found!"));
                }
                InputStream data = new ByteArrayInputStream(mData.get(digest).getBytes(UTF_8));
                StreamUtil.copyStreams(data, out);
                out.close();
                data.close();
            } catch (IOException e) {
                return Futures.immediateFailedFuture(e);
            }
            return Futures.immediateVoidFuture();
        }
    }

    private static class FakeByteStreamUploader extends ByteStreamUploader {
        public final Map<Digest, ByteString> blobs = new HashMap<>();

        public FakeByteStreamUploader() {
            super(INSTANCE, null, null, Duration.ofSeconds(5));
        }

        @Override
        public ListenableFuture<Void> uploadFile(Digest digest, File file) {
            try {
                blobs.put(digest, ByteString.copyFromUtf8(FileUtil.readStringFromFile(file)));
            } catch (IOException e) {
                return Futures.immediateFailedFuture(e);
            }
            return Futures.immediateVoidFuture();
        }

        @Override
        public ListenableFuture<Void> uploadBlob(Digest digest, ByteString blob) {
            blobs.put(digest, blob);
            return Futures.immediateVoidFuture();
        }
    }

    private static class DefaultContentAddressableStorage
            extends ContentAddressableStorageImplBase {
        @Override
        public void findMissingBlobs(
                FindMissingBlobsRequest request,
                StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(
                    FindMissingBlobsResponse.newBuilder()
                            .addAllMissingBlobDigests(request.getBlobDigestsList())
                            .build());
            responseObserver.onCompleted();
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
        mInput = FileUtil.createTempDir("input-dir");
        mWorkFolder = FileUtil.createTempDir("work-folder");
    }

    @After
    public void tearDown() throws Exception {
        mChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        mFakeServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        FileUtil.recursiveDelete(mInput);
        FileUtil.recursiveDelete(mWorkFolder);
    }

    @Test
    public void uploadCache_works() throws IOException, InterruptedException {
        class SpyActionCacheImpl extends ActionCacheImplBase {
            public ActionResult actionResult = null;
            public Digest actionDigest = null;

            @Override
            public void updateActionResult(
                    UpdateActionResultRequest request,
                    StreamObserver<ActionResult> responseObserver) {
                actionResult = request.getActionResult();
                actionDigest = request.getActionDigest();
                responseObserver.onNext(actionResult);
                responseObserver.onCompleted();
            }
        }
        SpyActionCacheImpl actionCache = new SpyActionCacheImpl();
        mServiceRegistry.addService(actionCache);
        mServiceRegistry.addService(new DefaultContentAddressableStorage());
        ExecutableAction action =
                ExecutableAction.create(
                        mInput, Arrays.asList("test", "command"), new HashMap<>(), 100L);
        int exitCode = 0;
        File stdoutFile = FileUtil.createTempFile("stdout-", ".txt", mWorkFolder);
        String stdout = "test stdout";
        FileUtil.writeToFile(stdout, stdoutFile);
        ExecutableActionResult result = ExecutableActionResult.create(exitCode, stdoutFile, null);
        RemoteCacheClient client =
                newClient(new FakeByteStreamDownloader(), new FakeByteStreamUploader());
        ActionResult expectedResult =
                ActionResult.newBuilder()
                        .setExitCode(exitCode)
                        .setStdoutDigest(DigestCalculator.compute(stdoutFile))
                        .build();

        client.uploadCache(action, result);

        assertEquals(actionCache.actionResult, expectedResult);
        assertEquals(actionCache.actionDigest, DigestCalculator.compute(action.action()));
    }

    @Test
    public void uploadCache_all_files_and_blobs_are_uploaded_except_existing_blobs()
            throws IOException, InterruptedException {
        mServiceRegistry.addService(
                new ActionCacheImplBase() {
                    @Override
                    public void updateActionResult(
                            UpdateActionResultRequest request,
                            StreamObserver<ActionResult> responseObserver) {
                        responseObserver.onNext(request.getActionResult());
                        responseObserver.onCompleted();
                    }
                });
        File configFile = new File(mInput, "hello_world_test.config");
        String config = "test config";
        MerkleTreeTest.addFile(configFile, config, false);
        Digest configFileDigest = DigestCalculator.compute(configFile);
        File x86 = new File(mInput, "x86");
        File testFile = new File(x86, "hello_world_test");
        String test = "test cases";
        MerkleTreeTest.addFile(testFile, test, true);
        File existingDataFile = new File(x86, "existing_test_data");
        MerkleTreeTest.addFile(existingDataFile, "test data", false);
        Digest existingDataFileDigest = DigestCalculator.compute(existingDataFile);
        Digest testFileDigest = DigestCalculator.compute(testFile);
        Directory x86Dir =
                Directory.newBuilder()
                        .addFiles(
                                MerkleTreeTest.newFileNode(
                                        "existing_test_data", existingDataFileDigest, false))
                        .addFiles(
                                MerkleTreeTest.newFileNode(
                                        "hello_world_test", testFileDigest, true))
                        .build();
        Digest x86Digest = DigestCalculator.compute(x86Dir);
        Directory inputRoot =
                Directory.newBuilder()
                        .addFiles(
                                MerkleTreeTest.newFileNode(
                                        "hello_world_test.config", configFileDigest, false))
                        .addDirectories(
                                DirectoryNode.newBuilder().setName("x86").setDigest(x86Digest))
                        .build();
        ExecutableAction action =
                ExecutableAction.create(
                        mInput, Arrays.asList("test", "command"), new HashMap<>(), 100L);
        File stdoutFile = FileUtil.createTempFile("stdout-", ".txt", mWorkFolder);
        String stdout = "test stdout";
        FileUtil.writeToFile(stdout, stdoutFile);
        File stderrFile = FileUtil.createTempFile("stderr-", ".txt", mWorkFolder);
        String stderr = "test stderr";
        FileUtil.writeToFile(stderr, stderrFile);
        ExecutableActionResult result = ExecutableActionResult.create(0, stdoutFile, stderrFile);
        FakeByteStreamUploader uploader = new FakeByteStreamUploader();
        RemoteCacheClient client = newClient(new FakeByteStreamDownloader(), uploader);
        mServiceRegistry.addService(
                new ContentAddressableStorageImplBase() {
                    @Override
                    public void findMissingBlobs(
                            FindMissingBlobsRequest request,
                            StreamObserver<FindMissingBlobsResponse> responseObserver) {
                        responseObserver.onNext(
                                FindMissingBlobsResponse.newBuilder()
                                        .addAllMissingBlobDigests(
                                                request.getBlobDigestsList().stream()
                                                        // Assume that the test data file and the
                                                        // Command message already exist.
                                                        .filter(
                                                                d ->
                                                                        !d.equals(
                                                                                        existingDataFileDigest)
                                                                                && !d.equals(
                                                                                        action
                                                                                                .commandDigest()))
                                                        .collect(Collectors.toList()))
                                        .build());
                        responseObserver.onCompleted();
                    }
                });
        // The test data file and the Command message should not be in the output.
        Map<Digest, ByteString> expectedDigestToBlob =
                Map.of(
                        DigestCalculator.compute(stdoutFile),
                        ByteString.copyFromUtf8(stdout),
                        DigestCalculator.compute(stderrFile),
                        ByteString.copyFromUtf8(stderr),
                        action.actionDigest(),
                        action.action().toByteString(),
                        configFileDigest,
                        ByteString.copyFromUtf8(config),
                        testFileDigest,
                        ByteString.copyFromUtf8(test),
                        x86Digest,
                        x86Dir.toByteString(),
                        DigestCalculator.compute(inputRoot),
                        inputRoot.toByteString());

        client.uploadCache(action, result);

        assertEquals(expectedDigestToBlob, uploader.blobs);
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
        String stdout = "STDOUT";
        Digest stdOutDigest = DigestCalculator.compute(stdout.getBytes());
        mServiceRegistry.addService(new DefaultContentAddressableStorage());
        mServiceRegistry.addService(
                new ActionCacheImplBase() {
                    @Override
                    public void getActionResult(
                            GetActionResultRequest request,
                            StreamObserver<ActionResult> responseObserver) {
                        if (request.getActionDigest().equals(cachedAction.actionDigest())) {
                            responseObserver.onNext(
                                    ActionResult.newBuilder()
                                            .setStdoutDigest(stdOutDigest)
                                            .setExitCode(exitCode)
                                            .build());
                            responseObserver.onCompleted();
                            return;
                        }
                        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
                    }
                });
        RemoteCacheClient client =
                newClient(
                        new FakeByteStreamDownloader(
                                Collections.singletonMap(stdOutDigest, stdout)),
                        new FakeByteStreamUploader());

        ExecutableActionResult notFoundResult = client.lookupCache(notFoundAction);
        ExecutableActionResult cachedResult = client.lookupCache(cachedAction);

        assertNull(notFoundResult);
        assertEquals(0, cachedResult.exitCode());
        assertEquals(stdout, FileUtil.readStringFromFile(cachedResult.stdOut()));
        assertNull(cachedResult.stdErr());
    }

    private RemoteCacheClient newClient(
            ByteStreamDownloader downloader, ByteStreamUploader uploader) {
        return new RemoteCacheClient(mWorkFolder, INSTANCE, mChannel, null, downloader, uploader);
    }
}
