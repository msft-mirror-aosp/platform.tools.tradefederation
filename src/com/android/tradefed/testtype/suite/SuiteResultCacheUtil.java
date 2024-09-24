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

import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.NullDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.ModuleProtoResultReporter;
import com.android.tradefed.result.skipped.SkipContext;
import com.android.tradefed.util.CacheClientFactory;
import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.Digest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/** Utility to upload and download cache results for a test module. */
public class SuiteResultCacheUtil {

    public static final String DEVICE_IMAGE_KEY = "device_image";

    /** Describes the cache results. */
    public static class CacheResultDescriptor {
        private final boolean cacheHit;
        private final String cacheExplanation;

        public CacheResultDescriptor(boolean cacheHit, String explanation) {
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

    /**
     * Upload results to RBE
     *
     * @param mainConfig
     * @param testInfo
     * @param module
     * @param moduleConfig
     * @param protoResults
     * @param moduleDir
     * @param skipContext
     */
    public static void uploadModuleResults(
            IConfiguration mainConfig,
            TestInformation testInfo,
            ModuleDefinition module,
            File moduleConfig,
            File protoResults,
            File moduleDir,
            SkipContext skipContext) {
        //  TODO: We don't support multi-devices
        if (testInfo.getDevices().size() > 1) {
            return;
        }
        if (!(testInfo.getDevice().getIDevice() instanceof NullDevice)
                && !skipContext.getImageToDigest().containsKey(DEVICE_IMAGE_KEY)) {
            CLog.d("We have device but no device digest.");
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.MODULE_RESULTS_CACHE_DEVICE_MISMATCH, 1);
            return;
        }
        if (skipContext.getImageToDigest().containsValue(null)) {
            CLog.d("No digest for device.");
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.MODULE_RESULTS_CACHE_DEVICE_MISMATCH, 1);
            return;
        }
        String moduleId = module.getId();
        // TODO: Ensure we have the link to the results
        try (CloseableTraceScope ignored = new CloseableTraceScope("upload_module_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            Map<String, String> environment = new HashMap<>();
            for (Entry<String, Digest> entry : skipContext.getImageToDigest().entrySet()) {
                environment.put(entry.getKey(), entry.getValue().getHash());
            }
            Digest configDigest = DigestCalculator.compute(moduleConfig);
            environment.put("module_config", configDigest.getHash());
            if (module.getIntraModuleShardCount() != null
                    && module.getIntraModuleShardIndex() != null) {
                environment.put(
                        "intra_module_shard_index",
                        Integer.toString(module.getIntraModuleShardIndex()));
                environment.put(
                        "intra_module_shard_count",
                        Integer.toString(module.getIntraModuleShardCount()));
            }
            ExecutableAction action =
                    ExecutableAction.create(
                            moduleDir, Arrays.asList(moduleId), environment, 60000L);
            ExecutableActionResult result = ExecutableActionResult.create(0, protoResults, null);
            CLog.d("Uploading cache for %s and %s", action, protoResults);
            cacheClient.uploadCache(action, result);
        } catch (IOException | RuntimeException | InterruptedException e) {
            CLog.e(e);
        }
    }

    /**
     * Look up results in RBE for the test module.
     *
     * @param mainConfig
     * @param module
     * @param moduleConfig
     * @param moduleDir
     * @param skipContext
     * @return a {@link CacheResultDescriptor} describing the cache result.
     */
    public static CacheResultDescriptor lookUpModuleResults(
            IConfiguration mainConfig,
            ModuleDefinition module,
            File moduleConfig,
            File moduleDir,
            SkipContext skipContext) {
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.MODULE_RESULTS_CHECKING_CACHE, 1);
        if (skipContext.getImageToDigest().containsValue(null)) {
            CLog.d("No digest for device.");
            return new CacheResultDescriptor(false, null);
        }
        String moduleId = module.getId();
        try (CloseableTraceScope ignored = new CloseableTraceScope("lookup_module_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            Map<String, String> environment = new HashMap<>();
            for (Entry<String, Digest> entry : skipContext.getImageToDigest().entrySet()) {
                environment.put(entry.getKey(), entry.getValue().getHash());
            }
            Digest configDigest = DigestCalculator.compute(moduleConfig);
            environment.put("module_config", configDigest.getHash());
            if (module.getIntraModuleShardCount() != null
                    && module.getIntraModuleShardIndex() != null) {
                environment.put(
                        "intra_module_shard_index",
                        Integer.toString(module.getIntraModuleShardIndex()));
                environment.put(
                        "intra_module_shard_count",
                        Integer.toString(module.getIntraModuleShardCount()));
            }
            ExecutableAction action =
                    ExecutableAction.create(
                            moduleDir, Arrays.asList(moduleId), environment, 60000L);
            CLog.d("Looking up cache for %s", action);
            ExecutableActionResult cachedResults = cacheClient.lookupCache(action);
            if (cachedResults == null) {
                CLog.d("No cached results for %s", moduleId);
            } else {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.MODULE_RESULTS_CACHE_HIT, 1);
                String details = "Cached results.";
                Map<String, String> metadata =
                        ModuleProtoResultReporter.parseResultsMetadata(cachedResults.stdOut());
                if (metadata.containsKey(ModuleProtoResultReporter.INVOCATION_ID_KEY)) {
                    details +=
                            String.format(
                                    " origin of results: http://ab/%s",
                                    metadata.get(ModuleProtoResultReporter.INVOCATION_ID_KEY));
                    CLog.d(details);
                }
                FileUtil.deleteFile(cachedResults.stdOut());
                FileUtil.deleteFile(cachedResults.stdErr());
                return new CacheResultDescriptor(true, details);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            CLog.e(e);
        }
        return new CacheResultDescriptor(false, null);
    }
}
