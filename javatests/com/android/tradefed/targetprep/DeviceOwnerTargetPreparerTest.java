/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class DeviceOwnerTargetPreparerTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TestInformation mTestInfo;

    private static final String TEST_DEVICE_OWNER_PACKAGE_NAME = "com.android.tradefed.targetprep";
    private static final String TEST_DEVICE_OWNER_COMPONENT_NAME =
            TEST_DEVICE_OWNER_PACKAGE_NAME + "/.TestOwner";

    private static final String SET_DEVICE_OWNER_COMMAND =
            "dpm set-device-owner --user 0 '" + TEST_DEVICE_OWNER_COMPONENT_NAME + "'";

    private DeviceOwnerTargetPreparer mPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws Exception {
        mPreparer = new DeviceOwnerTargetPreparer();
        mOptionSetter = new OptionSetter(mPreparer);

        mOptionSetter.setOptionValue(
                DeviceOwnerTargetPreparer.DEVICE_OWNER_COMPONENT_NAME_OPTION,
                null,
                TEST_DEVICE_OWNER_COMPONENT_NAME);

        when(mTestInfo.getDevice().listUsers()).thenReturn(new ArrayList<>(List.of(0)));
        when(mTestInfo.getDevice().executeShellCommand(SET_DEVICE_OWNER_COMMAND))
                .thenReturn("Success: test set-device-owner");
        when(mTestInfo.getDevice().uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME))
                .thenReturn("Success: test uninstall");
    }

    @Test
    public void testSetUp_removesDeviceOwners() throws Exception {
        mPreparer.setUp(mTestInfo);
        verify(mTestInfo.getDevice()).removeOwners();
    }

    /**
     * The preparer will not switch the user for the target as this can fail on HSUM devices and
     * it's not clear whether or not the test can succeed. The test should manually switch users
     * using another preparer like {@link SwitchUserTargetPreparer} or manually during the test.
     */
    @Test
    public void testSetUp_neverSwitches() throws Exception {
        mPreparer.setUp(mTestInfo);
        verify(mTestInfo.getDevice(), never()).switchUser(anyInt());
    }

    @Test
    public void testSetUp_removeSecondaryUsers() throws Exception {
        when(mTestInfo.getDevice().listUsers()).thenReturn(new ArrayList<>(List.of(0, 10, 11)));

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice(), never()).removeUser(0);
        verify(mTestInfo.getDevice()).removeUser(10);
        verify(mTestInfo.getDevice()).removeUser(11);
    }

    @Test
    public void testSetUp_setsDeviceOwner() throws Exception {
        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice()).executeShellCommand(SET_DEVICE_OWNER_COMMAND);
    }

    @Test
    public void testSetUp_setsDeviceOwner_failure() throws Exception {
        when(mTestInfo.getDevice().executeShellCommand(SET_DEVICE_OWNER_COMMAND))
                .thenReturn("Test failure");

        Exception exception =
                assertThrows(IllegalStateException.class, () -> mPreparer.setUp(mTestInfo));
        assertEquals("Unable to set device owner: Test failure", exception.getMessage());
    }

    @Test
    public void testSetUp_setsDeviceOwner_nullFailure() throws Exception {
        when(mTestInfo.getDevice().executeShellCommand(SET_DEVICE_OWNER_COMMAND)).thenReturn(null);

        Exception exception =
                assertThrows(IllegalStateException.class, () -> mPreparer.setUp(mTestInfo));
        assertEquals("Unable to set device owner: null", exception.getMessage());
    }

    @Test
    public void testTearDown_removesDeviceOwner() throws Exception {
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).removeAdmin(TEST_DEVICE_OWNER_COMPONENT_NAME, 0);
    }

    @Test
    public void testTearDown_uninstall_default() throws Exception {
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME);
    }

    @Test
    public void testTearDown_uninstall_false() throws Exception {
        mOptionSetter.setOptionValue(DeviceOwnerTargetPreparer.UNINSTALL_OPTION, null, "false");

        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice(), never()).uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME);
    }

    @Test
    public void testTearDown_uninstall_failure() throws Exception {
        when(mTestInfo.getDevice().uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME))
                .thenReturn("Failure: test uninstall");

        mPreparer.setUp(mTestInfo);

        Exception exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> mPreparer.tearDown(mTestInfo, /* throwable= */ null));
        assertEquals(
                "Failed to uninstall admin package "
                        + TEST_DEVICE_OWNER_COMPONENT_NAME
                        + ": Failure: test uninstall",
                exception.getMessage());
    }

    @Test
    public void testTearDown_uninstall_nullSuccess() throws Exception {
        when(mTestInfo.getDevice().uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME))
                .thenReturn(null);
        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).uninstallPackage(TEST_DEVICE_OWNER_PACKAGE_NAME);
    }

    @Test
    public void testTearDown_multiUser_removesDeviceOwner() throws Exception {
        when(mTestInfo.getDevice().listUsers()).thenReturn(new ArrayList<>(List.of(0, 10, 11)));
        when(mTestInfo.getDevice().getUserInfos())
                .thenReturn(
                        Map.of(
                                0, new UserInfo(0, "system", UserInfo.FLAG_PRIMARY, true),
                                10, new UserInfo(10, "main", UserInfo.FLAG_MAIN, true),
                                11, new UserInfo(11, "secondary", 0, false)));

        mPreparer.setUp(mTestInfo);
        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).removeAdmin(TEST_DEVICE_OWNER_COMPONENT_NAME, 0);
        verify(mTestInfo.getDevice()).removeAdmin(TEST_DEVICE_OWNER_COMPONENT_NAME, 10);
        verify(mTestInfo.getDevice()).removeAdmin(TEST_DEVICE_OWNER_COMPONENT_NAME, 11);
    }
}
