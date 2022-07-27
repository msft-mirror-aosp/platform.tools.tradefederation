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

import perfetto.protos.PerfettoTrace.TrackEvent;

/** A scoped class that allows to report tracing section via try-with-resources */
public class CloseableTraceScope implements AutoCloseable {

    private final String category;
    private final String name;

    /** Constructor. */
    public CloseableTraceScope(String category, String name) {
        this.category = category;
        this.name = name;
        ActiveTrace trace = TracingLogger.getActiveTrace();
        if (trace == null) {
            return;
        }
        trace.reportTraceEvent(category, name, TrackEvent.Type.TYPE_SLICE_BEGIN);
    }

    @Override
    public void close() {
        ActiveTrace trace = TracingLogger.getActiveTrace();
        if (trace == null) {
            return;
        }
        trace.reportTraceEvent(category, name, TrackEvent.Type.TYPE_SLICE_END);
    }
}
