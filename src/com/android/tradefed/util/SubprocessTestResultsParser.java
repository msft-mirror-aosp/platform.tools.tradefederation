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
package com.android.tradefed.util;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.util.SubprocessEventHelper.BaseTestEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.FailedTestEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.InvocationFailedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestEndedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestLogEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunEndedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunFailedEventInfo;
import com.android.tradefed.util.SubprocessEventHelper.TestRunStartedEventInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extends {@link FileOutputStream} to parse the output before writing to the file so we can
 * generate the test events on the launcher side.
 */
public class SubprocessTestResultsParser implements Closeable {

    private ITestInvocationListener mListener;
    private TestIdentifier currentTest = null;
    private Pattern mPattern = null;
    private Map<String, EventHandler> mHandlerMap = null;
    private EventReceiverThread mEventReceiver = null;
    private IInvocationContext mContext = null;

    /** Relevant test status keys. */
    public static class StatusKeys {
        public static final String INVOCATION_FAILED = "INVOCATION_FAILED";
        public static final String TEST_ASSUMPTION_FAILURE = "TEST_ASSUMPTION_FAILURE";
        public static final String TEST_ENDED = "TEST_ENDED";
        public static final String TEST_FAILED = "TEST_FAILED";
        public static final String TEST_IGNORED = "TEST_IGNORED";
        public static final String TEST_STARTED = "TEST_STARTED";
        public static final String TEST_RUN_ENDED = "TEST_RUN_ENDED";
        public static final String TEST_RUN_FAILED = "TEST_RUN_FAILED";
        public static final String TEST_RUN_STARTED = "TEST_RUN_STARTED";
        public static final String TEST_LOG = "TEST_LOG";
        public static final String TEST_TAG = "TEST_TAG";
    }

    /**
     * Internal receiver thread class with a socket.
     */
    private class EventReceiverThread extends Thread {
        private ServerSocket mSocket;
        private CountDownLatch mCountDown;

        public EventReceiverThread() throws IOException {
            super("EventReceiverThread");
            mSocket = new ServerSocket(0);
            mCountDown = new CountDownLatch(1);
        }

        protected int getLocalPort() {
            return mSocket.getLocalPort();
        }

        protected CountDownLatch getCountDown() {
            return mCountDown;
        }

        public void cancel() throws IOException {
            if (mSocket != null) {
                mSocket.close();
            }
        }

        @Override
        public void run() {
            Socket client = null;
            BufferedReader in = null;
            try {
                client = mSocket.accept();
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String event = null;
                while ((event = in.readLine()) != null) {
                    try {
                        CLog.i("received event: '%s'", event);
                        parse(event);
                    } catch (JSONException e) {
                        CLog.e(e);
                    }
                }
            } catch (IOException e) {
                CLog.e(e);
            } finally {
                StreamUtil.close(in);
                mCountDown.countDown();
            }
            CLog.d("EventReceiverThread done.");
        }
    }

    /**
     * If the event receiver is being used, ensure that we wait for it to terminate.
     * @param millis timeout in milliseconds.
     * @return True if receiver thread terminate before timeout, False otherwise.
     */
    public boolean joinReceiver(long millis) {
        if (mEventReceiver != null) {
            try {
                CLog.i("Waiting for events to finish being processed.");
                if (!mEventReceiver.getCountDown().await(millis, TimeUnit.MILLISECONDS)) {
                    CLog.e("Event receiver thread did not complete. Some events may be missing.");
                    return false;
                }
            } catch (InterruptedException e) {
                CLog.e(e);
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    /**
     * Returns the socket receiver that was open. -1 if none.
     */
    public int getSocketServerPort() {
        if (mEventReceiver != null) {
            return mEventReceiver.getLocalPort();
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        if (mEventReceiver != null) {
            mEventReceiver.cancel();
        }
    }

    /**
     * Constructor for the result parser
     *
     * @param listener {@link ITestInvocationListener} where to report the results
     * @param streaming if True, a socket receiver will be open to receive results.
     * @param context a {@link IInvocationContext} information about the invocation
     */
    public SubprocessTestResultsParser(
            ITestInvocationListener listener, boolean streaming, IInvocationContext context)
            throws IOException {
        this(listener, context);
        if (streaming) {
            mEventReceiver = new EventReceiverThread();
            mEventReceiver.start();
        }
    }

    /**
     * Constructor for the result parser
     *
     * @param listener {@link ITestInvocationListener} where to report the results
     * @param context a {@link IInvocationContext} information about the invocation
     */
    public SubprocessTestResultsParser(
            ITestInvocationListener listener, IInvocationContext context) {
        mListener = listener;
        mContext = context;
        StringBuilder sb = new StringBuilder();
        sb.append(StatusKeys.INVOCATION_FAILED).append("|");
        sb.append(StatusKeys.TEST_ASSUMPTION_FAILURE).append("|");
        sb.append(StatusKeys.TEST_ENDED).append("|");
        sb.append(StatusKeys.TEST_FAILED).append("|");
        sb.append(StatusKeys.TEST_IGNORED).append("|");
        sb.append(StatusKeys.TEST_STARTED).append("|");
        sb.append(StatusKeys.TEST_RUN_ENDED).append("|");
        sb.append(StatusKeys.TEST_RUN_FAILED).append("|");
        sb.append(StatusKeys.TEST_RUN_STARTED).append("|");
        sb.append(StatusKeys.TEST_LOG).append("|");
        sb.append(StatusKeys.TEST_TAG);
        String patt = String.format("(.*)(%s)( )(.*)", sb.toString());
        mPattern = Pattern.compile(patt);

        // Create Handler map for each event
        mHandlerMap = new HashMap<String, EventHandler>();
        mHandlerMap.put(StatusKeys.INVOCATION_FAILED, new InvocationFailedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_ASSUMPTION_FAILURE,
                new TestAssumptionFailureEventHandler());
        mHandlerMap.put(StatusKeys.TEST_ENDED, new TestEndedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_FAILED, new TestFailedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_IGNORED, new TestIgnoredEventHandler());
        mHandlerMap.put(StatusKeys.TEST_STARTED, new TestStartedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_RUN_ENDED, new TestRunEndedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_RUN_FAILED, new TestRunFailedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_RUN_STARTED, new TestRunStartedEventHandler());
        mHandlerMap.put(StatusKeys.TEST_LOG, new TestLogEventHandler());
        mHandlerMap.put(StatusKeys.TEST_TAG, new TestTagEventHandler());
    }

    public void parseFile(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
        } catch (FileNotFoundException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
        ArrayList<String> listString = new ArrayList<String>();
        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                listString.add(line);
            }
            reader.close();
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
        processNewLines(listString.toArray(new String[listString.size()]));
    }

    /**
     * call parse on each line of the array to extract the events if any.
     */
    public void processNewLines(String[] lines) {
        for (String line : lines) {
            try {
                parse(line);
            } catch (JSONException e) {
                CLog.e("Exception while parsing");
                CLog.e(e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Parse a line, if it matches one of the events, handle it.
     */
    private void parse(String line) throws JSONException {
        Matcher matcher = mPattern.matcher(line);
        if (matcher.find()) {
            EventHandler handler = mHandlerMap.get(matcher.group(2));
            if (handler != null) {
                handler.handleEvent(matcher.group(4));
            } else {
                CLog.w("No handler found matching: %s", matcher.group(2));
            }
        }
    }

    private void checkCurrentTestId(String className, String testName) {
        if (currentTest == null) {
            currentTest = new TestIdentifier(className, testName);
            CLog.w("Calling a test event without having called testStarted.");
        }
    }

    /**
     * Interface for event handling
     */
    interface EventHandler {
        public void handleEvent(String eventJson) throws JSONException;
    }

    private class TestRunStartedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            TestRunStartedEventInfo rsi = new TestRunStartedEventInfo(new JSONObject(eventJson));
            mListener.testRunStarted(rsi.mRunName, rsi.mTestCount);
        }
    }

    private class TestRunFailedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            TestRunFailedEventInfo rfi = new TestRunFailedEventInfo(new JSONObject(eventJson));
            mListener.testRunFailed(rfi.mReason);
        }
    }

    private class TestRunEndedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            try {
                TestRunEndedEventInfo rei = new TestRunEndedEventInfo(new JSONObject(eventJson));
                mListener.testRunEnded(rei.mTime, rei.mRunMetrics);
            } finally {
                currentTest = null;
            }
        }
    }

    private class InvocationFailedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            InvocationFailedEventInfo ifi =
                    new InvocationFailedEventInfo(new JSONObject(eventJson));
            mListener.invocationFailed(ifi.mCause);
        }
    }

    private class TestStartedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            BaseTestEventInfo bti = new BaseTestEventInfo(new JSONObject(eventJson));
            currentTest = new TestIdentifier(bti.mClassName, bti.mTestName);
            mListener.testStarted(currentTest);
        }
    }

    private class TestFailedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            FailedTestEventInfo fti = new FailedTestEventInfo(new JSONObject(eventJson));
            checkCurrentTestId(fti.mClassName, fti.mTestName);
            mListener.testFailed(currentTest, fti.mTrace);
        }
    }

    private class TestEndedEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            try {
                TestEndedEventInfo tei = new TestEndedEventInfo(new JSONObject(eventJson));
                checkCurrentTestId(tei.mClassName, tei.mTestName);
                mListener.testEnded(currentTest, tei.mRunMetrics);
            } finally {
                currentTest = null;
            }
        }
    }

    private class TestIgnoredEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            BaseTestEventInfo baseTestIgnored = new BaseTestEventInfo(new JSONObject(eventJson));
            checkCurrentTestId(baseTestIgnored.mClassName, baseTestIgnored.mTestName);
            mListener.testIgnored(currentTest);
        }
    }

    private class TestAssumptionFailureEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            FailedTestEventInfo FailedAssumption =
                    new FailedTestEventInfo(new JSONObject(eventJson));
            checkCurrentTestId(FailedAssumption.mClassName, FailedAssumption.mTestName);
            mListener.testAssumptionFailure(currentTest, FailedAssumption.mTrace);
        }
    }

    private class TestLogEventHandler implements EventHandler {
        @Override
        public void handleEvent(String eventJson) throws JSONException {
            TestLogEventInfo logInfo = new TestLogEventInfo(new JSONObject(eventJson));
            String name = String.format("subprocess-%s", logInfo.mDataName);
            InputStreamSource data = new FileInputStreamSource(logInfo.mDataFile);
            mListener.testLog(name, logInfo.mLogType, data);
            FileUtil.deleteFile(logInfo.mDataFile);
        }
    }

    /*
     * The parent process should use the test tag of the subprocess if it's not set yet.
     */
    private class TestTagEventHandler implements EventHandler {
        @Override
        public void handleEvent(String testTag) {
            if (mContext.getTestTag() == null || "stub".equals(mContext.getTestTag())) {
                mContext.setTestTag(testTag);
            }
        }
    }
}
