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

package com.android.tradefed.cache;

import static org.junit.Assert.assertEquals;

import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;

import com.google.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Tests for {@link UploadManifest}. */
@RunWith(JUnit4.class)
public class UploadManifestTest {
    private File mWorkFolder;

    @Before
    public final void setUp() throws Exception {
        mWorkFolder = FileUtil.createTempDir("work-folder");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.recursiveDelete(mWorkFolder);
    }

    @Test
    public void builder_works() throws IOException {
        File emptyFileA = FileUtil.createTempFile("empty-a-", ".txt", mWorkFolder);
        Digest emptyFileADigest = DigestCalculator.compute(emptyFileA);
        File emptyFileB = FileUtil.createTempFile("empty-b-", ".txt", mWorkFolder);
        Digest emptyFileBDigest = DigestCalculator.compute(emptyFileB);
        File testFile = FileUtil.createTempFile("test-", ".txt", mWorkFolder);
        FileUtil.writeToFile("test", testFile);
        Digest testFileDigest = DigestCalculator.compute(testFile);
        Directory defaultDirectoryA = Directory.getDefaultInstance();
        Digest defaultDirectoryADigest = DigestCalculator.compute(defaultDirectoryA);
        Directory defaultDirectoryB = Directory.getDefaultInstance();
        Digest defaultDirectoryBDigest = DigestCalculator.compute(defaultDirectoryB);
        Directory directory =
                Directory.newBuilder()
                        .addFiles(
                                FileNode.newBuilder()
                                        .setDigest(testFileDigest)
                                        .setName(testFile.getName()))
                        .build();
        Digest directoryDigest = DigestCalculator.compute(directory);
        Map<Digest, File> expectedDigestToFile =
                Map.of(testFileDigest, testFile, emptyFileBDigest, emptyFileB);
        Map<Digest, ByteString> expectedDigestToBlob =
                Map.of(
                        directoryDigest,
                        directory.toByteString(),
                        defaultDirectoryBDigest,
                        defaultDirectoryB.toByteString());

        UploadManifest manifest =
                UploadManifest.builder()
                        .addFile(emptyFileADigest, emptyFileA)
                        .addFiles(expectedDigestToFile)
                        .addBlob(defaultDirectoryADigest, defaultDirectoryA.toByteString())
                        .addBlobs(expectedDigestToBlob)
                        .build();

        assertEquals(emptyFileADigest, emptyFileBDigest);
        assertEquals(defaultDirectoryADigest, defaultDirectoryBDigest);
        assertEquals(expectedDigestToFile, manifest.digestToFile());
        assertEquals(expectedDigestToBlob, manifest.digestToBlob());
    }
}
