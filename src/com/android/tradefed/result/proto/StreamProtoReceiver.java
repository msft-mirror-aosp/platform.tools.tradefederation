/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.result.proto;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.proto.ProtoResultParser.TestLevel;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.TimeUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A receiver that translates proto TestRecord received into Tradefed events.
 */
public class StreamProtoReceiver implements Closeable {

    private static final int DEFAULT_AVAILABLE_PORT = 0;
    private static final long PER_MODULE_EXTRA_WAIT_TIME_MS = 5000L;
    private static final long PER_TESTRUN_EXTRA_WAIT_TIME_MS = 2000L;

    private EventReceiverThread mEventReceiver;
    private ITestInvocationListener mListener;
    private ProtoResultParser mParser;
    private Throwable mError;
    /**
     * For each module processed we give ourselves a couple extra seconds to process the final
     * results. The longer the invocation goes, the higher is the chance of backing up events.
     */
    private long mExtraWaitTimeForEvents = 0L;

    private AtomicBoolean mJoinStarted = new AtomicBoolean(false);

    /**
     * Stop parsing events when this is set. This allows to avoid a thread parsing the events when
     * we don't expect them anymore.
     */
    protected AtomicBoolean mStopParsing = new AtomicBoolean(false);

    /**
     * Ctor.
     *
     * @param listener the {@link ITestInvocationListener} where to report the results.
     * @param reportInvocation Whether or not to report the invocation level events.
     * @throws IOException
     */
    public StreamProtoReceiver(
            ITestInvocationListener listener,
            IInvocationContext mainContext,
            boolean reportInvocation)
            throws IOException {
        this(listener, mainContext, reportInvocation, true);
    }

    /**
     * Ctor.
     *
     * @param listener the {@link ITestInvocationListener} where to report the results.
     * @param reportInvocation Whether or not to report the invocation level events.
     * @param quietParsing Whether or not to let the parser log debug information.
     * @throws IOException
     */
    public StreamProtoReceiver(
            ITestInvocationListener listener,
            IInvocationContext mainContext,
            boolean reportInvocation,
            boolean quietParsing)
            throws IOException {
        this(listener, mainContext, reportInvocation, quietParsing, true, "subprocess-");
    }

    /**
     * Ctor.
     *
     * @param listener the {@link ITestInvocationListener} where to report the results.
     * @param reportInvocation Whether or not to report the invocation level events.
     * @param quietParsing Whether or not to let the parser log debug information.
     * @param logNamePrefix The prefix for file logged through the parser.
     * @throws IOException
     */
    public StreamProtoReceiver(
            ITestInvocationListener listener,
            IInvocationContext mainContext,
            boolean reportInvocation,
            boolean quietParsing,
            String logNamePrefix)
            throws IOException {
        this(listener, mainContext, reportInvocation, quietParsing, true, logNamePrefix);
    }

    public StreamProtoReceiver(
            ITestInvocationListener listener,
            IInvocationContext mainContext,
            boolean reportInvocation,
            boolean quietParsing,
            boolean reportLogs,
            String logNamePrefix)
            throws IOException {
        this(
                listener,
                mainContext,
                reportInvocation,
                quietParsing,
                reportLogs,
                logNamePrefix,
                true);
    }

    /**
     * Ctor.
     *
     * @param listener the {@link ITestInvocationListener} where to report the results.
     * @param reportInvocation Whether or not to report the invocation level events.
     * @param quietParsing Whether or not to let the parser log debug information.
     * @param reportLogs Whether or not to report the logs
     * @param logNamePrefix The prefix for file logged through the parser.
     * @throws IOException
     */
    public StreamProtoReceiver(
            ITestInvocationListener listener,
            IInvocationContext mainContext,
            boolean reportInvocation,
            boolean quietParsing,
            boolean reportLogs,
            String logNamePrefix,
            boolean mergeInvocationMetrics)
            throws IOException {
        mListener = listener;
        mParser = new ProtoResultParser(mListener, mainContext, reportInvocation, logNamePrefix);
        mParser.setReportLogs(reportLogs);
        mParser.setQuiet(quietParsing);
        mParser.setMergeInvocationContext(mergeInvocationMetrics);
        mEventReceiver = new EventReceiverThread();
        mEventReceiver.start();
    }

    /** Internal thread class that will be parsing the test records asynchronously using a queue. */
    private class EventParsingThread extends Thread {
        private Queue<TestRecord> mTestRecordQueue;
        private boolean mLastTestReceived = false;
        private boolean mThreadInterrupted = false;

        public EventParsingThread(Queue<TestRecord> testRecordQueue) {
            super("ProtoEventParsingThread");
            setDaemon(true);
            this.mTestRecordQueue = testRecordQueue;
        }

        public void notifyLastTestReceived() {
            mLastTestReceived = true;
        }

        @Override
        public void interrupt() {
            mThreadInterrupted = true;
            super.interrupt();
        }

        @Override
        public void run() {
            Queue<TestRecord> processingQueue = new LinkedList<>();
            while (!(mLastTestReceived && mTestRecordQueue.isEmpty()) && !mThreadInterrupted) {
                if (!mTestRecordQueue.isEmpty()) {
                    synchronized (mTestRecordQueue) {
                        processingQueue.addAll(mTestRecordQueue);
                        mTestRecordQueue.clear();
                    }
                    while (!processingQueue.isEmpty() && !mThreadInterrupted) {
                        parse(processingQueue.poll());
                    }
                } else {
                    RunUtil.getDefault().sleep(500L);
                }
            }
            CLog.d("ProtoEventParsingThread done.");
        }
    }

    /** Internal receiver thread class with a socket. */
    private class EventReceiverThread extends Thread {
        private ServerSocket mSocket;
        private Socket mClient;
        private CountDownLatch mCountDown;
        private Queue<TestRecord> mTestRecordQueue;
        EventParsingThread mEventParsingThread;

        public EventReceiverThread() throws IOException {
            super("ProtoEventReceiverThread");
            setDaemon(true);
            mSocket = new ServerSocket(DEFAULT_AVAILABLE_PORT);
            mCountDown = new CountDownLatch(1);
            mTestRecordQueue = new LinkedList<>();
            mEventParsingThread = new EventParsingThread(mTestRecordQueue);
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
            if (mClient != null) {
                mClient.close();
            }
            if (mEventParsingThread.isAlive()) {
                mEventParsingThread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                mClient = mSocket.accept();
                mEventParsingThread.start();
                TestRecord received = null;
                while ((received = TestRecord.parseDelimitedFrom(mClient.getInputStream()))
                        != null) {
                    synchronized (mTestRecordQueue) {
                        mTestRecordQueue.add(received);
                    }
                }
                // notify EventParsingThread of last test received so it can finish listening.
                mEventParsingThread.notifyLastTestReceived();
                // wait for the event parsing thread to finish
                try {
                    mEventParsingThread.join();
                } catch (InterruptedException e) {
                    // if EventReceiverThread is interrupted, interrupt the EventParsingThread
                    mEventParsingThread.interrupt();
                }
            } catch (IOException e) {
                CLog.e(e);
                mEventParsingThread.interrupt();
            } finally {
                StreamUtil.close(mClient);
                mCountDown.countDown();
            }
            CLog.d("ProtoEventReceiverThread done.");
        }
    }

    /** Returns the socket receiver that was open. -1 if none. */
    public int getSocketServerPort() {
        if (mEventReceiver != null) {
            return mEventReceiver.getLocalPort();
        }
        return -1;
    }

    /** Returns the error caugh in the receiver thread. If none it will return null. */
    public Throwable getError() {
        return mError;
    }

    @Override
    public void close() throws IOException {
        if (mEventReceiver != null) {
            mEventReceiver.cancel();
        }
    }

    public boolean joinReceiver(long millis) {
        if (mEventReceiver != null) {
            mJoinStarted.set(true);
            try {
                long waitTime = getJoinTimeout(millis);
                CLog.i(
                        "Waiting for events to finish being processed for %s",
                        TimeUtil.formatElapsedTime(waitTime));
                if (!mEventReceiver.getCountDown().await(waitTime, TimeUnit.MILLISECONDS)) {
                    CLog.e("Event receiver thread did not complete. Some events may be missing.");
                    mEventReceiver.interrupt();
                    return false;
                }
            } catch (InterruptedException e) {
                CLog.e(e);
                throw new RuntimeException(e);
            } finally {
                mStopParsing.set(true);
            }
        }
        return true;
    }

    /** If needed to ensure consistent reporting, complete the events of the module. */
    public void completeModuleEvents() {
        mParser.completeModuleEvents();
    }

    /** Returns whether or not the invocation failed has been reported. */
    public boolean hasInvocationFailed() {
        return mParser.hasInvocationFailed();
    }

    @VisibleForTesting
    protected long getJoinTimeout(long millis) {
        return millis + mExtraWaitTimeForEvents;
    }

    private void parse(TestRecord receivedRecord) {
        if (mStopParsing.get()) {
            CLog.i(
                    "Skip parsing of %s. It came after joinReceiver.",
                    receivedRecord.getTestRecordId());
            return;
        }
        try {
            TestLevel level = mParser.processNewProto(receivedRecord);
            if (TestLevel.MODULE.equals(level) && !mJoinStarted.get()) {
                mExtraWaitTimeForEvents += PER_MODULE_EXTRA_WAIT_TIME_MS;
            } else if (TestLevel.TEST_RUN.equals(level)
                    && !receivedRecord.hasEndTime()
                    && !mJoinStarted.get()) {
                // increase wait time for each new test run
                mExtraWaitTimeForEvents += PER_TESTRUN_EXTRA_WAIT_TIME_MS;
            }
        } catch (Throwable e) {
            CLog.e(e);
            mError = e;
            throw e;
        }
    }
}
