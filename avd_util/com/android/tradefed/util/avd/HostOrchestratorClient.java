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
package com.android.tradefed.util.avd;

import com.android.tradefed.log.LogUtil.CLog;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;

/**
 * Java implementation of Cuttlefish Host Orchestator API.
 *
 * <p>- Endpoints:
 * https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/orchestrator/controller.go#L56-L102
 * - Objects:
 * https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/api/v1/messages.go
 */
public class HostOrchestratorClient {

    // https://github.com/google/android-cuttlefish/blob/main/frontend/src/host_orchestrator/api/v1/messages.go#L104
    public static final class Operation {
        public String name;
        public boolean done;
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L78
    public static HttpRequest buildGetOperationRequest(String baseURL, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/operations/%s", baseURL, name)))
                .build();
    }

    // https://github.com/google/android-cuttlefish/blob/fff7e3487c924435e6f6120345edf1dddb49d50b/frontend/src/host_orchestrator/orchestrator/controller.go#L82
    public static HttpRequest buildGetOperationResultRequest(String baseURL, String name) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/operations/%s/result", baseURL, name)))
                .build();
    }

    public static interface IHoHttpClient {
        HttpResponse<String> send(HttpRequest request)
                throws IOException, InterruptedException, ErrorResponseException;

        HttpResponse<Path> send(HttpRequest request, Path dst)
                throws IOException, InterruptedException, ErrorResponseException;
    }

    public static final class HoHttpClient implements IHoHttpClient {
        private final HttpClient mClient;

        public HoHttpClient() {
            mClient = HttpClient.newBuilder().build();
        }

        @Override
        public HttpResponse<String> send(HttpRequest request)
                throws IOException, InterruptedException, ErrorResponseException {
            return mClient.send(request, BodyHandlers.ofString());
        }

        @Override
        public HttpResponse<Path> send(HttpRequest request, Path dst)
                throws IOException, InterruptedException, ErrorResponseException {
            return mClient.send(request, BodyHandlers.ofFile(dst));
        }
    }

    public static final class ErrorResponseException extends Exception {
        private final int mStatusCode;
        private final String mBody;

        public ErrorResponseException(int statusCode, String body) {
            super(
                    String.format(
                            "error response with status code: %d, response body: %s",
                            statusCode, body));
            mStatusCode = statusCode;
            mBody = body;
        }

        public int getStatusCode() {
            return mStatusCode;
        }

        public String getBody() {
            return mBody;
        }
    }

    public static <T> T sendRequest(
        IHoHttpClient client, HttpRequest request, Class<T> responseClass)
            throws IOException, InterruptedException, ErrorResponseException {
        HttpResponse<String> res = client.send(request);
        if (res.statusCode() != 200) {
            throw new ErrorResponseException(res.statusCode(), res.body());
        }
        return new Gson().fromJson(res.body(), responseClass);
    }

    public static void saveToFile(IHoHttpClient client, HttpRequest request, Path dst)
            throws IOException, InterruptedException, ErrorResponseException {
        HttpResponse<Path> res = client.send(request, dst);
        if (res.statusCode() != 200) {
            throw new ErrorResponseException(res.statusCode(), "");
        }
        CLog.i("Response body for \"%s\" successfully saved to \"%s\"", request.uri(), dst);
    }
}
