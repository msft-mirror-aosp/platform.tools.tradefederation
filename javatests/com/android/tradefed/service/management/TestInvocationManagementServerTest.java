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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.android.tradefed.command.ICommandScheduler;
import com.android.tradefed.command.ICommandScheduler.IScheduledInvocationListener;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.util.FileUtil;

import com.google.common.truth.Truth;
import com.proto.tradefed.invocation.InvocationDetailRequest;
import com.proto.tradefed.invocation.InvocationDetailResponse;
import com.proto.tradefed.invocation.InvocationStatus;
import com.proto.tradefed.invocation.NewTestCommandRequest;
import com.proto.tradefed.invocation.NewTestCommandResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;

import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;

/** Unit tests for {@link TestInvocationManagementServer}. */
@RunWith(JUnit4.class)
public class TestInvocationManagementServerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private TestInvocationManagementServer mServer;
    @Mock private ICommandScheduler mMockScheduler;
    @Mock private StreamObserver<NewTestCommandResponse> mRequestObserver;
    @Mock private StreamObserver<InvocationDetailResponse> mDetailObserver;
    @Captor ArgumentCaptor<NewTestCommandResponse> mResponseCaptor;
    @Captor ArgumentCaptor<InvocationDetailResponse> mResponseDetailCaptor;

    @Before
    public void setUp() {
        Server server = null;
        mServer = new TestInvocationManagementServer(server, mMockScheduler);
    }

    @Test
    public void testSubmitTestCommand_andDetails() throws Exception {
        doAnswer(
                        invocation -> {
                            Object listeners = invocation.getArgument(0);
                            ((IScheduledInvocationListener) listeners)
                                    .invocationComplete(null, null);
                            return null;
                        })
                .when(mMockScheduler)
                .execCommand(Mockito.any(), Mockito.any());
        NewTestCommandRequest.Builder requestBuilder =
                NewTestCommandRequest.newBuilder().addArgs("empty");
        mServer.submitTestCommand(requestBuilder.build(), mRequestObserver);

        verify(mRequestObserver).onNext(mResponseCaptor.capture());
        NewTestCommandResponse response = mResponseCaptor.getValue();
        Truth.assertThat(response.getInvocationId()).isNotEmpty();

        InvocationDetailRequest.Builder detailBuilder =
                InvocationDetailRequest.newBuilder().setInvocationId(response.getInvocationId());
        mServer.getInvocationDetail(detailBuilder.build(), mDetailObserver);
        verify(mDetailObserver).onNext(mResponseDetailCaptor.capture());
        InvocationDetailResponse responseDetails = mResponseDetailCaptor.getValue();
        Truth.assertThat(responseDetails.getInvocationStatus().getStatus())
                .isEqualTo(InvocationStatus.Status.DONE);
        File record = new File(responseDetails.getTestRecordPath());
        Truth.assertThat(record.exists()).isTrue();
        FileUtil.deleteFile(record);
    }

    @Test
    public void testSubmitTestCommand_schedulingError() throws Exception {
        doThrow(new ConfigurationException("failed to schedule"))
                .when(mMockScheduler)
                .execCommand(Mockito.any(), Mockito.any());

        NewTestCommandRequest.Builder requestBuilder =
                NewTestCommandRequest.newBuilder().addArgs("empty");
        mServer.submitTestCommand(requestBuilder.build(), mRequestObserver);

        verify(mRequestObserver).onNext(mResponseCaptor.capture());
        NewTestCommandResponse response = mResponseCaptor.getValue();
        Truth.assertThat(response.getInvocationId()).isEmpty();
    }
}
