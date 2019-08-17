/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.tradefed.testtype.retry.IAutoRetriableTest;

/**
 * A {@link IRemoteTest} that supports retrying if the test aborted before completion.
 *
 * @deprecated Retry is now supported at harness level or through {@link IAutoRetriableTest}.
 */
@Deprecated
public interface IRetriableTest extends IRemoteTest {

    /** @return {@code true} if the test is currently retriable. */
    public default boolean isRetriable() {
        return false;
    }
}
