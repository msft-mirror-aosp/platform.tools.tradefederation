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

package com.android.tradefed.util;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IFileDownloader;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.gcs.GCSFileDownloaderBase;

import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** File downloader to download file from google cloud storage (GCS). */
public class GCSFileDownloader extends GCSFileDownloaderBase implements IFileDownloader {

    // Cache the freshness
    private final LoadingCache<String, Boolean> mFreshnessCache;

    public GCSFileDownloader(Boolean createEmptyFile) {
        super(createEmptyFile);
        mFreshnessCache =
                CacheBuilder.newBuilder()
                        .maximumSize(50)
                        .expireAfterAccess(60, TimeUnit.MINUTES)
                        .build(
                                new CacheLoader<String, Boolean>() {
                                    @Override
                                    public Boolean load(String key) throws BuildRetrievalError {
                                        return true;
                                    }
                                });
    }

    public GCSFileDownloader() {
        this(false);
    }

    public GCSFileDownloader(File jsonKeyFile) {
        this(false);
        mJsonKeyFile = jsonKeyFile;
    }

    /**
     * Override the implementation in base to support credential based on TF options.
     *
     * @param scopes specific scopes to request credential for.
     * @return {@link Storage} object of the GCS bucket
     * @throws IOException
     */
    @Override
    protected Storage getStorage(Collection<String> scopes) throws IOException {
        return GCSHelper.getStorage(scopes, mJsonKeyFile);
    }

    protected void clearCache() {
        mFreshnessCache.invalidateAll();
    }

    /**
     * Download file from GCS.
     *
     * <p>Right now only support GCS path.
     *
     * @param remoteFilePath gs://bucket/file/path format GCS path.
     * @return local file
     * @throws BuildRetrievalError
     */
    @Override
    public File downloadFile(String remoteFilePath) throws BuildRetrievalError {
        File destFile = createTempFileForRemote(remoteFilePath, null);
        try {
            downloadFile(remoteFilePath, destFile);
            return destFile;
        } catch (BuildRetrievalError e) {
            FileUtil.recursiveDelete(destFile);
            throw e;
        }
    }

    @Override
    public void downloadFile(String remotePath, File destFile) throws BuildRetrievalError {
        String[] pathParts = parseGcsPath(remotePath);
        downloadFile(pathParts[0], pathParts[1], destFile);
    }

    @VisibleForTesting
    @Override
    protected void downloadFile(String bucketName, String remoteFilename, File localFile)
            throws BuildRetrievalError {
        try {
            super.downloadFile(bucketName, remoteFilename, localFile);
        } catch (Exception e) {
            throw new BuildRetrievalError(e.getMessage(), e, InfraErrorIdentifier.GCS_ERROR);
        }
    }

    private boolean isFileFresh(File localFile, StorageObject remoteFile) {
        if (localFile == null && remoteFile == null) {
            return true;
        }
        if (localFile == null || remoteFile == null) {
            return false;
        }
        if (!localFile.exists()) {
            return false;
        }
        return remoteFile.getMd5Hash().equals(FileUtil.calculateBase64Md5(localFile));
    }

    @Override
    public boolean isFresh(File localFile, String remotePath) throws BuildRetrievalError {
        String[] pathParts = parseGcsPath(remotePath);
        String bucketName = pathParts[0];
        String remoteFilename = pathParts[1];

        if (localFile != null && localFile.exists()) {
            Boolean cache = mFreshnessCache.getIfPresent(remotePath);
            if (cache != null && Boolean.TRUE.equals(cache)) {
                return true;
            }
        }

        try (CloseableTraceScope ignored = new CloseableTraceScope("gcs_is_fresh " + remotePath)) {
            StorageObject remoteFileMeta = getRemoteFileMetaData(bucketName, remoteFilename);
            if (localFile == null || !localFile.exists()) {
                if (!isRemoteFolder(bucketName, remoteFilename) && remoteFileMeta == null) {
                    // The local doesn't exist and the remote filename is not a folder or a file.
                    return true;
                }
                return false;
            }
            if (!localFile.isDirectory()) {
                return isFileFresh(localFile, remoteFileMeta);
            }
            remoteFilename = sanitizeDirectoryName(remoteFilename);
            boolean fresh = recursiveCheckFolderFreshness(bucketName, remoteFilename, localFile);
            mFreshnessCache.put(remotePath, fresh);
            return fresh;
        } catch (IOException e) {
            mFreshnessCache.invalidate(remotePath);
            throw new BuildRetrievalError(e.getMessage(), e, InfraErrorIdentifier.GCS_ERROR);
        }
    }

    /**
     * Check if remote folder is the same as local folder, recursively. The remoteFolderName must
     * end with "/".
     *
     * @param bucketName is the gcs bucket name.
     * @param remoteFolderName is the relative path to the bucket.
     * @param localFolder is the local folder
     * @return true if local file is the same as remote file, otherwise false.
     * @throws IOException
     */
    private boolean recursiveCheckFolderFreshness(
            String bucketName, String remoteFolderName, File localFolder) throws IOException {
        Set<String> subFilenames = new HashSet<>(Arrays.asList(localFolder.list()));
        List<String> subRemoteFolders = new ArrayList<>();
        List<StorageObject> subRemoteFiles = new ArrayList<>();
        listRemoteFilesUnderFolder(bucketName, remoteFolderName, subRemoteFiles, subRemoteFolders);
        for (StorageObject subRemoteFile : subRemoteFiles) {
            String subFilename = Paths.get(subRemoteFile.getName()).getFileName().toString();
            if (!isFileFresh(new File(localFolder, subFilename), subRemoteFile)) {
                return false;
            }
            subFilenames.remove(subFilename);
        }
        for (String subRemoteFolder : subRemoteFolders) {
            String subFolderName = Paths.get(subRemoteFolder).getFileName().toString();
            File subFolder = new File(localFolder, subFolderName);
            if (!subFolder.exists()) {
                return false;
            }
            if (!subFolder.isDirectory()) {
                CLog.w("%s exists as a non-directory.", subFolder);
                subFolder = new File(localFolder, subFolderName + "_folder");
            }
            if (!recursiveCheckFolderFreshness(bucketName, subRemoteFolder, subFolder)) {
                return false;
            }
            subFilenames.remove(subFolder.getName());
        }
        return subFilenames.isEmpty();
    }

    @Override
    protected String[] parseGcsPath(String remotePath) throws BuildRetrievalError {
        try {
            return super.parseGcsPath(remotePath);
        } catch (Exception e) {
            throw new BuildRetrievalError(
                    e.getMessage(), InfraErrorIdentifier.ARTIFACT_UNSUPPORTED_PATH);
        }
    }

    public static File createTempFileForRemote(String remoteFilePath, File rootDir)
            throws BuildRetrievalError {
        try {
            return GCSFileDownloaderBase.createTempFileForRemote(remoteFilePath, rootDir);
        } catch (Exception e) {
            throw new BuildRetrievalError(e.getMessage(), e);
        }
    }
}
