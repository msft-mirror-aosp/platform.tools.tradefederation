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
package com.android.tradefed.targetprep;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Target preparer for creating user and cleaning it up at the end. */
@OptionClass(alias = "create-user-preparer")
public class CreateUserPreparer extends BaseTargetPreparer {
    private static final String TF_CREATED_USER = "tf_created_user";

    // Needed when running tests on background user on visible display
    @VisibleForTesting protected static final String RUN_TESTS_AS_USER_KEY = "RUN_TESTS_AS_USER";

    @Option(
            name = "reuse-test-user",
            description =
                    "Whether or not to reuse already created tradefed test user, or remove them "
                            + " and re-create them between module runs.")
    private boolean mReuseTestUser;

    @Option(
            name = "start-user-visible-on-background",
            description =
                    "When `true`, it will start the user in the background visible in a display,"
                            + " instead of switching the current forground user.")
    private boolean mStartUserVisibleOnBackground;

    private Integer mOriginalUser;
    private Integer mCreatedUserId;
    private boolean mCreatedUserIdAlreadyVisible;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (mStartUserVisibleOnBackground) {
            if (!device.isVisibleBackgroundUsersSupported()) {
                throw new TargetSetupError("feature not supported", device.getDeviceDescriptor());
            }
        } else {
            mOriginalUser = device.getCurrentUser();
            if (mOriginalUser == TestDevice.INVALID_USER_ID) {
                mOriginalUser = null;
                throw new TargetSetupError(
                        "Failed to get the current user.", device.getDeviceDescriptor());
            }
        }
        CLog.i(
                "setUp(): mOriginalUser=%d, mReuseTestUser=%b, mStartUserVisibleOnBackground=%b",
                mOriginalUser, mReuseTestUser, mStartUserVisibleOnBackground);

        mCreatedUserId = createUser(device);

        if (mStartUserVisibleOnBackground) {
            startUserVisibleOnBackground(testInfo, device, mCreatedUserId);
        } else {
            switchCurrentUser(device, mCreatedUserId);
        }

        device.waitForDeviceAvailable();
        device.postBootSetup();
    }

    private void switchCurrentUser(ITestDevice device, int userId)
            throws TargetSetupError, DeviceNotAvailableException {
        if (!device.startUser(mCreatedUserId, true)) {
            throw new TargetSetupError(
                    String.format("Failed to start to user '%s'", mCreatedUserId),
                    device.getDeviceDescriptor());
        }
        if (!device.switchUser(mCreatedUserId)) {
            throw new TargetSetupError(
                    String.format("Failed to switch to user '%s'", mCreatedUserId),
                    device.getDeviceDescriptor());
        }
    }

    private void startUserVisibleOnBackground(
            TestInformation testInfo, ITestDevice device, int userId)
            throws TargetSetupError, DeviceNotAvailableException {
        Set<Integer> displays = device.listDisplayIdsForStartingVisibleBackgroundUsers();
        CLog.d("Displays: %s", displays);
        if (displays.isEmpty()) {
            throw new TargetSetupError(
                    String.format("No display available to start to user '%d'", userId),
                    device.getDeviceDescriptor());
        }
        // TODO(b/266851112): add option to explicitly set display id as parameter
        int displayId = displays.iterator().next();

        mCreatedUserIdAlreadyVisible = device.isUserVisibleOnDisplay(userId, displayId);
        if (mCreatedUserIdAlreadyVisible) {
            CLog.d(
                    "startUserVisibleOnBackground(): user %d already visible on display %d",
                    userId, displayId);
        } else {
            CLog.d(
                    "startUserVisibleOnBackground(): starting user %d visible on display %d",
                    userId, displayId);

            if (!device.startVisibleBackgroundUser(userId, displayId, /* waitFlag= */ true)) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to start to user '%s' on display %d",
                                mCreatedUserId, displayId),
                        device.getDeviceDescriptor());
            }
        }

        CLog.i("Setting test property %s=%d", RUN_TESTS_AS_USER_KEY, mCreatedUserId);
        testInfo.properties().put(RUN_TESTS_AS_USER_KEY, Integer.toString(mCreatedUserId));
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mCreatedUserId == null) {
            CLog.d("Skipping teardown because no user was created");
            return;
        }
        if (e instanceof DeviceNotAvailableException) {
            CLog.d("Skipping teardown due to dnae: %s", e.getMessage());
            return;
        }
        ITestDevice device = testInfo.getDevice();

        if (mStartUserVisibleOnBackground) {
            stopTestUser(device);
        } else {
            if (mOriginalUser == null) {
                CLog.d("Skipping teardown because original user is null");
                return;
            }
            switchBackToOriginalUser(device);
        }

        if (!mReuseTestUser) {
            device.removeUser(mCreatedUserId);
        }
    }

    private void switchBackToOriginalUser(ITestDevice device) throws DeviceNotAvailableException {
        CLog.d(
                "switchBackToOriginalUser(): switching current user from %d to user %d ",
                mCreatedUserId, mOriginalUser);
        if (!device.switchUser(mOriginalUser)) {
            CLog.e("Failed to switch back to original user '%s'", mOriginalUser);
        }
    }

    private void stopTestUser(ITestDevice device) throws DeviceNotAvailableException {
        if (mCreatedUserIdAlreadyVisible) {
            CLog.d("stopTestUser(): user %d was already visible on start", mCreatedUserId);
            return;
        }
        CLog.d("stopTestUser(): stopping user %d ", mCreatedUserId);
        if (!device.stopUser(mCreatedUserId, /* waitFlag= */ true, /* forceFlag= */ true)) {
            CLog.e("Failed to stop user '%d'", mCreatedUserId);
        }
    }

    private int createUser(ITestDevice device)
            throws DeviceNotAvailableException, TargetSetupError {
        if (mReuseTestUser) {
            Integer existingTFUser = findExistingTradefedUser(device);
            if (existingTFUser != null) {
                return existingTFUser;
            }
        }

        cleanupOldUsersIfLimitReached(device);

        try {
            return device.createUser(TF_CREATED_USER);
        } catch (IllegalStateException e) {
            throw new TargetSetupError("Failed to create user.", e, device.getDeviceDescriptor());
        }
    }

    private void cleanupOldUsersIfLimitReached(ITestDevice device)
            throws DeviceNotAvailableException {
        ArrayList<Integer> tfCreatedUsers = new ArrayList<>();
        int existingUsersCount = 0;
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            UserInfo userInfo = entry.getValue();
            String userName = userInfo.userName();

            if (!userInfo.isGuest()) {
                // Guest users don't fall under the quota.
                existingUsersCount++;
            }
            if (userName != null && userName.equals(TF_CREATED_USER)) {
                tfCreatedUsers.add(entry.getKey());
            }
        }

        if (existingUsersCount >= device.getMaxNumberOfUsersSupported()) {
            // Reached the maximum number of users allowed. Remove stale users to free up space.
            for (int userId : tfCreatedUsers) {
                device.removeUser(userId);
            }
        }
    }

    private Integer findExistingTradefedUser(ITestDevice device)
            throws DeviceNotAvailableException {
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            String userName = entry.getValue().userName();

            if (userName != null && userName.equals(TF_CREATED_USER)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
