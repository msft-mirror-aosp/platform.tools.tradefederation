/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.tradefed.log.LogUtil;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.util.SystemUtil;

import com.google.errorprone.annotations.MustBeClosed;

/**
 * Class that collects logcat in background. Continues to capture logcat even if device goes
 * offline then online.
 */
public class LogcatReceiver implements ILogcatReceiver {
    private BackgroundDeviceAction mDeviceAction;
    private LargeOutputReceiver mReceiver;

    private static final String LOGCAT_DESC = "logcat";

    /**
     * Creates an instance with any specified logcat command
     * @param device the device to start logcat on
     * @param logcatCmd the logcat command to run (including 'logcat' part), see details on
     *        available options in logcat help message
     * @param maxFileSize maximum file size, earlier lines will be discarded once size is reached
     * @param logStartDelay the delay to wait after the device becomes online
     */
    public LogcatReceiver(ITestDevice device, String logcatCmd,
            long maxFileSize, int logStartDelay) {
        mReceiver = new LargeOutputReceiver(LOGCAT_DESC, device.getSerialNumber(),
                maxFileSize);
        // FIXME: remove mLogStartDelay. Currently delay starting logcat, as starting
        // immediately after a device comes online has caused adb instability
        mDeviceAction = new BackgroundDeviceAction(logcatCmd, LOGCAT_DESC, device,
                mReceiver, logStartDelay);
    }

    /**
     * Creates an instance with default logcat 'threadtime' format
     * @param device the device to start logcat on
     * @param maxFileSize maximum file size, earlier lines will be discarded once size is reached
     * @param logStartDelay the delay to wait after the device becomes online
     */
    public LogcatReceiver(ITestDevice device, long maxFileSize, int logStartDelay) {
        this(device, getDefaultLogcatCmd(device), maxFileSize, logStartDelay);
    }

    @Override
    public void start() {
        mDeviceAction.start();
    }

    @Override
    public void stop() {
        mDeviceAction.cancel();
        mReceiver.cancel();
        mReceiver.delete();
    }

    @MustBeClosed
    @Override
    public InputStreamSource getLogcatData() {
        return mReceiver.getData();
    }

    @MustBeClosed
    @Override
    public InputStreamSource getLogcatData(int maxBytes) {
        return mReceiver.getData(maxBytes);
    }

    @Override
    public InputStreamSource getLogcatData(int maxBytes, int offset) {
        return mReceiver.getData(maxBytes, offset);
    }

    @Override
    public void clear() {
        mReceiver.clear();
    }

    /** Get the default logcat command, only append uid format if api level > 24. */
    public static String getDefaultLogcatCmd(ITestDevice device) {
        String logcatCmd = "logcat -b all -v threadtime";
        // Logcat format support UID started from api level 24.
        try {
            if (SystemUtil.isLocalMode() || device.getApiLevel() >= 24) {
                logcatCmd = logcatCmd + ",uid";
            }
        } catch (DeviceNotAvailableException e) {
            LogUtil.CLog.e("Use logcat command without UID format due to: ");
            LogUtil.CLog.e(e);
        }

        return logcatCmd;
    }
}
