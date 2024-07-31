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

package com.android.tradefed.util;

import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/*
 * A Collection of helper methods for creating functional processes.
 */
public class ProcessUtil {

    // Format string for non-interactive SSH tunneling parameter;params in
    // order:local port, remote port
    private static final String TUNNEL_PARAM = "-L%d:127.0.0.1:%d";

    // Format string for local hostname.
    public static final String DEFAULT_LOCAL_HOST = "127.0.0.1:%d";

    public static final int DEFAULT_ADB_PORT = 5555;

    /**
     * Create an ssh tunnel to a given remote host and return the process.
     *
     * @param remoteHost the hostname/ip of the remote tcp ip Android device.
     * @param localPort the port of the local tcp ip device.
     * @param remotePort the port of the remote tcp ip device.
     * @return {@link Process} of the ssh command.
     */
    public static Process createSshTunnel(
            String remoteHost,
            int localPort,
            int remotePort,
            File sshPrivateKeyPath,
            String user,
            File sshTunnelLog,
            IRunUtil runUtil) {
        try {
            String serial = String.format(DEFAULT_LOCAL_HOST, localPort);
            CLog.d("Device serial will be %s", serial);

            List<String> tunnelParam = new ArrayList<>();
            tunnelParam.add(String.format(TUNNEL_PARAM, localPort, remotePort));
            tunnelParam.add("-N");
            List<String> sshTunnel =
                    GceRemoteCmdFormatter.getSshCommand(
                            sshPrivateKeyPath, tunnelParam, user, remoteHost, "" /* no command */);
            FileOutputStream output = null;
            if (sshTunnelLog != null && sshTunnelLog.exists()) {
                output = new FileOutputStream(sshTunnelLog, true);
            }
            return runUtil.runCmdInBackground(sshTunnel, output);
        } catch (IOException e) {
            CLog.d("Failed to connect to remote GCE using ssh tunnel %s", e.getMessage());
        }
        return null;
    }
}
