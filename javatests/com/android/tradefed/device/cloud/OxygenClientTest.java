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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;

import com.google.common.base.Joiner;
import com.google.common.net.HostAndPort;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link OxygenClient} */
@RunWith(JUnit4.class)
public class OxygenClientTest {

    private File mOxygenBinaryFile;

    private OxygenClient mOxygenClient;

    private BuildInfo mBuildInfo;

    private GceAvdInfo mGceAvdInfo;

    private TestDeviceOptions mTestDeviceOptions;

    private IRunUtil mRunUtil;

    private static final String[] GCE_DEVICE_PARAMS =
            new String[] {
                "--branch",
                "testBranch",
                "--build-target",
                "target",
                "--build-id",
                "P1234567",
                "--system-build-target",
                "testSystemTarget",
                "--system-build-id",
                "S1234567",
                "--kernel-build-target",
                "testKernelTarget",
                "--kernel-build-id",
                "K1234567"
            };

    private static final String EXPECTED_OUTPUT =
            "debug info lease result: session_id:\"6a6a744e-0653-4926-b7b8-535d121a2fc9\"\n"
                    + " server_url:\"10.0.80.227\"\n"
                    + " ports:{type:test value:12345}\n"
                    + " random_key:\"this-is-12345678\"\n"
                    + " leased_device_spec:{type:TESTTYPE build_artifacts:{build_id:\"P1234567\""
                    + " build_target:\"target\" build_branch:\"testBranch\"}}"
                    + " debug_info:{reserved_cores:1 region:\"test-region\" environment:\"test\"}";

    @Before
    public void setUp() throws Exception {
        mOxygenBinaryFile = FileUtil.createTempFile("oxygen", "binary");
        mBuildInfo = new BuildInfo("P1234567", "target");
        mBuildInfo.setBuildBranch("testBranch");
        mGceAvdInfo =
                new GceAvdInfo(
                        "6a6a744e-0653-4926-b7b8-535d121a2fc9",
                        HostAndPort.fromString("10.0.80.227").withDefaultPort(12345));
        mTestDeviceOptions =
                new TestDeviceOptions() {
                    @Override
                    public List<String> getGceDriverParams() {
                        return Arrays.asList(GCE_DEVICE_PARAMS);
                    }
                };
        OptionSetter setter = new OptionSetter(mTestDeviceOptions);
        setter.setOptionValue("oxygen-target-region", "us-east");
        setter.setOptionValue("oxygen-lease-length", "60m");
        setter.setOptionValue("oxygen-device-size", "large");
        setter.setOptionValue("oxygen-service-address", "10.1.23.45");
        setter.setOptionValue("gce-boot-timeout", "900000");
        setter.setOptionValue("oxygen-accounting-user", "random1234@space.com");
        mRunUtil = Mockito.mock(IRunUtil.class);
        mOxygenClient = new OxygenClient(mOxygenBinaryFile, mRunUtil);
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mOxygenBinaryFile);
    }

    /** Test leasing a device with Oxygen client binary. */
    @Test
    public void testLease() throws Exception {
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                long timeout = mock.getArgument(0);
                                List<String> cmd = new ArrayList<>();
                                for (int i = 1; i < mock.getArguments().length; i++) {
                                    cmd.add(mock.getArgument(i));
                                }
                                String cmdString = Joiner.on(" ").join(cmd);
                                String expectedCmdString =
                                        mOxygenBinaryFile.getAbsolutePath()
                                                + " -lease -build_branch testBranch -build_target"
                                                + " target -build_id P1234567"
                                                + " -system_build_target testSystemTarget"
                                                + " -system_build_id S1234567"
                                                + " -kernel_build_target testKernelTarget"
                                                + " -kernel_build_id K1234567 -target_region"
                                                + " us-east -accounting_user random1234@space.com"
                                                + " -lease_length_secs 3600";
                                assertEquals(timeout, 900000);
                                assertEquals(expectedCmdString, cmdString);

                                CommandResult res = new CommandResult();
                                res.setStatus(CommandStatus.SUCCESS);
                                res.setStdout("");
                                res.setStderr(EXPECTED_OUTPUT);
                                return res;
                            }
                        })
                .when(mRunUtil)
                .runTimedCmd(Mockito.anyLong(), Mockito.any());
        CommandResult res = mOxygenClient.lease(mBuildInfo, mTestDeviceOptions);
        assertEquals(res.getStatus(), CommandStatus.SUCCESS);
        assertEquals(res.getStderr(), EXPECTED_OUTPUT);
    }

    /** Test releasing a device with Oxygen client binary. */
    @Test
    public void testRelease() throws Exception {
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                long timeout = mock.getArgument(0);
                                List<String> cmd = new ArrayList<>();
                                for (int i = 1; i < mock.getArguments().length; i++) {
                                    cmd.add(mock.getArgument(i));
                                }
                                String cmdString = Joiner.on(" ").join(cmd);
                                String expectedCmdString =
                                        mOxygenBinaryFile.getAbsolutePath()
                                                + " -release -server_url 10.0.80.227"
                                                + " -session_id"
                                                + " 6a6a744e-0653-4926-b7b8-535d121a2fc9";
                                assertEquals(timeout, 900000);
                                assertEquals(expectedCmdString, cmdString);

                                CommandResult res = new CommandResult();
                                res.setStatus(CommandStatus.SUCCESS);
                                return res;
                            }
                        })
                .when(mRunUtil)
                .runTimedCmd(Mockito.anyLong(), Mockito.any());
        boolean isReleased =
                mOxygenClient.release(mGceAvdInfo, mTestDeviceOptions.getGceCmdTimeout());
        assertTrue(isReleased);
    }
}
