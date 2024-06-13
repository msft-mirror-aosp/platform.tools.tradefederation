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


import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/** Class that helps to manage tracing for each test invocation. */
public class TracingLogger {

    private static final Map<ThreadGroup, ActiveTrace> mPerGroupActiveTrace =
            Collections.synchronizedMap(new HashMap<ThreadGroup, ActiveTrace>());

    /**
     * Creates and register an active trace for an invocation.
     *
     * @param pid Current process id
     * @param tid Current thread id
     */
    public static ActiveTrace createActiveTrace(long pid, long tid) {
        return createActiveTrace(pid, tid, false);
    }

    public static ActiveTrace createActiveTrace(long pid, long tid, boolean mainProcess) {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupActiveTrace) {
            ActiveTrace trace = new ActiveTrace(pid, tid, mainProcess);
            mPerGroupActiveTrace.put(group, trace);
            return trace;
        }
    }

    /** If it exists, returns the current trace of the Tradefed process itself. */
    public static ActiveTrace getMainTrace() {
        synchronized (mPerGroupActiveTrace) {
            for (ActiveTrace t : mPerGroupActiveTrace.values()) {
                if (t != null && t.isMainTradefedProcess()) {
                    return t;
                }
            }
            return null;
        }
    }

    private static ThreadLocal<ThreadGroup> sLocal = new ThreadLocal<>();

    /** Tracks a localized context when using the properties inside the gRPC server */
    public static void setLocalGroup(ThreadGroup tg) {
        sLocal.set(tg);
    }

    /** Resets the localized context. */
    public static void resetLocalGroup() {
        sLocal.remove();
    }

    /**
     * Sets the currently active trace for an invocation.
     *
     * @return the previous active trace or {@code null} if there was none.
     */
    @Nullable
    static ActiveTrace setActiveTrace(ActiveTrace trace) {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupActiveTrace) {
            return mPerGroupActiveTrace.put(group, trace);
        }
    }

    /** Returns the current active trace for the invocation, or null if none. */
    public static ActiveTrace getActiveTrace() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupActiveTrace) {
            if (sLocal.get() != null) {
                group = sLocal.get();
            }
            return mPerGroupActiveTrace.get(group);
        }
    }

    public static ActiveTrace getActiveTraceForGroup(ThreadGroup group) {
        if (group == null) {
            return null;
        }
        synchronized (mPerGroupActiveTrace) {
            return mPerGroupActiveTrace.get(group);
        }
    }

    /** Finalize the tracing and clear the tracking. */
    public static File finalizeTrace() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupActiveTrace) {
            ActiveTrace trace = mPerGroupActiveTrace.remove(group);
            if (trace != null) {
                return trace.finalizeTracing();
            }
        }
        return null;
    }
}
