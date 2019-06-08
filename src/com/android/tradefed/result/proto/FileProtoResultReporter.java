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

import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Proto reporter that dumps the {@link TestRecord} into a file. */
public class FileProtoResultReporter extends ProtoResultReporter {

    @Option(
        name = "proto-output-file",
        description = "File where the proto output will be saved",
        mandatory = true
    )
    private File mOutputFile;

    @Option(
        name = "periodic-proto-writing",
        description =
                "Whether or not to output intermediate proto per module following a numbered "
                        + "sequence."
    )
    private boolean mPeriodicWriting = false;

    // Current index of the sequence of proto output
    private int mIndex = 0;

    @Override
    public void processStartInvocation(
            TestRecord invocationStartRecord, IInvocationContext invocationContext) {
        writeProto(invocationStartRecord);
    }

    @Override
    public void processTestModuleEnd(TestRecord moduleRecord) {
        writeProto(moduleRecord);
    }

    @Override
    public void processTestRunEnded(TestRecord runRecord, boolean moduleInProgress) {
        if (!moduleInProgress) {
            // If it's a testRun outside of the module scope, output it to ensure we support
            // non-module use cases.
            writeProto(runRecord);
        }
    }

    @Override
    public void processFinalProto(TestRecord finalRecord) {
        writeProto(finalRecord);
    }

    /** Sets the file where to output the result. */
    public void setFileOutput(File output) {
        mOutputFile = output;
    }

    /** Enable writing each module individualy to a file. */
    public void setPeriodicWriting(boolean enabled) {
        mPeriodicWriting = enabled;
    }

    private void writeProto(TestRecord record) {
        File outputFile = mOutputFile;
        if (mPeriodicWriting) {
            outputFile = new File(mOutputFile.getAbsolutePath() + mIndex);
        }
        try {
            record.writeDelimitedTo(new FileOutputStream(outputFile));
            if (mPeriodicWriting) {
                nextOutputFile();
            }
        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException(e);
        }
    }

    private void nextOutputFile() {
        mIndex++;
    }
}
