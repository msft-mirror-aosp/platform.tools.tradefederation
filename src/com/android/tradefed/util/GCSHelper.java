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

import com.android.tradefed.host.HostOptions;
import com.android.tradefed.util.gcs.GCSCommon;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.storage.Storage;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;

public class GCSHelper {

    /** This is the key for {@link HostOptions}'s service-account-json-key-file option. */
    private static final String GCS_JSON_KEY = "gcs-json-key";

    /**
     * Get {@link Storage} object for the remote GCS bucket with credential based on TF options.
     *
     * @param scopes specific scopes to request credential for.
     * @return {@link Storage} object of the GCS bucket
     * @throws IOException
     */
    public static Storage getStorage(Collection<String> scopes, File keyFile) throws IOException {
        Credentials credential = null;
        try {
            credential = GoogleApiClientUtil.createCredential(scopes, true, keyFile, GCS_JSON_KEY);
            HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
            return new Storage.Builder(
                            GoogleNetHttpTransport.newTrustedTransport(),
                            GsonFactory.getDefaultInstance(),
                            GoogleApiClientUtil.configureRetryStrategy(
                                    GoogleApiClientUtil.setHttpTimeout(
                                            requestInitializer,
                                            GCSCommon.DEFAULT_TIMEOUT,
                                            GCSCommon.DEFAULT_TIMEOUT)))
                    .setApplicationName(GoogleApiClientUtil.APP_NAME)
                    .build();

        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
