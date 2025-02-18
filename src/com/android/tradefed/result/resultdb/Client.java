/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tradefed.result.resultdb;

import com.android.resultdb.proto.CreateInvocationRequest;
import com.android.resultdb.proto.Invocation;
import com.android.resultdb.proto.TestResult;
import com.android.resultdb.proto.UpdateInvocationRequest;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.ArrayList;
import java.util.List;

/** ResultDB recorder client that uploads test results to ResultDB. */
public class Client implements IRecorderClient {

    private final Uploader mUploader;
    private final Thread mUploadThread;
    // The id of the ResultDB invocation to upload results to.
    // Currently only one ResultDB invocation per TF invocation is supported.
    private final String mInvocationId;
    private final String mUpdateToken;

    private Client(String invocationId, String updateToken) {
        mInvocationId = invocationId;
        mUpdateToken = updateToken;
        mUploader = new Uploader();
        mUploadThread = new Thread(mUploader, "Recorder upload thread");
        mUploadThread.setDaemon(true);
        mUploadThread.start();
    }

    public static IRecorderClient create(String invocationId, String updateToken) {
        return new Client(invocationId, updateToken);
    }

    @Override
    public Invocation createInvocation(CreateInvocationRequest request) {
        // TODO: Call recorder grpc client to create invocation.
        CLog.i("Creating invocation: %s", request.toString());
        return request.getInvocation();
    }

    @Override
    public Invocation updateInvocation(UpdateInvocationRequest request) {
        // TODO: Call recorder grpc client to update invocation.
        CLog.i("Updating invocation: %s", request.toString());
        return request.getInvocation();
    }

    @Override
    public Invocation finalizeInvocation(String invocationId) {
        // TODO: Call recorder grpc client to finalize invocation.
        CLog.i("Finalize invocation: %s", invocationId);
        return Invocation.getDefaultInstance();
    }

    @Override
    public void uploadTestResult(TestResult result) {
        mUploader.enqueue(result);
    }

    @Override
    public void finalizeTestResults() {
        mUploader.cancel();
        try {
            mUploadThread.join();
        } catch (InterruptedException e) {
            CLog.e("Error joining upload thread: %s", e);
        }
    }

    private class Uploader implements Runnable {
        private static final int BATCH_SIZE = 500;

        private final List<List<TestResult>> mPendingBatches = new ArrayList<>();
        private List<TestResult> currentBatch = new ArrayList<>();
        private volatile boolean mCanceled = false;

        public void enqueue(TestResult result) {
            if (mCanceled) {
                throw new IllegalStateException(
                        "Attempted to upload results after upload thread was cancelled");
            }
            synchronized (this) {
                if (currentBatch.size() + 1 >= BATCH_SIZE) {
                    mPendingBatches.add(currentBatch);
                    currentBatch = new ArrayList<>();
                    this.notifyAll();
                }
                currentBatch.add(result);
            }
        }

        public synchronized void forceUpload() {
            synchronized (this) {
                if (!currentBatch.isEmpty()) {
                    mPendingBatches.add(currentBatch);
                }
                while (!mPendingBatches.isEmpty()) {
                    int lastIndex = mPendingBatches.size() - 1;
                    upload(mPendingBatches.remove(lastIndex));
                }
            }
        }

        public synchronized void cancel() {
            CLog.i("Canceling recorder uploader");
            mCanceled = true;
            this.notifyAll();
        }

        @Override
        public void run() {
            List<TestResult> uploadBatch = new ArrayList<TestResult>();
            while (!mCanceled) {
                synchronized (this) {
                    // Wait with a timeout of 10 seconds when nothing to upload.
                    if (mPendingBatches.isEmpty()) {
                        try {
                            this.wait(10000L);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                    if (!mPendingBatches.isEmpty()) {
                        int lastIndex = mPendingBatches.size() - 1;
                        CLog.i(
                                "Starting batch upload of %d results",
                                mPendingBatches.get(lastIndex).size());
                        uploadBatch.addAll(mPendingBatches.remove(lastIndex));
                    }
                }
                if (!uploadBatch.isEmpty()) {
                    upload(uploadBatch);
                    uploadBatch.clear();
                }
            }
            // Upload any remaining results.
            synchronized (this) {
                if (!currentBatch.isEmpty()) {
                    mPendingBatches.add(currentBatch);
                }
                while (!mPendingBatches.isEmpty()) {
                    int lastIndex = mPendingBatches.size() - 1;
                    upload(mPendingBatches.remove(lastIndex));
                }
            }
            CLog.i("Uploader terminating");
        }

        private void upload(List<TestResult> allResults) {
            // TODO: Call recorder grpc client to upload test results.
            CLog.i(
                    "Uploading %d results to invocation %s with update token %s",
                    allResults.size(), mInvocationId, mUpdateToken);
            CLog.i("Uploading results request %s", allResults.toString());
        }
    }
}
