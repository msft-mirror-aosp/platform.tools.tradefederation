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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.IDeviceManager;

import com.proto.tradefed.device.DeviceStatus.ReservationStatus;
import com.proto.tradefed.device.GetDevicesStatusRequest;
import com.proto.tradefed.device.GetDevicesStatusResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

import io.grpc.Server;
import io.grpc.stub.StreamObserver;

/** Unit tests for {@link DeviceManagementGrpcServer}. */
@RunWith(JUnit4.class)
public class DeviceManagementGrpcServerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private DeviceManagementGrpcServer mServer;
    @Mock private IDeviceManager mMockDeviceManager;
    @Mock private StreamObserver<GetDevicesStatusResponse> mGetDevicesStatusObserver;
    @Captor ArgumentCaptor<GetDevicesStatusResponse> mGetDevicesStatusCaptor;

    @Before
    public void setUp() {
        Server server = null;
        mServer = new DeviceManagementGrpcServer(server, mMockDeviceManager);
    }

    @Test
    public void testGetDevicesStatus() {
        List<DeviceDescriptor> descriptors = new ArrayList<>();
        descriptors.add(createDescriptor("serial1", DeviceAllocationState.Available));
        descriptors.add(createDescriptor("serial2", DeviceAllocationState.Allocated));
        descriptors.add(createDescriptor("serial3", DeviceAllocationState.Unavailable));
        descriptors.add(createDescriptor("serial4", DeviceAllocationState.Unknown));
        when(mMockDeviceManager.listAllDevices()).thenReturn(descriptors);

        GetDevicesStatusRequest.Builder requestBuilder = GetDevicesStatusRequest.newBuilder();
        mServer.getDevicesStatus(requestBuilder.build(), mGetDevicesStatusObserver);
        verify(mGetDevicesStatusObserver).onNext(mGetDevicesStatusCaptor.capture());
        GetDevicesStatusResponse response = mGetDevicesStatusCaptor.getValue();

        assertThat(response.getDeviceStatusList()).hasSize(4);
        assertThat(response.getDeviceStatusList().get(0).getDeviceId()).isEqualTo("serial1");
        assertThat(response.getDeviceStatusList().get(0).getReservationStatus())
                .isEqualTo(ReservationStatus.READY);

        assertThat(response.getDeviceStatusList().get(1).getDeviceId()).isEqualTo("serial2");
        assertThat(response.getDeviceStatusList().get(1).getReservationStatus())
                .isEqualTo(ReservationStatus.ALLOCATED);

        assertThat(response.getDeviceStatusList().get(2).getDeviceId()).isEqualTo("serial3");
        assertThat(response.getDeviceStatusList().get(2).getReservationStatus())
                .isEqualTo(ReservationStatus.UNAVAILABLE);

        assertThat(response.getDeviceStatusList().get(3).getDeviceId()).isEqualTo("serial4");
        assertThat(response.getDeviceStatusList().get(3).getReservationStatus())
                .isEqualTo(ReservationStatus.UNKNOWN);
    }

    @Test
    public void testGetDevicesStatus_filter() {
        when(mMockDeviceManager.getDeviceDescriptor("serial2"))
                .thenReturn(createDescriptor("serial2", DeviceAllocationState.Allocated));

        GetDevicesStatusRequest.Builder requestBuilder =
                GetDevicesStatusRequest.newBuilder().addDeviceId("serial2");
        mServer.getDevicesStatus(requestBuilder.build(), mGetDevicesStatusObserver);
        verify(mGetDevicesStatusObserver).onNext(mGetDevicesStatusCaptor.capture());
        GetDevicesStatusResponse response = mGetDevicesStatusCaptor.getValue();

        assertThat(response.getDeviceStatusList()).hasSize(1);
        assertThat(response.getDeviceStatusList().get(0).getDeviceId()).isEqualTo("serial2");
        assertThat(response.getDeviceStatusList().get(0).getReservationStatus())
                .isEqualTo(ReservationStatus.ALLOCATED);
    }

    private DeviceDescriptor createDescriptor(String serial, DeviceAllocationState state) {
        return new DeviceDescriptor(serial, false, state, "", "", "", "", "");
    }
}
