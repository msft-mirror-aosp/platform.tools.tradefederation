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

package com.android.tradefed.observatory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.command.ICommandOptions;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.UniqueMultiMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class TestDiscoveryInvokerTests {
    private IRunUtil mRunUtil;
    private File mRootDir;
    private File mTradefedJar;
    private File mCompatibilityJar;
    private IConfiguration mConfiguration;
    private ICommandOptions mCommandOptions;
    private TestDiscoveryInvoker mTestDiscoveryInvoker;
    private static final String CONFIG_NAME = "test_config_name";
    private static final String TEST_MODULE_1_NAME = "test_module_1";
    private static final String TEST_MODULE_2_NAME = "test_module_2";

    @Before
    public void setUp() throws Exception {
        mRunUtil = Mockito.mock(IRunUtil.class);
        mConfiguration = Mockito.mock(IConfiguration.class);
        mCommandOptions = Mockito.mock(ICommandOptions.class);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return mCommandOptions;
                            }
                        })
                .when(mConfiguration)
                .getCommandOptions();
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return new UniqueMultiMap<String, String>();
                            }
                        })
                .when(mCommandOptions)
                .getInvocationData();
        mRootDir = FileUtil.createTempDir("test_suite_root");
        File mainDir = FileUtil.createNamedTempDir(mRootDir, "android-xts");
        mTestDiscoveryInvoker =
                new TestDiscoveryInvoker(mConfiguration, mRootDir) {
                    @Override
                    IRunUtil getRunUtil() {
                        return mRunUtil;
                    }
                };
        File toolsDir = FileUtil.createNamedTempDir(mainDir, "tools");
        mTradefedJar = FileUtil.createNamedTempDir(toolsDir, "tradefed.jar");
        mCompatibilityJar = FileUtil.createNamedTempDir(toolsDir, "compatibility_mock.jar");
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mRootDir);
    }

    /** Test the invocation when all necessary information are in the command line. */
    @Test
    public void testSuccessTestDiscoveryInvocation() throws Exception {
        String successStdout =
                "{\"TestModules\":[" + TEST_MODULE_1_NAME + "," + TEST_MODULE_2_NAME + "]}";
        String commandLine =
                String.format(
                        "random/test/name --cts-package-name android-cts.zip --cts-params"
                            + " --include-test-log-tags --cts-params --log-level --cts-params"
                            + " VERBOSE --cts-params --logcat-on-failure --config-name %s"
                            + " --cts-params --test-tag-suffix --cts-params x86 --cts-params"
                            + " --compatibility:test-arg --cts-params"
                            + " com.android.tradefed.testtype.HostTest:include-annotation:android.platform.test.annotations.Presubmit"
                            + " --cts-params --compatibility:include-filter --cts-params %s"
                            + " --cts-params --compatibility:include-filter --cts-params %s"
                            + " --test-tag --cts-params camera-presubmit --test-tag"
                            + " camera-presubmit --post-method=TEST_ARTIFACT",
                        CONFIG_NAME, TEST_MODULE_1_NAME, TEST_MODULE_2_NAME);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return commandLine;
                            }
                        })
                .when(mConfiguration)
                .getCommandLine();
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                Set<String> args = new HashSet<>();
                                for (int i = 1; i < mock.getArguments().length; i++) {
                                    args.add(mock.getArgument(i));
                                }

                                // Those are the necessary args that we care about
                                assertTrue(
                                        args.contains(
                                                mCompatibilityJar.getAbsolutePath()
                                                        + ":"
                                                        + mTradefedJar.getAbsolutePath()));
                                assertTrue(
                                        args.contains(
                                                TestDiscoveryInvoker
                                                        .TRADEFED_OBSERVATORY_ENTRY_PATH));
                                assertTrue(args.contains(CONFIG_NAME));
                                assertTrue(args.contains("--compatibility:include-filter"));
                                assertTrue(args.contains(TEST_MODULE_1_NAME));
                                assertTrue(args.contains(TEST_MODULE_2_NAME));

                                // Both cts params and config name should already been filtered out
                                // and applied
                                assertFalse(args.contains("--cts-params"));
                                assertFalse(args.contains("--config-name"));
                                CommandResult res = new CommandResult();
                                res.setExitCode(0);
                                res.setStatus(CommandStatus.SUCCESS);
                                res.setStdout(successStdout);
                                return res;
                            }
                        })
                .when(mRunUtil)
                .runTimedCmd(Mockito.anyLong(), Mockito.any());
        List<String> testModules = mTestDiscoveryInvoker.discoverTestModuleNames();
        assertEquals(testModules.size(), 2);
        assertTrue(testModules.contains(TEST_MODULE_1_NAME));
        assertTrue(testModules.contains(TEST_MODULE_2_NAME));
    }

    /**
     * Test the invocation when the command line does not have all necessary information for the
     * subprocess.
     */
    @Test
    public void testFailTestDiscoveryInvocation() throws Exception {
        // --config-name is missing from the cmd
        String commandLine =
                String.format(
                        "random/test/name --cts-package-name android-cts.zip --cts-params"
                            + " --include-test-log-tags --cts-params --log-level --cts-params"
                            + " VERBOSE --cts-params --logcat-on-failure --cts-params"
                            + " --test-tag-suffix --cts-params x86 --cts-params"
                            + " --compatibility:test-arg --cts-params"
                            + " com.android.tradefed.testtype.HostTest:include-annotation:android.platform.test.annotations.Presubmit"
                            + " --cts-params --compatibility:include-filter --cts-params %s"
                            + " --cts-params --compatibility:include-filter --cts-params %s"
                            + " --test-tag --cts-params camera-presubmit --test-tag"
                            + " camera-presubmit --post-method=TEST_ARTIFACT",
                        TEST_MODULE_1_NAME, TEST_MODULE_2_NAME);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                return commandLine;
                            }
                        })
                .when(mConfiguration)
                .getCommandLine();
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock mock) throws Throwable {
                                fail(
                                        "TestDiscoveryInvoker should not able to build a Java cmd"
                                            + " to invoke tradefed observatory without config-name"
                                            + " in parent test's command line options");
                                return null;
                            }
                        })
                .when(mRunUtil)
                .runTimedCmd(Mockito.anyLong(), Mockito.any());
        try {
            List<String> testModules = mTestDiscoveryInvoker.discoverTestModuleNames();
            fail("Should throw a ConfigurationException");
        } catch (Exception e) {
            assertTrue(e instanceof ConfigurationException);
        }
    }
}
