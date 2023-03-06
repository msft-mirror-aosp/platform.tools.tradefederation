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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.RemoteAndroidDevice;
import com.android.tradefed.util.IRunUtil;

/**
 * Default connection representation of a device, assumed to be a standard adb connection of the
 * device.
 */
public class DefaultConnection extends AbstractConnection {

    private final IRunUtil mRunUtil;

    /** Create the requested connection. */
    public static DefaultConnection createConnection(ConnectionBuilder builder) {
        if (builder.device != null && builder.device instanceof RemoteAndroidDevice) {
            return new AdbTcpConnection(builder);
        }
        return new DefaultConnection(builder);
    }

    /** Builder used to described the connection. */
    public static class ConnectionBuilder {

        ITestDevice device;
        IRunUtil runUtil;

        public ConnectionBuilder(IRunUtil runUtil) {
            this.runUtil = runUtil;
        }

        public ConnectionBuilder setDevice(ITestDevice device) {
            this.device = device;
            return this;
        }
    }

    /** Constructor */
    protected DefaultConnection(ConnectionBuilder builder) {
        mRunUtil = builder.runUtil;
    }

    /** Returns {@link IRunUtil} to execute commands. */
    protected IRunUtil getRunUtil() {
        return mRunUtil;
    }
}
