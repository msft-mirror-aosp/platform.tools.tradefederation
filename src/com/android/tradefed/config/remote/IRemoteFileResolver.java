/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.Option;

import java.io.File;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Interface for objects that can resolve a remote file into a local one. For example:
 * gs://bucket/dir/file.txt would be downloaded and changed to a local path.
 */
public interface IRemoteFileResolver {

    /**
     * Resolve the remote file.
     *
     * @param consideredFile {@link File} evaluated as remote.
     * @param option The original option configuring the file.
     * @return The resolved local file.
     * @throws BuildRetrievalError if something goes wrong.
     */
    public default @Nonnull File resolveRemoteFiles(File consideredFile, Option option)
            throws BuildRetrievalError {
        throw new BuildRetrievalError("Should not have been called");
    }

    /**
     * Resolve the remote file.
     *
     * @param consideredFile {@link File} evaluated as remote.
     * @param option The original option configuring the file.
     * @param queryArgs The arguments passed as a query to the URL.
     * @return The resolved local file.
     * @throws BuildRetrievalError if something goes wrong.
     */
    public default @Nonnull File resolveRemoteFiles(
            File consideredFile, Option option, Map<String, String> queryArgs)
            throws BuildRetrievalError {
        return resolveRemoteFiles(consideredFile, option);
    }

    /** Returns the associated protocol supported for download. */
    public @Nonnull String getSupportedProtocol();
}
