/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.media.tests;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.BugreportCollector;
import com.android.tradefed.result.BugreportCollector.Freq;
import com.android.tradefed.result.BugreportCollector.Noun;
import com.android.tradefed.result.BugreportCollector.Relation;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.SnapshotInputStreamSource;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.StreamUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Assert;

/**
 * Runs the Media memory test. This test will do various media actions ( ie.
 * playback, recording and etc.) then capture the snapshot of mediaserver memory
 * usage. The test summary is save to /sdcard/mediaMemOutput.txt
 * <p/>
 * Note that this test will not run properly unless /sdcard is mounted and
 * writable.
 */
public class MediaMemoryTest implements IDeviceTest, IRemoteTest {
    private static final String LOG_TAG = "MediaMemoryTest";

    ITestDevice mTestDevice = null;

    private static final String METRICS_RUN_NAME = "MediaMemoryLeak";

    // Constants for running the tests
    private static final String TEST_CLASS_NAME =
            "com.android.mediaframeworktest.performance.MediaPlayerPerformance";
    private static final String TEST_PACKAGE_NAME = "com.android.mediaframeworktest";
    private static final String TEST_RUNNER_NAME = ".MediaFrameworkPerfTestRunner";

    private final String mOutputPath = "mediaMemOutput.txt";

    //Max test timeout - 4 hrs
    private static final int MAX_TEST_TIMEOUT = 4 * 60 * 60 * 1000;

    public Map<String, String> mPatternMap = new HashMap<String, String>();
    private static final Pattern TOTAL_MEM_DIFF_PATTERN =
            Pattern.compile("^The total diff = (\\d+)");

    @Option(name = "getHeapDump", description = "Collect the heap ")
    private boolean mGetHeapDump = false;

    public MediaMemoryTest() {
        mPatternMap.put("Camera Preview Only", "CameraPreview");
        mPatternMap.put("Audio record only", "AudioRecord");
        mPatternMap.put("H263 Video Playback Only", "H263Playback");
        mPatternMap.put("Audio and h263 video record", "H263RecordVideoAudio");
        mPatternMap.put("H263 video record only", "H263RecordVideoOnly");
        mPatternMap.put("H264 Video Playback only", "H264Playback");
        mPatternMap.put("MPEG4 video record only", "MPEG4RecordVideoOnly");
        mPatternMap.put("WMV video playback only", "WMVPlayback");
    }


    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        Assert.assertNotNull(mTestDevice);

        IRemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(TEST_PACKAGE_NAME,
                TEST_RUNNER_NAME, mTestDevice.getIDevice());
        runner.setClassName(TEST_CLASS_NAME);
        runner.setMaxtimeToOutputResponse(MAX_TEST_TIMEOUT);
        if (mGetHeapDump) {
            runner.addInstrumentationArg("get_heap_dump", "getNativeHeap");
        }

        BugreportCollector bugListener = new BugreportCollector(listener,
                mTestDevice);
        bugListener.addPredicate(new BugreportCollector.Predicate(
                Relation.AFTER, Freq.EACH, Noun.TESTRUN));

        mTestDevice.runInstrumentationTests(runner, bugListener);

        logOutputFiles(listener);
        cleanResultFile();
    }

    /**
     * Clean up the test result file from test run
     */
    private void cleanResultFile() throws DeviceNotAvailableException {
        String extStore = mTestDevice.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE);
        mTestDevice.executeShellCommand(String.format("rm %s/%s", extStore, mOutputPath));
        if (mGetHeapDump) {
            mTestDevice.executeShellCommand(String.format("rm %s/%s", extStore, "*.dump"));
        }
    }

    private void uploadHeapDumpFiles(ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        // Pull and upload the heap dump output files.
        InputStreamSource outputSource = null;
        File outputFile = null;

        String extStore = mTestDevice.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE);

        String out = mTestDevice.executeShellCommand(String.format("ls %s/%s",
                extStore, "*.dump"));
        String heapOutputFiles[] = out.split("\n");

        for (String heapFile : heapOutputFiles) {
            try {
                outputFile = mTestDevice.pullFile(heapFile.trim());
                if (outputFile == null) {
                    continue;
                }
                outputSource = new SnapshotInputStreamSource(
                        new FileInputStream(outputFile));
                listener.testLog(heapFile, LogDataType.TEXT,
                        outputSource);
            } catch (IOException e) {
                Log.e(LOG_TAG, String.format(
                        "IOException while reading or parsing output file: %s",
                        e));
            } finally {
                if (outputFile != null) {
                    outputFile.delete();
                }
                if (outputSource != null) {
                    outputSource.cancel();
                }
            }
        }
    }

    /**
     * Pull the output files from the device, add it to the logs, and also parse
     * out the relevant test metrics and report them.
     */
    private void logOutputFiles(ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        File outputFile = null;
        InputStreamSource outputSource = null;
        try {
            outputFile = mTestDevice.pullFileFromExternal(mOutputPath);

            if (outputFile == null) {
                return;
            }
            if (mGetHeapDump) {
                // Upload all the heap dump files.
                uploadHeapDumpFiles(listener);
            }

            // Upload a verbatim copy of the output file
            Log.d(LOG_TAG, String.format("Sending %d byte file %s into the logosphere!",
                    outputFile.length(), outputFile));
            outputSource = new SnapshotInputStreamSource(new FileInputStream(outputFile));
            listener.testLog(mOutputPath, LogDataType.TEXT, outputSource);

            // Parse the output file to upload aggregated metrics
            parseOutputFile(new FileInputStream(outputFile), listener);
        } catch (IOException e) {
            Log.e(LOG_TAG, String.format(
                            "IOException while reading or parsing output file: %s", e));
        } finally {
            if (outputFile != null) {
                outputFile.delete();
            }
            if (outputSource != null) {
                outputSource.cancel();
            }
        }
    }

    /**
     * Parse the relevant metrics from the Instrumentation test output file
     */
    private void parseOutputFile(InputStream dataStream,
            ITestInvocationListener listener) {

        Map<String, String> runMetrics = new HashMap<String, String>();

        // try to parse it
        String contents;
        try {
            contents = StreamUtil.getStringFromStream(dataStream);
        } catch (IOException e) {
            Log.e(LOG_TAG, String.format(
                    "Got IOException during test processing: %s", e));
            return;
        }

        List<String> lines = Arrays.asList(contents.split("\n"));
        ListIterator<String> lineIter = lines.listIterator();
        String line;
        while (lineIter.hasNext()) {
            line = lineIter.next();
            if (mPatternMap.containsKey(line)) {

                String key = mPatternMap.get(line);
                // Look for the total diff
                while (lineIter.hasNext()) {
                    line = lineIter.next();
                    Matcher m = TOTAL_MEM_DIFF_PATTERN.matcher(line);
                    if (m.matches()) {
                        int result = Integer.parseInt(m.group(1));
                        runMetrics.put(key, Integer.toString(result));
                        break;
                    }
                }
            } else {
                Log.e(LOG_TAG, String.format("Got unmatched line: %s", line));
                continue;
            }
        }
        reportMetrics(listener, runMetrics);
    }

    /**
     * Report run metrics by creating an empty test run to stick them in
     * <p />
     * Exposed for unit testing
     */
    void reportMetrics(ITestInvocationListener listener, Map<String, String> metrics) {
        Log.d(LOG_TAG, String.format("About to report metrics: %s", metrics));
        listener.testRunStarted(METRICS_RUN_NAME, 0);
        listener.testRunEnded(0, metrics);
    }

    @Override
    public void setDevice(ITestDevice device) {
        mTestDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }
}
