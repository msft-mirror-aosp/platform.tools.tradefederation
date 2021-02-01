/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparer.RUN_TESTS_AS_USER_KEY;
import static com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparer.SKIP_TESTS_REASON_KEY;
import static com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparer.TEST_PACKAGE_NAME_OPTION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.IConfiguration;
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
import java.util.HashMap;
import java.util.Map;

@RunWith(JUnit4.class)
public class RunOnWorkProfileTargetPreparerTest {
    private static final String CREATED_USER_10_MESSAGE = "Created user id 10";

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TestInformation mTestInfo;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IConfiguration mConfiguration;

    private RunOnWorkProfileTargetPreparer mPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws Exception {
        mPreparer = new RunOnWorkProfileTargetPreparer();
        mOptionSetter = new OptionSetter(mPreparer);
        mPreparer.setConfiguration(mConfiguration);

        ArrayList<Integer> userIds = new ArrayList<>();
        userIds.add(0);

        when(mTestInfo.getDevice().hasFeature("android.software.managed_users")).thenReturn(true);
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(2);
        when(mTestInfo.getDevice().listUsers()).thenReturn(userIds);
    }

    @Test
    public void setUp_createsAndStartsWorkProfile() throws Exception {
        String expectedCreateUserCommand = "pm create-user --profileOf 0 --managed work";
        String expectedStartUserCommand = "am start-user -w 10";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_10_MESSAGE);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice()).executeShellCommand(expectedCreateUserCommand);
        verify(mTestInfo.getDevice()).executeShellCommand(expectedStartUserCommand);
    }

    @Test
    public void setUp_workProfileAlreadyExists_doesNotCreateWorkProfile() throws Exception {
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                10,
                new UserInfo(
                        10,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice(), never()).executeShellCommand(any());
    }

    @Test
    public void setUp_nonZeroCurrentUser_createsWorkProfileForCorrectUser() throws Exception {
        when(mTestInfo.getDevice().getCurrentUser()).thenReturn(1);
        String expectedCreateUserCommand = "pm create-user --profileOf 1 --managed work";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_10_MESSAGE);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice()).executeShellCommand(expectedCreateUserCommand);
    }

    @Test
    public void setUp_workProfileAlreadyExists_runsTestAsExistingUser() throws Exception {
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                11,
                new UserInfo(
                        11,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties()).put(RUN_TESTS_AS_USER_KEY, "11");
    }

    @Test
    public void setUp_setsRunTestsAsUser() throws Exception {
        String expectedCreateUserCommand = "pm create-user --profileOf 0 --managed work";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_10_MESSAGE);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties()).put(RUN_TESTS_AS_USER_KEY, "10");
    }

    @Test
    public void setUp_workProfileAlreadyExists_installsPackagesInExistingUser() throws Exception {
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                11,
                new UserInfo(
                        11,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);
        mOptionSetter.setOptionValue(TEST_PACKAGE_NAME_OPTION, "com.android.testpackage");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 11 com.android.testpackage");
    }

    @Test
    public void setUp_installsPackagesInWorkUser() throws Exception {
        String expectedCreateUserCommand = "pm create-user --profileOf 0 --managed work";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_10_MESSAGE);
        mOptionSetter.setOptionValue(TEST_PACKAGE_NAME_OPTION, "com.android.testpackage");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 10 com.android.testpackage");
    }

    @Test
    public void setUp_workProfileAlreadyExists_disablesTearDown() throws Exception {
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                11,
                new UserInfo(
                        11,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);
        mOptionSetter.setOptionValue("disable-tear-down", "false");

        mPreparer.setUp(mTestInfo);

        assertThat(mPreparer.isTearDownDisabled()).isTrue();
    }

    @Test
    public void setUp_doesNotDisableTearDown() throws Exception {
        String expectedCreateUserCommand = "pm create-user --profileOf 0 --managed work";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_10_MESSAGE);
        mOptionSetter.setOptionValue("disable-tear-down", "false");

        mPreparer.setUp(mTestInfo);

        assertThat(mPreparer.isTearDownDisabled()).isFalse();
    }

    @Test
    public void tearDown_removesWorkUser() throws Exception {
        when(mTestInfo.properties().get(RUN_TESTS_AS_USER_KEY)).thenReturn("10");

        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).removeUser(10);
    }

    @Test
    public void tearDown_clearsRunTestsAsUserProperty() throws Exception {
        when(mTestInfo.properties().get(RUN_TESTS_AS_USER_KEY)).thenReturn("10");

        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.properties()).remove(RUN_TESTS_AS_USER_KEY);
    }

    @Test
    public void setUp_doesNotSupportManagedUsers_doesNotChangeTestUser() throws Exception {
        when(mTestInfo.getDevice().hasFeature("android.software.managed_users")).thenReturn(false);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties(), never()).put(eq(RUN_TESTS_AS_USER_KEY), any());
    }

    @Test
    public void setUp_doesNotSupportManagedUsers_setsArgumentToSkipTests() throws Exception {
        when(mTestInfo.getDevice().hasFeature("android.software.managed_users")).thenReturn(false);

        mPreparer.setUp(mTestInfo);

        verify(mConfiguration)
                .injectOptionValue(eq("instrumentation-arg"), eq(SKIP_TESTS_REASON_KEY), any());
    }

    @Test
    public void setUp_doesNotSupportManagedUsers_disablesTearDown() throws Exception {
        when(mTestInfo.getDevice().hasFeature("android.software.managed_users")).thenReturn(false);

        mPreparer.setUp(mTestInfo);

        assertThat(mPreparer.isTearDownDisabled()).isTrue();
    }

    @Test
    public void setUp_doesNotSupportAdditionalUsers_doesNotChangeTestUser() throws Exception {
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(1);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties(), never()).put(eq(RUN_TESTS_AS_USER_KEY), any());
    }

    @Test
    public void setUp_doesNotSupportAdditionalUsers_setsArgumentToSkipTests() throws Exception {
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(1);

        mPreparer.setUp(mTestInfo);

        verify(mConfiguration)
                .injectOptionValue(eq("instrumentation-arg"), eq(SKIP_TESTS_REASON_KEY), any());
    }

    @Test
    public void setUp_doesNotSupportAdditionalUsers_disablesTearDown() throws Exception {
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(1);

        mPreparer.setUp(mTestInfo);

        assertThat(mPreparer.isTearDownDisabled()).isTrue();
    }

    @Test
    public void setUp_doesNotSupportAdditionalUsers_alreadyHasWorkProfile_runsTestAsExistingUser()
            throws Exception {
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(1);
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                11,
                new UserInfo(
                        11,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);
        mOptionSetter.setOptionValue(TEST_PACKAGE_NAME_OPTION, "com.android.testpackage");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 11 com.android.testpackage");
    }

    @Test
    public void setUp_doesNotSupportAdditionalUsers_alreadyHasWorkProfile_doesNotSkipTests()
            throws Exception {
        when(mTestInfo.getDevice().getMaxNumberOfUsersSupported()).thenReturn(1);
        Map<Integer, UserInfo> userInfos = new HashMap<>();
        userInfos.put(
                11,
                new UserInfo(
                        11,
                        "work",
                        /* flag= */ UserInfo.FLAG_MANAGED_PROFILE,
                        /* isRunning= */ true));
        when(mTestInfo.getDevice().getUserInfos()).thenReturn(userInfos);
        mOptionSetter.setOptionValue(TEST_PACKAGE_NAME_OPTION, "com.android.testpackage");

        mPreparer.setUp(mTestInfo);

        verify(mConfiguration, never())
                .injectOptionValue(eq("instrumentation-arg"), eq(SKIP_TESTS_REASON_KEY), any());
    }
}
