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

package com.android.tradefed.cache.remote;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import java.io.File;

/** A client implementing the {@code Write} method of the {@code ByteStream} gRPC service. */
public class ByteStreamUploader {
    public ByteStreamUploader() {}

    /**
     * Uploads a BLOB by the remote {@code ByteStream} service.
     *
     * @param digest the digest of the BLOB to upload.
     * @param blob the BLOB to upload.
     */
    public ListenableFuture<Void> uploadBlob(Digest digest, ByteString blob) {
        throw new UnsupportedOperationException("Not implemented feature.");
    }

    /**
     * Uploads a file by the remote {@code ByteStream} service.
     *
     * @param digest the digest of the file to upload.
     * @param file the file to upload.
     */
    public ListenableFuture<Void> uploadFile(Digest digest, File file) {
        throw new UnsupportedOperationException("Not implemented feature.");
    }
}
