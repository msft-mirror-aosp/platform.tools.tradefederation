/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tradefed.invoker;

/** Utility to handle uploading and looking up invocation cache results. */
public class InvocationCacheHelper {

    /** Describes the cache results. */
    public static class CacheInvocationResultDescriptor {
        private final boolean cacheHit;
        private final String cacheExplanation;

        public CacheInvocationResultDescriptor(boolean cacheHit, String explanation) {
            this.cacheHit = cacheHit;
            this.cacheExplanation = explanation;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }

        public String getDetails() {
            return cacheExplanation;
        }
    }

    public static void uploadInvocationResults() {
        // TODO: implement
    }

    public static CacheInvocationResultDescriptor lookupInvocationResults() {
        // TODO: Implement
        return null;
    }
}
