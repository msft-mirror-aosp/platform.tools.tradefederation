/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tradefed.invoker.tracing;

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;

import com.google.common.base.Enums;
import com.google.common.base.Optional;

import perfetto.protos.PerfettoTrace.TrackEvent;

/** A scoped class that allows to report tracing section via try-with-resources */
public class CloseableTraceScope implements AutoCloseable {

    private static final String DEFAULT_CATEGORY = "invocation";
    private final String category;
    private final String name;
    private final long startTime;

    /**
     * Report a scoped trace.
     *
     * @param category The category of the operation
     * @param name The name for reporting the section
     */
    public CloseableTraceScope(String category, String name) {
        this(category, name, System.nanoTime());
    }

    /**
     * Report a scoped trace.
     *
     * @param category The category of the operation
     * @param name The name for reporting the section
     * @param startTimestampNano start timestamp in nano seconds
     */
    public CloseableTraceScope(String category, String name, long startTimestampNano) {
        this.category = category;
        this.name = name;
        this.startTime = System.currentTimeMillis();
        ActiveTrace trace = TracingLogger.getActiveTrace();
        if (trace == null) {
            return;
        }
        int threadId = (int) Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        trace.reportTraceEvent(
                category,
                name,
                threadId,
                threadName,
                startTimestampNano,
                TrackEvent.Type.TYPE_SLICE_BEGIN);
    }

    public CloseableTraceScope(String name, long startTimestampNano) {
        this(DEFAULT_CATEGORY, name, startTimestampNano);
    }

    /** Constructor. */
    public CloseableTraceScope(String name) {
        this(DEFAULT_CATEGORY, name);
    }

    /** Constructor for reporting scope from threads. */
    public CloseableTraceScope() {
        this(DEFAULT_CATEGORY, Thread.currentThread().getName());
    }

    public void close(long endTimestampNano) {
        ActiveTrace trace = TracingLogger.getActiveTrace();
        if (trace == null) {
            return;
        }
        int threadId = (int) Thread.currentThread().getId();
        String threadName = Thread.currentThread().getName();
        trace.reportTraceEvent(
                category,
                name,
                threadId,
                threadName,
                endTimestampNano,
                TrackEvent.Type.TYPE_SLICE_END);
        Optional<InvocationMetricKey> optionalKey =
                Enums.getIfPresent(InvocationMetricKey.class, name);
        if (optionalKey.isPresent()) {
            InvocationMetricLogger.addInvocationPairMetrics(
                    optionalKey.get(), startTime, System.currentTimeMillis());
        }
    }

    @Override
    public void close() {
        close(System.nanoTime());
    }
}
