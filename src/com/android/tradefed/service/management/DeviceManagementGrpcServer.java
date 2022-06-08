package com.android.tradefed.service.management;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceSelectionOptions;
import com.android.tradefed.device.FreeDeviceState;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.base.Strings;
import com.proto.tradefed.device.DeviceManagementGrpc.DeviceManagementImplBase;
import com.proto.tradefed.device.DeviceStatus;
import com.proto.tradefed.device.DeviceStatus.ReservationStatus;
import com.proto.tradefed.device.GetDevicesStatusRequest;
import com.proto.tradefed.device.GetDevicesStatusResponse;
import com.proto.tradefed.device.ReleaseReservationRequest;
import com.proto.tradefed.device.ReleaseReservationResponse;
import com.proto.tradefed.device.ReserveDeviceRequest;
import com.proto.tradefed.device.ReserveDeviceResponse;
import com.proto.tradefed.device.ReserveDeviceResponse.Result;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

/** GRPC server allowing to reserve a device from Tradefed. */
public class DeviceManagementGrpcServer extends DeviceManagementImplBase {
    private static final String TF_DEVICE_MANAGEMENT_PORT = "TF_DEVICE_MANAGEMENT_PORT";

    private final Server mServer;
    private final IDeviceManager mDeviceManager;
    private final Map<String, ReservationInformation> mSerialToReservation =
            new ConcurrentHashMap<>();

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
        ReleaseReservationResponse.Builder responseBuilder =
                ReleaseReservationResponse.newBuilder();
        ITestDevice device = getDeviceFromReservationAndClear(request.getReservationId());
        if (device == null) {
            responseBuilder
                    .setResult(ReleaseReservationResponse.Result.FAIL)
                    .setMessage(
                            String.format(
                                    "Reservation id released '%s' is untracked",
                                    request.getReservationId()));
        } else {
            mDeviceManager.freeDevice(device, FreeDeviceState.AVAILABLE);
            responseBuilder.setResult(ReleaseReservationResponse.Result.SUCCEED);
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void reserveDevice(
            ReserveDeviceRequest request, StreamObserver<ReserveDeviceResponse> responseObserver) {
        ReserveDeviceResponse.Builder responseBuilder = ReserveDeviceResponse.newBuilder();
        String serial = request.getDeviceId();
        if (Strings.isNullOrEmpty(serial)) {
            responseBuilder
                    .setResult(Result.UNKNOWN)
                    .setMessage("serial requested was null or empty.");
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }

        DeviceDescriptor descriptor = mDeviceManager.getDeviceDescriptor(serial);
        if (DeviceAllocationState.Allocated.equals(descriptor.getState())) {
            Result result = Result.ALREADY_ALLOCATED;
            if (mSerialToReservation.containsKey(serial)) {
                result = Result.ALREADY_RESERVED;
            }
            responseBuilder.setResult(result).setMessage("device is currently in allocated state.");
        } else if (DeviceAllocationState.Unavailable.equals(descriptor.getState())) {
            responseBuilder
                    .setResult(Result.UNAVAILABLE)
                    .setMessage("device is currently in unavailable state.");
        } else {
            DeviceSelectionOptions selection = new DeviceSelectionOptions();
            selection.addSerial(serial);
            ITestDevice device = mDeviceManager.allocateDevice(selection);
            if (device == null) {
                responseBuilder
                        .setResult(Result.UNKNOWN)
                        .setMessage(
                                String.format(
                                        "Failed to allocate '%s' reason: '%s'",
                                        serial, selection.getNoMatchReason()));
            } else {
                String reservationId = UUID.randomUUID().toString();
                responseBuilder.setResult(Result.SUCCEED).setReservationId(reservationId);
                mSerialToReservation.put(serial, new ReservationInformation(device, reservationId));
            }
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
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

    private Entry<String, ReservationInformation> getDeviceEntryFromReservation(
            String reservationId) {
        for (Entry<String, ReservationInformation> info : mSerialToReservation.entrySet()) {
            if (info.getValue().reservationId.equals(reservationId)) {
                return info;
            }
        }
        return null;
    }

    private ITestDevice getDeviceFromReservationAndClear(String reservationId) {
        Entry<String, ReservationInformation> entry = getDeviceEntryFromReservation(reservationId);
        if (entry != null) {
            mSerialToReservation.remove(entry.getKey());
            return entry.getValue().device;
        }
        return null;
    }

    public ITestDevice getDeviceFromReservation(String reservationId) {
        Entry<String, ReservationInformation> entry = getDeviceEntryFromReservation(reservationId);
        if (entry != null) {
            return entry.getValue().device;
        }
        return null;
    }

    private class ReservationInformation {
        final ITestDevice device;
        final String reservationId;

        ReservationInformation(ITestDevice device, String reservationId) {
            this.device = device;
            this.reservationId = reservationId;
        }
    }
}
