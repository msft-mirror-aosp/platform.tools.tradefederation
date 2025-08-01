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

package com.android.tradefed.result;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogRegistry;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.log.StdoutLogger;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SystemUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** A {@link ResultForwarder} for saving logs with the global file saver. */
public class LogSaverResultForwarder extends ResultForwarder implements ILogSaverListener {

    ILogSaver mLogSaver;
    IConfiguration mConfig;

    public LogSaverResultForwarder(
            ILogSaver logSaver, List<ITestInvocationListener> listeners, IConfiguration config) {
        super(listeners);
        mLogSaver = logSaver;
        mConfig = config;
        for (ITestInvocationListener listener : listeners) {
            if (listener instanceof ILogSaverListener) {
                ((ILogSaverListener) listener).setLogSaver(mLogSaver);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invocationStarted(IInvocationContext context) {
        // Intentionally call invocationStarted for the log saver first.
        try {
            mLogSaver.invocationStarted(context);
        } catch (RuntimeException e) {
            CLog.e("Caught runtime exception from log saver: %s", mLogSaver.getClass().getName());
            CLog.e(e);
        }
        InvocationSummaryHelper.reportInvocationStarted(getListeners(), context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invocationEnded(long elapsedTime) {
        InvocationSummaryHelper.reportInvocationEnded(getListeners(), elapsedTime);
        // Intentionally call invocationEnded for the log saver last.
        try {
            mLogSaver.invocationEnded(elapsedTime);
        } catch (RuntimeException e) {
            CLog.e("Caught runtime exception from log saver: %s", mLogSaver.getClass().getName());
            CLog.e(e);
        }
        String endHostLogName = TestInvocation.TRADEFED_END_HOST_LOG;
        if (mConfig.getCommandOptions().getHostLogSuffix() != null) {
            endHostLogName += mConfig.getCommandOptions().getHostLogSuffix();
        }
        reportEndHostLog(getListeners(), mLogSaver, endHostLogName);
    }

    /** Log a final file before completion */
    public static void logFile(
            List<ITestInvocationListener> listeners,
            ILogSaver saver,
            InputStreamSource source,
            String name,
            LogDataType type) {
        try (InputStream stream = source.createInputStream()) {
            LogFile logFile = saver.saveLogData(name, type, stream);

            for (ITestInvocationListener listener : listeners) {
                try {
                    if (listener instanceof ILogSaverListener) {
                        ((ILogSaverListener) listener).testLogSaved(name, type, source, logFile);
                        ((ILogSaverListener) listener).logAssociation(name, logFile);
                    }
                } catch (Exception e) {
                    CLog.logAndDisplay(LogLevel.ERROR, e.getMessage());
                    CLog.e(e);
                }
            }
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /** Reports host_log from session in progress. */
    public static void reportEndHostLog(
            List<ITestInvocationListener> listeners, ILogSaver saver, String name) {
        LogRegistry registry = (LogRegistry) LogRegistry.getLogRegistry();
        try (InputStreamSource source = registry.getLogger().getLog()) {
            if (source == null) {
                if (!(registry.getLogger() instanceof StdoutLogger)) {
                    CLog.e("%s stream was null, skip saving it.", name);
                }
                return;
            }
            logFile(listeners, saver, source, name, LogDataType.HOST_LOG);
            if (SystemUtil.isRemoteEnvironment()) {
                try (InputStream stream = source.createInputStream()) {
                    // In remote environment, dump to the stdout so we can get the logs in the
                    // console.
                    System.out.println(
                            String.format(
                                    "===== Result Reporters =====\n%s",
                                    StreamUtil.getStringFromStream(stream)));
                }
            }
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Also, save the log file with the global {@link ILogSaver} and call
     * {@link ILogSaverListener#testLogSaved(String, LogDataType, InputStreamSource, LogFile)}
     * for those listeners implementing the {@link ILogSaverListener} interface.
     */
    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        testLogForward(dataName, dataType, dataStream);
        try {
            if (dataStream == null) {
                CLog.w("Skip forwarding of '%s', data stream is null.", dataName);
                return;
            }
            long startTime = System.currentTimeMillis();
            LogFile logFile = null;
            try {
                // If it's a file, copy it directly as it's faster
                if (dataStream instanceof FileInputStreamSource) {
                    logFile =
                            mLogSaver.saveLogFile(
                                    dataName,
                                    dataType,
                                    ((FileInputStreamSource) dataStream).getFile());
                } else {
                    logFile =
                            mLogSaver.saveLogData(
                                    dataName, dataType, dataStream.createInputStream());
                }
            } finally {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.LOG_SAVING_TIME,
                        System.currentTimeMillis() - startTime);
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.LOG_SAVING_COUNT, 1);
            }
            for (ITestInvocationListener listener : getListeners()) {
                if (listener instanceof ILogSaverListener) {
                    ((ILogSaverListener) listener).testLogSaved(dataName, dataType,
                            dataStream, logFile);
                    ((ILogSaverListener) listener).logAssociation(dataName, logFile);
                }
            }
        } catch (RuntimeException | IOException e) {
            CLog.e("Failed to save log data: %s", dataName);
            CLog.e(e);
        }
    }

    /** Only forward the testLog instead of saving the log first. */
    public void testLogForward(
            String dataName, LogDataType dataType, InputStreamSource dataStream) {
        super.testLog(dataName, dataType, dataStream);
    }

    /**
     * {@inheritDoc}
     *
     * <p>If {@link LogSaverResultForwarder} is wrap in another one, ensure we forward the
     * testLogSaved callback to the listeners under it.
     */
    @Override
    public void testLogSaved(
            String dataName, LogDataType dataType, InputStreamSource dataStream, LogFile logFile) {
        try {
            for (ITestInvocationListener listener : getListeners()) {
                if (listener instanceof ILogSaverListener) {
                    ((ILogSaverListener) listener)
                            .testLogSaved(dataName, dataType, dataStream, logFile);
                }
            }
        } catch (RuntimeException e) {
            CLog.e("Failed to save log data for %s", dataName);
            CLog.e(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void logAssociation(String dataName, LogFile logFile) {
        for (ITestInvocationListener listener : getListeners()) {
            try {
                // Forward the logAssociation call
                if (listener instanceof ILogSaverListener) {
                    ((ILogSaverListener) listener).logAssociation(dataName, logFile);
                }
            } catch (RuntimeException e) {
                CLog.e("Failed to provide the log association");
                CLog.e(e);
            }
        }
    }
}
