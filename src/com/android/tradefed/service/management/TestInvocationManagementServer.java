/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tradefed.service.management;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.command.ICommandScheduler;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.proto.FileProtoResultReporter;
import com.android.tradefed.util.FileUtil;

import com.proto.tradefed.invocation.InvocationDetailRequest;
import com.proto.tradefed.invocation.InvocationDetailResponse;
import com.proto.tradefed.invocation.InvocationStatus;
import com.proto.tradefed.invocation.InvocationStatus.Status;
import com.proto.tradefed.invocation.NewTestCommandRequest;
import com.proto.tradefed.invocation.NewTestCommandResponse;
import com.proto.tradefed.invocation.TestInvocationManagementGrpc.TestInvocationManagementImplBase;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

/**
 * GRPC server helping to management test invocation and their lifecycle. This service isn't
 * currently mandatory and only runs if configured with a port.
 */
public class TestInvocationManagementServer extends TestInvocationManagementImplBase {
    private static final String TF_INVOCATION_SERVER_PORT = "TF_INVOCATION_SERVER_PORT";

    private Server mServer;
    private ICommandScheduler mCommandScheduler;
    private Map<String, ScheduledInvocationForwarder> mTracker = new HashMap<>();

    /** Returns the port used by the server. */
    public static Integer getPort() {
        return System.getenv(TF_INVOCATION_SERVER_PORT) != null
                ? Integer.parseInt(System.getenv(TF_INVOCATION_SERVER_PORT))
                : null;
    }

    public TestInvocationManagementServer(int port) {
        this(ServerBuilder.forPort(port));
    }

    @VisibleForTesting
    public TestInvocationManagementServer(ServerBuilder<?> serverBuilder) {
        mServer = serverBuilder.addService(this).build();
    }

    public void setCommandScheduler(ICommandScheduler scheduler) {
        mCommandScheduler = scheduler;
    }

    /** Start the grpc server. */
    public void start() {
        try {
            CLog.d("Starting invocation server.");
            mServer.start();
        } catch (IOException e) {
            CLog.w("Invocation server already started: %s", e.getMessage());
        }
    }

    /** Stop the grpc server. */
    public void shutdown() throws InterruptedException {
        if (mServer != null) {
            CLog.d("Stopping invocation server.");
            mServer.shutdown();
            mServer.awaitTermination();
        }
    }

    @Override
    public void submitTestCommand(
            NewTestCommandRequest request,
            StreamObserver<NewTestCommandResponse> responseObserver) {
        NewTestCommandResponse.Builder responseBuilder = NewTestCommandResponse.newBuilder();
        String[] command = request.getArgsList().toArray(new String[0]);
        File record = null;
        try {
            record = FileUtil.createTempFile("test_record", ".pb");
            CommandStatusHandler handler = new CommandStatusHandler();
            FileProtoResultReporter fileReporter = new FileProtoResultReporter();
            fileReporter.setOutputFile(record);
            fileReporter.setDelimitedOutput(false);
            fileReporter.setGranularResults(false);
            ScheduledInvocationForwarder forwarder =
                    new ScheduledInvocationForwarder(handler, fileReporter);
            mCommandScheduler.execCommand(forwarder, command);
            // TODO: Align trackerId with true invocation id
            String trackerId = UUID.randomUUID().toString();
            mTracker.put(trackerId, forwarder);
            responseBuilder.setInvocationId(trackerId);
        } catch (ConfigurationException | IOException e) {
            // TODO: Expand proto to convey those errors
            responseBuilder.setInvocationId(null);
            FileUtil.deleteFile(record);
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getInvocationDetail(
            InvocationDetailRequest request,
            StreamObserver<InvocationDetailResponse> responseObserver) {
        InvocationDetailResponse.Builder responseBuilder = InvocationDetailResponse.newBuilder();
        String invocationId = request.getInvocationId();
        if (mTracker.containsKey(invocationId)) {
            responseBuilder.setInvocationStatus(
                    createStatus(mTracker.get(invocationId).getListeners()));
            if (responseBuilder.getInvocationStatus().getStatus().equals(Status.DONE)) {
                responseBuilder.setTestRecordPath(
                        getProtoPath(mTracker.get(invocationId).getListeners()));
                // Finish the tracking after returning the first status done.
                mTracker.remove(invocationId);
            }
        } else {
            responseBuilder.setInvocationStatus(
                    InvocationStatus.newBuilder()
                            .setStatus(Status.UNKNOWN)
                            .setStatusReason("invocation id is not tracked."));
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    private InvocationStatus createStatus(List<ITestInvocationListener> listeners) {
        InvocationStatus.Builder invocationStatusBuilder = InvocationStatus.newBuilder();
        Status status = Status.UNKNOWN;
        for (ITestInvocationListener listener : listeners) {
            if (listener instanceof CommandStatusHandler) {
                status = ((CommandStatusHandler) listener).getCurrentStatus();
            }
        }
        invocationStatusBuilder.setStatus(status);
        if (Status.UNKNOWN.equals(status)) {
            invocationStatusBuilder.setStatusReason("Failed to find the CommandStatusHandler.");
        }
        return invocationStatusBuilder.build();
    }

    private String getProtoPath(List<ITestInvocationListener> listeners) {
        for (ITestInvocationListener listener : listeners) {
            if (listener instanceof FileProtoResultReporter) {
                return ((FileProtoResultReporter) listener).getOutputFile().getAbsolutePath();
            }
        }
        return null;
    }
}
