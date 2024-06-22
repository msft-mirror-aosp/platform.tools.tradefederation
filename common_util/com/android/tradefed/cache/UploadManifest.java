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
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** A manifest of the BLOBs and files to upload. */
@AutoValue
public abstract class UploadManifest {
    public abstract ImmutableMap<Digest, File> digestToFile();

    public abstract ImmutableMap<Digest, ByteString> digestToBlob();

    public static Builder builder() {
        return new AutoValue_UploadManifest.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract ImmutableMap.Builder<Digest, File> digestToFileBuilder();

        public abstract ImmutableMap.Builder<Digest, ByteString> digestToBlobBuilder();

        public final Builder addFile(Digest digest, File file) throws IOException {
            if (!file.exists() || !file.isFile()) {
                throw new IOException("File does not exist or is not a file!");
            }
            digestToFileBuilder().put(digest, file);
            return this;
        }

        public final Builder addFiles(Map<Digest, File> digestToFile) throws IOException {
            for (Map.Entry<Digest, File> entry : digestToFile.entrySet()) {
                addFile(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public final Builder addBlob(Digest digest, ByteString blob) {
            digestToBlobBuilder().put(digest, blob);
            return this;
        }

        public final Builder addBlobs(Map<Digest, ByteString> digestToBlob) {
            digestToBlobBuilder().putAll(digestToBlob);
            return this;
        }

        public abstract UploadManifest build();
    }
}
