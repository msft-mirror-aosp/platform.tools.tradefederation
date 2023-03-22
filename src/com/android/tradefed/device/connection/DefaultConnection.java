/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.device.connection;

import com.android.ddmlib.IDevice;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.IConfigurableVirtualDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.device.TestDeviceOptions.InstanceType;
import com.android.tradefed.device.cloud.RemoteAndroidVirtualDevice;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;

/**
 * Default connection representation of a device, assumed to be a standard adb connection of the
 * device.
 */
public class DefaultConnection extends AbstractConnection {

    private final IRunUtil mRunUtil;
    private final ITestDevice mDevice;
    private final IBuildInfo mBuildInfo;
    private final MultiMap<String, String> mAttributes;
    private final String mInitialIpDevice;
    private final String mInitialUser;
    private final Integer mInitialDeviceNumOffset;
    private final String mInitialSerial;
    private final ITestLogger mTestLogger;

    public static DefaultConnection createInopConnection(ConnectionBuilder builder) {
        return new DefaultConnection(builder);
    }

    /** Create the requested connection. */
    public static DefaultConnection createConnection(ConnectionBuilder builder) {
        if (builder.device != null && builder.device instanceof RemoteAndroidVirtualDevice) {
            ((NativeDevice) builder.device).setLogStartDelay(0);
            ((NativeDevice) builder.device).setFastbootEnabled(false);
            return new AdbSshConnection(builder);
        }
        if (builder.device != null && builder.device instanceof RemoteAndroidDevice) {
            return new AdbTcpConnection(builder);
        }
        if (builder.device != null) {
            InstanceType type = builder.device.getOptions().getInstanceType();
            if (InstanceType.REMOTE_AVD.equals(type) || InstanceType.GCE.equals(type)) {
                ((NativeDevice) builder.device).setLogStartDelay(0);
                ((NativeDevice) builder.device).setFastbootEnabled(false);
                return new AdbSshConnection(builder);
            }
        }
        return new DefaultConnection(builder);
    }

    /** Builder used to described the connection. */
    public static class ConnectionBuilder {

        ITestDevice device;
        IBuildInfo buildInfo;
        MultiMap<String, String> attributes;
        IRunUtil runUtil;
        ITestLogger logger;

        public ConnectionBuilder(
                IRunUtil runUtil, ITestDevice device, IBuildInfo buildInfo, ITestLogger logger) {
            this.runUtil = runUtil;
            this.device = device;
            this.buildInfo = buildInfo;
            this.logger = logger;
            attributes = new MultiMap<String, String>();
        }

        public ConnectionBuilder addAttributes(MultiMap<String, String> attributes) {
            this.attributes.putAll(attributes);
            return this;
        }
    }

    /** Constructor */
    protected DefaultConnection(ConnectionBuilder builder) {
        mRunUtil = builder.runUtil;
        mDevice = builder.device;
        mBuildInfo = builder.buildInfo;
        mAttributes = builder.attributes;
        IDevice idevice = mDevice.getIDevice();
        mInitialSerial = mDevice.getSerialNumber();
        mTestLogger = builder.logger;
        if (idevice instanceof IConfigurableVirtualDevice) {
            mInitialIpDevice = ((IConfigurableVirtualDevice) idevice).getKnownDeviceIp();
            mInitialUser = ((IConfigurableVirtualDevice) idevice).getKnownUser();
            mInitialDeviceNumOffset = ((IConfigurableVirtualDevice) idevice).getDeviceNumOffset();
        } else {
            mInitialIpDevice = null;
            mInitialUser = null;
            mInitialDeviceNumOffset = null;
        }
    }

    /** Returns {@link IRunUtil} to execute commands. */
    protected IRunUtil getRunUtil() {
        return mRunUtil;
    }

    public final ITestDevice getDevice() {
        return mDevice;
    }

    public final IBuildInfo getBuildInfo() {
        return mBuildInfo;
    }

    public final MultiMap<String, String> getAttributes() {
        return mAttributes;
    }

    /**
     * Returns the initial associated ip to the device if any. Returns null if no known initial ip.
     */
    public String getInitialIp() {
        return mInitialIpDevice;
    }

    /** Returns the initial known user if any. Returns null if no initial known user. */
    public String getInitialUser() {
        return mInitialUser;
    }

    /** Returns the known device num offset if any. Returns null if not available. */
    public Integer getInitialDeviceNumOffset() {
        return mInitialDeviceNumOffset;
    }

    /** Returns the initial serial name of the device. */
    public String getInitialSerial() {
        return mInitialSerial;
    }

    /** Returns the {@link ITestLogger} to log files. */
    public ITestLogger getLogger() {
        return mTestLogger;
    }
}
