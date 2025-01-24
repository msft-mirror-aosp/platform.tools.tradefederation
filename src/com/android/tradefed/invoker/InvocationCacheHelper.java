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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.cache.ExecutableAction;
import com.android.tradefed.cache.ExecutableActionResult;
import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.proxy.TradefedDelegator;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.ModuleProtoResultReporter;
import com.android.tradefed.util.CacheClientFactory;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.QuotationAwareTokenizer;

import build.bazel.remote.execution.v2.Digest;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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

    /**
     * Upload invocation results
     *
     * @param mainConfig
     * @param protoResults
     * @param testInfo
     */
    public static void uploadInvocationResults(
            IConfiguration mainConfig, File protoResults, TestInformation testInfo) {
        if (testInfo.getDevices().size() > 1) {
            return;
        }
        boolean emptyTestsDir = false;
        File invocationTestsDir = testInfo.getBuildInfo().getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        try (CloseableTraceScope ignored = new CloseableTraceScope("upload_invocation_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            if (invocationTestsDir == null) {
                emptyTestsDir = true;
                invocationTestsDir = FileUtil.createTempDir("invoc-cache-tmp");
            }
            ExecutableAction action =
                    ExecutableAction.create(
                            invocationTestsDir,
                            getCommonCommandLine(mainConfig.getCommandLine()),
                            computeEnvironment(mainConfig),
                            60000L);
            ExecutableActionResult result = ExecutableActionResult.create(0, protoResults, null);
            CLog.d("Uploading cache for %s and %s", action, protoResults);
            cacheClient.uploadCache(action, result);
        } catch (IOException | RuntimeException | InterruptedException e) {
            CLog.e(e);
        } finally {
            if (emptyTestsDir) {
                FileUtil.recursiveDelete(invocationTestsDir);
            }
        }
    }

    public static CacheInvocationResultDescriptor lookupInvocationResults(
            IConfiguration mainConfig, TestInformation testInfo) {
        if (testInfo.getDevices().size() > 1) {
            return null;
        }
        if (mainConfig.getSkipManager().getImageToDigest().containsValue(null)) {
            CLog.d("No digest for device.");
            return new CacheInvocationResultDescriptor(false, null);
        }
        boolean emptyTestsDir = false;
        File invocationTestsDir = testInfo.getBuildInfo().getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        try (CloseableTraceScope ignored = new CloseableTraceScope("lookup_invocation_results")) {
            String cacheInstance = mainConfig.getCommandOptions().getRemoteCacheInstanceName();
            ICacheClient cacheClient =
                    CacheClientFactory.createCacheClient(
                            CurrentInvocation.getWorkFolder(), cacheInstance);
            if (invocationTestsDir == null) {
                emptyTestsDir = true;
                invocationTestsDir = FileUtil.createTempDir("invoc-cache-tmp");
            }
            ExecutableAction action =
                    ExecutableAction.create(
                            invocationTestsDir,
                            getCommonCommandLine(mainConfig.getCommandLine()),
                            computeEnvironment(mainConfig),
                            60000L);
            CLog.d("Looking up cache for %s", action);
            ExecutableActionResult cachedResults = cacheClient.lookupCache(action);
            if (cachedResults == null) {
                CLog.d("No cached results for the invocation.");
                return null;
            } else {
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
                return new CacheInvocationResultDescriptor(true, details);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            CLog.e(e);
        } finally {
            if (emptyTestsDir) {
                FileUtil.recursiveDelete(invocationTestsDir);
            }
        }
        return null;
    }

    private static Map<String, String> computeEnvironment(IConfiguration mainConfig) {
        Map<String, String> environment = new HashMap<>();
        for (Entry<String, Digest> entry :
                mainConfig.getSkipManager().getImageToDigest().entrySet()) {
            environment.put(entry.getKey(), entry.getValue().getHash());
        }
        String atpTestName =
                mainConfig
                        .getCommandOptions()
                        .getInvocationData()
                        .getUniqueMap()
                        .get("atp_test_name");
        if (atpTestName != null) {
            environment.put("atp_test_name", atpTestName);
        }
        // add tradefed.jar version
        return environment;
    }

    private static List<String> getCommonCommandLine(String commandLine) {
        String[] commandArray = QuotationAwareTokenizer.tokenizeLine(commandLine, false);
        try {
            commandArray =
                    TradefedDelegator.clearCommandlineFromOneArg(commandArray, "invocation-data");
            commandArray = TradefedDelegator.clearCommandlineFromOneArg(commandArray, "build-id");
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
        return Arrays.asList(commandArray);
    }
}
