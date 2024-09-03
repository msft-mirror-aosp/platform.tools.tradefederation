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
package com.android.tradefed.targetprep;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.when;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceAllocationState;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link InstallKernelModulePreparer} */
@RunWith(JUnit4.class)
public class InstallKernelModulePreparerTest {

    private static final String SERIAL = "SERIAL";
    private static final String KUNIT_MODULE = "/data/kunit/kunit.ko";
    private static final String KUNIT_MODULE_INSTALLATION_COMMAND =
            String.format("insmod %s enable=1", KUNIT_MODULE);
    private static final String KUNIT_MODULE_REMOVAL_COMMAND = "rmmod kunit";
    private static final String LIST_MODULE_COMMAND = "lsmod";
    private static final String NO_PREEXISTING_MODULE_OUTPUT =
            "Module Size  Used by\n" + "sec_touch             663552  0";
    private static final String PREEXISTING_MODULE_OUTPUT =
            "Module Size  Used by\n"
                    + "sec_touch             663552  0"
                    + "kunit                  57344  0";

    private InstallKernelModulePreparer mPreparer;
    private TestInformation mTestInfo;
    private OptionSetter mOptionSetter;
    @Mock ITestDevice mMockDevice;
    private DeviceDescriptor mDeviceDescriptor;
    private final CommandResult mSuccessResult;
    private final CommandResult mFailedResult;

    public InstallKernelModulePreparerTest() {
        mSuccessResult = new CommandResult(CommandStatus.SUCCESS);
        mSuccessResult.setStdout("ffffffffffff\n");
        mSuccessResult.setExitCode(0);

        mFailedResult = new CommandResult(CommandStatus.FAILED);
        mFailedResult.setStdout("");
        mFailedResult.setExitCode(-1);
        mFailedResult.setStderr("error");
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDeviceDescriptor =
                new DeviceDescriptor(
                        "serial_1",
                        false,
                        DeviceAllocationState.Available,
                        "unknown",
                        "unknown",
                        "unknown",
                        "unknown",
                        "unknown");

        InvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);

        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();

        mPreparer = new InstallKernelModulePreparer();
        mOptionSetter = new OptionSetter(mPreparer);
        mOptionSetter.setOptionValue("module-path", KUNIT_MODULE);
        when(mMockDevice.getSerialNumber()).thenReturn(SERIAL);
        when(mMockDevice.getDeviceDescriptor()).thenReturn(mDeviceDescriptor);
        when(mMockDevice.getOptions()).thenReturn(new TestDeviceOptions());
    }

    /**
     * Test {@link InstallKernelModulePreparer#setUp()} by successfully installing a kernel module
     * file
     */
    @Test
    public void testSetup()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        mOptionSetter.setOptionValue("install-arg", "enable=1");
        when(mMockDevice.isAdbRoot()).thenReturn(true);

        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(NO_PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellV2Command(matches(KUNIT_MODULE_REMOVAL_COMMAND)))
                .thenReturn(mFailedResult);
        when(mMockDevice.executeShellV2Command(
                        matches(KUNIT_MODULE_INSTALLATION_COMMAND), anyLong(), any()))
                .thenReturn(mSuccessResult);
        mPreparer.setUp(mTestInfo);
    }

    /** Test {@link InstallKernelModulePreparer#getDependentModules()} */
    @Test
    public void testGetDependentModules() throws ConfigurationException {
        String output =
                "Module Size  Used b\n"
                        + "kunit_test             663552  0\n"
                        + "time_test             663558  0\n"
                        + "kunit                  57344  15 kunit_test,time_test\n";
        String[] expected = {"kunit_test", "time_test"};
        String[] actual = mPreparer.getDependentModules("kunit", output);
        assertArrayEquals(expected, actual);
        assertArrayEquals(new String[0], mPreparer.getDependentModules("kunit", "kunit 123 12"));
    }

    /** Test {@link InstallKernelModulePreparer#getDisplayedModuleName()} */
    @Test
    public void testGetDisplayedModuleName() throws ConfigurationException {
        assertEquals("kunit_test", mPreparer.getDisplayedModuleName("/data/kunit-test.ko"));
        assertEquals("kunit_test", mPreparer.getDisplayedModuleName("kunit-test.ko"));
    }

    /**
     * Test {@link InstallKernelModulePreparer#setUp()} by successfully installing a kernel module
     * file with the module already loaded on the device
     */
    @Test
    public void testSetupWithPreExistingDependentModule()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        String output =
                "Module Size  Used b\n"
                        + "kunit_test             663552  0\n"
                        + "time_test             663558  0\n"
                        + "kunit                  57344  15 kunit_test,time_test\n";
        mOptionSetter.setOptionValue("install-arg", "enable=1");
        when(mMockDevice.isAdbRoot()).thenReturn(true);

        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(output)
                .thenReturn(output)
                .thenReturn(output);
        when(mMockDevice.executeShellV2Command(
                        matches(KUNIT_MODULE_INSTALLATION_COMMAND), anyLong(), any()))
                .thenReturn(mSuccessResult)
                .thenReturn(mSuccessResult)
                .thenReturn(mSuccessResult);
        mPreparer.setUp(mTestInfo);
        InOrder inOrder = Mockito.inOrder(mMockDevice);
        inOrder.verify(mMockDevice).executeShellCommand(matches("rmmod kunit_test"));
        inOrder.verify(mMockDevice).executeShellCommand(matches("rmmod time_test"));
        inOrder.verify(mMockDevice).executeShellCommand(matches("rmmod kunit"));
    }

    /** Test {@link InstallKernelModulePreparer#setUp()} by successfully installing 1 ko file */
    @Test
    public void testSetupWithDifferentArgs()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        mOptionSetter.setOptionValue("install-arg", "enable=0");
        mOptionSetter.setOptionValue("install-arg", "stats_enabled=0");
        when(mMockDevice.isAdbRoot()).thenReturn(true);

        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(NO_PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellV2Command(matches(KUNIT_MODULE_REMOVAL_COMMAND)))
                .thenReturn(mFailedResult);
        when(mMockDevice.executeShellV2Command(
                        matches(String.format("insmod %s enable=0 stats_enabled=0", KUNIT_MODULE)),
                        anyLong(),
                        any()))
                .thenReturn(mSuccessResult);
        mPreparer.setUp(mTestInfo);
    }

    /** Test {@link InstallKernelModulePreparer#setUp()} by successfully installing 2 modules */
    @Test
    public void testSetupWithTwoModules()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        mOptionSetter.setOptionValue("install-arg", "enable=1");
        mOptionSetter.setOptionValue("module-path", "/data/kunit/kunit-test.ko");
        when(mMockDevice.isAdbRoot()).thenReturn(true);

        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(NO_PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellV2Command(matches(KUNIT_MODULE_REMOVAL_COMMAND)))
                .thenReturn(mFailedResult);
        when(mMockDevice.executeShellV2Command(
                        matches(KUNIT_MODULE_INSTALLATION_COMMAND), anyLong(), any()))
                .thenReturn(mSuccessResult);
        when(mMockDevice.executeShellCommand(matches("rmmod kunit_test"))).thenReturn("");
        when(mMockDevice.executeShellV2Command(
                        matches("insmod /data/kunit/kunit-test.ko enable=1"), anyLong(), any()))
                .thenReturn(mSuccessResult);
        mPreparer.setUp(mTestInfo);
    }

    /**
     * Test {@link InstallKernelModulePreparer#setUp()} by having module installation failure and
     * throwing an exception
     */
    @Test
    public void testInstallFailureThrow()
            throws DeviceNotAvailableException, BuildError, ConfigurationException {
        mOptionSetter.setOptionValue("install-arg", "enable=1");
        when(mMockDevice.isAdbRoot()).thenReturn(true);

        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(NO_PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellV2Command(matches(KUNIT_MODULE_REMOVAL_COMMAND)))
                .thenReturn(mFailedResult);
        when(mMockDevice.executeShellV2Command(matches(KUNIT_MODULE_INSTALLATION_COMMAND)))
                .thenReturn(mFailedResult);

        try {
            mPreparer.setUp(mTestInfo);
            fail("should have failed due to installation failure");
        } catch (TargetSetupError expected) {
            // expected
        }
    }

    /**
     * Test {@link InstallKernelModulePreparer#tearDown()} by successfully uninstall kernel module
     */
    @Test
    public void testTearDown()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellCommand(matches("rmmod kunit"))).thenReturn("");
        mPreparer.tearDown(mTestInfo, null);
    }

    /**
     * Test {@link InstallKernelModulePreparer#tearDown()} by successfully uninstall 2 kernel
     * modules
     */
    @Test
    public void testTearDownTwoModules()
            throws DeviceNotAvailableException,
                    BuildError,
                    TargetSetupError,
                    ConfigurationException {
        mOptionSetter.setOptionValue("module-path", "/data/kunit/kunit_test.ko");
        when(mMockDevice.executeShellCommand(matches(LIST_MODULE_COMMAND)))
                .thenReturn(NO_PREEXISTING_MODULE_OUTPUT);
        when(mMockDevice.executeShellCommand(matches("rmmod kunit_test"))).thenReturn("");
        when(mMockDevice.executeShellCommand(matches("rmmod kunit"))).thenReturn("");
        mPreparer.tearDown(mTestInfo, null);
        InOrder inOrder = Mockito.inOrder(mMockDevice);
        inOrder.verify(mMockDevice).executeShellCommand(matches("rmmod kunit_test"));
        inOrder.verify(mMockDevice).executeShellCommand(matches("rmmod kunit"));
    }
}
