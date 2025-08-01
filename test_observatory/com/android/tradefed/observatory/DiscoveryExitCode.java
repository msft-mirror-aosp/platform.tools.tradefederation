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

public enum DiscoveryExitCode {
    SUCCESS(0),
    COMPONENT_METADATA(5),
    NO_DISCOVERY_POSSIBLE(6), // When the command doesn't have any properties useful for discovery.
    CONFIGURATION_EXCEPTION(7), // When the command itself doesn't parse
    DISCOVERY_RESULTS_CORREPUTED(8), // When the discovery results are corrupted.
    ERROR(1);

    private final int code;

    private DiscoveryExitCode(int code) {
        this.code = code;
    }

    public int exitCode() {
        return code;
    }
}
