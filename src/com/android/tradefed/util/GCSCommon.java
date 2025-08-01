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
package com.android.tradefed.util;

import com.android.tradefed.host.HostOptions;

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

/**
 * Obsoleted! Use com.android.tradefed.util.gcs.GCSCommon instead.
 *
 * <p>This class is kept for backwards compatibility, so tradefed prebuilt can be used to build
 * tests on older branches.
 *
 * <p>Base class for Gcs operation like download and upload. {@link GCSFileDownloader} and {@link
 * GCSFileUploader}.
 */
public abstract class GCSCommon {
    /** This is the key for {@link HostOptions}'s service-account-json-key-file option. */
    private static final String GCS_JSON_KEY = "gcs-json-key";

    protected static final int DEFAULT_TIMEOUT = 10 * 60 * 1000; // 10minutes

    private File mJsonKeyFile = null;
    private Storage mStorage;

    public GCSCommon(File jsonKeyFile) {
        mJsonKeyFile = jsonKeyFile;
    }

    public GCSCommon() {}

    void setJsonKeyFile(File jsonKeyFile) {
        mJsonKeyFile = jsonKeyFile;
    }

    protected Storage getStorage(Collection<String> scopes) throws IOException {
        Credentials credential = null;
        try {
            if (mStorage == null) {
                credential =
                        GoogleApiClientUtil.createCredential(
                                scopes, true, mJsonKeyFile, GCS_JSON_KEY);
                HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credential);
                mStorage =
                        new Storage.Builder(
                                        GoogleNetHttpTransport.newTrustedTransport(),
                                        GsonFactory.getDefaultInstance(),
                                        GoogleApiClientUtil.configureRetryStrategy(
                                                GoogleApiClientUtil.setHttpTimeout(
                                                        requestInitializer,
                                                        DEFAULT_TIMEOUT,
                                                        DEFAULT_TIMEOUT)))
                                .setApplicationName(GoogleApiClientUtil.APP_NAME)
                                .build();
            }
            return mStorage;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
