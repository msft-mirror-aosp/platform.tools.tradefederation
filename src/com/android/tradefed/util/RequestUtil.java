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
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.util.concurrent.Callable;

/** Utilities to allow generic retry of network requests with error handling. */
public class RequestUtil {
    /**
     * Call the specified request with backoff parameters.
     *
     * @param requestMethod the method to call to make the request
     * @param minWaitMSec the shortest period to wait between requests
     * @param maxWaitMSec the longest period to wait between requests
     * @param scalingFactor the multiple to apply to the waiting period on a failed request
     */
    public static <T> T requestWithBackoff(
            Callable<T> requestMethod, int minWaitMSec, int maxWaitMSec, int scalingFactor) {
        IRunUtil runUtil = RunUtil.getDefault();
        int timeToWait = minWaitMSec;
        while (timeToWait <= maxWaitMSec) {
            try {
                return requestMethod.call();
            } catch (StatusRuntimeException e) {
                CLog.w(
                        "StatusRuntimeException while making request:\nCode: %s\n%s",
                        Status.fromThrowable(e).getCode().toString(), e.getMessage());
            } catch (IOException e) {
                CLog.w(
                        "IOException while attempting to make request:\n%s\n%s",
                        e.getMessage(), e.getStackTrace());
            } catch (Throwable e) {
                // If the exception is not something like an IOError or StatusRuntimeException
                // then it's not a simple request failure and we should error out.
                throw new HarnessRuntimeException(
                        "Request failed with unexpected exception.",
                        e,
                        InfraErrorIdentifier.UNDETERMINED);
            }

            // Wait some amount of time before trying again
            runUtil.sleep(timeToWait);
            // Increase the amount of time to wait the next time
            timeToWait *= scalingFactor;
        }
        throw new HarnessRuntimeException(
                "Request failed after too many retries.", InfraErrorIdentifier.UNDETERMINED);
    }

    /**
     * Call the specified request with backoff parameters.
     *
     * <p>Uses some default timing parameters.
     *
     * @param requestMethod the method to call to make the request
     */
    public static <T> T requestWithBackoff(Callable<T> requestMethod) {
        return requestWithBackoff(requestMethod, 100, 100 * 64, 4);
    }
}
