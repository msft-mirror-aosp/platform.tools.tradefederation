/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TimeUtil;

/**
 * A {@link ITargetPreparer} that waits for datetime to be set on device
 *
 * <p>Optionally this preparer can force a {@link TargetSetupError} if datetime is not set within
 * timeout.
 */
@OptionClass(alias = "wait-for-datetime")
public class WaitForDeviceDatetimePreparer extends BaseTargetPreparer {

    // 6s to wait for device datetime
    private static final long DATETIME_WAIT_TIMEOUT = 6 * 1000;
    // poll every 2s when waiting correct device datetime
    private static final long DATETIME_CHECK_INTERVAL = 2 * 1000;
    // allow 10s of margin for datetime difference between host/device
    private static final long DATETIME_MARGIN = 10 * 1000;

    @Option(
            name = "force-datetime",
            description =
                    "Force sync host datetime to device if device "
                            + "fails to set datetime automatically.")
    @Deprecated
    private boolean mForceDatetime = true;

    @Option(name = "datetime-wait-timeout",
            description = "Timeout in ms to wait for correct datetime on device.")
    private long mDatetimeWaitTimeout = DATETIME_WAIT_TIMEOUT;

    @Option(
            name = "force-setup-error",
            description = "Throw a TargetSetupError if correct datetime was not set.")
    private boolean mForceSetupError = false;

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        if (!waitForDeviceDatetime(device)) {
            if (mForceSetupError) {
                throw new TargetSetupError(
                        String.format(
                                "datetime on device is incorrect after wait timeout of '%s'",
                                TimeUtil.formatElapsedTime(mDatetimeWaitTimeout)),
                        device.getDeviceDescriptor(),
                        DeviceErrorIdentifier.DEVICE_UNEXPECTED_RESPONSE);
            } else {
                CLog.w("datetime on device is incorrect after wait timeout.");
            }
        }
    }

    /**
     * Sets the timeout for waiting on valid device datetime
     */
    public void setDatetimeWaitTimeout(long datetimeWaitTimeout) {
        mDatetimeWaitTimeout = datetimeWaitTimeout;
    }

    /** Sets the if datetime should be forced from host to device */
    @Deprecated
    public void setForceDatetime(boolean forceDatetime) {
        mForceDatetime = forceDatetime;
    }

    /**
     * Sets the boolean for forcing a {@link TargetSetupError} if the datetime is not set correctly.
     */
    public void setForceSetupError(boolean forceSetupError) {
        mForceSetupError = forceSetupError;
    }

    /**
     * Waits for a correct datetime on device, optionally force host datetime onto device
     *
     * @param device {@link ITestDevice} where date time will be set
     * @return <code>true</code> if datetime is correct or forced, <code>false</code> otherwise
     */
    boolean waitForDeviceDatetime(ITestDevice device) throws DeviceNotAvailableException {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < mDatetimeWaitTimeout) {
            device.setDate(null);
            // check if the date is set within margin
            long timeOffset = device.getDeviceTimeOffset(null);
            if (Math.abs(timeOffset) < DATETIME_MARGIN) {
                CLog.d("Device date time is set with offset: %s.", Math.abs(timeOffset));
                return true;
            }
            getRunUtil().sleep(DATETIME_CHECK_INTERVAL);
        }
        return false;
    }

    /**
     * @return the {@link IRunUtil} to use
     */
    protected IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }
}
