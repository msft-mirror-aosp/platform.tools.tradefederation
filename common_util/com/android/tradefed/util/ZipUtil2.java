/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.tradefed.error.HarnessIOException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * A helper class for zip extraction that takes POSIX file permissions into account
 */
public class ZipUtil2 {

    /**
     * A util method to apply unix mode from {@link ZipArchiveEntry} to the created local file
     * system entry if necessary
     *
     * @param entry the entry inside zipfile (potentially contains mode info)
     * @param localFile the extracted local file entry
     * @return True if the Unix permissions are set, false otherwise.
     * @throws IOException
     */
    private static boolean applyUnixModeIfNecessary(ZipArchiveEntry entry, File localFile)
            throws IOException {
        if (entry.getPlatform() == ZipArchiveEntry.PLATFORM_UNIX) {
            Files.setPosixFilePermissions(localFile.toPath(),
                    FileUtil.unixModeToPosix(entry.getUnixMode()));
            return true;
        }
        return false;
    }

    /**
     * Utility method to extract a zip entry to a file.
     *
     * @param zipFile the {@link ZipFile} to extract
     * @param entry the {@link ZipArchiveEntry} to extract
     * @param destFile the {@link File} to extract to
     * @return whether the Unix permissions are set
     * @throws IOException if failed to extract file
     */
    private static boolean extractZipEntry(ZipFile zipFile, ZipArchiveEntry entry, File destFile)
            throws IOException {
        FileUtil.writeToFile(zipFile.getInputStream(entry), destFile);
        return applyUnixModeIfNecessary(entry, destFile);
    }

    /**
     * Utility method to extract entire contents of zip file into given directory
     *
     * @param zipFile the {@link ZipFile} to extract
     * @param destDir the local dir to extract file to
     * @throws IOException if failed to extract file
     */
    public static void extractZip(ZipFile zipFile, File destDir) throws IOException {
        Enumeration<? extends ZipArchiveEntry> entries = zipFile.getEntries();
        Set<String> noPermissions = new HashSet<>();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            File childFile = new File(destDir, entry.getName());
            ZipUtil.validateDestinationDir(destDir, entry.getName());
            childFile.getParentFile().mkdirs();
            if (entry.isDirectory()) {
                childFile.mkdirs();
                if (!applyUnixModeIfNecessary(entry, childFile)) {
                    noPermissions.add(entry.getName());
                }
                continue;
            } else {
                if (!extractZipEntry(zipFile, entry, childFile)) {
                    noPermissions.add(entry.getName());
                }
            }
        }
        if (!noPermissions.isEmpty()) {
            CLog.d(
                    "Entries '%s' exist but do not contain Unix mode permission info. Files will "
                            + "have default permission.",
                    noPermissions);
        }
    }

    /**
     * Utility method to extract a zip file into a given directory. The zip file being presented as
     * a {@link File}.
     *
     * @param toUnzip a {@link File} pointing to a zip file.
     * @param destDir the local dir to extract file to
     * @throws IOException if failed to extract file
     */
    public static void extractZip(File toUnzip, File destDir) throws IOException {
        // Extract fast
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(toUnzip)) {
            ZipUtil.extractZip(zipFile, destDir);
        } catch (IOException e) {
            if (e instanceof FileNotFoundException) {
                throw new HarnessIOException(e, InfraErrorIdentifier.ARTIFACT_INVALID);
            }
            throw e;
        }
        // Then restore permissions
        try (ZipFile zip = new ZipFile(toUnzip)) {
            restorePermissions(zip, destDir);
        }
    }

    /**
     * Utility method to extract one specific file from zip file
     *
     * @param zipFile the {@link ZipFile} to extract
     * @param filePath the file path in the zip
     * @param destFile the {@link File} to extract to
     * @return whether the file is found and extracted
     * @throws IOException if failed to extract file
     */
    public static boolean extractFileFromZip(ZipFile zipFile, String filePath, File destFile)
            throws IOException {
        ZipArchiveEntry entry = zipFile.getEntry(filePath);
        if (entry == null) {
            return false;
        }
        extractZipEntry(zipFile, entry, destFile);
        return true;
    }

    /**
     * Utility method to extract one specific file from zip file into a tmp file
     *
     * @param zipFile the {@link ZipFile} to extract
     * @param filePath the filePath of to extract
     * @throws IOException if failed to extract file
     * @return the {@link File} or null if not found
     */
    public static File extractFileFromZip(ZipFile zipFile, String filePath) throws IOException {
        ZipArchiveEntry entry = zipFile.getEntry(filePath);
        if (entry == null) {
            return null;
        }
        File createdFile = FileUtil.createTempFile("extracted", FileUtil.getExtension(filePath));
        extractZipEntry(zipFile, entry, createdFile);
        return createdFile;
    }

    /**
     * Extract a zip file to a temp directory prepended with a string
     *
     * @param zipFile the zip file to extract
     * @param nameHint a prefix for the temp directory
     * @return a {@link File} pointing to the temp directory
     */
    public static File extractZipToTemp(File zipFile, String nameHint) throws IOException {
        File localRootDir = FileUtil.createTempDir(nameHint);
        try {
            extractZip(zipFile, localRootDir);
            return localRootDir;
        } catch (IOException e) {
            // clean tmp file since we couldn't extract.
            FileUtil.recursiveDelete(localRootDir);
            throw e;
        }
    }

    /**
     * Close an open {@link ZipFile}, ignoring any exceptions.
     *
     * @param zipFile the file to close
     */
    public static void closeZip(ZipFile zipFile) {
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /** Match permission on an already extracted destination directory. */
    private static void restorePermissions(ZipFile zipFile, File destDir) throws IOException {
        Enumeration<? extends ZipArchiveEntry> entries = zipFile.getEntries();
        Set<String> noPermissions = new HashSet<>();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            File childFile = new File(destDir, entry.getName());
            if (!applyUnixModeIfNecessary(entry, childFile)) {
                noPermissions.add(entry.getName());
            }
        }
        if (!noPermissions.isEmpty()) {
            CLog.e(
                    "Entries '%s' exist but do not contain Unix mode permission info. Files will "
                            + "have default permission.",
                    noPermissions);
        }
    }
}
