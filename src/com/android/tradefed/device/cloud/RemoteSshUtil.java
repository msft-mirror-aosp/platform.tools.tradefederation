/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.device.cloud.OxygenClient.LHPTunnelMode;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.GceRemoteCmdFormatter;
import com.android.tradefed.util.IRunUtil;

import java.io.OutputStream;
import java.util.List;

/** Utility to execute ssh commands on remote instances. */
public class RemoteSshUtil {

    /**
     * Execute a command on the remote instance using ssh.
     *
     * @param remoteInstance The {@link GceAvdInfo} that describe the device.
     * @param options a {@link TestDeviceOptions} describing the device options to be used for the
     *     GCE device.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeoutMs in millisecond for the fetch to complete
     * @param stdout An {@link OutputStream} where the stdout will be logged.
     * @param stderr An {@link OutputStream} where the stderr will be logged.
     * @param command The command to be executed.
     * @return A {@link CommandResult} containing the status and logs.
     */
    public static CommandResult remoteSshCommandExec(
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs,
            OutputStream stdout,
            OutputStream stderr,
            String... command) {
        Process sshTunnel = null;
        CommandResult resSsh = null;
        OxygenClient oxygenClient = null;
        try {
            // In Oxygenation, the existing ssh/scp way would not work since the remote instance
            // can't be connected by passing $HOST_IP, thus the ssh/scp command would be a bit
            // different, so we need to differentiate if it's an oxygenation device and compile it.
            // TODO(easoncylee/haoch): Flesh out this section when it's ready.
            if (remoteInstance.isOxygenationDevice()) {
                oxygenClient = new OxygenClient(options.getAvdDriverBinary());
                // To execute ssh/scp on a remote instance, create the ssh tunnel first.
                Integer portNumber = oxygenClient.createServerSocket();
                sshTunnel =
                        oxygenClient.createTunnelViaLHP(
                                LHPTunnelMode.SSH,
                                Integer.toString(portNumber),
                                remoteInstance.instanceName(),
                                remoteInstance.getOxygenationDeviceId());
                if (sshTunnel == null || !sshTunnel.isAlive()) {
                    resSsh = new CommandResult(CommandStatus.EXCEPTION);
                    resSsh.setStderr("Failed to establish an ssh tunnel via LHP.");
                    resSsh.setExitCode(-1);
                    return resSsh;
                }
                // TODO(b/330197325): Flesh out the extra ssh parameters when the oxygenation CF
                // instance launched by cvd is supported.
            }
            List<String> sshCmd =
                    GceRemoteCmdFormatter.getSshCommand(
                            options.getSshPrivateKeyPath(),
                            null,
                            options.getInstanceUser(),
                            remoteInstance.hostAndPort().getHost(),
                            command);
            return runUtil.runTimedCmd(timeoutMs, stdout, stderr, sshCmd.toArray(new String[0]));
        } finally {
            if (remoteInstance.isOxygenationDevice()) {
                // Once the ssh/scp is executed successfully, close the ssh tunnel.
                oxygenClient.closeLHPConnection(sshTunnel);
            }
        }
    }

    /**
     * Execute a command on the remote instance using ssh.
     *
     * @param remoteInstance The {@link GceAvdInfo} that describe the device.
     * @param options a {@link TestDeviceOptions} describing the device options to be used for the
     *     GCE device.
     * @param runUtil a {@link IRunUtil} to execute commands.
     * @param timeoutMs in millisecond for the fetch to complete
     * @param command The command to be executed.
     * @return A {@link CommandResult} containing the status and logs.
     */
    public static CommandResult remoteSshCommandExec(
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs,
            String... command) {
        return remoteSshCommandExec(
                remoteInstance, options, runUtil, timeoutMs, null, null, command);
    }
}
