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

package com.android.tradefed.util.avd;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.RunUtil;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A class that manages the use of Oxygen client binary to lease or release Oxygen device. */
public class OxygenClient {

    public enum LHPTunnelMode {
        SSH,
        ADB,
        CURL;
    }

    // A list of commands to be executed to lease or release Oxygen devices, examples:
    // 1. if the binary is an executable script, execute it directly.
    // 2. if the binary is a jar file, execute it by using java -jar ${binary_path}.
    private final List<String> mCmdArgs = Lists.newArrayList();

    private IRunUtil mRunUtil;

    // A list of attributes to be stored in Oxygen metadata.
    private static final Set<String> INVOCATION_ATTRIBUTES =
            new HashSet<>(Arrays.asList("work_unit_id"));

    // We can't be sure from GceDeviceParams use _ in options or -. This is because acloud used -
    // in its options while Oxygen use _. For compatibility reason, this mapping is needed.
    public static final Map<String, String> sGceDeviceParamsToOxygenMap =
            Stream.of(
                            new String[][] {
                                {"--branch", "-build_branch"},
                                {"--build-branch", "-build_branch"},
                                {"--build_branch", "-build_branch"},
                                {"--build-target", "-build_target"},
                                {"--build_target", "-build_target"},
                                {"--build-id", "-build_id"},
                                {"--build_id", "-build_id"},
                                {"--system-build-id", "-system_build_id"},
                                {"--system_build_id", "-system_build_id"},
                                {"--system-build-target", "-system_build_target"},
                                {"--system_build_target", "-system_build_target"},
                                {"--kernel-build-id", "-kernel_build_id"},
                                {"--kernel_build_id", "-kernel_build_id"},
                                {"--kernel-build-target", "-kernel_build_target"},
                                {"--kernel_build_target", "-kernel_build_target"},
                                {"--boot-build-id", "-boot_build_id"},
                                {"--boot_build_id", "-boot_build_id"},
                                {"--boot-build-target", "-boot_build_target"},
                                {"--boot_build_target", "-boot_build_target"},
                                {"--boot-artifact", "-boot_artifact"},
                                {"--boot_artifact", "-boot_artifact"},
                                {"--host_package_build_id", "-host_package_build_id"},
                                {"--host_package_build_target", "-host_package_build_target"},
                                {"--bootloader-build-id", "-bootloader_build_id"},
                                {"--bootloader_build_id", "-bootloader_build_id"},
                                {"--bootloader-build-target", "-bootloader_build_target"},
                                {"--bootloader_build_target", "-bootloader_build_target"}
                            })
                    .collect(
                            Collectors.collectingAndThen(
                                    Collectors.toMap(data -> data[0], data -> data[1]),
                                    Collections::<String, String>unmodifiableMap));

    protected IRunUtil getRunUtil() {
        return mRunUtil;
    }

    /**
     * The constructor of OxygenClient class.
     *
     * @param cmdArgs a {@link List<String>} of commands to run Oxygen client.
     * @param runUtil a {@link IRunUtil} to execute commands.
     */
    public OxygenClient(List<String> cmdArgs, IRunUtil runUtil) {
        mCmdArgs.addAll(cmdArgs);
        mRunUtil = runUtil;
    }

    /**
     * The constructor of OxygenClient class.
     *
     * @param cmdArgs a {@link List<String>} of commands to run Oxygen client.
     */
    public OxygenClient(List<String> cmdArgs) {
        mCmdArgs.addAll(cmdArgs);
        mRunUtil = RunUtil.getDefault();
    }

    /**
     * Adds invocation attributes to the given list of arguments.
     *
     * @param args command line args to call Oxygen client
     * @param attributes the map of attributes to add
     */
    private void addInvocationAttributes(List<String> args, MultiMap<String, String> attributes) {
        if (attributes == null) {
            return;
        }
        List<String> debugInfo = new ArrayList<>();
        for (Map.Entry<String, String> attr : attributes.entries()) {
            if (INVOCATION_ATTRIBUTES.contains(attr.getKey())) {
                debugInfo.add(String.format("%s:%s", attr.getKey(), attr.getValue()));
            }
        }
        if (debugInfo.size() > 0) {
            args.add("-user_debug_info");
            args.add(String.join(",", debugInfo));
        }
    }

    /**
     * Attempt to lease a device by calling Oxygen client binary.
     *
     * @param buildTarget build target
     * @param buildBranch build branch
     * @param buildId build ID
     * @param targetRegion target region for Oxygen instance
     * @param accountingUser Oxygen accounting user email
     * @param leaseLength number of ms for the lease duration
     * @param gceDriverParams {@link List<String>} of gce driver params
     * @param extraOxygenArgs {@link Map<String, String>} of extra Oxygen lease args
     * @param attributes attributes associated with current invocation
     * @param gceCmdTimeout number of ms for the command line timeout
     * @param useOxygenation whether the device is leased from OmniLab Infra or not.
     * @return a {@link CommandResult} that Oxygen binary returned.
     */
    public CommandResult leaseDevice(
            String buildTarget,
            String buildBranch,
            String buildId,
            String targetRegion,
            String accountingUser,
            long leaseLength,
            List<String> gceDriverParams,
            Map<String, String> extraOxygenArgs,
            MultiMap<String, String> attributes,
            long gceCmdTimeout,
            boolean useOxygenation) {
        List<String> oxygenClientArgs = Lists.newArrayList(mCmdArgs);
        oxygenClientArgs.add("-lease");
        // Add options from GceDriverParams
        int i = 0;
        String branch = null;
        Boolean buildIdSet = false;
        while (i < gceDriverParams.size()) {
            String gceDriverOption = gceDriverParams.get(i);
            if (sGceDeviceParamsToOxygenMap.containsKey(gceDriverOption)) {
                // add device build options in oxygen's way
                oxygenClientArgs.add(sGceDeviceParamsToOxygenMap.get(gceDriverOption));
                // add option's value
                oxygenClientArgs.add(gceDriverParams.get(i + 1));
                if (gceDriverOption.equals("--branch")) {
                    branch = gceDriverParams.get(i + 1);
                } else if (!buildIdSet
                        && sGceDeviceParamsToOxygenMap.get(gceDriverOption).equals("-build_id")) {
                    buildIdSet = true;
                }
                i++;
            }
            i++;
        }

        // In case branch is set through gce-driver-param, but not build-id, set the name of
        // branch to option `-build-id`, so LKGB will be used.
        if (branch != null && !buildIdSet) {
            oxygenClientArgs.add("-build_id");
            oxygenClientArgs.add(branch);
        }

        // check if build info exists after added from GceDriverParams
        if (!oxygenClientArgs.contains("-build_target")) {
            oxygenClientArgs.add("-build_target");
            oxygenClientArgs.add(buildTarget);
            oxygenClientArgs.add("-build_branch");
            oxygenClientArgs.add(buildBranch);
            oxygenClientArgs.add("-build_id");
            oxygenClientArgs.add(buildId);
        }

        // add oxygen side lease options
        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(targetRegion);
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(accountingUser);
        oxygenClientArgs.add("-lease_length_secs");
        oxygenClientArgs.add(Long.toString(leaseLength / 1000));

        // Check if there is a new CVD path to override
        if (extraOxygenArgs.containsKey("override_cvd_path")) {
            oxygenClientArgs.add("-override_cvd_path");
            oxygenClientArgs.add(extraOxygenArgs.get("override_cvd_path"));
        }

        for (Map.Entry<String, String> arg : extraOxygenArgs.entrySet()) {
            oxygenClientArgs.add("-" + arg.getKey());
            if (!Strings.isNullOrEmpty(arg.getValue())) {
                oxygenClientArgs.add(arg.getValue());
            }
        }

        addInvocationAttributes(oxygenClientArgs, attributes);

        if (useOxygenation) {
            oxygenClientArgs.add("-use_omnilab");
        }

        CLog.i("Leasing device from oxygen client with %s", oxygenClientArgs.toString());
        return runOxygenTimedCmd(
                oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]), gceCmdTimeout);
    }

    /**
     * Attempt to lease multiple devices by calling Oxygen client binary.
     *
     * @param buildTargets a {@link List<String>} of build targets
     * @param buildBranches a {@link List<String>} of build branches
     * @param buildIds a {@link List<String>} of build IDs
     * @param targetRegion target region for Oxygen instance
     * @param accountingUser Oxygen accounting user email
     * @param leaseLength number of ms for the lease duration
     * @param gceDriverParams {@link List<String>} of gce driver params
     * @param extraOxygenArgs {@link Map<String, String>} of extra Oxygen lease args
     * @param attributes attributes associated with current invocation
     * @param gceCmdTimeout number of ms for the command line timeout
     * @return {@link CommandResult} that Oxygen binary returned.
     */
    public CommandResult leaseMultipleDevices(
            List<String> buildTargets,
            List<String> buildBranches,
            List<String> buildIds,
            String targetRegion,
            String accountingUser,
            long leaseLength,
            Map<String, String> extraOxygenArgs,
            MultiMap<String, String> attributes,
            long gceCmdTimeout) {
        List<String> oxygenClientArgs = Lists.newArrayList(mCmdArgs);
        oxygenClientArgs.add("-lease");

        if (buildTargets.size() > 0) {
            oxygenClientArgs.add("-build_target");
            oxygenClientArgs.add(String.join(",", buildTargets));
        }

        if (buildBranches.size() > 0) {
            oxygenClientArgs.add("-build_branch");
            oxygenClientArgs.add(String.join(",", buildBranches));
        }
        if (buildIds.size() > 0) {
            oxygenClientArgs.add("-build_id");
            oxygenClientArgs.add(String.join(",", buildIds));
        }
        oxygenClientArgs.add("-multidevice_size");
        oxygenClientArgs.add(String.valueOf(buildTargets.size()));
        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(targetRegion);
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(accountingUser);
        oxygenClientArgs.add("-lease_length_secs");
        oxygenClientArgs.add(Long.toString(leaseLength / 1000));

        for (Map.Entry<String, String> arg : extraOxygenArgs.entrySet()) {
            oxygenClientArgs.add("-" + arg.getKey());
            if (!Strings.isNullOrEmpty(arg.getValue())) {
                oxygenClientArgs.add(arg.getValue());
            }
        }

        addInvocationAttributes(oxygenClientArgs, attributes);

        CLog.i("Leasing multiple devices from oxygen client with %s", oxygenClientArgs.toString());
        return runOxygenTimedCmd(
                oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]), gceCmdTimeout);
    }

    /**
     * Attempt to release a device by using Oxygen client binary.
     *
     * @param instanceName name of the Oxygen instance
     * @param host hostname of the Oxygen instance
     * @param targetRegion target region
     * @param accountingUser name of accounting user email
     * @param extraOxygenArgs {@link Map<String, String>} of extra Oxygen args
     * @param gceCmdTimeout number of ms for the command line timeout
     * @param useOxygenation whether the device is leased from OmniLab Infra or not.
     * @return a {@link CommandResult} that Oxygen binary returned.
     */
    public CommandResult release(
            String instanceName,
            String host,
            String targetRegion,
            String accountingUser,
            Map<String, String> extraOxygenArgs,
            long gceCmdTimeout,
            boolean useOxygenation) {
        List<String> oxygenClientArgs = Lists.newArrayList(mCmdArgs);

        // If gceAvdInfo is missing info, then it means the device wasn't get leased successfully.
        // In such case, there is no need to release the device.
        if (instanceName == null || host == null) {
            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            return res;
        }

        if (extraOxygenArgs != null) {
            for (Map.Entry<String, String> arg : extraOxygenArgs.entrySet()) {
                oxygenClientArgs.add("-" + arg.getKey());
                if (!Strings.isNullOrEmpty(arg.getValue())) {
                    oxygenClientArgs.add(arg.getValue());
                }
            }
        }

        oxygenClientArgs.add("-release");
        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(targetRegion);
        oxygenClientArgs.add("-server_url");
        oxygenClientArgs.add(host);
        oxygenClientArgs.add("-session_id");
        oxygenClientArgs.add(instanceName);
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(accountingUser);
        if (useOxygenation) {
            oxygenClientArgs.add("-use_omnilab");
        }
        CLog.i("Releasing device from oxygen client with command %s", oxygenClientArgs.toString());
        return runOxygenTimedCmd(
                oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]), gceCmdTimeout);
    }

    /**
     * Create an adb or ssh tunnel to a given instance name and assign the endpoint to a device via
     * LHP based on the given tunnel mode.
     *
     * @param mode The mode for oxygen client to talk to the device.
     * @param portNumber The port number that Host Orchestrator communicates with.
     * @param sessionId The session id returned by lease method in oxygenation.
     * @param serverUrl The server url returned by lease method in oxygenation.
     * @param targetRegion The target region for Oxygen instance
     * @param accountingUser Oxygen accounting user email
     * @param oxygenationDeviceId The device id returned by lease method in oxygenation.
     * @param extraOxygenArgs {@link Map<String, String>} of extra Oxygen lease args
     * @param tunnelLog {@link FileOutputStream} for storing logs.
     * @return {@link Process} of the adb over LHP tunnel.
     */
    public Process createTunnelViaLHP(
            LHPTunnelMode mode,
            String portNumber,
            String sessionId,
            String serverUrl,
            String targetRegion,
            String accountingUser,
            String oxygenationDeviceId,
            Map<String, String> extraOxygenArgs,
            FileOutputStream tunnelLog) {
        Process lhpTunnel = null;
        List<String> oxygenClientArgs = Lists.newArrayList(mCmdArgs);
        oxygenClientArgs.add("-build_lab_host_proxy_tunnel");
        oxygenClientArgs.add("-server_url");
        oxygenClientArgs.add(serverUrl);
        oxygenClientArgs.add("-session_id");
        oxygenClientArgs.add(sessionId);

        if (extraOxygenArgs != null) {
            for (Map.Entry<String, String> arg : extraOxygenArgs.entrySet()) {
                oxygenClientArgs.add("-" + arg.getKey());
                if (!Strings.isNullOrEmpty(arg.getValue())) {
                    oxygenClientArgs.add(arg.getValue());
                }
            }
        }

        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(targetRegion);
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(accountingUser);
        oxygenClientArgs.add("-use_omnilab");
        oxygenClientArgs.add("-tunnel_type");
        if (LHPTunnelMode.ADB.equals(mode)) {
            oxygenClientArgs.add("adb");
        } else if (LHPTunnelMode.CURL.equals(mode)) {
            oxygenClientArgs.add("curl");
        } else {
            oxygenClientArgs.add("ssh");
        }
        oxygenClientArgs.add("-tunnel_local_port");
        oxygenClientArgs.add(portNumber);
        oxygenClientArgs.add("-device_id");
        oxygenClientArgs.add(oxygenationDeviceId);
        try {
            CLog.i(
                    "Building %s tunnel from oxygen client with command %s...",
                    mode, oxygenClientArgs.toString());
            tunnelLog.write(String.format("\n=== Beginning ===\n").getBytes());
            tunnelLog.write(
                    String.format("\n=== Session id: %s, Server URL: %s===\n", sessionId, serverUrl)
                            .getBytes());
            lhpTunnel = getRunUtil().runCmdInBackground(oxygenClientArgs, tunnelLog);
            // TODO(b/363861223): reduce the waiting time when LHP is stable.
            getRunUtil().sleep(15 * 1000);
        } catch (IOException e) {
            CLog.d("Failed connecting to remote GCE using %s over LHP, %s", mode, e.getMessage());
        }
        if (lhpTunnel == null || !lhpTunnel.isAlive()) {
            closeLHPConnection(lhpTunnel);
            return null;
        }
        return lhpTunnel;
    }

    /** Helper to create an unused server socket. */
    public Integer createServerSocket() {
        ServerSocket s = null;
        try {
            s = new ServerSocket(0);
            // even after being closed, socket may remain in TIME_WAIT state
            // reuse address allows to connect to it even in this state.
            s.setReuseAddress(true);
            s.close();
        } catch (IOException e) {
            CLog.d("Failed to connect to remote GCE using adb tunnel %s", e.getMessage());
        }
        return s.getLocalPort();
    }

    /** Close the connection to the remote oxygenation device with a given {@link Process}. */
    public void closeLHPConnection(Process p) {
        if (p != null) {
            p.destroy();
            try {
                boolean res = p.waitFor(20 * 1000, TimeUnit.MILLISECONDS);
                if (!res) {
                    CLog.e("Tunnel may not have properly terminated.");
                }
            } catch (InterruptedException e) {
                CLog.e("Tunnel interrupted during shutdown: %s", e.getMessage());
            }
        }
    }

    /**
     * Utility function to execute a timed Oxygen command with logging.
     *
     * @param oxygenCmd command line options.
     * @param timeout command timeout.
     * @return {@link CommandResult}.
     */
    private CommandResult runOxygenTimedCmd(String[] oxygenCmd, long timeout) {
        CommandResult res = getRunUtil().runTimedCmd(timeout, oxygenCmd);
        CLog.i(
                "Oxygen client result status: %s, stdout: %s, stderr: %s",
                res.getStatus(), res.getStdout(), res.getStderr());
        return res;
    }
}
