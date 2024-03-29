/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.device;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.ConditionPriorityBlockingQueue;
import com.android.tradefed.util.ConditionPriorityBlockingQueue.IMatcher;
import com.android.tradefed.util.IRunUtil;

import org.junit.Assert;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link IDeviceManager} that simulates the resource allocation of {@link DeviceManager} for a
 * configurable set of devices.
 */
public class MockDeviceManager implements IDeviceManager {

    // acts as an available device queue
    ConditionPriorityBlockingQueue<ITestDevice> mAvailableDeviceQueue =
            new ConditionPriorityBlockingQueue<ITestDevice>();

    private int mTotalDevices;
    private DeviceMonitorMultiplexer mDvcMon = new DeviceMonitorMultiplexer();
    private boolean mGceDeviceRequested = false;
    private boolean mNullDeviceRequested = false;
    private boolean mStubDeviceRequested = false;
    private int mStopAdbBridgeCallCount = 0;
    private int mRestartAdbBridgeCallCount = 0;

    public MockDeviceManager(int numDevices) {
        setNumDevices(numDevices);
    }

    public void setNumDevices(int numDevices) {
        setNumDevicesInternal(numDevices, true);
    }

    public void setNumDevicesUnresponsive(int numDevices) {
        setNumDevicesInternal(numDevices, false);
    }

    private void setNumDevicesInternal(int numDevices, boolean responsive) {
        mAvailableDeviceQueue.clear();
        mTotalDevices = numDevices;
        for (int i = 0; i < numDevices; i++) {
            ITestDevice mockDevice = mock(ITestDevice.class);
            when(mockDevice.getSerialNumber()).thenReturn("serial" + i);
            IDevice mockIDevice = mock(IDevice.class);
            when(mockIDevice.getSerialNumber()).thenReturn("serial" + i);
            when(mockDevice.getIDevice()).thenReturn(mockIDevice);
            when(mockDevice.getDeviceState()).thenReturn(TestDeviceState.ONLINE);
            when(mockDevice.waitForDeviceShell(Mockito.anyLong())).thenReturn(responsive);

            mAvailableDeviceQueue.add(mockDevice);
        }
    }

    /** Create a real {@link ITestDevice} with recovery mode NONE */
    public void setNumDevicesCustomRealNoRecovery(int numDevices, Class<IDevice> idevicetype) {
        mAvailableDeviceQueue.clear();
        mTotalDevices = numDevices;
        for (int i = 0; i < numDevices; i++) {
            IDevice mockIDevice = mock(idevicetype);
            when(mockIDevice.getSerialNumber()).thenReturn("serial" + i);
            IDeviceStateMonitor stateMonitor = mock(IDeviceStateMonitor.class);
            IDeviceMonitor allocationMonitor = mock(IDeviceMonitor.class);

            ITestDevice mockDevice =
                    new TestDevice(mockIDevice, stateMonitor, allocationMonitor) {
                        @Override
                        public boolean waitForDeviceShell(long waitTime) {
                            return true;
                        }
                    };
            mockDevice.setRecoveryMode(RecoveryMode.NONE);
            mAvailableDeviceQueue.add(mockDevice);
        }
    }

    public void setNumDevicesCustom(
            int numDevices, TestDeviceState state, Class<? extends IDevice> idevicetype) {
        mAvailableDeviceQueue.clear();
        mTotalDevices = numDevices;
        for (int i = 0; i < numDevices; i++) {
            ITestDevice mockDevice = mock(ITestDevice.class);
            when(mockDevice.getSerialNumber()).thenReturn("serial" + i);
            IDevice mockIDevice = mock(idevicetype);
            when(mockIDevice.getSerialNumber()).thenReturn("serial" + i);
            when(mockDevice.getIDevice()).thenReturn(mockIDevice);
            when(mockDevice.getDeviceState()).thenReturn(state);

            mAvailableDeviceQueue.add(mockDevice);
        }
    }

    public void setNumDevicesStub(int numDevices, TestDeviceState state, IDevice idevice) {
        if (idevice instanceof RemoteAvdIDevice) {
            mGceDeviceRequested = true;
        } else if (idevice instanceof NullDevice) {
            mNullDeviceRequested = true;
        } else if (idevice instanceof StubDevice) {
            mStubDeviceRequested = true;
        }
        mAvailableDeviceQueue.clear();
        mTotalDevices = numDevices;
        for (int i = 0; i < numDevices; i++) {
            ITestDevice mockDevice = mock(ITestDevice.class);
            when(mockDevice.getSerialNumber()).thenReturn("serial" + i);
            IDevice mockIDevice = idevice;
            // when(mockIDevice.getSerialNumber()).thenReturn("serial" + i);
            when(mockDevice.getIDevice()).thenReturn(mockIDevice);
            when(mockDevice.getDeviceState()).thenReturn(state);

            mAvailableDeviceQueue.add(mockDevice);
        }
    }

    public void addDevice(ITestDevice mockDevice) {
        mTotalDevices += 1;
        mAvailableDeviceQueue.add(mockDevice);
    }

    public void clearAllDevices() {
        mTotalDevices = 0;
        mAvailableDeviceQueue.clear();
    }

    private static class TestDeviceMatcher implements IMatcher<ITestDevice> {
        private IDeviceSelection mDeviceOptions;

        /**
         * @param deviceSelectionOptions
         */
        public TestDeviceMatcher(IDeviceSelection deviceSelectionOptions) {
            mDeviceOptions = deviceSelectionOptions;
        }

        /** {@inheritDoc} */
        @Override
        public boolean matches(ITestDevice element) {
            return mDeviceOptions.matches(element.getIDevice());
        }
    }

    public int getQueueOfAvailableDeviceSize() {
        return mAvailableDeviceQueue.size();
    }

    /** {@inheritDoc} */
    @Override
    public void addFastbootListener(IFastbootListener listener) {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice allocateDevice() {
        try {
            return mAvailableDeviceQueue.take();
        } catch (InterruptedException e) {
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice allocateDevice(IDeviceSelection options) {
        return allocateDevice(options, false);
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice allocateDevice(IDeviceSelection options, boolean isTemporary) {
        if (mGceDeviceRequested) {
            ((DeviceSelectionOptions) options).setGceDeviceRequested(true);
        }
        if (mNullDeviceRequested) {
            ((DeviceSelectionOptions) options).setNullDeviceRequested(true);
        }
        if (mStubDeviceRequested) {
            ((DeviceSelectionOptions) options).setStubEmulatorRequested(true);
        }
        ITestDevice d = mAvailableDeviceQueue.poll(new TestDeviceMatcher(options));
        if (d != null) {
            mDvcMon.notifyDeviceStateChange(
                    d.getSerialNumber(),
                    DeviceAllocationState.Available,
                    DeviceAllocationState.Allocated);
        }
        return d;
    }

    /** {@inheritDoc} */
    @Override
    public void freeDevice(ITestDevice device, FreeDeviceState state) {
        if (!state.equals(FreeDeviceState.UNAVAILABLE)) {
            mAvailableDeviceQueue.add(device);
            mDvcMon.notifyDeviceStateChange(
                    device.getSerialNumber(),
                    DeviceAllocationState.Allocated,
                    DeviceAllocationState.Available);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice forceAllocateDevice(String serial) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    public void removeFastbootListener(IFastbootListener listener) {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void terminate() {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void terminateDeviceRecovery() {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void terminateDeviceMonitor() {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void terminateHard() {
        // ignore
    }

    @Override
    public void init() {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void init(
            IDeviceSelection globalDeviceFilter, List<IDeviceMonitor> globalDeviceMonitors) {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void stopAdbBridge() {
        mStopAdbBridgeCallCount += 1;
    }

    /** {@inheritDoc} */
    @Override
    public void restartAdbBridge() {
        mRestartAdbBridgeCallCount += 1;
    }

    /**
     * Verifies that all devices were returned to queue.
     *
     * @throws AssertionError
     */
    public void assertDevicesFreed() throws AssertionError {
        Assert.assertEquals(
                "allocated device was not returned to queue",
                mTotalDevices,
                mAvailableDeviceQueue.size());
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice reconnectDeviceToTcp(ITestDevice usbDevice)
            throws DeviceNotAvailableException {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice connectToTcpDevice(String ipAndPort) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean disconnectFromTcpDevice(ITestDevice tcpDevice) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void launchEmulator(
            ITestDevice device, long bootTimeout, IRunUtil runUtil, List<String> emulatorArgs)
            throws DeviceNotAvailableException {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void killEmulator(ITestDevice device) throws DeviceNotAvailableException {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public void displayDevicesInfo(PrintWriter stream, boolean includeStub) {
        // ignore
    }

    /** {@inheritDoc} */
    @Override
    public List<DeviceDescriptor> listAllDevices() {
        return new ArrayList<DeviceDescriptor>();
    }

    /** {@inheritDoc} */
    @Override
    public List<DeviceDescriptor> listAllDevices(boolean shortDescriptor) {
        return new ArrayList<DeviceDescriptor>();
    }

    /** {@inheritDoc} */
    @Override
    public DeviceDescriptor getDeviceDescriptor(String serial) {
        return new DeviceDescriptor(
                serial,
                false,
                DeviceAllocationState.Available,
                "product",
                "productVariant",
                "sdkVersion",
                "buildId",
                "batteryLevel");
    }

    @Override
    public boolean isNullDevice(String serial) {
        return false;
    }

    @Override
    public boolean isEmulator(String serial) {
        return false;
    }

    @Override
    public void addDeviceMonitor(IDeviceMonitor mon) {
        mDvcMon.addMonitor(mon);
    }

    @Override
    public void removeDeviceMonitor(IDeviceMonitor mon) {
        mDvcMon.removeMonitor(mon);
    }

    @Override
    public String getAdbPath() {
        return "adb";
    }

    @Override
    public String getFastbootPath() {
        return "fastboot";
    }

    @Override
    public boolean waitForFirstDeviceAdded(long timeout) {
        return false;
    }

    /**
     * Enable unittest for stopAdbBridge().
     *
     * @return number of times stopAdbBridge() was called.
     */
    public int getStopAdbBridgeCallCount() {
        return mStopAdbBridgeCallCount;
    }

    /**
     * Enable unittest for restartAdbBridge().
     *
     * @return number of times restartAdbBridge() was called.
     */
    public int getRestartAdbBridgeCallCount() {
        return mRestartAdbBridgeCallCount;
    }

    @Override
    public CommandResult executeCmdOnAvailableDevice(
            String serial, String command, long timeout, TimeUnit timeUnit) {
        return null;
    }

    @Override
    public String getAdbVersion() {
        return null;
    }

    @Override
    public void addMonitoringTcpFastbootDevice(String serial, String fastboot_serial) {
        // ignore
    }
}
