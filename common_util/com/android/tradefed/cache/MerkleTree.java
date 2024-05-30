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

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import com.google.auto.value.AutoValue;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.TreeSet;

/** A merkle tree representation as defined by the remote execution api. */
@AutoValue
public abstract class MerkleTree {

    /** Builds a merkle tree for the {@code directory}. */
    public static MerkleTree buildFromDir(File directory) throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("Directory does not exist or is not a Directory!");
        }

        Directory.Builder rootBuilder = Directory.newBuilder();

        // Sort the files, so that two equivalent directory messages have matching digests.
        TreeSet<File> files = new TreeSet(Arrays.asList(directory.listFiles()));
        for (File f : files) {
            if (f.isFile()) {
                rootBuilder.addFiles(
                        FileNode.newBuilder()
                                .setDigest(DigestCalculator.compute(f))
                                .setName(f.getName())
                                .setIsExecutable(f.canExecute()));
            }
            if (f.isDirectory()) {
                MerkleTree childTree = buildFromDir(f);
                rootBuilder.addDirectories(
                        DirectoryNode.newBuilder()
                                .setDigest(childTree.rootDigest())
                                .setName(childTree.rootName()));
            }
        }

        return new AutoValue_MerkleTree(
                directory.getName(), DigestCalculator.compute(rootBuilder.build()));
    }

    /** The name of the root {@link Directory} of this Merkle tree. */
    public abstract String rootName();

    /**
     * The {@link Digest} of the root {@link Directory} of this Merkle tree. Note, this is only
     * consumed by the cache client.
     */
    public abstract Digest rootDigest();
}
