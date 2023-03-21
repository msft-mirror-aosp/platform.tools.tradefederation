/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Strings;

/** A class that manages the use of Oxygen client binary to lease or release Oxygen device. */
public class OxygenClient {

    private final File mClientBinary;

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
                                {"--kernel_build_target", "-kernel_build_target"}
                            })
                    .collect(
                            Collectors.collectingAndThen(
                                    Collectors.toMap(data -> data[0], data -> data[1]),
                                    Collections::<String, String>unmodifiableMap));

    @VisibleForTesting
    IRunUtil getRunUtil() {
        return mRunUtil;
    }

    @VisibleForTesting
    public OxygenClient(File clientBinary, IRunUtil runUtil) {
        this(clientBinary);
        mRunUtil = runUtil;
    }

    /**
     * The constructor of OxygenClient class.
     *
     * @param clientBinary the executable Oxygen client binary file.
     */
    public OxygenClient(File clientBinary) {
        mRunUtil = RunUtil.getDefault();
        String error = null;
        if (clientBinary == null) {
            error = "the Oxygen client binary reference is null";
        } else if (!clientBinary.exists()) {
            error =
                    String.format(
                            "the Oxygen client binary file does not exist at %s",
                            clientBinary.getAbsolutePath());
        } else if (!clientBinary.canExecute()) {
            error =
                    String.format(
                            "the Oxygen client binary file at %s is not executable",
                            clientBinary.getAbsolutePath());
        }
        if (clientBinary == null || !clientBinary.exists()) {
            throw new HarnessRuntimeException(
                    String.format("Error in instantiating OxygenClient class: %s", error),
                    InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
        }
        mClientBinary = clientBinary;
    }

    /**
     * Check if no_wait_for_boot is specified in Oxygen lease request
     *
     * @param deviceOptions {@link TestDeviceOptions}
     * @return true if no_wait_for_boot is specified
     */
    public Boolean noWaitForBootSpecified(TestDeviceOptions deviceOptions) {
        for (Map.Entry<String, String> arg : deviceOptions.getExtraOxygenArgs().entrySet()) {
            if (arg.getKey().equals("no_wait_for_boot")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if no_wait_for_boot is specified in Oxygen lease request
     *
     * @param args command line args to call Oxygen client
     * @param attributes attributes associated with current invocation
     * @return true if no_wait_for_boot is specified
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
     * @param b {@link IBuildInfo}
     * @param deviceOptions {@link TestDeviceOptions}
     * @param attributes attributes associated with current invocation
     * @return a {@link CommandResult} that Oxygen binary returned.
     */
    public CommandResult leaseDevice(
            IBuildInfo b, TestDeviceOptions deviceOptions, MultiMap<String, String> attributes) {
        List<String> oxygenClientArgs = ArrayUtil.list(mClientBinary.getAbsolutePath());
        List<String> gceDriverParams = deviceOptions.getGceDriverParams();
        oxygenClientArgs.add("-lease");
        // Add options from GceDriverParams
        int i = 0;
        while (i < gceDriverParams.size()) {
            String gceDriverOption = gceDriverParams.get(i);
            if (sGceDeviceParamsToOxygenMap.containsKey(gceDriverOption)) {
                // add device build options in oxygen's way
                oxygenClientArgs.add(sGceDeviceParamsToOxygenMap.get(gceDriverOption));
                // add option's value
                oxygenClientArgs.add(gceDriverParams.get(i + 1));
                i++;
            }
            i++;
        }

        // check if build info exists after added from GceDriverParams
        if (!oxygenClientArgs.contains("-build_target")) {
            oxygenClientArgs.add("-build_target");
            if (b.getBuildAttributes().containsKey("build_target")) {
                // If BuildInfo contains the attribute for a build target, use that.
                oxygenClientArgs.add(b.getBuildAttributes().get("build_target"));
            } else {
                oxygenClientArgs.add(b.getBuildFlavor());
            }
            oxygenClientArgs.add("-build_branch");
            oxygenClientArgs.add(b.getBuildBranch());
            oxygenClientArgs.add("-build_id");
            oxygenClientArgs.add(b.getBuildId());
        }

        // add oxygen side lease options
        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(deviceOptions.getOxygenTargetRegion());
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(deviceOptions.getOxygenAccountingUser());
        oxygenClientArgs.add("-lease_length_secs");
        oxygenClientArgs.add(Long.toString(deviceOptions.getOxygenLeaseLength() / 1000));

        for (Map.Entry<String, String> arg : deviceOptions.getExtraOxygenArgs().entrySet()) {
            oxygenClientArgs.add("-" + arg.getKey());
            if (!Strings.isNullOrEmpty(arg.getValue())) {
                oxygenClientArgs.add(arg.getValue());
            }
        }

        addInvocationAttributes(oxygenClientArgs, attributes);

        CLog.i("Leasing device from oxygen client with %s", oxygenClientArgs.toString());
        return runOxygenTimedCmd(
                oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]),
                deviceOptions.getGceCmdTimeout());
    }

    /**
     * Attempt to lease multiple devices by calling Oxygen client binary.
     *
     * @param buildInfos {@link List<IBuildInfo>}
     * @param deviceOptions {@link TestDeviceOptions}
     * @param attributes attributes associated with current invocation
     * @return {@link CommandResult} that Oxygen binary returned.
     */
    public CommandResult leaseMultipleDevices(
            List<IBuildInfo> buildInfos,
            TestDeviceOptions deviceOptions,
            MultiMap<String, String> attributes) {
        List<String> oxygenClientArgs = ArrayUtil.list(mClientBinary.getAbsolutePath());
        oxygenClientArgs.add("-lease");

        List<String> buildTargets = new ArrayList<>();
        List<String> buildBranches = new ArrayList<>();
        List<String> buildIds = new ArrayList<>();

        for (IBuildInfo b : buildInfos) {
            if (b.getBuildAttributes().containsKey("build_target")) {
                // If BuildInfo contains the attribute for a build target, use that.
                buildTargets.add(b.getBuildAttributes().get("build_target"));
            } else {
                buildTargets.add(b.getBuildFlavor());
            }
            buildBranches.add(b.getBuildBranch());
            buildIds.add(b.getBuildId());
        }

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
        oxygenClientArgs.add(String.valueOf(buildInfos.size()));
        oxygenClientArgs.add("-target_region");
        oxygenClientArgs.add(deviceOptions.getOxygenTargetRegion());
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(deviceOptions.getOxygenAccountingUser());
        oxygenClientArgs.add("-lease_length_secs");
        oxygenClientArgs.add(Long.toString(deviceOptions.getOxygenLeaseLength() / 1000));

        for (Map.Entry<String, String> arg : deviceOptions.getExtraOxygenArgs().entrySet()) {
            oxygenClientArgs.add("-" + arg.getKey());
            if (!Strings.isNullOrEmpty(arg.getValue())) {
                oxygenClientArgs.add(arg.getValue());
            }
        }

        addInvocationAttributes(oxygenClientArgs, attributes);

        CLog.i("Leasing multiple devices from oxygen client with %s", oxygenClientArgs.toString());
        return runOxygenTimedCmd(
                oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]),
                deviceOptions.getGceCmdTimeout());
    }

    /**
     * Attempt to release a device by using Oxygen client binary.
     *
     * @param gceAvdInfo {@link GceAvdInfo}
     * @param deviceOptions {@link TestDeviceOptions}
     * @return a boolean which indicate whether the device release is successful.
     */
    public boolean release(GceAvdInfo gceAvdInfo, TestDeviceOptions deviceOptions) {
        // If gceAvdInfo is missing info, then it means the device wasn't get leased successfully.
        // In such case, there is no need to release the device.
        if (gceAvdInfo == null
                || gceAvdInfo.instanceName() == null
                || gceAvdInfo.hostAndPort() == null
                || gceAvdInfo.hostAndPort().getHost() == null) {
            return true;
        }
        List<String> oxygenClientArgs = ArrayUtil.list(mClientBinary.getAbsolutePath());

        for (Map.Entry<String, String> arg : deviceOptions.getExtraOxygenArgs().entrySet()) {
            oxygenClientArgs.add("-" + arg.getKey());
            if (!Strings.isNullOrEmpty(arg.getValue())) {
                oxygenClientArgs.add(arg.getValue());
            }
        }

        oxygenClientArgs.add("-release");
        oxygenClientArgs.add("-server_url");
        oxygenClientArgs.add(gceAvdInfo.hostAndPort().getHost());
        oxygenClientArgs.add("-session_id");
        oxygenClientArgs.add(gceAvdInfo.instanceName());
        oxygenClientArgs.add("-accounting_user");
        oxygenClientArgs.add(deviceOptions.getOxygenAccountingUser());
        CLog.i("Releasing device from oxygen client with command %s", oxygenClientArgs.toString());
        CommandResult res =
                runOxygenTimedCmd(
                        oxygenClientArgs.toArray(new String[oxygenClientArgs.size()]),
                        deviceOptions.getGceCmdTimeout());
        return res.getStatus().equals(CommandStatus.SUCCESS);
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
