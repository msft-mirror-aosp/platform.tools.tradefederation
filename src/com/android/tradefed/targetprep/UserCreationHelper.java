/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;

import java.util.ArrayList;
import java.util.Map;

// Not directly unit tested, but its clients are
final class UserCreationHelper {

    private static final String TF_CREATED_USER = "tf_created_user";

    public static int createUser(ITestDevice device, boolean reuseTestUser)
            throws DeviceNotAvailableException, TargetSetupError {
        if (reuseTestUser) {
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

    private static void cleanupOldUsersIfLimitReached(ITestDevice device)
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

    private static Integer findExistingTradefedUser(ITestDevice device)
            throws DeviceNotAvailableException {
        for (Map.Entry<Integer, UserInfo> entry : device.getUserInfos().entrySet()) {
            String userName = entry.getValue().userName();

            if (userName != null && userName.equals(TF_CREATED_USER)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private UserCreationHelper() {
        throw new UnsupportedOperationException("provide only static methods");
    }
}
