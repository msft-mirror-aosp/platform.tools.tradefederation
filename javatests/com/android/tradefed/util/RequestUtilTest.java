/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tradefed.error.HarnessRuntimeException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@RunWith(JUnit4.class)
public class RequestUtilTest {
    static final Integer TIMING_PRECISION_MILLIS = 25;

    protected class AlwaysFailsCallable implements Callable<Boolean> {
        @Override
        public Boolean call() throws Exception {
            throw new IOException("This request always fails!");
        }
    }

    @Test
    public void testRequestFailedAll() {
        Callable<Boolean> requestCallable = new AlwaysFailsCallable();
        try {
            Boolean success = RequestUtil.requestWithBackoff(requestCallable, 100, 400, 2);
        } catch (HarnessRuntimeException e) {
            if (!e.getMessage().contains("Request failed after too many retries.")) {
                throw e;
            }
        }
    }

    protected class TimingAlwaysFailsCallable implements Callable<Boolean> {
        long mLastTimeMillis;
        List<Integer> mTimesToWaitMillis;
        int mStep;

        public TimingAlwaysFailsCallable(List<Integer> times) {
            mTimesToWaitMillis = times;
            mStep = -1;
            mLastTimeMillis = System.currentTimeMillis();
        }

        @Override
        public Boolean call() throws Exception {
            long timeNow = System.currentTimeMillis();
            if (mStep >= 0) {
                if (Math.abs((timeNow - mLastTimeMillis) - mTimesToWaitMillis.get(mStep))
                        > TIMING_PRECISION_MILLIS) {
                    // Since this isn't an IOException, it will abort the retry and fail the test
                    throw new RuntimeException("Waited the wrong amount of time between requests");
                }
            }
            mLastTimeMillis = timeNow;
            mStep++;
            throw new IOException("This request always fails!");
        }
    }

    @Test
    public void testWaitRightTimeBetweenRequests_1() {
        List<Integer> expectedTimes = List.of(100, 200, 400);
        Callable<Boolean> requestCallable = new TimingAlwaysFailsCallable(expectedTimes);
        try {
            Boolean success = RequestUtil.requestWithBackoff(requestCallable, 100, 400, 2);
        } catch (HarnessRuntimeException e) {
            if (!e.getMessage().contains("Request failed after too many retries.")) {
                throw e;
            }
        }
    }

    @Test
    public void testWaitRightTimeBetweenRequests_2() {
        List<Integer> expectedTimes = List.of(100, 200, 400);
        Callable<Boolean> requestCallable = new TimingAlwaysFailsCallable(expectedTimes);
        try {
            Boolean success = RequestUtil.requestWithBackoff(requestCallable, 100, 700, 2);
        } catch (HarnessRuntimeException e) {
            if (!e.getMessage().contains("Request failed after too many retries.")) {
                throw e;
            }
        }
    }

    @Test
    public void testWaitRightTimeBetweenRequests_3() {
        List<Integer> expectedTimes = List.of(100, 300, 900);
        Callable<Boolean> requestCallable = new TimingAlwaysFailsCallable(expectedTimes);
        try {
            Boolean success = RequestUtil.requestWithBackoff(requestCallable, 100, 1000, 3);
        } catch (HarnessRuntimeException e) {
            if (!e.getMessage().contains("Request failed after too many retries.")) {
                throw e;
            }
        }
    }
}
