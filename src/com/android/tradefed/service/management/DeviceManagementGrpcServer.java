package com.android.tradefed.service.management;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.log.LogUtil.CLog;

import com.proto.tradefed.device.DeviceManagementGrpc.DeviceManagementImplBase;

import java.io.IOException;

import com.proto.tradefed.device.GetDevicesStatusRequest;
import com.proto.tradefed.device.GetDevicesStatusResponse;
import com.proto.tradefed.device.ReleaseReservationRequest;
import com.proto.tradefed.device.ReleaseReservationResponse;
import com.proto.tradefed.device.ReserveDeviceRequest;
import com.proto.tradefed.device.ReserveDeviceResponse;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

/** GRPC server allowing to reserve a device from Tradefed. */
public class DeviceManagementGrpcServer extends DeviceManagementImplBase {
    private static final String TF_DEVICE_MANAGEMENT_PORT = "TF_DEVICE_MANAGEMENT_PORT";

    private final Server mServer;

    /** Returns the port used by the server. */
    public static Integer getPort() {
        return System.getenv(TF_DEVICE_MANAGEMENT_PORT) != null
                ? Integer.parseInt(System.getenv(TF_DEVICE_MANAGEMENT_PORT))
                : null;
    }

    public DeviceManagementGrpcServer(int port) {
        this(ServerBuilder.forPort(port));
    }

    @VisibleForTesting
    public DeviceManagementGrpcServer(ServerBuilder<?> serverBuilder) {
        mServer = serverBuilder.addService(this).build();
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
        // TODO: Implementation
        super.getDevicesStatus(request, responseObserver);
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
}
