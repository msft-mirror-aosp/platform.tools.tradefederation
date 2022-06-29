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

package com.android.tradefed.observatory;

import com.android.tradefed.error.HarnessException;
import com.android.tradefed.result.error.ErrorIdentifier;

public class TestDiscoveryException extends HarnessException {
    /**
     * Creates a {@link TestDiscoveryException}.
     *
     * @param message a meaningful error message
     */
    protected TestDiscoveryException(String message) {
        super(message, null);
    }
    /**
     * Creates a {@link TestDiscoveryException}.
     *
     * @param message a meaningful error message
     * @param error The {@link ErrorIdentifier} associated with the exception
     */
    protected TestDiscoveryException(String message, ErrorIdentifier error) {
        super(message, error);
    }
}