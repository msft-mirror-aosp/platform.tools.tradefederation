/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tradefed.invoker.shard;

import com.android.tradefed.invoker.ShardMainResultForwarder;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;

/**
 * When running local sharding, sometimes we only want to execute some actions when the last shard
 * reaches {@link #invocationEnded(long)}. This reporter allows to detect it.
 *
 * @see ShardMainResultForwarder
 */
public final class LastShardDetector implements ITestInvocationListener {

    private boolean mLastShardDone = false;

    @Override
    public void invocationEnded(long elapsedTime) {
        CLog.d("Last shard invocationEnded was reported.");
        mLastShardDone = true;
    }

    /** Returns True if the last shard had called {@link #invocationEnded(long)}. */
    public boolean isLastShardDone() {
        return mLastShardDone;
    }
}
