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
package com.android.tradefed.device;

/** Enum representation of when to join/block for the snapuserd update to finish. */
public enum SnapuserdWaitPhase {
    BLOCK_AFTER_UPDATE, // Block right after updating the device
    BLOCK_BEFORE_TEST, // Block before running any tests
    BLOCK_BEFORE_RELEASING; // Do not block until it's time for the invocation to finish to avoid
    // leaking that state.
}
