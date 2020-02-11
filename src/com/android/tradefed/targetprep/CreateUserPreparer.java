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

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.TestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;

/** Target preparer for creating user and cleaning it up at the end. */
public class CreateUserPreparer extends BaseTargetPreparer {

    private static final String TF_CREATED_USER = "tf_created_user";

    private Integer mOriginalUser = null;
    private Integer mCreatedUserId = null;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        mOriginalUser = device.getCurrentUser();
        if (mOriginalUser == TestDevice.INVALID_USER_ID) {
            mOriginalUser = null;
            throw new TargetSetupError(
                    "Failed to get the current user.", device.getDeviceDescriptor());
        }
        try {
            mCreatedUserId = device.createUser(TF_CREATED_USER);
        } catch (IllegalStateException e) {
            throw new TargetSetupError("Failed to create user.", e, device.getDeviceDescriptor());
        }
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
        device.waitForDeviceAvailable();
        device.postBootSetup();
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mCreatedUserId == null) {
            return;
        }
        if (mOriginalUser == null) {
            return;
        }
        if (e instanceof DeviceNotAvailableException) {
            CLog.d("Skipping teardown due to dnae: %s", e.getMessage());
            return;
        }
        ITestDevice device = testInfo.getDevice();
        if (!device.switchUser(mOriginalUser)) {
            CLog.e("Failed to switch back to original user '%s'", mOriginalUser);
        }
        if (!device.removeUser(mCreatedUserId)) {
            CLog.e(
                    "Failed to delete user %s on device %s",
                    mCreatedUserId, device.getSerialNumber());
        }
    }
}
