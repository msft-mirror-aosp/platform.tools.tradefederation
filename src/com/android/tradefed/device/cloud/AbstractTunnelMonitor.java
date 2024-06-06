/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tradefed.device.cloud;

import com.android.tradefed.log.ITestLogger;

/** Abstract tunnel monitor for GCE AVD. */
public abstract class AbstractTunnelMonitor extends Thread {

    public AbstractTunnelMonitor(String name) {
        super(name);
        this.setDaemon(true);
    }

    /** Close all the connections from the monitor (adb tunnel). */
    public void closeConnection() {
        // Empty by default
    }

    /** Returns true if the tunnel is alive, false otherwise. */
    public boolean isTunnelAlive() {
        return false;
    }

    /** Log all the interesting log files generated from the ssh tunnel. */
    public void logSshTunnelLogs(ITestLogger logger) {
        // Empty by default
    }

    /** Terminate the tunnel monitor */
    public void shutdown() {
        // Empty by default
    }

    /** Set True when an adb reboot is about to be called to make sure the monitor expect it. */
    public void isAdbRebootCalled(boolean isCalled) {
        // Empty by default
    }
}
