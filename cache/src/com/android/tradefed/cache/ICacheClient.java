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

package com.android.tradefed.cache;

/** An interface for a cache client. */
public interface ICacheClient {

    /**
     * Uploads the results for the {@link ExecutableAction}.
     *
     * <p>If the result of the {@code action} doesn't exist, the {@code actionResult} will be
     * stored. Otherwise, the result will be updated.
     *
     * @param action The action that generated the results.
     * @param actionResult The action result to associate with the {@code action}.
     */
    void uploadCache(ExecutableAction action, ExecutableActionResult actionResult);
}
