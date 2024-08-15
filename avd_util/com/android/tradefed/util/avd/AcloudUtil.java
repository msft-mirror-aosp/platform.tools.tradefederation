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

import com.android.tradefed.util.MultiMap;

import com.google.common.collect.Lists;

import java.io.File;
import java.util.List;
import java.util.Map;

/** A utility for Acloud related operations */
public class AcloudUtil {

    /**
     * Build and return the command to launch GCE. Exposed for testing.
     *
     * @param binary Full path to the binary to run
     * @param createCmd command to create the instance, e.g., create, create_gf
     * @param reportFile Full path to the report file to save the output to
     * @param buildTarget name of the device build target
     * @param buildBranch name of the device build branch
     * @param buildID ID of the device build
     * @param ipDevice IP address of the remote host
     * @param hostUser Name of the user to use for the remote host, must be set if ipDevice is set
     * @param sshKeyPath path to the private ssh key file
     * @param extraArgs a {@link List<String>} of extra command line args
     * @param serviceAccountKeyPath path to the service account json key file
     * @param offset integer of the device offset
     * @param gceAccount account for the GCP project
     * @param gceDriverParams a {@link List<String>} of parameters from gce-driver-param option
     * @param gceDriverFileParams a {@link MultiMap<String, File>} of parameters from
     *     gce-driver-file-param option
     * @param extraFiles a {@link MultiMap<File, String>} of extra files fromg gce-extra-files
     *     option, which contains the files to upload GCE instance during Acloud create. Key is
     *     local file, value is GCE destination path.
     * @return a {@link List<String>} of command line args
     */
    public static List<String> buildGceCmd(
            String binary,
            String createCmd,
            File reportFile,
            String buildTarget,
            String buildBranch,
            String buildId,
            String ipDevice,
            String hostUser,
            String sshKeyPath,
            List<String> extraArgs,
            String serviceAccountKeyPath,
            Integer offset,
            String gceAccount,
            List<String> gceDriverParams,
            MultiMap<String, File> gceDriverFileParams,
            MultiMap<File, String> extraFiles) {
        List<String> gceArgs = Lists.newArrayList(binary);
        gceArgs.add(createCmd);
        gceArgs.addAll(extraArgs);

        /* If args passed by gce-driver-param contain build-target or build_target, or
        test device options include local-image and cvd-host-package to side load prebuilt virtual
        device images, there is no need to pass the build info from device BuildInfo to gce
        arguments. Otherwise, generate gce args from device BuildInfo. Please refer to acloud
        arguments for the supported format:
        https://android.googlesource.com/platform/tools/acloud/+/refs/heads/master/create/create_args.py  */
        if (!gceDriverParams.contains("--build-target")
                && !gceDriverParams.contains("--build_target")
                && !(gceDriverFileParams.containsKey("local-image")
                        && gceDriverFileParams.containsKey("cvd-host-package"))) {
            gceArgs.add("--build-target");
            gceArgs.add(buildTarget);
            gceArgs.add("--branch");
            gceArgs.add(buildBranch);
            gceArgs.add("--build-id");
            gceArgs.add(buildId);
        }

        for (Map.Entry<String, File> entry : gceDriverFileParams.entries()) {
            gceArgs.add("--" + entry.getKey());
            gceArgs.add(entry.getValue().getAbsolutePath());
        }

        if (!extraFiles.isEmpty()) {
            gceArgs.add("--extra-files");
            for (File local : extraFiles.keySet()) {
                for (String remoteDestination : extraFiles.get(local)) {
                    gceArgs.add(local.getAbsolutePath() + "," + remoteDestination);
                }
            }
        }

        // Add additional args passed by gce-driver-param.
        gceArgs.addAll(gceDriverParams);
        if (serviceAccountKeyPath != null) {
            gceArgs.add("--service-account-json-private-key-path");
            gceArgs.add(serviceAccountKeyPath);
        }

        if (ipDevice != null) {
            gceArgs.add("--host");
            gceArgs.add(ipDevice);
            gceArgs.add("--host-user");
            gceArgs.add(hostUser);
            gceArgs.add("--host-ssh-private-key-path");
            gceArgs.add(sshKeyPath);
        }
        gceArgs.add("--report_file");
        gceArgs.add(reportFile.getAbsolutePath());

        // Add base-instance-num args with offset, and override the remote adb port.
        // When offset is 1, base-instance-num=2 and virtual device adb forward port is 6521.
        if (offset != null) {
            gceArgs.add("--base-instance-num");
            gceArgs.add(String.valueOf(offset + 1));
        }

        if (gceAccount != null) {
            gceArgs.add("--email");
            gceArgs.add(gceAccount);
        }
        // Do not pass flags --logcat_file and --serial_log_file to collect logcat and serial logs.

        return gceArgs;
    }
}
