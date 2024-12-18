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

package com.android.tradefed.util.gcs;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;
import com.google.common.annotations.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File downloader to download file from google cloud storage (GCS). */
public class GCSFileDownloaderBase extends GCSCommon {
    public static final String GCS_PREFIX = "gs://";
    public static final String GCS_APPROX_PREFIX = "gs:/";

    private static final Pattern GCS_PATH_PATTERN = Pattern.compile("gs://([^/]*)/(.*)");
    private static final String PATH_SEP = "/";
    private static final Collection<String> SCOPES =
            Collections.singleton("https://www.googleapis.com/auth/devstorage.read_only");
    private static final long LIST_BATCH_SIZE = 100;

    // Allow downloader to create empty files instead of throwing exception.
    protected Boolean mCreateEmptyFile = false;

    public GCSFileDownloaderBase(Boolean createEmptyFile) {
        mCreateEmptyFile = createEmptyFile;
    }

    public GCSFileDownloaderBase() {
        this(false);
    }

    protected Storage getStorage() throws IOException {
        return getStorage(SCOPES);
    }

    public StorageObject getRemoteFileMetaData(String bucketName, String remoteFilename)
            throws IOException {
        int i = 0;
        do {
            i++;
            try {
                return getStorage().objects().get(bucketName, remoteFilename).execute();
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 404) {
                    return null;
                }
                throw e;
            } catch (SocketTimeoutException e) {
                // Allow one retry in case of flaky connection.
                if (i >= 2) {
                    throw e;
                }
            }
        } while (true);
    }

    public File downloadFile(String remoteFilePath) throws Exception {
        File destFile = createTempFile(remoteFilePath, null);
        try {
            downloadFile(remoteFilePath, destFile);
            return destFile;
        } catch (IOException e) {
            FileUtil.recursiveDelete(destFile);
            throw e;
        }
    }

    /**
     * Download a file from a GCS bucket file.
     *
     * @param bucketName GCS bucket name
     * @param filename the filename
     * @return {@link InputStream} with the file content.
     */
    public InputStream downloadFile(String bucketName, String filename) throws IOException {
        InputStream remoteInput = null;
        ByteArrayOutputStream tmpStream = null;
        try {
            remoteInput =
                    getStorage().objects().get(bucketName, filename).executeMediaAsInputStream();
            // The input stream from api call can not be reset. Change it to ByteArrayInputStream.
            tmpStream = new ByteArrayOutputStream();
            StreamUtil.copyStreams(remoteInput, tmpStream);
            return new ByteArrayInputStream(tmpStream.toByteArray());
        } finally {
            StreamUtil.close(remoteInput);
            StreamUtil.close(tmpStream);
        }
    }

    public void downloadFile(String remotePath, File destFile) throws Exception {
        String[] pathParts = parseGcsPath(remotePath);
        downloadFile(pathParts[0], pathParts[1], destFile);
    }

    @VisibleForTesting
    protected void downloadFile(String bucketName, String remoteFilename, File localFile)
            throws Exception {
        int i = 0;
        try {
            do {
                i++;
                try {
                    if (!isRemoteFolder(bucketName, remoteFilename)) {
                        fetchRemoteFile(bucketName, remoteFilename, localFile);
                        return;
                    }
                    remoteFilename = sanitizeDirectoryName(remoteFilename);
                    recursiveDownloadFolder(bucketName, remoteFilename, localFile);
                    return;
                } catch (IOException e) {
                    // Allow one retry in case of flaky connection.
                    if (i >= 2) {
                        throw e;
                    }
                    // Allow `Read timed out` exception to be retried.
                    if (!(e instanceof SocketException)
                            && !"Read timed out".equals(e.getMessage())) {
                        throw e;
                    }
                    CLog.e(
                            "Error '%s' while downloading gs://%s/%s. retrying.",
                            e.getMessage(), bucketName, remoteFilename);
                    CLog.e(e);
                }
            } while (true);
        } catch (IOException e) {
            String message =
                    String.format(
                            "Failed to download gs://%s/%s due to: %s",
                            bucketName, remoteFilename, e.getMessage());
            CLog.e(message);
            CLog.e(e);
            throw new IOException(message, e);
        }
    }

    protected void listRemoteFilesUnderFolder(
            String bucketName, String folder, List<StorageObject> subFiles, List<String> subFolders)
            throws IOException {
        String pageToken = null;
        while (true) {
            com.google.api.services.storage.Storage.Objects.List listOperation =
                    getStorage()
                            .objects()
                            .list(bucketName)
                            .setPrefix(folder)
                            .setDelimiter(PATH_SEP)
                            .setMaxResults(LIST_BATCH_SIZE);
            if (pageToken != null) {
                listOperation.setPageToken(pageToken);
            }
            Objects objects = listOperation.execute();
            if (objects.getItems() != null && !objects.getItems().isEmpty()) {
                for (int i = 0; i < objects.getItems().size(); i++) {
                    if (objects.getItems().get(i).getName().equals(folder)) {
                        // If the folder is created from UI, the folder itself
                        // is a size 0 text file and its name will be
                        // the folder's name, we should ignore this file.
                        continue;
                    }
                    subFiles.add(objects.getItems().get(i));
                }
            }
            if (objects.getPrefixes() != null && !objects.getPrefixes().isEmpty()) {
                // size 0 sub-folders will also be listed under the prefix.
                // So this includes all the sub-folders.
                subFolders.addAll(objects.getPrefixes());
            }
            pageToken = objects.getNextPageToken();
            if (pageToken == null) {
                return;
            }
        }
    }

    protected String[] parseGcsPath(String remotePath) throws Exception {
        if (remotePath.startsWith(GCS_APPROX_PREFIX) && !remotePath.startsWith(GCS_PREFIX)) {
            // File object remove double // so we have to rebuild it in some cases
            remotePath = remotePath.replaceAll(GCS_APPROX_PREFIX, GCS_PREFIX);
        }
        Matcher m = GCS_PATH_PATTERN.matcher(remotePath);
        if (!m.find()) {
            throw new IOException(
                    String.format("Only GCS path is supported, %s is not supported", remotePath));
        }
        return new String[] {m.group(1), m.group(2)};
    }

    public String sanitizeDirectoryName(String name) {
        /** Folder name should end with "/" */
        if (!name.endsWith(PATH_SEP)) {
            name += PATH_SEP;
        }
        return name;
    }

    /**
     * Check given filename is a folder or not.
     *
     * <p>There 2 types of folders in gcs: 1. Created explicitly from UI. The folder is a size 0
     * text file (it's an object). 2. When upload a file, all its parent folders will be created,
     * but these folders doesn't exist (not objects) in gcs. This function work for both cases. But
     * we should not try to download the size 0 folders.
     *
     * @param bucketName is the gcs bucket name.
     * @param filename is the relative path to the bucket.
     * @return true if the filename is a folder, otherwise false.
     */
    public boolean isRemoteFolder(String bucketName, String filename) throws IOException {
        filename = sanitizeDirectoryName(filename);
        Objects objects =
                getStorage()
                        .objects()
                        .list(bucketName)
                        .setPrefix(filename)
                        .setDelimiter(PATH_SEP)
                        .setMaxResults(1L)
                        .execute();
        if (objects.getItems() != null && !objects.getItems().isEmpty()) {
            // The filename is end with '/', if there are objects use filename as prefix
            // then filename must be a folder.
            return true;
        }
        if (objects.getPrefixes() != null && !objects.getPrefixes().isEmpty()) {
            // This will happen when the folder only contains folders but no objects.
            // objects.getItems() will be empty, but objects.getPrefixes will list
            // sub-folders.
            return true;
        }
        return false;
    }

    void fetchRemoteFile(String bucketName, String remoteFilename, File localFile)
            throws IOException {
        CLog.d("Fetching gs://%s/%s to %s.", bucketName, remoteFilename, localFile.toString());
        StorageObject meta = getRemoteFileMetaData(bucketName, remoteFilename);
        if (meta == null || meta.getSize().equals(BigInteger.ZERO)) {
            if (!mCreateEmptyFile) {
                throw new IOException(
                        String.format(
                                "File (not folder) gs://%s/%s doesn't exist or is size 0.",
                                bucketName, remoteFilename));
            } else {
                // Create the empty file.
                CLog.d("GCS file is empty: gs://%s/%s", bucketName, remoteFilename);
                localFile.createNewFile();
                return;
            }
        }
        try (OutputStream writeStream = new FileOutputStream(localFile)) {
            getStorage()
                    .objects()
                    .get(bucketName, remoteFilename)
                    .executeMediaAndDownloadTo(writeStream);
        }
    }

    /**
     * Recursively download remote folder to local folder.
     *
     * @param bucketName the gcs bucket name
     * @param remoteFolderName remote folder name, must end with "/"
     * @param localFolder local folder
     * @throws IOException
     */
    private void recursiveDownloadFolder(
            String bucketName, String remoteFolderName, File localFolder) throws IOException {
        CLog.d("Downloading folder gs://%s/%s.", bucketName, remoteFolderName);
        if (!localFolder.exists()) {
            FileUtil.mkdirsRWX(localFolder);
        }
        if (!localFolder.isDirectory()) {
            String error =
                    String.format(
                            "%s is not a folder. (gs://%s/%s)",
                            localFolder, bucketName, remoteFolderName);
            CLog.e(error);
            throw new IOException(error);
        }
        Set<String> subFilenames = new HashSet<>(Arrays.asList(localFolder.list()));
        List<String> subRemoteFolders = new ArrayList<>();
        List<StorageObject> subRemoteFiles = new ArrayList<>();
        listRemoteFilesUnderFolder(bucketName, remoteFolderName, subRemoteFiles, subRemoteFolders);
        for (StorageObject subRemoteFile : subRemoteFiles) {
            String subFilename = Paths.get(subRemoteFile.getName()).getFileName().toString();
            fetchRemoteFile(
                    bucketName, subRemoteFile.getName(), new File(localFolder, subFilename));
            subFilenames.remove(subFilename);
        }
        for (String subRemoteFolder : subRemoteFolders) {
            String subFolderName = Paths.get(subRemoteFolder).getFileName().toString();
            File subFolder = new File(localFolder, subFolderName);
            if (new File(localFolder, subFolderName).exists()
                    && !new File(localFolder, subFolderName).isDirectory()) {
                CLog.w("%s exists as a non-directory.", subFolder);
                subFolder = new File(localFolder, subFolderName + "_folder");
            }
            recursiveDownloadFolder(bucketName, subRemoteFolder, subFolder);
            subFilenames.remove(subFolder.getName());
        }
        for (String subFilename : subFilenames) {
            FileUtil.recursiveDelete(new File(localFolder, subFilename));
        }
    }

    @VisibleForTesting
    protected File createTempFile(String remoteFilePath, File rootDir) throws Exception {
        return createTempFileForRemote(remoteFilePath, rootDir);
    }

    /**
     * Creates a unique file on temporary disk to house downloaded file with given path.
     *
     * <p>Constructs the file name based on base file name from path
     *
     * @param remoteFilePath the remote path to construct the name from
     */
    public static File createTempFileForRemote(String remoteFilePath, File rootDir)
            throws Exception {
        // create a unique file.
        File tmpFile = FileUtil.createTempFileForRemote(remoteFilePath, rootDir);
        // now delete it so name is available
        tmpFile.delete();
        return tmpFile;
    }
}
