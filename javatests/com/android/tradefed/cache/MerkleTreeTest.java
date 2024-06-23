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
import java.util.Map;
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
        File bar = new File(root, "srcs/bar.cc");
        addFile(bar, "bar", false);
        Digest barDigest = DigestCalculator.compute(bar);
        File foo = new File(root, "srcs/foo.cc");
        addFile(foo, "foo", false);
        Digest fooDigest = DigestCalculator.compute(foo);
        File fizzbuzz = new File(root, "srcs/fizz/fizzbuzz");
        addFile(fizzbuzz, "fizzbuzz", true);
        Digest fizzbuzzDigest = DigestCalculator.compute(fizzbuzz);
        File buzz = new File(root, "srcs/fizz/buzz.cc");
        addFile(buzz, "buzz", false);
        Digest buzzDigest = DigestCalculator.compute(buzz);
        Map<Digest, File> digestToFile =
                Map.of(
                        barDigest, bar,
                        fooDigest, foo,
                        fizzbuzzDigest, fizzbuzz,
                        buzzDigest, buzz);
        Directory fizzDir =
                Directory.newBuilder()
                        .addFiles(newFileNode("buzz.cc", buzzDigest, false))
                        .addFiles(newFileNode("fizzbuzz", fizzbuzzDigest, true))
                        .build();
        Digest fizzDirDigest = DigestCalculator.compute(fizzDir);
        Directory srcsDir =
                Directory.newBuilder()
                        .addFiles(newFileNode("bar.cc", barDigest, false))
                        .addFiles(newFileNode("foo.cc", fooDigest, false))
                        .addDirectories(
                                DirectoryNode.newBuilder().setName("fizz").setDigest(fizzDirDigest))
                        .build();
        Digest srcsDirDigest = DigestCalculator.compute(srcsDir);
        Map<Digest, Directory> digestToSubdir =
                Map.of(
                        fizzDirDigest, fizzDir,
                        srcsDirDigest, srcsDir);
        Directory rootDir =
                Directory.newBuilder()
                        .addDirectories(
                                DirectoryNode.newBuilder().setName("srcs").setDigest(srcsDirDigest))
                        .build();

        MerkleTree tree = MerkleTree.buildFromDir(root);

        assertEquals(tree.rootDigest(), DigestCalculator.compute(rootDir));
        assertEquals(tree.digestToFile(), digestToFile);
        assertEquals(tree.digestToSubdir(), digestToSubdir);
    }

    public static void addFile(File file, String content, boolean isExecutable) throws IOException {
        File parent = file.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }
        file.createNewFile();
        Files.writeString(file.toPath(), content, UTF_8);
        file.setExecutable(isExecutable);
    }

    public static FileNode newFileNode(String name, Digest digest, boolean isExecutable) {
        return FileNode.newBuilder()
                .setName(name)
                .setDigest(digest)
                .setIsExecutable(isExecutable)
                .build();
    }
}
