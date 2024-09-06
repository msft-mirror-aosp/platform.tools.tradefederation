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
package com.android.tradefed.result.skipped;

import build.bazel.remote.execution.v2.Digest;

import java.util.Map;
import java.util.Set;

/** Representation of the context surrounding decision about skipping or caching of results. */
public class SkipContext {

    private final Set<String> unchangedModules;
    private final boolean presubmit;
    private final Map<String, Digest> imageToDigest;

    public SkipContext(
            boolean presubmit, Set<String> unchangedModules, Map<String, Digest> imageToDigest) {
        this.presubmit = presubmit;
        this.unchangedModules = unchangedModules;
        this.imageToDigest = imageToDigest;
    }

    /**
     * Only skip unchanged modules in presubmit. At this stage the unchanged modules are known
     * (based on unchanged device image and test artifacts)
     */
    public boolean shouldSkipModule(String moduleName) {
        return presubmit && unchangedModules.contains(moduleName);
    }

    /** Reports whether to use caching or not. */
    public boolean shouldUseCache() {
        return !presubmit; // For now, we only allow caching in postsubmit.
    }

    public Map<String, Digest> getImageToDigest() {
        return imageToDigest;
    }
}
