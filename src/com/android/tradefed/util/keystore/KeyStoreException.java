/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.tradefed.util.keystore;

/**
 * Thrown when a fatal key store error happens.
 */
public class KeyStoreException extends Exception {
    private static final long serialVersionUID = 7742154448269011969L;

    /**
     * Creates a {@link KeyStoreException}.
     */
    public KeyStoreException() {
        super();
    }

    /**
     * Creates a {@link KeyStoreException}.
     *
     * @param msg a descriptive message.
     */
    public KeyStoreException(String msg) {
        super(msg);
    }

    /**
     * Creates a {@link KeyStoreException}.
     *
     * @param msg a descriptive message.
     * @param cause the root {@link Throwable} that caused the device to become
     *            unavailable.
     */
    public KeyStoreException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
