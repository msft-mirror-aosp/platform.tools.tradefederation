/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.tradefed.build.BuildSerializedVersion;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.result.error.InfraErrorIdentifier;

/**
 * Thrown when a device is not able to connect to network for testing. This usually gets thrown if a
 * device fails to reconnect to wifi after reboot.
 */
public class NetworkNotAvailableException extends HarnessRuntimeException {
    private static final long serialVersionUID = BuildSerializedVersion.VERSION;

    /**
     * Creates a {@link NetworkNotAvailableException}.
     *
     * @param msg a descriptive message.
     */
    public NetworkNotAvailableException(String msg) {
        super(msg, InfraErrorIdentifier.WIFI_FAILED_CONNECT);
    }

    /**
     * Creates a {@link NetworkNotAvailableException}.
     *
     * @param msg a descriptive message.
     * @param cause the root {@link Throwable} that caused the connection failure.
     */
    public NetworkNotAvailableException(String msg, Throwable cause) {
        super(msg, cause, InfraErrorIdentifier.WIFI_FAILED_CONNECT);
    }
}
