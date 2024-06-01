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
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.protobuf.Message;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Utility methods to compute {@link Digest}. */
public class DigestCalculator {
    private static final HashFunction HASH_FUNCTION = Hashing.sha256();

    /**
     * Computes a digest for a file.
     *
     * @param file the {@link File} that the digest is calculated for
     * @return the {@link Digest} of the {@code file}
     */
    public static Digest compute(File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a file!");
        }

        byte[] digest =
                new ByteSource() {
                    @Override
                    public InputStream openStream() throws IOException {
                        return new FileInputStream(file);
                    }
                }.hash(HASH_FUNCTION).asBytes();
        Preconditions.checkNotNull(digest, "Missing digest for %s", file.getAbsolutePath());
        return DigestCalculator.buildDigest(digest, Files.size(file.toPath()));
    }

    /**
     * Computes a digest for a proto message.
     *
     * @param message the {@link Message} that the digest is calculated for
     * @return the {@link Digest} of the {@code message}
     */
    public static Digest compute(Message message) {
        return DigestCalculator.compute(message.toByteArray());
    }

    /**
     * Computes a digest for a byte array.
     *
     * @param blob the byte array that the digest is calculated for
     * @return the {@link Digest} of the {@code blob}
     */
    public static Digest compute(byte[] blob) {
        return DigestCalculator.buildDigest(HASH_FUNCTION.hashBytes(blob).asBytes(), blob.length);
    }

    private static Digest buildDigest(byte[] hash, long size) {
        return Digest.newBuilder()
                .setHash(HashCode.fromBytes(hash).toString())
                .setSizeBytes(size)
                .build();
    }
}
