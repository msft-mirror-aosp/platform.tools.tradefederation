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
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
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
    private File mWorkFolder;

    private static class FakeByteStreamDownloader extends ByteStreamDownloader {
        private final Map<Digest, String> mData;

        public FakeByteStreamDownloader(Map<Digest, String> data) {
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
        ExecutableAction action =
                ExecutableAction.create(
                        mInput, Arrays.asList("test", "command"), new HashMap<>(), 100L);
        int exitCode = 0;
        File stdoutFile = FileUtil.createTempFile("stdout-", ".txt", mWorkFolder);
        String stdout = "test stdout";
        FileUtil.writeToFile(stdout, stdoutFile);
        ExecutableActionResult result = ExecutableActionResult.create(exitCode, stdoutFile, null);
        RemoteCacheClient client = newClient(null);
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
                                Collections.singletonMap(stdOutDigest, stdout)));

        ExecutableActionResult notFoundResult = client.lookupCache(notFoundAction);
        ExecutableActionResult cachedResult = client.lookupCache(cachedAction);

        assertNull(notFoundResult);
        assertEquals(0, cachedResult.exitCode());
        assertEquals(stdout, FileUtil.readStringFromFile(cachedResult.stdOut()));
        assertNull(cachedResult.stdErr());
    }

    private RemoteCacheClient newClient(ByteStreamDownloader downloader) {
        return new RemoteCacheClient(mWorkFolder, "test instance", mChannel, null, downloader);
    }
}
