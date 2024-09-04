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
package com.android.tradefed.testtype.suite;

import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.skipped.SkipContext;
import com.android.tradefed.util.CacheClientFactory;

import build.bazel.remote.execution.v2.Digest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/** Utility to upload and download cache results for a test module. */
public class SuiteResultCacheUtil {

    /**
     * Upload results to RBE
     *
     * @param mainConfig
     * @param moduleConfig
     * @param protoResults
     * @param moduleDir
     * @param skipContext
     */
    public static void uploadModuleResults(
            IConfiguration mainConfig,
            String moduleId,
            File moduleConfig,
            File protoResults,
            File moduleDir,
            SkipContext skipContext) {
        // TODO: Ensure skipContext is complete
        try (CloseableTraceScope ignored = new CloseableTraceScope("upload_module_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            Map<String, String> environment = new HashMap<>();
            for (Entry<String, Digest> entry : skipContext.getImageToDigest().entrySet()) {
                environment.put(entry.getKey(), entry.getValue().getHash());
            }
            ExecutableAction action =
                    ExecutableAction.create(
                            moduleDir, Arrays.asList(moduleId), environment, 60000L);
            ExecutableActionResult result = ExecutableActionResult.create(0, protoResults, null);
            cacheClient.uploadCache(action, result);
        } catch (IOException | RuntimeException | InterruptedException e) {
            CLog.e(e);
        }
    }
}
