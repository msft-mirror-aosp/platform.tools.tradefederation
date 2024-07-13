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

package com.android.tradefed.util;

import com.android.tradefed.cache.ICacheClient;
import com.android.tradefed.cache.remote.ByteStreamDownloader;
import com.android.tradefed.cache.remote.ByteStreamUploader;
import com.android.tradefed.cache.remote.RemoteCacheClient;
import com.android.tradefed.log.LogUtil.CLog;

import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Collections;

/** A factory that creates a singleton instance of {@link ICacheClient}. */
public final class CacheClientFactory {
    private static ICacheClient sCacheClient = null;

    /** A list of default Google Cloud authentication scopes. */
    private static final Collection<String> GOOGLE_AUTH_SCOPES =
            Collections.singleton("https://www.googleapis.com/auth/cloud-platform");

    /**
     * The key of the json key file for {@link HostOptions}'s service-account-json-key-file option.
     */
    private static final String REMOTE_CACHE_JSON_KEY = "gcs-json-key";

    /** The URI of remote API used to setup channel. */
    private static final String REMOTE_API_URI = "remotebuildexecution.googleapis.com";

    /**
     * Creates a singleton instance of {@link ICacheClient}.
     *
     * <p>Only one singleton instance will be created and shared everywhere in this invocation. This
     * method is thread-safe.
     *
     * @param workFolder The work folder where the client creates temporary files.
     * @param instanceName The instance name of the remote execution API.
     * @return An instance of {@link ICacheClient}.
     */
    public static ICacheClient createCacheClient(File workFolder, String instanceName) {
        if (sCacheClient == null) {
            synchronized (RemoteCacheClient.class) {
                if (sCacheClient == null) {
                    CallCredentials callCredentials;
                    try {
                        callCredentials =
                                MoreCallCredentials.from(
                                        GoogleApiClientUtil.createCredential(
                                                GOOGLE_AUTH_SCOPES,
                                                true,
                                                null,
                                                REMOTE_CACHE_JSON_KEY));
                    } catch (IOException | GeneralSecurityException e) {
                        CLog.e("Exception occurred when creating call credentials!");
                        CLog.e(e);
                        return null;
                    }
                    ManagedChannel channel =
                            ManagedChannelBuilder.forTarget(REMOTE_API_URI).build();
                    sCacheClient =
                            new RemoteCacheClient(
                                    workFolder,
                                    instanceName,
                                    channel,
                                    callCredentials,
                                    new ByteStreamDownloader(
                                            instanceName,
                                            channel,
                                            callCredentials,
                                            RemoteCacheClient.REMOTE_TIMEOUT),
                                    new ByteStreamUploader(
                                            instanceName,
                                            channel,
                                            callCredentials,
                                            RemoteCacheClient.REMOTE_TIMEOUT));
                }
            }
        }
        return sCacheClient;
    }
}
