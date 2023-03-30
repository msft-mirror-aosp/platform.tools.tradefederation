/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tradefed.testtype.mobly;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.DeviceBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.utils.FileUtils;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link MoblyBinaryHostTest}. */
@RunWith(JUnit4.class)
public class MoblyBinaryHostTestTest {
    private static final String BINARY_PATH = "/binary/file/path/test.par";
    private static final String LOG_PATH = "/log/dir/abs/path";
    private static final String DEVICE_SERIAL = "X123SER";
    private static final String DEVICE_SERIAL_2 = "Y456SER";
    private static final long DEFAULT_TIME_OUT = 30 * 1000L;
    private static final String TEST_RESULT_FILE_NAME = "test_summary.yaml";

    private MoblyBinaryHostTest mSpyTest;
    private ITestDevice mMockDevice;
    private ITestDevice mMockDevice2;
    private IRunUtil mMockRunUtil;
    private MoblyYamlResultParser mMockParser;
    private InputStream mMockSummaryInputStream;
    private File mMoblyTestDir;
    private File mMoblyBinary; // used by mobly-binaries option
    private File mMoblyBinary2; // used by mobly-par-file-name option
    private File mVenvDir;
    private DeviceBuildInfo mMockBuildInfo;
    private TestInformation mTestInfo;
    private Set<String> mIncludeFilters = new LinkedHashSet<>();

    @Before
    public void setUp() throws Exception {
        mSpyTest = Mockito.spy(new MoblyBinaryHostTest());
        mMockDevice = Mockito.mock(ITestDevice.class);
        mMockRunUtil = Mockito.mock(IRunUtil.class);
        mMockBuildInfo = Mockito.mock(DeviceBuildInfo.class);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        mTestInfo = Mockito.spy(TestInformation.newBuilder().setInvocationContext(context).build());
        mSpyTest.setDevice(mMockDevice);

        mVenvDir = FileUtil.createTempDir("venv");
        new File(mVenvDir, "bin").mkdir();

        Mockito.doReturn(Arrays.asList(mMockDevice)).when(mTestInfo).getDevices();
        Mockito.doReturn(mTestInfo).when(mSpyTest).getTestInfo();
        Mockito.doReturn(mMockRunUtil).when(mSpyTest).getRunUtil();
        Mockito.doReturn(DEFAULT_TIME_OUT).when(mSpyTest).getTestTimeout();
        Mockito.doReturn("not_adb").when(mSpyTest).getAdbPath();
        mMoblyTestDir = FileUtil.createTempDir("mobly_tests");
        mMoblyBinary = FileUtil.createTempFile("mobly_binary", ".par", mMoblyTestDir);
        mMoblyBinary2 = FileUtil.createTempFile("mobly_binary_2", ".par", mMoblyTestDir);
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mMoblyTestDir);
        FileUtil.recursiveDelete(mVenvDir);
        // Delete log dir.
        FileUtil.recursiveDelete(new File(mSpyTest.getLogDirAbsolutePath()));
    }

    @Test
    public void testRun_withPythonBinariesOption() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        // Mimics the behavior of a successful test run.
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        verify(mSpyTest.getRunUtil(), times(2)).runTimedCmd(anyLong(), any());
        assertNull(mSpyTest.getLogDirFile());
    }

    @Test
    public void testRun_withPythonBinariesOption_binaryNotFound() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        FileUtil.deleteFile(mMoblyBinary);

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        verify(mSpyTest, never()).reportLogs(any(), any());
    }

    @Test
    public void testRun_withParFileNameOption() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-par-file-name", mMoblyBinary2.getName());
        Mockito.doReturn(mMoblyTestDir)
                .when(mTestInfo)
                .getDependencyFile(eq(mMoblyBinary2.getName()), eq(false));
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        verify(mSpyTest.getRunUtil(), times(2)).runTimedCmd(anyLong(), any());
        assertNull(mSpyTest.getLogDirFile());
    }

    @Test
    public void testRun_withParFileNameOption_binaryNotFound() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-par-file-name", mMoblyBinary2.getName());
        Mockito.doThrow(new FileNotFoundException())
                .when(mTestInfo)
                .getDependencyFile(eq(mMoblyBinary2.getName()), eq(false));
        FileUtil.deleteFile(mMoblyBinary2);
        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mSpyTest, never()).reportLogs(any(), any());
        verify(mockListener, times(1)).testRunStarted(eq(mMoblyBinary2.getName()), eq(0));
        verify(mockListener, times(1)).testRunFailed(any(FailureDescription.class));
        verify(mockListener, times(1)).testRunEnded(eq(0L), eq(new HashMap<String, Metric>()));
    }

    @Test
    public void testRun_withStdLogOption() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-std-log", "true");
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        // Mimics the behavior of a successful test run.
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        });
        Mockito.when(
                        mMockRunUtil.runTimedCmd(
                                anyLong(), any(OutputStream.class), any(OutputStream.class), any()))
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));
    }

    @Test
    public void testRun_testResultIsMissing() throws Exception {
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        // Test result and log files were not created for some reasons during test run.
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));
        assertFalse(testResult.exists());
    }

    @Test
    public void testRun_shouldActivateVenvAndCleanUp_whenVenvIsSet() throws Exception {
        Mockito.when(mMockBuildInfo.getFile(eq("VIRTUAL_ENV"))).thenReturn(mVenvDir);
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult result = new CommandResult(CommandStatus.SUCCESS);
                            result.setStdout(
                                    "Name: pip\nLocation: "
                                            + new File(
                                                    mVenvDir.getAbsolutePath(),
                                                    "lib/python3.8/site-packages"));
                            return result;
                        })
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });
        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setStdout(
                "Name: pip\nLocation: "
                        + new File(mVenvDir.getAbsolutePath(), "lib/python3.8/site-packages"));
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), anyString(), eq("show"), eq("pip")))
                .thenReturn(result);

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        verify(mSpyTest.getRunUtil(), times(3)).runTimedCmd(anyLong(), any());
        verify(mSpyTest.getRunUtil(), times(1))
                .setEnvVariable(eq("VIRTUAL_ENV"), eq(mVenvDir.getAbsolutePath()));
        assertFalse(mVenvDir.exists());
    }

    @Test
    public void testRun_shouldNotActivateVenv_whenVenvIsNotSet() throws Exception {
        FileUtil.recursiveDelete(mVenvDir);
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        verify(mSpyTest.getRunUtil(), never())
                .setEnvVariable(eq("VIRTUAL_ENV"), eq(mVenvDir.getAbsolutePath()));
    }

    @Test
    public void testBuildCommandLineArrayWithOutConfig() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        List<String> expOptions = Arrays.asList("--option1", "--option2=test_option");
        Mockito.doReturn(expOptions).when(mSpyTest).getTestOptions();

        String[] cmdArray = mSpyTest.buildCommandLineArray(BINARY_PATH, null);
        Truth.assertThat(cmdArray)
                .isEqualTo(
                        new String[] {
                            BINARY_PATH,
                            "--",
                            "--device_serial=" + DEVICE_SERIAL,
                            "--log_path=" + LOG_PATH,
                            "--option1",
                            "--option2=test_option"
                        });
    }

    @Test
    public void testBuildCommandLineArrayWithOutConfigWithWildcardOn() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-par-file-name", mMoblyBinary2.getName());
        setter.setOptionValue("mobly-wildcard-config", "true");
        Mockito.doReturn(mMoblyTestDir)
                .when(mTestInfo)
                .getDependencyFile(eq(mMoblyBinary2.getName()), eq(false));
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        // Verify the command line contains "--config"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="));
    }

    @Test
    public void testRun_withoutTests() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-par-file-name", mMoblyBinary2.getName());
        Mockito.doReturn(mMoblyTestDir)
                .when(mTestInfo)
                .getDependencyFile(eq(mMoblyBinary2.getName()), eq(false));
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout(""); // No tests.
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        // Verify no tests where run.
        verify(mSpyTest.getRunUtil()).runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
    }

    @Test
    public void testRun_withoutFilters() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-par-file-name", mMoblyBinary2.getName());
        Mockito.doReturn(mMoblyTestDir)
                .when(mTestInfo)
                .getDependencyFile(eq(mMoblyBinary2.getName()), eq(false));
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        mSpyTest.run(mTestInfo, Mockito.mock(ITestInvocationListener.class));

        // Verify the command line contains "--tests"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="));
    }

    @Test
    public void testRun_withInvalidIncludeFilters() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addIncludeFilter("test_bar");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(0));
        verify(mockListener, times(1)).testRunFailed(any(FailureDescription.class));
        verify(mockListener, times(1)).testRunEnded(eq(0L), eq(new HashMap<String, Metric>()));
    }

    @Test
    public void testRun_withInvalidExcludeFilters() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addExcludeFilter("test_bar");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(0));
    }

    @Test
    public void testRun_withInvalidExcludeFiltersPrefix() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addExcludeFilter("test_f");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(0));
    }

    @Test
    public void testRun_withIncludeFiltersExact() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addIncludeFilter("test_bar");
        mSpyTest.addIncludeFilter("test_foo");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo\ntest_baz\ntest_bar");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(2));

        // Verify the command line contains "--tests"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="),
                        eq("--tests"),
                        eq("test_foo"),
                        eq("test_bar"));
    }

    @Test
    public void testRun_withIncludeFiltersPrefix() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addIncludeFilter("test_b");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo\ntest_baz\ntest_bar");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(2));

        // Verify the command line contains "--tests"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="),
                        eq("--tests"),
                        eq("test_baz"),
                        eq("test_bar"));
    }

    @Test
    public void testRun_withExcludeFiltersExact() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addExcludeFilter("test_bar");
        mSpyTest.addExcludeFilter("test_foo");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo\ntest_baz\ntest_bar");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(1));

        // Verify the command line contains "--tests"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="),
                        eq("--tests"),
                        eq("test_baz"));
    }

    @Test
    public void testRun_withExcludeFiltersNoTests() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addExcludeFilter("test_bar");
        mSpyTest.addExcludeFilter("test_baz");
        mSpyTest.addExcludeFilter("test_foo");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo\ntest_baz\ntest_bar");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(0));
    }

    @Test
    public void testRun_withBothIncludeAndExcludeFilters() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        OptionSetter setter = new OptionSetter(mSpyTest);
        setter.setOptionValue("mobly-binaries", mMoblyBinary.getAbsolutePath());
        File testResult = new File(mSpyTest.getLogDirAbsolutePath(), TEST_RESULT_FILE_NAME);
        mSpyTest.addIncludeFilter("test_b");
        mSpyTest.addExcludeFilter("test_bar");
        Mockito.when(mMockRunUtil.runTimedCmd(anyLong(), any()))
                .thenAnswer(
                        invocation -> {
                            CommandResult res = new CommandResult(CommandStatus.SUCCESS);
                            res.setStdout("test_foo\ntest_baz\ntest_bar");
                            return res;
                        })
                .thenAnswer(
                        invocation -> {
                            FileUtils.createFile(testResult, "");
                            FileUtils.createFile(
                                    new File(mSpyTest.getLogDirAbsolutePath(), "log"),
                                    "log content");
                            return new CommandResult(CommandStatus.SUCCESS);
                        });

        ITestInvocationListener mockListener = Mockito.mock(ITestInvocationListener.class);

        mSpyTest.run(mTestInfo, mockListener);

        verify(mockListener, times(1)).testRunStarted(anyString(), eq(1));

        // Verify the command line contains "--tests"
        InOrder inOrder = inOrder(mSpyTest.getRunUtil());
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(anyLong(), any(), eq("--"), eq("--list_tests"));
        inOrder.verify(mSpyTest.getRunUtil())
                .runTimedCmd(
                        anyLong(),
                        any(),
                        eq("--"),
                        contains("--config="),
                        contains("--device_serial="),
                        contains("--log_path="),
                        eq("--tests"),
                        eq("test_baz"));
    }

    @Test
    public void testBuildCommandLineArrayWithConfig() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        List<String> expOptions = Arrays.asList("--option1", "--option2=test_option");
        Mockito.doReturn(expOptions).when(mSpyTest).getTestOptions();
        String[] cmdArray = mSpyTest.buildCommandLineArray(BINARY_PATH, "path");
        Truth.assertThat(cmdArray)
                .isEqualTo(
                        new String[] {
                            BINARY_PATH,
                            "--",
                            "--config=path",
                            "--device_serial=" + DEVICE_SERIAL,
                            "--log_path=" + LOG_PATH,
                            "--option1",
                            "--option2=test_option"
                        });
    }

    @Test
    public void testBuildCommandLineArrayWithTests() throws Exception {
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        List<String> tests =
                Arrays.asList("ExampleTest#test_print_addresses", "ExampleTest#test_le_connect");
        String[] cmdArray = mSpyTest.buildCommandLineArray(BINARY_PATH, "path", tests);
        Truth.assertThat(cmdArray)
                .isEqualTo(
                        new String[] {
                            BINARY_PATH,
                            "--",
                            "--config=path",
                            "--device_serial=" + DEVICE_SERIAL,
                            "--log_path=" + LOG_PATH,
                            "--tests",
                            "ExampleTest.test_print_addresses",
                            "ExampleTest.test_le_connect"
                        });
    }

    @Test
    public void testProcessYamlTestResultsSuccess() throws Exception {
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        mMockSummaryInputStream = Mockito.mock(InputStream.class);
        mMockParser = Mockito.mock(MoblyYamlResultParser.class);
        mSpyTest.processYamlTestResults(
                mMockSummaryInputStream,
                mMockParser,
                Mockito.mock(ITestInvocationListener.class),
                "runName");
        verify(mMockParser, times(1)).parse(mMockSummaryInputStream);
    }

    @Test
    public void testUpdateConfigFile() throws Exception {
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        Mockito.doReturn("testBedName").when(mSpyTest).getTestBed();
        String configString =
                new StringBuilder()
                        .append("TestBeds:")
                        .append("\n")
                        .append("- TestParams:")
                        .append("\n")
                        .append("    dut_name: is_dut")
                        .append("\n")
                        .append("  Name: testBedName")
                        .append("\n")
                        .append("  Controllers:")
                        .append("\n")
                        .append("    AndroidDevice:")
                        .append("\n")
                        .append("    - dimensions: {mobile_type: 'dut_rear'}")
                        .append("\n")
                        .append("      serial: old123")
                        .append("\n")
                        .append("MoblyParams: {{LogPath: {log_path}}}")
                        .append("\n")
                        .toString();
        InputStream inputStream = new ByteArrayInputStream(configString.getBytes());
        Writer writer = new StringWriter();
        mSpyTest.updateConfigFile(inputStream, writer);
        String updatedConfigString = writer.toString();
        LogUtil.CLog.d("Updated config string: %s", updatedConfigString);
        // Check if serial injected.
        assertThat(updatedConfigString).contains(DEVICE_SERIAL);
        // Check if original still exists.
        assertThat(updatedConfigString).contains("mobile_type");
        // Check if log path is injected.
        assertThat(updatedConfigString).contains(LOG_PATH);
    }

    @Test
    public void testUpdateConfigFileMultidevice() throws Exception {
        mMockDevice2 = Mockito.mock(ITestDevice.class);
        Mockito.doReturn(Arrays.asList(mMockDevice, mMockDevice2)).when(mTestInfo).getDevices();
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(DEVICE_SERIAL_2).when(mMockDevice2).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        Mockito.doReturn("testBedName").when(mSpyTest).getTestBed();
        String configString =
                new StringBuilder()
                        .append("TestBeds:")
                        .append("\n")
                        .append("- TestParams:")
                        .append("\n")
                        .append("    dut_name: is_dut")
                        .append("\n")
                        .append("  Name: testBedName")
                        .append("\n")
                        .append("  Controllers:")
                        .append("\n")
                        .append("    AndroidDevice:")
                        .append("\n")
                        .append("    - dimensions: {mobile_type: 'dut_rear'}")
                        .append("\n")
                        .append("      serial: old123")
                        .append("\n")
                        .append("    - dimensions: {mobile_type: 'dut_rear'}")
                        .append("\n")
                        .append("      serial: old456")
                        .append("\n")
                        .append("MoblyParams: {{LogPath: {log_path}}}")
                        .append("\n")
                        .toString();
        InputStream inputStream = new ByteArrayInputStream(configString.getBytes());
        Writer writer = new StringWriter();
        mSpyTest.updateConfigFile(inputStream, writer);
        String updatedConfigString = writer.toString();
        LogUtil.CLog.d("Updated config string: %s", updatedConfigString);
        // Check if serials are injected.
        assertThat(updatedConfigString).contains(DEVICE_SERIAL);
        assertThat(updatedConfigString).contains(DEVICE_SERIAL_2);
        // Check if original still exists.
        assertThat(updatedConfigString).contains("mobile_type");
        // Check if log path is injected.
        assertThat(updatedConfigString).contains(LOG_PATH);
    }

    @Test
    public void testUpdateConfigFileDetectDevice() throws Exception {
        mMockDevice2 = Mockito.mock(ITestDevice.class);
        Mockito.doReturn(Arrays.asList(mMockDevice, mMockDevice2)).when(mTestInfo).getDevices();
        Mockito.doReturn(DEVICE_SERIAL).when(mMockDevice).getSerialNumber();
        Mockito.doReturn(DEVICE_SERIAL_2).when(mMockDevice2).getSerialNumber();
        Mockito.doReturn(LOG_PATH).when(mSpyTest).getLogDirAbsolutePath();
        Mockito.doNothing().when(mSpyTest).reportLogs(any(), any());
        Mockito.doReturn("testBedName").when(mSpyTest).getTestBed();
        String configString =
                new StringBuilder()
                        .append("TestBeds:")
                        .append("\n")
                        .append("- TestParams:")
                        .append("\n")
                        .append("    dut_name: is_dut")
                        .append("\n")
                        .append("  Name: testBedName")
                        .append("\n")
                        .append("  Controllers:")
                        .append("\n")
                        .append("    AndroidDevice: '*'")
                        .append("\n")
                        .toString();
        InputStream inputStream = new ByteArrayInputStream(configString.getBytes());
        Writer writer = new StringWriter();
        mSpyTest.updateConfigFile(inputStream, writer);
        String updatedConfigString = writer.toString();
        LogUtil.CLog.d("Updated config string: %s", updatedConfigString);
        // Check if serials are injected.
        assertThat(updatedConfigString).contains(DEVICE_SERIAL);
        assertThat(updatedConfigString).contains(DEVICE_SERIAL_2);
        // Check if original still exists.
        assertThat(updatedConfigString).contains(LOG_PATH);
    }
}
