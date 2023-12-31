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
package com.android.tradefed.config.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.remote.IRemoteFileResolver.RemoteFileResolverArgs;
import com.android.tradefed.config.remote.IRemoteFileResolver.ResolvedFile;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.net.IHttpHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;

/** Unit tests for {@link HttpRemoteFileResolver}. */
@RunWith(JUnit4.class)
public class HttpRemoteFileResolverTest {
    private HttpRemoteFileResolver mResolver;
    private IHttpHelper mHttpDownloader;

    @Before
    public void setUp() {
        mHttpDownloader = Mockito.mock(IHttpHelper.class);
        mResolver =
                new HttpRemoteFileResolver() {
                    @Override
                    protected IHttpHelper getDownloader() {
                        return mHttpDownloader;
                    }
                };
    }

    @Test
    public void testResolve() throws Exception {
        RemoteFileResolverArgs args = new RemoteFileResolverArgs();
        args.setConsideredFile(new File("http:/fake/HttpRemoteFileResolverTest"));
        String fakeUrl = "http://fake/HttpRemoteFileResolverTest";
        Mockito.doReturn(fakeUrl).when(mHttpDownloader).buildUrl(Mockito.eq(fakeUrl),
                Mockito.any());
        ResolvedFile res = mResolver.resolveRemoteFile(args);
        FileUtil.deleteFile(res.getResolvedFile());

        Mockito.verify(mHttpDownloader)
                .doGet(Mockito.eq(fakeUrl), Mockito.any());
    }

    @Test
    public void testResolve_error() throws Exception {
        String fakeUrl = "http://fake/HttpRemoteFileResolverTest";
        Mockito.doReturn(fakeUrl).when(mHttpDownloader).buildUrl(Mockito.eq(fakeUrl),
                Mockito.any());
        Mockito.doThrow(new IOException("download failure"))
                .when(mHttpDownloader)
                .doGet(Mockito.eq(fakeUrl), Mockito.any());

        try {
            RemoteFileResolverArgs args = new RemoteFileResolverArgs();
            args.setConsideredFile(new File("http:/fake/HttpRemoteFileResolverTest"));
            mResolver.resolveRemoteFile(args);
            fail("Should have thrown an exception.");
        } catch (BuildRetrievalError expected) {
            assertEquals(
                    "Failed to download http://fake/HttpRemoteFileResolverTest due to: download failure",
                    expected.getMessage());
        }

        Mockito.verify(mHttpDownloader)
                .doGet(Mockito.eq(fakeUrl), Mockito.any());
    }
}
