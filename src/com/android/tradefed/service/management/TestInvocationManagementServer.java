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
import com.android.tradefed.log.LogUtil.CLog;

import com.proto.tradefed.invocation.TestInvocationManagementGrpc.TestInvocationManagementImplBase;

import java.io.IOException;

import io.grpc.Server;
import io.grpc.ServerBuilder;

/**
 * GRPC server helping to management test invocation and their lifecycle. This service isn't
 * currently mandatory and only runs if configured with a port.
 */
public class TestInvocationManagementServer extends TestInvocationManagementImplBase {
    private static final String TF_INVOCATION_SERVER_PORT = "TF_INVOCATION_SERVER_PORT";

    private Server mServer;
    private ICommandScheduler mCommandScheduler;

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
}
