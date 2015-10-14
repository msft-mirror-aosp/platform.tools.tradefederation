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
package com.android.tradefed.util;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.util.ArrayUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * An atrace utility developed primarily for identifying the root causes of ANRs during Monkey
 * testing. Invoking the start command will start asynchronously monitoring the tagged traces in a
 * circular buffer. Invoking stop will dump the contents of the buffer into an InputStreamSource
 * that it returns.
 *
 * To use this for the case mentioned above (identifying ANRs), one has to first implement the start
 * method at the beginning of the test and the end method immediately at the end of the test. From
 * here one can choose how to store and handle the data. Most should probably use the systrace
 * with the --from-file option to generate an HTML viewer.
 */
public class CircularAtraceUtil {
    private static final String LOG_TAG = "CircularAtraceUtil";

    private static final String ATRACE_START_CMD = "atrace --async_start -z -b %d -c %s";
    private static final String ATRACE_STOP_CMD = "atrace --async_stop -z > %s";
    private static final String DEFAULT_TAGS_STRING = "am gfx sched view";
    private static final String DEVICE_FILE = "${EXTERNAL_STORAGE}/atrace.txt";
    private static final int KB_IN_MB = 1000;

    private static HashSet<ITestDevice> isTracing = new HashSet<ITestDevice>();

    /**
     * Starts atrace asynchronously with the tags specified.
     * @param device the device whose actions will be monitored
     * @param tags tags that atrace should monitor; defaults to 'am gfx sched view'
     * @param bufferSizeMB the circular buffers size in MB
     */
    public static void startTrace(ITestDevice device, List<String> tags, int bufferSizeMB) throws
            DeviceNotAvailableException, IllegalStateException {
        // Errors and warnings
        if (device == null) {
            throw new IllegalStateException("Cannot start circular atrace without a device");
        } else if (isTracing.contains(device)) {
            throw new IllegalStateException("Must end current atrace before starting another");
        } else {
            CLog.d("Starting circular atrace utility");
        }

        // Supply tags if empty or null
        String tagsString = DEFAULT_TAGS_STRING;
        if (tags != null && !tags.isEmpty()) {
            tagsString = ArrayUtil.join(" ", tags);
        }

        // Execute shell command to start atrace
        String command = String.format(ATRACE_START_CMD, bufferSizeMB * KB_IN_MB, tagsString);
        device.executeShellCommand(command);
        // Set tracing flag to ensure use-flow
        isTracing.add(device);
    }

    /**
     * Stops and dumps atrace asynchronously into a File, which it returns in an InputStreamSource.
     * @return a FileInputStreamSource with the results from the atrace command
     */
    public static FileInputStreamSource endTrace(ITestDevice device) throws DeviceNotAvailableException,
            IllegalStateException {
        // Errors and warnings
        if (!isTracing.contains(device)) {
            throw new IllegalStateException("Must start circular atrace before ending");
        } else {
            CLog.d("Ending circular atrace utility");
        }

        // Execute shell command to stop atrace with results
        String command = String.format(ATRACE_STOP_CMD, DEVICE_FILE);
        String result = device.executeShellCommand(command);
        // Set tracing flag to ensure use-flow
        isTracing.remove(device);

        File temp = device.pullFile(DEVICE_FILE);
        if (temp != null) {
            FileInputStreamSource stream = new FileInputStreamSource(temp);
            return stream;
        } else {
            CLog.w("Could not pull file: %s", DEVICE_FILE);
            return null;
        }
    }
}
