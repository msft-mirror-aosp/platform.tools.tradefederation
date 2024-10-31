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

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.cache.DigestCalculator;
import com.android.tradefed.log.LogUtil.CLog;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Compute a MerkleTree from the device content information. */
public class DeviceMerkleTree {

    /** Builds a merkle tree and returns the root digest from the device content information */
    public static Digest buildFromContext(ContentAnalysisContext context) {
        try {
            ArtifactDetails currentContent =
                    ArtifactDetails.parseFile(
                            context.contentInformation().currentContent, context.contentEntry());
            Directory.Builder rootBuilder = Directory.newBuilder();
            List<ArtifactFileDescriptor> allFiles = currentContent.details;
            ImageContentAnalyzer.normalizeDeviceImage(allFiles);
            // Sort to ensure final messages are identical
            Collections.sort(
                    allFiles,
                    new Comparator<ArtifactFileDescriptor>() {
                        @Override
                        public int compare(
                                ArtifactFileDescriptor arg0, ArtifactFileDescriptor arg1) {
                            return arg0.path.compareTo(arg1.path);
                        }
                    });
            for (ArtifactFileDescriptor afd : currentContent.details) {
                Digest digest =
                        Digest.newBuilder().setHash(afd.digest).setSizeBytes(afd.size).build();
                rootBuilder.addFiles(
                        FileNode.newBuilder()
                                .setDigest(digest)
                                .setName(afd.path)
                                .setIsExecutable(false));
            }
            Directory root = rootBuilder.build();
            Digest d = DigestCalculator.compute(root);
            CLog.d("Digest for '%s' is '%s'", context.contentEntry(), d);
            return d;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
            return null;
        }
    }
}
