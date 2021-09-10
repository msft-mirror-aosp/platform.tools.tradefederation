/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.BackgroundDeviceAction;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LargeOutputReceiver;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.StreamUtil;

/**
 * A {@link ITargetPreparer} that runs crash collector on device which suppresses and logs crashes
 * during test execution.
 *
 * <p>Note: this preparer requires N platform or newer.
 */
@OptionClass(alias = "crash-collector")
public class CrashCollector extends TestFilePushSetup implements ITestLoggerReceiver {

    private static final String LOG_NAME = "crash-collector-log";
    private ITestLogger mTestLogger;
    private BackgroundDeviceAction mCrashCollector;
    private LargeOutputReceiver mCrashReceiver;

    @Option(name = "crash-collector-path",
            description = "Path to crashcollector binary on device.")
    private String mCrashCollectorPath = "/data/local/tmp/crashcollector";

    @Option(name = "crash-collector-binary",
            description = "The name of crashcollector binary in test artifact bundle.")
    private String mCrashCollectorBinary = "crashcollector";

    @Option(name = "max-crash-log-size", description = "Max size to retain for crash logs.")
    private long mMaxCrashLogSize = 10 * 1024 * 1024;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        boolean shouldDisable = true;
        if (shouldDisable) {
            CLog.i("Crash collector disabled.");
            return;
        }
        // for backwards compatibility, don't throw if the crash collector does not exist in
        // test zip bundle
        setThrowIfNoFile(false);
        // clear all existing test file names, since we may receive that from the parameter defined
        // in parent class TestFilePushSetup when this class is used together with TestFilePushSetup
        // in a same config
        clearTestFileName();
        addTestFileName(mCrashCollectorBinary);
        try {
            super.setUp(testInfo);
        } catch (TargetSetupError | BuildError e) {
            // Avoid exception since we want the no-throw if file is missing behavior
            CLog.e(e);
        }
        if (getFailedToPushFiles().contains(mCrashCollectorPath)) {
            CLog.w("Failed to push crash collector binary. Skipping the collection.");
            return;
        }
        String crashCollectorPath = mCrashCollectorPath;
        device.executeShellCommand("chmod 755 " + crashCollectorPath);
        mCrashReceiver = new LargeOutputReceiver("crash-collector",
                device.getSerialNumber(), mMaxCrashLogSize);
        mCrashCollector = new BackgroundDeviceAction(crashCollectorPath, "crash-collector",
                device, mCrashReceiver, 0);
        mCrashCollector.start();
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mCrashCollector != null) {
            mCrashCollector.cancel();
        }
        if (mCrashReceiver != null) {
            mCrashReceiver.cancel();
            InputStreamSource iss = mCrashReceiver.getData();
            try {
                mTestLogger.testLog(LOG_NAME, LogDataType.TEXT, iss);
            } finally {
                StreamUtil.cancel(iss);
            }
            mCrashReceiver.delete();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mTestLogger = testLogger;
    }
}
