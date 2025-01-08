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

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Utility to upload and download cache results for a test module. */
public class SuiteResultCacheUtil {

    public static final String DEVICE_IMAGE_KEY = "device_image";
    public static final String MODULE_CONFIG_KEY = "module_config";
    public static final String TRADEFED_JAR_VERSION_KEY = "tradefed.jar_version";

    private static final Set<String> REMOVE_APKS =
            ImmutableSet.of("TradefedContentProvider.apk", "TelephonyUtility.apk", "WifiUtil.apk");
    private static final Map<String, Digest> COMPUTE_CACHE = new ConcurrentHashMap<String, Digest>();

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
        long startTime = System.currentTimeMillis();
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
            environment.put(MODULE_CONFIG_KEY, configDigest.getHash());
            Digest tradefedDigest = computeTradefedVersion();
            if (tradefedDigest != null) {
                environment.put(TRADEFED_JAR_VERSION_KEY, tradefedDigest.getHash());
            }
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
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.MODULE_CACHE_UPLOAD_ERROR, 1);
        } finally {
            InvocationMetricLogger.addInvocationPairMetrics(
                    InvocationMetricKey.MODULE_CACHE_UPLOAD_TIME,
                    startTime,
                    System.currentTimeMillis());
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
        long startTime = System.currentTimeMillis();
        try (CloseableTraceScope ignored = new CloseableTraceScope("lookup_module_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            Map<String, String> environment = new HashMap<>();
            for (Entry<String, Digest> entry : skipContext.getImageToDigest().entrySet()) {
                environment.put(entry.getKey(), entry.getValue().getHash());
            }
            try (CloseableTraceScope computeDigest = new CloseableTraceScope("compute_digest")) {
                Digest configDigest = DigestCalculator.compute(moduleConfig);
                environment.put(MODULE_CONFIG_KEY, configDigest.getHash());
                Digest tradefedDigest = computeTradefedVersion();
                if (tradefedDigest != null) {
                    environment.put(TRADEFED_JAR_VERSION_KEY, tradefedDigest.getHash());
                }
            }
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
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.MODULE_CACHE_MISS_ID, moduleId);
            } else {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.MODULE_RESULTS_CACHE_HIT, 1);
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.MODULE_CACHE_HIT_ID, moduleId);
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
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.MODULE_CACHE_DOWNLOAD_ERROR, 1);
        } finally {
            InvocationMetricLogger.addInvocationPairMetrics(
                    InvocationMetricKey.MODULE_CACHE_DOWNLOAD_TIME,
                    startTime,
                    System.currentTimeMillis());
        }
        return new CacheResultDescriptor(false, null);
    }

    /**
     * Hash Tradefed.jar as a denominator to keep results. This helps consider changes to Tradefed.
     */
    private static Digest computeTradefedVersion() throws IOException {
        String classpathStr = System.getProperty("java.class.path");
        if (classpathStr == null) {
            return null;
        }
        for (String file : classpathStr.split(":")) {
            File currentJar = new File(file);
            if (currentJar.exists() && "tradefed.jar".equals(currentJar.getName())) {
                return processJarFile(currentJar.getAbsolutePath());
            }
        }
        return null;
    }

    private static Digest processJarFile(String jarFilePath) throws IOException {
        if (COMPUTE_CACHE.containsKey(jarFilePath)) {
            return COMPUTE_CACHE.get(jarFilePath);
        }
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (REMOVE_APKS.contains(entry.getName())) {
                    continue;
                }
                if (!entry.isDirectory()) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;

                        while ((bytesRead = is.read(buffer)) != -1) {
                            digest.update(buffer, 0, bytesRead);
                        }
                    } catch (IOException e) {
                        CLog.e(e);
                    }
                }
            }
            Digest tfDigest =
                    Digest.newBuilder()
                            .setHash(HashCode.fromBytes(digest.digest()).toString())
                            .setSizeBytes(digest.getDigestLength())
                            .build();
            COMPUTE_CACHE.put(jarFilePath, tfDigest);
            return tfDigest;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
