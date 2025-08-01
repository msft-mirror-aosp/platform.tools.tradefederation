/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.util.gcs;

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

/** Base class for Gcs operation like download and upload. */
public abstract class GCSCommon {

    public static final int DEFAULT_TIMEOUT = 10 * 60 * 1000; // 10minutes

    protected File mJsonKeyFile = null;

    protected Storage mStorage;

    public GCSCommon() {}

    protected void setJsonKeyFile(File jsonKeyFile) {
        mJsonKeyFile = jsonKeyFile;
    }

    /*
     * The base implementation only supports using default credential.
     */
    protected Storage getStorage(Collection<String> scopes) throws IOException {
        Credentials credential = null;
        try {
            if (mStorage == null) {
                credential = GoogleApiClientUtilBase.createCredential(scopes);
                HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
                mStorage =
                        new Storage.Builder(
                                        GoogleNetHttpTransport.newTrustedTransport(),
                                        GsonFactory.getDefaultInstance(),
                                        GoogleApiClientUtilBase.configureRetryStrategy(
                                                GoogleApiClientUtilBase.setHttpTimeout(
                                                        requestInitializer,
                                                        DEFAULT_TIMEOUT,
                                                        DEFAULT_TIMEOUT)))
                                .setApplicationName(GoogleApiClientUtilBase.APP_NAME)
                                .build();
            }
            return mStorage;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
