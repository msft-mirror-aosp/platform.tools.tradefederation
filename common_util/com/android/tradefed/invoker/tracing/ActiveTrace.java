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

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import perfetto.protos.PerfettoTrace.DebugAnnotation;
import perfetto.protos.PerfettoTrace.ProcessDescriptor;
import perfetto.protos.PerfettoTrace.Trace;
import perfetto.protos.PerfettoTrace.TracePacket;
import perfetto.protos.PerfettoTrace.TrackDescriptor;
import perfetto.protos.PerfettoTrace.TrackEvent;

/** Main class helping to describe and manage an active trace. */
public class ActiveTrace {

    public static final String TRACE_KEY = "invocation-trace";
    private final long pid;
    // private final long tid;
    private final long traceUuid;
    private final int uid = 5555; // TODO: collect a real uid
    // File where the final trace gets outputed
    private File mTraceOutput;

    /**
     * Constructor.
     *
     * @param pid Current process id
     * @param tid Current thread id
     */
    public ActiveTrace(long pid, long tid) {
        this.pid = pid;
        // this.tid = tid;
        this.traceUuid = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    /** Start the tracing and report the metadata of the trace. */
    public void startTracing(boolean isSubprocess) {
        if (mTraceOutput != null) {
            throw new IllegalStateException("Tracing was already started.");
        }
        try {
            mTraceOutput = FileUtil.createTempFile(TRACE_KEY, ".perfetto-trace");
        } catch (IOException e) {
            CLog.e(e);
        }
        // Initialize all the trace metadata
        createMainInvocationTracker((int) pid, traceUuid, isSubprocess);
    }

    /** Provide the trace file from a subprocess to be added to the parent. */
    public void addSubprocessTrace(File trace) {
        if (mTraceOutput == null) {
            return;
        }

        try (FileInputStream stream = new FileInputStream(trace)) {
            FileUtil.writeToFile(stream, mTraceOutput, true);
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /**
     * Very basic event reporting to do START / END of traces.
     *
     * @param categories Category associated with event
     * @param name Event name
     * @param type Type of the event being reported
     */
    public void reportTraceEvent(String categories, String name, TrackEvent.Type type) {
        TracePacket.Builder tracePacket =
                TracePacket.newBuilder()
                        .setTrustedUid(uid)
                        .setTrustedPid((int) pid)
                        .setTimestamp(System.nanoTime())
                        .setTrustedPacketSequenceId(1)
                        .setSequenceFlags(1)
                        .setTrackEvent(
                                TrackEvent.newBuilder()
                                        .setTrackUuid(traceUuid)
                                        .setName(name)
                                        .setType(type)
                                        .addCategories(categories)
                                        .addDebugAnnotations(
                                                DebugAnnotation.newBuilder().setName(name)));
        writeToTrace(tracePacket.build());
    }

    /** Reports the final trace files and clean up resources as needed. */
    public File finalizeTracing() {
        CLog.logAndDisplay(LogLevel.DEBUG, "Finalizing trace: %s", mTraceOutput);
        File trace = mTraceOutput;
        mTraceOutput = null;
        return trace;
    }

    private String createProcessName(boolean isSubprocess) {
        if (isSubprocess) {
            return "subprocess-test-invocation";
        }
        return "test-invocation";
    }

    private void createMainInvocationTracker(int pid, long traceUuid, boolean isSubprocess) {
        TrackDescriptor.Builder descriptor =
                TrackDescriptor.newBuilder()
                        .setUuid(traceUuid)
                        .setProcess(
                                ProcessDescriptor.newBuilder()
                                        .setPid(pid)
                                        .setProcessName(createProcessName(isSubprocess)));

        TracePacket.Builder traceTrackDescriptor =
                TracePacket.newBuilder()
                        .setTrustedUid(uid)
                        .setTimestamp(System.nanoTime())
                        .setTrustedPacketSequenceId(1)
                        .setSequenceFlags(1)
                        .setTrustedPid(pid)
                        .setTrackDescriptor(descriptor.build());

        writeToTrace(traceTrackDescriptor.build());
    }

    private synchronized void writeToTrace(TracePacket packet) {
        if (mTraceOutput == null) {
            return;
        }
        // Perfetto UI supports repeated Trace
        Trace wrappingTrace = Trace.newBuilder().addPacket(packet).build();
        try (FileOutputStream out = new FileOutputStream(mTraceOutput, true)) {
            wrappingTrace.writeTo(out);
            out.flush();
        } catch (IOException e) {
            CLog.e("Failed to write execution trace to file.");
            CLog.e(e);
        }
    }
}
