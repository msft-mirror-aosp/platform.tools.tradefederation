package com.android.tradefed.service.management;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.log.LogUtil.CLog;

import com.proto.tradefed.device.DeviceManagementGrpc.DeviceManagementImplBase;
import com.proto.tradefed.device.DeviceStatus;
import com.proto.tradefed.device.DeviceStatus.ReservationStatus;
import com.proto.tradefed.device.GetDevicesStatusRequest;
import com.proto.tradefed.device.GetDevicesStatusResponse;
import com.proto.tradefed.device.ReleaseReservationRequest;
import com.proto.tradefed.device.ReleaseReservationResponse;
import com.proto.tradefed.device.ReserveDeviceRequest;
import com.proto.tradefed.device.ReserveDeviceResponse;

import java.io.IOException;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

/** GRPC server allowing to reserve a device from Tradefed. */
public class DeviceManagementGrpcServer extends DeviceManagementImplBase {
    private static final String TF_DEVICE_MANAGEMENT_PORT = "TF_DEVICE_MANAGEMENT_PORT";

    private final Server mServer;
    private final IDeviceManager mDeviceManager;

    /** Returns the port used by the server. */
    public static Integer getPort() {
        return System.getenv(TF_DEVICE_MANAGEMENT_PORT) != null
                ? Integer.parseInt(System.getenv(TF_DEVICE_MANAGEMENT_PORT))
                : null;
    }

    public DeviceManagementGrpcServer(int port, IDeviceManager deviceManager) {
        this(ServerBuilder.forPort(port), deviceManager);
    }

    @VisibleForTesting
    public DeviceManagementGrpcServer(
            ServerBuilder<?> serverBuilder, IDeviceManager deviceManager) {
        mServer = serverBuilder.addService(this).build();
        mDeviceManager = deviceManager;
    }

    @VisibleForTesting
    public DeviceManagementGrpcServer(Server server, IDeviceManager deviceManager) {
        mServer = server;
        mDeviceManager = deviceManager;
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
    public void getDevicesStatus(
            GetDevicesStatusRequest request,
            StreamObserver<GetDevicesStatusResponse> responseObserver) {
        GetDevicesStatusResponse.Builder responseBuilder = GetDevicesStatusResponse.newBuilder();
        if (request.getDeviceIdList().isEmpty()) {
            for (DeviceDescriptor descriptor : mDeviceManager.listAllDevices()) {
                responseBuilder.addDeviceStatus(descriptorToStatus(descriptor));
            }
        } else {
            for (String serial : request.getDeviceIdList()) {
                DeviceDescriptor descriptor = mDeviceManager.getDeviceDescriptor(serial);
                responseBuilder.addDeviceStatus(descriptorToStatus(descriptor));
            }
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void releaseReservation(
            ReleaseReservationRequest request,
            StreamObserver<ReleaseReservationResponse> responseObserver) {
        // TODO: Implementation
        super.releaseReservation(request, responseObserver);
    }

    @Override
    public void reserveDevice(
            ReserveDeviceRequest request, StreamObserver<ReserveDeviceResponse> responseObserver) {
        // TODO: Implementation
        super.reserveDevice(request, responseObserver);
    }

    private DeviceStatus descriptorToStatus(DeviceDescriptor descriptor) {
        DeviceStatus.Builder deviceStatusBuilder = DeviceStatus.newBuilder();
        deviceStatusBuilder.setDeviceId(descriptor.getSerial());
        deviceStatusBuilder.setReservationStatus(
                allocationStateToReservation(descriptor.getState()));
        return deviceStatusBuilder.build();
    }

    private ReservationStatus allocationStateToReservation(DeviceAllocationState state) {
        switch (state) {
            case Available:
                return ReservationStatus.READY;
            case Allocated:
                return ReservationStatus.ALLOCATED;
            case Unavailable:
            case Ignored:
                return ReservationStatus.UNAVAILABLE;
            case Checking_Availability:
            case Unknown:
            default:
                return ReservationStatus.UNKNOWN;
        }
    }
}
