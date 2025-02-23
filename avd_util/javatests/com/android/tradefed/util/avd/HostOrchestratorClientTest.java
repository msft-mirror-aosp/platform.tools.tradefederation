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

import static com.android.tradefed.util.avd.HostOrchestratorClient.ErrorResponseException;
import static com.android.tradefed.util.avd.HostOrchestratorClient.Operation;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildCreateBugreportRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildGetOperationResultRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildPowerwashRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.buildRemoveInstanceRequest;
import static com.android.tradefed.util.avd.HostOrchestratorClient.saveToFile;
import static com.android.tradefed.util.avd.HostOrchestratorClient.sendRequest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

/** Unit tests for {@link HostOrchestratorClient} */
@RunWith(JUnit4.class)
public class HostOrchestratorClientTest {

    @Mock private HostOrchestratorClient.IHoHttpClient mFakeHttpClient;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void tearDown() {}

    @Test
    public void testBuildGetOperationRequest() throws Exception {
        HttpRequest r = buildGetOperationRequest("https://ho.test", "opfoo");

        Assert.assertEquals("https://ho.test/operations/opfoo", r.uri().toString());
    }

    @Test
    public void testBuildGetOperationResultRequest() throws Exception {
        HttpRequest r = buildGetOperationResultRequest("https://ho.test", "opfoo");

        Assert.assertEquals("https://ho.test/operations/opfoo/result", r.uri().toString());
    }

    @Test
    public void testBuildCreateBugreportRequest() throws Exception {
        HttpRequest r = buildCreateBugreportRequest("https://ho.test", "foo");

        Assert.assertEquals("https://ho.test/cvds/foo/:bugreport", r.uri().toString());
    }

    @Test
    public void testBuildPowerwashRequest() throws Exception {
        HttpRequest r = buildPowerwashRequest("https://ho.test", "foo", "1");

        Assert.assertEquals("https://ho.test/cvds/foo/1/:powerwash", r.uri().toString());
    }

    @Test
    public void testBuildRemoveInstanceRequest() throws Exception {
        HttpRequest r = buildRemoveInstanceRequest("https://ho.test", "foo", "1");

        Assert.assertEquals("https://ho.test/cvds/foo/1", r.uri().toString());
    }

    @Test
    public void testSendRequestSucceeds() throws Exception {
        String body = "{ \"name\":\"foo\", \"done\": \"true\" }";
        HttpRequest request = buildGetOperationRequest("https://ho.test", "opfoo");
        HttpResponse<String> response = buildFakeResponse(200, body);
        Mockito.when(mFakeHttpClient.send(Mockito.any())).thenReturn(response);

        Operation result = sendRequest(mFakeHttpClient, request, Operation.class);

        Assert.assertEquals("foo", result.name);
        Assert.assertTrue(result.done);
    }

    @Test
    public void testSendRequestErrorResponse() throws Exception {
        String body = "500 Internal Server Error";
        HttpRequest request = buildGetOperationRequest("https://ho.test", "opfoo");
        HttpResponse<String> response = buildFakeResponse(500, body);
        Mockito.when(mFakeHttpClient.send(Mockito.any())).thenReturn(response);

        ErrorResponseException mE = new ErrorResponseException(0, "");
        try {
            Operation result = sendRequest(mFakeHttpClient, request, Operation.class);
        } catch (ErrorResponseException e) {
            mE = e;
        }

        Assert.assertEquals(mE.getStatusCode(), 500);
        Assert.assertEquals(mE.getBody(), body);
    }

    @Test
    public void testSendSaveToFileRequestSucceeds() throws Exception {
        Path dst = Paths.get("foo.txt");
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create("http://ho.test/foo.txt")).build();
        HttpResponse<Path> response = buildFakeResponse(200, dst);
        Mockito.when(mFakeHttpClient.send(Mockito.any(), Mockito.any())).thenReturn(response);

        saveToFile(mFakeHttpClient, request, dst);
    }

    @Test
    public void testSendSaveToFileRequestFails() throws Exception {
        Path dst = Paths.get("foo.txt");
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create("http://ho.test/foo.txt")).build();
        HttpResponse<Path> response = buildFakeResponse(500, dst);
        Mockito.when(mFakeHttpClient.send(Mockito.any(), Mockito.any())).thenReturn(response);

        ErrorResponseException mE = new ErrorResponseException(0, "");
        try {
            saveToFile(mFakeHttpClient, request, dst);
        } catch (ErrorResponseException e) {
            mE = e;
        }

        Assert.assertEquals(mE.getStatusCode(), 500);
        Assert.assertEquals(mE.getBody(), "");
    }

    private static <T> HttpResponse<T> buildFakeResponse(int statusCode, T body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public T body() {
                return body;
            }

            @Override
            public Optional<HttpResponse<T>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return null;
            }

            @Override
            public HttpClient.Version version() {
                return null;
            }
        };
    }
}
