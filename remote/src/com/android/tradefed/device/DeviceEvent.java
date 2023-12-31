/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * Represents a test device event that can change allocation state
 */
enum DeviceEvent {
        CONNECTED_ONLINE,
        CONNECTED_OFFLINE,
        STATE_CHANGE_ONLINE,
        STATE_CHANGE_OFFLINE,
        DISCONNECTED,
        FORCE_AVAILABLE,
        AVAILABLE_CHECK_PASSED,
        AVAILABLE_CHECK_FAILED,
        AVAILABLE_CHECK_IGNORED,
        FASTBOOT_DETECTED,
        ALLOCATE_REQUEST,
        EXPLICIT_ALLOCATE_REQUEST,
        FORCE_ALLOCATE_REQUEST,
        FREE_AVAILABLE,
        FREE_UNRESPONSIVE,
        FREE_UNAVAILABLE,
        FREE_UNKNOWN;
}
