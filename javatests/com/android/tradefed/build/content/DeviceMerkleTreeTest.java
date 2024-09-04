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
package com.android.tradefed.build.content;

import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.util.FileUtil;

import build.bazel.remote.execution.v2.Digest;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;

/** Unit tests for {@link DeviceMerkleTree}. */
@RunWith(JUnit4.class)
public class DeviceMerkleTreeTest {

    @Test
    public void testGenerateDigest() throws IOException {
        File baseJson = ArtifactDetailsTest.generateBaseContent();
        File currentJson = ArtifactDetailsTest.generateCurrentContent();

        try {
            ContentInformation infoBase = new ContentInformation(null, null, baseJson, "8888");
            ContentAnalysisContext contextBase =
                    new ContentAnalysisContext(
                            "mysuite.zip", infoBase, AnalysisMethod.DEVICE_IMAGE);
            Digest baseDigest = DeviceMerkleTree.buildFromContext(contextBase);

            ContentInformation currentBase =
                    new ContentInformation(null, null, currentJson, "8888");
            ContentAnalysisContext contextCurrent =
                    new ContentAnalysisContext(
                            "mysuite.zip", currentBase, AnalysisMethod.DEVICE_IMAGE);
            Digest currentDigest = DeviceMerkleTree.buildFromContext(contextCurrent);

            Truth.assertThat(baseDigest.getHash()).isNotEqualTo(currentDigest.getHash());
            Truth.assertThat(baseDigest.getHash())
                    .isEqualTo("a4246469911c553cae9d8ce1b6e40cacb2131d6e07aabe5c28b1c180b4e7114f");
            Truth.assertThat(currentDigest.getHash())
                    .isEqualTo("d4f7d421e22f24bbf0a5d3bfad906a475e390b2aa0f69215d587a65ba1c3a484");
        } finally {
            FileUtil.deleteFile(baseJson);
            FileUtil.deleteFile(currentJson);
        }
    }
}
