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

import static org.junit.Assert.assertEquals;

import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test for {@link AcloudUtil} */
public class AcloudUtilTest {
    /** Test {@link AcloudUtil#buildGceCmd}. */
    @Test
    public void testBuildGceCommand() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            null, // idDevice
                            null, // hostUser
                            null, // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            "/path/to/key.json", // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            new ArrayList<>(), // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--service-account-json-private-key-path",
                                    "/path/to/key.json",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with IP based device. */
    @Test
    public void testBuildGceCommandWithIpDevice() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            "bar", // idDevice
                            "foo", // hostUser
                            "/path/to/id_rsa", // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            "/path/to/key.json", // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            new ArrayList<>(), // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--service-account-json-private-key-path",
                                    "/path/to/key.json",
                                    "--host",
                                    "bar",
                                    "--host-user",
                                    "foo",
                                    "--host-ssh-private-key-path",
                                    "/path/to/id_rsa",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with emulator build. */
    @Test
    public void testBuildGceCommandWithEmulatorBuild() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            List<String> gceDriverParams =
                    new ArrayList<>(Arrays.asList("--emulator-build-id", "EMULATOR_BUILD_ID"));

            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            null, // idDevice
                            null, // hostUser
                            null, // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            null, // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            gceDriverParams, // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--emulator-build-id",
                                    "EMULATOR_BUILD_ID",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with specified images. */
    @Test
    public void testBuildGceCommandWithSpecifiedImages() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            MultiMap<String, File> gceDriverFileParams = new MultiMap<String, File>();
            gceDriverFileParams.put(
                    "cvd-host-package", new File("/path/to/cvd-host-package.tar.gz"));
            gceDriverFileParams.put(
                    "local-image", new File("/path/to/cvd-cuttlefish-android-os.tar.gz"));
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            null, // idDevice
                            null, // hostUser
                            null, // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            null, // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            new ArrayList<>(), // gceDriverParams
                            gceDriverFileParams, // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--cvd-host-package",
                                    "/path/to/cvd-host-package.tar.gz",
                                    "--local-image",
                                    "/path/to/cvd-cuttlefish-android-os.tar.gz",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with preconfigured virtual device. */
    @Test
    public void testBuildGceCommand_withPreconfiguredVirtualDevice() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            "bar", // idDevice
                            "vsoc-01", // hostUser
                            "/path/to/id_rsa", // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            "/path/to/key.json", // serviceAccountKeyPath
                            2, // offset
                            null, // gceAccount
                            new ArrayList<>(), // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--service-account-json-private-key-path",
                                    "/path/to/key.json",
                                    "--host",
                                    "bar",
                                    "--host-user",
                                    "vsoc-01",
                                    "--host-ssh-private-key-path",
                                    "/path/to/id_rsa",
                                    "--report_file",
                                    reportFile.getAbsolutePath(),
                                    "--base-instance-num",
                                    "3"));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with extra files. */
    @Test
    public void testBuildGceCommandWithExtraFiles() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        File file1 = null;
        File file2 = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            MultiMap<File, String> extraFiles = new MultiMap<>();
            file1 = FileUtil.createTempFile("test_file1", ".txt");
            file2 = FileUtil.createTempFile("test_file2", ".txt");
            extraFiles.put(file1, "/home/vsoc-01/test_file1.txt");
            extraFiles.put(file2, "/home/vsoc-01/test_file2.txt");
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            null, // idDevice
                            null, // hostUser
                            null, // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            null, // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            new ArrayList<>(), // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            extraFiles // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--extra-files",
                                    file1.getAbsolutePath() + ",/home/vsoc-01/test_file1.txt",
                                    file2.getAbsolutePath() + ",/home/vsoc-01/test_file2.txt",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
            FileUtil.deleteFile(file1);
            FileUtil.deleteFile(file2);
        }
    }

    /** Test {@link AcloudUtil#buildGceCmd} with kernel build. */
    @Test
    public void testBuildGceCommandWithKernelBuild() throws IOException {
        MultiMap<String, String> stubAttributes = new MultiMap<>();
        File reportFile = null;
        try {
            reportFile = FileUtil.createTempFile("test-gce-cmd", "report");
            List<String> gceDriverParams =
                    new ArrayList<>(Arrays.asList("--kernel-build-id", "KERNELBUILDID"));
            List<String> result =
                    AcloudUtil.buildGceCmd(
                            "acloud",
                            "create",
                            reportFile,
                            "FLAVOR",
                            "BRANCH",
                            "BUILDID",
                            null, // idDevice
                            null, // hostUser
                            null, // sshKeyPath
                            new ArrayList<>(), // extraArgs
                            null, // serviceAccountKeyPath
                            null, // offset
                            null, // gceAccount
                            gceDriverParams, // gceDriverParams
                            new MultiMap<String, File>(), // gceDriverFileParams
                            new MultiMap<File, String>() // extraFiles
                            );
            List<String> expected =
                    new ArrayList<>(
                            Arrays.asList(
                                    "acloud",
                                    "create",
                                    "--build-target",
                                    "FLAVOR",
                                    "--branch",
                                    "BRANCH",
                                    "--build-id",
                                    "BUILDID",
                                    "--kernel-build-id",
                                    "KERNELBUILDID",
                                    "--report_file",
                                    reportFile.getAbsolutePath()));
            assertEquals(expected, result);
        } finally {
            FileUtil.deleteFile(reportFile);
        }
    }
}
