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

// Confirmed in b/323295206, it still needs a tunnel monitor due to some edge-cases can't be
// recovered by LHP.
public class GceLHPTunnelMonitor extends AbstractTunnelMonitor {

    public GceLHPTunnelMonitor() {
        super(String.format("GceSshTunnelMonitor"));
        // TODO(easoncylee): Constructor to monitor the adb connection to an oxygenation device.
    }

    @Override
    public void run() {
        // TODO(easoncylee): At run method, it would do
        // 1. Establish adb connection to the oxygenation device through LHP.
        // 2. Monitor the connection, and re-establish the connection if it's disconnected.
    }

    /** Returns True if the {@link GceLHPTunnelMonitor} is still alive, false otherwise. */
    @Override
    public boolean isTunnelAlive() {
        // TODO(easoncylee): Flesh out this section when it's ready, return true for now.
        return false;
    }

    /** Close the adb connection from the monitor. */
    @Override
    public void closeConnection() {
        // TODO(easoncylee): Flesh out this section when it's ready.
    }

    /** Log all the interesting log files generated from the ssh tunnel. */
    @Override
    public void logSshTunnelLogs(ITestLogger logger) {
        // TODO(easoncylee): Flesh out this section when it's ready.
    }

    /** Terminate the tunnel monitor */
    @Override
    public void shutdown() {
        // TODO(easoncylee): Flesh out this section when it's ready.
    }
}
