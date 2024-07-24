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

package com.android.tradefed.util.gcs;

import com.android.tradefed.log.LogUtil.CLog;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.util.ExponentialBackOff;
import com.google.auth.Credentials;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;

public class GoogleApiClientUtilBase {

    public static final String APP_NAME = "tradefed";
    private static GoogleApiClientUtilBase sInstance = null;

    private static GoogleApiClientUtilBase getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleApiClientUtilBase();
        }
        return sInstance;
    }

    /**
     * Try to create Google API credential with default credential.
     *
     * <p>Only default credential is used.
     *
     * @param scopes scopes for the credential.
     * @return a {@link Credential}
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static Credentials createCredential(Collection<String> scopes)
            throws IOException, GeneralSecurityException {
        return getInstance().doCreateDefaultCredential(scopes);
    }

    /**
     * Create credential from json key file.
     *
     * @param file is the p12 key file
     * @param scopes is the API's scope.
     * @return a {@link Credential}.
     * @throws FileNotFoundException
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public static Credentials createCredentialFromJsonKeyFile(File file, Collection<String> scopes)
            throws IOException, GeneralSecurityException {
        return getInstance().doCreateCredentialFromJsonKeyFile(file, scopes);
    }

    @VisibleForTesting
    protected Credentials doCreateCredentialFromJsonKeyFile(File file, Collection<String> scopes)
            throws IOException, GeneralSecurityException {
        Credentials credentail =
                GoogleCredentials.fromStream(new FileInputStream(file)).createScoped(scopes);
        return credentail;
    }

    @VisibleForTesting
    protected Credentials doCreateDefaultCredential(Collection<String> scopes) throws IOException {
        try {
            CLog.d("Using local authentication.");
            return ComputeEngineCredentials.getApplicationDefault().createScoped(scopes);
        } catch (IOException e) {
            CLog.e(
                    "Try 'gcloud auth application-default login' to login for "
                            + "personal account; Or 'export "
                            + "GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json' "
                            + "for service account.");
            throw e;
        }
    }

    /**
     * @param requestInitializer a {@link HttpRequestInitializer}, normally it's {@link Credential}.
     * @param connectTimeout connect timeout in milliseconds.
     * @param readTimeout read timeout in milliseconds.
     * @return a {@link HttpRequestInitializer} with timeout.
     */
    public static HttpRequestInitializer setHttpTimeout(
            final HttpRequestInitializer requestInitializer, int connectTimeout, int readTimeout) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) throws IOException {
                requestInitializer.initialize(request);
                request.setConnectTimeout(connectTimeout);
                request.setReadTimeout(readTimeout);
            }
        };
    }

    /**
     * Setup a retry strategy for the provided HttpRequestInitializer. In case of server errors
     * requests will be automatically retried with an exponential backoff.
     *
     * @param initializer - an initializer which will setup a retry strategy.
     * @return an initializer that will retry failed requests automatically.
     */
    public static HttpRequestInitializer configureRetryStrategyAndTimeout(
            HttpRequestInitializer initializer, int connectTimeout, int readTimeout) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) throws IOException {
                initializer.initialize(request);
                request.setConnectTimeout(connectTimeout);
                request.setReadTimeout(readTimeout);
                request.setUnsuccessfulResponseHandler(new RetryResponseHandler());
            }
        };
    }

    /**
     * Setup a retry strategy for the provided HttpRequestInitializer. In case of server errors
     * requests will be automatically retried with an exponential backoff.
     *
     * @param initializer - an initializer which will setup a retry strategy.
     * @return an initializer that will retry failed requests automatically.
     */
    public static HttpRequestInitializer configureRetryStrategy(
            HttpRequestInitializer initializer) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) throws IOException {
                initializer.initialize(request);
                request.setUnsuccessfulResponseHandler(new RetryResponseHandler());
            }
        };
    }

    private static class RetryResponseHandler implements HttpUnsuccessfulResponseHandler {
        // Initial interval to wait before retrying if a request fails.
        private static final int INITIAL_RETRY_INTERVAL = 1000;
        private static final int MAX_RETRY_INTERVAL = 3 * 60000; // Set max interval to 3 minutes.

        private final HttpUnsuccessfulResponseHandler backOffHandler;

        public RetryResponseHandler() {
            backOffHandler =
                    new HttpBackOffUnsuccessfulResponseHandler(
                            new ExponentialBackOff.Builder()
                                    .setInitialIntervalMillis(INITIAL_RETRY_INTERVAL)
                                    .setMaxIntervalMillis(MAX_RETRY_INTERVAL)
                                    .build());
        }

        @Override
        public boolean handleResponse(
                HttpRequest request, HttpResponse response, boolean supportsRetry)
                throws IOException {
            CLog.w(
                    "Request to %s failed: %d %s",
                    request.getUrl(), response.getStatusCode(), response.getStatusMessage());
            if (response.getStatusCode() == 400) {
                return false;
            }
            return backOffHandler.handleResponse(request, response, supportsRetry);
        }
    }
}
