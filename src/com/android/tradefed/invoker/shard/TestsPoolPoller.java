/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.invoker.shard;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IMultiDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;

import java.util.List;
import java.util.Map;

/**
 * Tests wrapper that allow to execute all the tests of a pool of tests. Tests can be shared by
 * another {@link TestsPoolPoller} so synchronization is required.
 *
 * <p>TODO: Add handling for token module/tests.
 */
public class TestsPoolPoller
        implements IRemoteTest,
                IConfigurationReceiver,
                IDeviceTest,
                IBuildReceiver,
                IMultiDeviceTest,
                IInvocationContextReceiver {

    private List<IRemoteTest> mGenericPool;

    private ITestDevice mDevice;
    private IBuildInfo mBuildInfo;
    private IInvocationContext mContext;
    private Map<ITestDevice, IBuildInfo> mDeviceInfos;
    private IConfiguration mConfig;

    /**
     * Ctor where the pool of {@link IRemoteTest} is provided.
     *
     * @param tests {@link IRemoteTest}s pool of all tests.
     */
    public TestsPoolPoller(List<IRemoteTest> tests) {
        mGenericPool = tests;
    }

    /** Returns the first {@link IRemoteTest} from the pool or null if none remaining. */
    IRemoteTest poll() {
        synchronized (mGenericPool) {
            if (mGenericPool.isEmpty()) {
                return null;
            }
            IRemoteTest test = mGenericPool.remove(0);
            return test;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        while (true) {
            IRemoteTest test = poll();
            if (test == null) {
                return;
            }
            if (test instanceof IBuildReceiver) {
                ((IBuildReceiver) test).setBuild(mBuildInfo);
            }
            if (test instanceof IConfigurationReceiver) {
                ((IConfigurationReceiver) test).setConfiguration(mConfig);
            }
            if (test instanceof IDeviceTest) {
                ((IDeviceTest) test).setDevice(mDevice);
            }
            if (test instanceof IInvocationContextReceiver) {
                ((IInvocationContextReceiver) test).setInvocationContext(mContext);
            }
            if (test instanceof IMultiDeviceTest) {
                ((IMultiDeviceTest) test).setDeviceInfos(mDeviceInfos);
            }
            // Run the test itself and prevent random exception from stopping the poller.
            try {
                test.run(listener);
            } catch (RuntimeException e) {
                CLog.e(
                        "Caught an Exception in a test: %s. Proceeding to next test.",
                        test.getClass());
                CLog.e(e);
            } catch (DeviceUnresponsiveException due) {
                // being able to catch a DeviceUnresponsiveException here implies that recovery was
                // successful, and test execution should proceed to next test.
                CLog.w(
                        "Ignored DeviceUnresponsiveException because recovery was "
                                + "successful, proceeding with next test. Stack trace:");
                CLog.w(due);
                CLog.w("Proceeding to the next test.");
            } catch (DeviceNotAvailableException dnae) {
                // We catch and rethrow in order to log that the poller associated with the device
                // that went offline is terminating.
                CLog.e(
                        "Test %s threw DeviceNotAvailableException. Test poller associated with "
                                + "device %s is terminating.",
                        test.getClass(), mDevice.getSerialNumber());
                // TODO: Add a fail-safe mechanism in case all pollers terminate and we still have
                // tests in the pool.
                throw dnae;
            }
        }
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    @Override
    public void setDeviceInfos(Map<ITestDevice, IBuildInfo> deviceInfos) {
        mDeviceInfos = deviceInfos;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }
}
