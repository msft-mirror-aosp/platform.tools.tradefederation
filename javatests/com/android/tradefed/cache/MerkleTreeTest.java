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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.Test;

/** Tests for {@link MerkleTree}. */
@RunWith(JUnit4.class)
public class MerkleTreeTest {

    @Rule public final TemporaryFolder workingDir = new TemporaryFolder();

    @Test
    public void buildMerkleTree() throws IOException {
        File root = workingDir.getRoot();
        // Not sort the files in purpose to test the digests of two equivalent directories will
        // match.
        addFile(new File(root, "srcs/bar.cc"), "bar", false);
        addFile(new File(root, "srcs/foo.cc"), "foo", false);
        addFile(new File(root, "srcs/fizz/fizzbuzz"), "fizzbuzz", true);
        addFile(new File(root, "srcs/fizz/buzz.cc"), "buzz", false);
        Directory fizzDir =
                Directory.newBuilder()
                        .addFiles(
                                newFileNode(
                                        "buzz.cc",
                                        DigestCalculator.compute("buzz".getBytes(UTF_8)),
                                        false))
                        .addFiles(
                                newFileNode(
                                        "fizzbuzz",
                                        DigestCalculator.compute("fizzbuzz".getBytes(UTF_8)),
                                        true))
                        .build();
        Directory srcsDir =
                Directory.newBuilder()
                        .addFiles(
                                newFileNode(
                                        "bar.cc",
                                        DigestCalculator.compute("bar".getBytes(UTF_8)),
                                        false))
                        .addFiles(
                                newFileNode(
                                        "foo.cc",
                                        DigestCalculator.compute("foo".getBytes(UTF_8)),
                                        false))
                        .addDirectories(
                                DirectoryNode.newBuilder()
                                        .setName("fizz")
                                        .setDigest(DigestCalculator.compute(fizzDir)))
                        .build();
        Directory rootDir =
                Directory.newBuilder()
                        .addDirectories(
                                DirectoryNode.newBuilder()
                                        .setName("srcs")
                                        .setDigest(DigestCalculator.compute(srcsDir)))
                        .build();

        MerkleTree tree = MerkleTree.buildFromDir(root);

        assertEquals(tree.rootDigest(), DigestCalculator.compute(rootDir));
    }

    private void addFile(File file, String content, boolean isExecutable) throws IOException {
        File parent = file.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }
        file.createNewFile();
        Files.writeString(file.toPath(), content, UTF_8);
        file.setExecutable(isExecutable);
    }

    private static FileNode newFileNode(String name, Digest digest, boolean isExecutable) {
        return FileNode.newBuilder()
                .setName(name)
                .setDigest(digest)
                .setIsExecutable(isExecutable)
                .build();
    }
}
