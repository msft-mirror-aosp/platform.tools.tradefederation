/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.ddmlib.Log;
import com.android.tradefed.command.FatalHostError;
import com.android.tradefed.config.Option;
import com.android.tradefed.log.LogUtil.CLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * A helper class for file related operations
 */
public class FileUtil {

    private static final String LOG_TAG = "FileUtil";
    /**
     * The minimum allowed disk space in megabytes. File creation methods will throw
     * {@link LowDiskSpaceException} if the usable disk space in desired partition is less than
     * this amount.
     */
    @Option(name = "min-disk-space", description = "The minimum allowed disk"
        + " space in megabytes for file-creation methods. May be set to"
        + " 0 to disable checking.")
    private static long mMinDiskSpaceMb = 100;

    private static final char[] SIZE_SPECIFIERS = {
            ' ', 'K', 'M', 'G', 'T'
    };

    private static String CHMOD = "chmod";

    /**
     * Exposed for testing. Allows to modify the chmod binary name we look for, in order to tests
     * system with no chmod support.
     */
    protected static void setChmodBinary(String chmodName) {
        CHMOD = chmodName;
    }

    /**
     * Thrown if usable disk space is below minimum threshold.
     */
    @SuppressWarnings("serial")
    public static class LowDiskSpaceException extends FatalHostError {

        LowDiskSpaceException(String msg, Throwable cause) {
            super(msg, cause);
        }

        LowDiskSpaceException(String msg) {
            super(msg);
        }

    }

    /**
     * Method to create a chain of directories, and set them all group execute/read/writable as they
     * are created, by calling {@link #chmodGroupRWX(File)}.  Essentially a version of
     * {@link File#mkdirs()} that also runs {@link #chmod(File, String)}.
     *
     * @param file the name of the directory to create, possibly with containing directories that
     *        don't yet exist.
     * @return {@code true} if {@code file} exists and is a directory, {@code false} otherwise.
     */
    public static boolean mkdirsRWX(File file) {
        File parent = file.getParentFile();

        if (parent != null && !parent.isDirectory()) {
            // parent doesn't exist.  recurse upward, which should both mkdir and chmod
            if (!mkdirsRWX(parent)) {
                // Couldn't mkdir parent, fail
                Log.w(LOG_TAG, String.format("Failed to mkdir parent dir %s.", parent));
                return false;
            }
        }

        // by this point the parent exists.  Try to mkdir file
        if (file.isDirectory() || file.mkdir()) {
            // file should exist.  Try chmod and complain if that fails, but keep going
            boolean setPerms = chmodGroupRWX(file);
            if (!setPerms) {
                Log.w(LOG_TAG, String.format("Failed to set dir %s to be group accessible.", file));
            }
        }

        return file.isDirectory();
    }

    public static boolean chmodRWXRecursively(File file) {
        boolean success = true;
        if (!file.setExecutable(true, false)) {
            CLog.w("Failed to set %s executable.", file.getAbsolutePath());
            success = false;
        }
        if (!file.setWritable(true, false)) {
            CLog.w("Failed to set %s writable.", file.getAbsolutePath());
            success = false;
        }
        if (!file.setReadable(true, false)) {
            CLog.w("Failed to set %s readable", file.getAbsolutePath());
            success = false;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            for (File child : children) {
                if (!chmodRWXRecursively(child)) {
                    success = false;
                }
            }

        }
        return success;
    }

    public static boolean chmod(File file, String perms) {
        Log.d(LOG_TAG, String.format("Attempting to chmod %s to %s",
                file.getAbsolutePath(), perms));
        CommandResult result = RunUtil.getDefault().runTimedCmd(10 * 1000, CHMOD, perms,
                file.getAbsolutePath());
        return result.getStatus().equals(CommandStatus.SUCCESS);
    }

    /**
     * Performs a best effort attempt to make given file group readable and writable.
     * <p/>
     * Note that the execute permission is required to make directories accessible.  See
     * {@link #chmodGroupRWX(File)}.
     * <p/>
     * If 'chmod' system command is not supported by underlying OS, will set file to writable by
     * all.
     *
     * @param file the {@link File} to make owner and group writable
     * @return <code>true</code> if file was successfully made group writable, <code>false</code>
     *         otherwise
     */
    public static boolean chmodGroupRW(File file) {
        if (chmodExists()) {
            if (chmod(file, "ug+rw")) {
                return true;
            } else {
                Log.d(LOG_TAG, String.format("Failed chmod on %s", file.getAbsolutePath()));
                return false;
            }
        } else {
            Log.d(LOG_TAG, String.format("chmod not available; "
                    + "attempting to set %s globally RW", file.getAbsolutePath()));
            return file.setWritable(true, false /* false == writable for all */) &&
                    file.setReadable(true, false /* false == readable for all */);
        }
    }

    /**
     * Performs a best effort attempt to make given file group executable, readable, and writable.
     * <p/>
     * If 'chmod' system command is not supported by underlying OS, will attempt to set permissions
     * for all users.
     *
     * @param file the {@link File} to make owner and group writable
     * @return <code>true</code> if permissions were set successfully, <code>false</code> otherwise
     */
    public static boolean chmodGroupRWX(File file) {
        if (chmodExists()) {
            if (chmod(file, "ug+rwx")) {
                return true;
            } else {
                Log.d(LOG_TAG, String.format("Failed chmod on %s", file.getAbsolutePath()));
                return false;
            }
        } else {
            Log.d(LOG_TAG, String.format("chmod not available; "
                    + "attempting to set %s globally RWX", file.getAbsolutePath()));
            return file.setExecutable(true, false /* false == executable for all */) &&
                    file.setWritable(true, false /* false == writable for all */) &&
                    file.setReadable(true, false /* false == readable for all */);
        }
    }

    /**
     * Internal helper to determine if 'chmod' is available on the system OS.
     */
    protected static boolean chmodExists() {
        CommandResult result = RunUtil.getDefault().runTimedCmd(10 * 1000, CHMOD);
        // We expect a status fail because 'chmod' requires arguments.
        if (CommandStatus.FAILED.equals(result.getStatus()) &&
                result.getStderr().contains("chmod: missing operand")) {
            return true;
        }
        return false;
    }

    /**
     * Recursively set read and exec (if folder) permissions for given file.
     */
    public static void setReadableRecursive(File file) {
        file.setReadable(true);
        if (file.isDirectory()) {
            file.setExecutable(true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File childFile : file.listFiles()) {
                    setReadableRecursive(childFile);
                }
            }
        }
    }

    /**
     * Helper function to create a temp directory in the system default temporary file directory.
     *
     * @param prefix The prefix string to be used in generating the file's name; must be at least
     *            three characters long
     * @return the created directory
     * @throws IOException if file could not be created
     */
    public static File createTempDir(String prefix) throws IOException {
        return createTempDir(prefix, null);
    }

    /**
     * Helper function to create a temp directory.
     *
     * @param prefix The prefix string to be used in generating the file's name; must be at least
     *            three characters long
     * @param parentDir The parent directory in which the directory is to be created. If
     *            <code>null</code> the system default temp directory will be used.
     * @return the created directory
     * @throws IOException if file could not be created
     */
    public static File createTempDir(String prefix, File parentDir) throws IOException {
        // create a temp file with unique name, then make it a directory
        if (parentDir != null) {
            CLog.d("Creating temp directory at %s with prefix \"%s\"",
              parentDir.getAbsolutePath(), prefix);
        }
        File tmpDir = File.createTempFile(prefix, "", parentDir);
        return deleteFileAndCreateDirWithSameName(tmpDir);
    }

    private static File deleteFileAndCreateDirWithSameName(File tmpDir) throws IOException {
        tmpDir.delete();
        return createDir(tmpDir);
    }

    private static File createDir(File tmpDir) throws IOException {
        if (!tmpDir.mkdirs()) {
            throw new IOException("unable to create directory");
        }
        return tmpDir;
    }

    /**
     * Helper function to create a named directory inside your temp folder.
     * <p/>
     * This directory will not have it's name randomized. If the directory already exists it will
     * be returned.
     *
     * @param name The name of the directory to create in your tmp folder.
     * @return the created directory
     */
    public static File createNamedTempDir(String name) throws IOException {
        File namedTmpDir = new File(System.getProperty("java.io.tmpdir"), name);
        if (!namedTmpDir.exists()) {
            createDir(namedTmpDir);
        }
        return namedTmpDir;
    }

    /**
     * Helper wrapper function around {@link File#createTempFile(String, String)} that audits for
     * potential out of disk space scenario.
     *
     * @see File#createTempFile(String, String)
     * @throws LowDiskSpaceException if disk space on temporary partition is lower than minimum
     *             allowed
     */
    public static File createTempFile(String prefix, String suffix) throws IOException {
        File returnFile = File.createTempFile(prefix, suffix);
        verifyDiskSpace(returnFile);
        return returnFile;
    }

    /**
     * Helper wrapper function around {@link File#createTempFile(String, String, File)}
     * that audits for potential out of disk space scenario.
     *
     * @see File#createTempFile(String, String, File)
     * @throws LowDiskSpaceException if disk space on partition is lower than minimum allowed
     */
    public static File createTempFile(String prefix, String suffix, File parentDir)
            throws IOException {
        if (parentDir != null) {
            CLog.d("Creating temp file at %s with prefix \"%s\" suffix \"%s\"",
                parentDir.getAbsolutePath(), prefix, suffix);
        }
        File returnFile = File.createTempFile(prefix, suffix, parentDir);
        verifyDiskSpace(returnFile);
        return returnFile;
    }

    /**
     * A helper method that hardlinks a file to another file
     *
     * @param origFile the original file
     * @param destFile the destination file
     * @throws IOException if failed to hardlink file
     */
    public static void hardlinkFile(File origFile, File destFile) throws IOException {
        if (!origFile.exists()) {
            throw new IOException(String.format("Cannot hardlink %s. File does not exist",
                    origFile.getAbsolutePath()));
        }
        // `ln src dest` will create a hardlink (note: not `ln -s src dest`, which creates symlink)
        // note that this will fail across filesystem boundaries
        // FIXME: should probably just fall back to normal copy if this fails
        CommandResult result = RunUtil.getDefault().runTimedCmd(10 * 1000, "ln",
                origFile.getAbsolutePath(), destFile.getAbsolutePath());
        if (!result.getStatus().equals(CommandStatus.SUCCESS)) {
            throw new IOException(String.format(
                    "Failed to hardlink %s to %s.  Across filesystem boundary?",
                    origFile.getAbsolutePath(), destFile.getAbsolutePath()));
        }
    }

    /**
     * Recursively hardlink folder contents.
     * <p/>
     * Only supports copying of files and directories - symlinks are not copied. If the destination
     * directory does not exist, it will be created.
     *
     * @param sourceDir the folder that contains the files to copy
     * @param destDir the destination folder
     * @throws IOException
     */
    public static void recursiveHardlink(File sourceDir, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdir()) {
            throw new IOException(String.format("Could not create directory %s",
                    destDir.getAbsolutePath()));
        }
        for (File childFile : sourceDir.listFiles()) {
            File destChild = new File(destDir, childFile.getName());
            if (childFile.isDirectory()) {
                recursiveHardlink(childFile, destChild);
            } else if (childFile.isFile()) {
                hardlinkFile(childFile, destChild);
            }
        }
    }

    /**
     * A helper method that copies a file's contents to a local file
     *
     * @param origFile the original file to be copied
     * @param destFile the destination file
     * @throws IOException if failed to copy file
     */
    public static void copyFile(File origFile, File destFile) throws IOException {
        writeToFile(new FileInputStream(origFile), destFile);
    }

    /**
     * Recursively copy folder contents.
     * <p/>
     * Only supports copying of files and directories - symlinks are not copied. If the destination
     * directory does not exist, it will be created.
     *
     * @param sourceDir the folder that contains the files to copy
     * @param destDir the destination folder
     * @throws IOException
     */
    public static void recursiveCopy(File sourceDir, File destDir) throws IOException {
        File[] childFiles = sourceDir.listFiles();
        if (childFiles == null) {
            throw new IOException(String.format(
                    "Failed to recursively copy. Could not determine contents for directory '%s'",
                    sourceDir.getAbsolutePath()));
        }
        if (!destDir.isDirectory() && !destDir.mkdir()) {
            throw new IOException(String.format("Could not create directory %s",
                destDir.getAbsolutePath()));
        }
        for (File childFile : childFiles) {
            File destChild = new File(destDir, childFile.getName());
            if (childFile.isDirectory()) {
                recursiveCopy(childFile, destChild);
            } else if (childFile.isFile()) {
                copyFile(childFile, destChild);
            }
        }
    }

    /**
     * A helper method for reading string data from a file
     *
     * @param sourceFile the file to read from
     * @throws IOException
     * @throws FileNotFoundException
     */
    public static String readStringFromFile(File sourceFile) throws IOException {
        FileInputStream is = null;
        try {
            // no need to buffer since StreamUtil does
            is = new FileInputStream(sourceFile);
            return StreamUtil.getStringFromStream(is);
        } finally {
            StreamUtil.close(is);
        }
    }

    /**
     * A helper method for writing string data to file
     *
     * @param inputString the input {@link String}
     * @param destFile the dest file to write to
     */
    public static void writeToFile(String inputString, File destFile) throws IOException {
        writeToFile(new ByteArrayInputStream(inputString.getBytes()), destFile);
    }

    /**
     * A helper method for writing stream data to file
     *
     * @param input the unbuffered input stream
     * @param destFile the dest file to write to
     */
    public static void writeToFile(InputStream input, File destFile) throws IOException {
        InputStream origStream = null;
        OutputStream destStream = null;
        try {
            origStream = new BufferedInputStream(input);
            destStream = new BufferedOutputStream(new FileOutputStream(destFile));
            StreamUtil.copyStreams(origStream, destStream);
        } finally {
            StreamUtil.close(origStream);
            StreamUtil.flushAndCloseStream(destStream);
        }
    }

    private static void verifyDiskSpace(File file) {
        // Based on empirical testing File.getUsableSpace is a low cost operation (~ 100 us for
        // local disk, ~ 100 ms for network disk). Therefore call it every time tmp file is
        // created
        long usableSpace = file.getUsableSpace();
        long minDiskSpace = mMinDiskSpaceMb * 1024 * 1024;
        if (usableSpace < minDiskSpace) {
            throw new LowDiskSpaceException(String.format(
                    "Available space on %s is %.2f MB. Min is %d MB", file.getAbsolutePath(),
                    file.getUsableSpace() / (1024.0 * 1024.0), mMinDiskSpaceMb));
        }
    }

    /**
     * Recursively delete given file or directory and all its contents.
     *
     * @param rootDir the directory or file to be deleted; can be null
     */
    public static void recursiveDelete(File rootDir) {
        if (rootDir != null) {
            if (rootDir.isDirectory()) {
                File[] childFiles = rootDir.listFiles();
                if (childFiles != null) {
                    for (File child : childFiles) {
                        recursiveDelete(child);
                    }
                }
            }
            rootDir.delete();
        }
    }

    /**
     * Gets the extension for given file name.
     *
     * @param fileName
     * @return the extension or empty String if file has no extension
     */
    public static String getExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return "";
        } else {
            return fileName.substring(index);
        }
    }

    /**
     * Gets the base name, without extension, of given file name.
     * <p/>
     * e.g. getBaseName("file.txt") will return "file"
     *
     * @param fileName
     * @return the base name
     */
    public static String getBaseName(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return fileName;
        } else {
            return fileName.substring(0, index);
        }
    }

    /**
     * Utility method to do byte-wise content comparison of two files.
     *
     * @return <code>true</code> if file contents are identical
     */
    public static boolean compareFileContents(File file1, File file2) throws IOException {
        BufferedInputStream stream1 = null;
        BufferedInputStream stream2 = null;

        boolean result = true;
        try {
            stream1 = new BufferedInputStream(new FileInputStream(file1));
            stream2 = new BufferedInputStream(new FileInputStream(file2));
            boolean eof = false;
            while (!eof) {
                int byte1 = stream1.read();
                int byte2 = stream2.read();
                if (byte1 != byte2) {
                    result = false;
                    break;
                }
                eof = byte1 == -1;
            }
        } finally {
            StreamUtil.close(stream1);
            StreamUtil.close(stream2);
        }
        return result;
    }

    /**
     * Helper method which constructs a unique file on temporary disk, whose name corresponds as
     * closely as possible to the file name given by the remote file path
     *
     * @param remoteFilePath the '/' separated remote path to construct the name from
     * @param parentDir the parent directory to create the file in. <code>null</code> to use the
     * default temporary directory
     */
    public static File createTempFileForRemote(String remoteFilePath, File parentDir)
            throws IOException {
        String[] segments = remoteFilePath.split("/");
        // take last segment as base name
        String remoteFileName = segments[segments.length - 1];
        String prefix = getBaseName(remoteFileName);
        if (prefix.length() < 3) {
            // prefix must be at least 3 characters long
            prefix = prefix + "XXX";
        }
        String fileExt = getExtension(remoteFileName);

        // create a unique file name. Add a underscore to prefix so file name is more readable
        // e.g. myfile_57588758.img rather than myfile57588758.img
        File tmpFile = FileUtil.createTempFile(prefix + "_", fileExt, parentDir);
        return tmpFile;
    }

    /**
     * Try to delete a file. Intended for use when cleaning up
     * in {@code finally} stanzas.
     *
     * @param file may be null.
     */
    public static void deleteFile(File file) {
        if (file != null) {
            file.delete();
        }
    }

    /**
     * Helper method to build a system-dependent File
     *
     * @param parentDir the parent directory to use.
     * @param pathSegments the relative path segments to use
     * @return the {@link File} representing given path, with each <var>pathSegment</var>
     *         separated by {@link File#separatorChar}
     */
    public static File getFileForPath(File parentDir, String... pathSegments) {
        return new File(parentDir, getPath(pathSegments));
    }

    /**
     * Helper method to build a system-dependent relative path
     *
     * @param pathSegments the relative path segments to use
     * @return the {@link String} representing given path, with each <var>pathSegment</var>
     *         separated by {@link File#separatorChar}
     */
    public static String getPath(String... pathSegments) {
        StringBuilder pathBuilder = new StringBuilder();
        boolean isFirst = true;
        for (String path : pathSegments) {
            if (!isFirst) {
                pathBuilder.append(File.separatorChar);
            } else {
                isFirst = false;
            }
            pathBuilder.append(path);
        }
        return pathBuilder.toString();
    }

    /**
     * Recursively search given directory for first file with given name
     *
     * @param dir the directory to search
     * @param fileName the name of the file to search for
     * @return the {@link File} or <code>null</code> if it could not be found
     */
    public static File findFile(File dir, String fileName) {
        if (dir.listFiles() != null) {
            for (File file : dir.listFiles()) {
                if (file.getName().equals(fileName)) {
                    return file;
                } else if (file.isDirectory()) {
                    File result = findFile(file, fileName);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Recursively find all directories under the given {@code rootDir}
     *
     * @param rootDir the root directory to search in
     * @param relativeParent An optional parent for all {@link File}s returned. If not specified,
     *            all {@link File}s will be relative to {@code rootDir}.
     * @return An set of {@link File}s, representing all directories under {@code rootDir},
     *         including {@code rootDir} itself. If {@code rootDir} is null, an empty set is
     *         returned.
     */
    public static Set<File> findDirsUnder(File rootDir, File relativeParent) {
        Set<File> dirs = new HashSet<File>();
        if (rootDir != null) {
            if (!rootDir.isDirectory()) {
                throw new IllegalArgumentException("Can't find dirs under '" + rootDir
                        + "'. It's not a directory.");
            }
            File thisDir = new File(relativeParent, rootDir.getName());
            dirs.add(thisDir);
            for (File file : rootDir.listFiles()) {
                if (file.isDirectory()) {
                    dirs.addAll(findDirsUnder(file, thisDir));
                }
            }
        }
        return dirs;
    }

    /**
     * Convert the given file size in bytes to a more readable format in X.Y[KMGT] format.
     *
     * @param sizeLong file size in bytes
     * @return descriptive string of file size
     */
    public static String convertToReadableSize(long sizeLong) {

        double size = sizeLong;
        for (int i = 0; i < SIZE_SPECIFIERS.length; i++) {
            if (size < 1024) {
                return String.format("%.1f%c", size, SIZE_SPECIFIERS[i]);
            }
            size /= 1024f;
        }
        throw new IllegalArgumentException(String.format(
                "Passed a file size of %d, I cannot count that high", size));
    }

    /**
     * The inverse of {@link #convertToReadableSize(long)}. Converts the readable format described
     * in {@link #convertToReadableSize(long)} to a byte value.
     *
     * @param sizeString the string description of the size.
     * @return the size in bytes
     * @throws IllegalArgumentException if cannot recognize size
     */
    public static long convertSizeToBytes(String sizeString) throws IllegalArgumentException {
        if (sizeString.isEmpty()) {
            throw new IllegalArgumentException("invalid empty string");
        }
        char sizeSpecifier = sizeString.charAt(sizeString.length() - 1);
        long multiplier = findMultiplier(sizeSpecifier);
        try {
            String numberString = sizeString;
            if (multiplier != 1) {
                // strip off last char
                numberString = sizeString.substring(0, sizeString.length() - 1);
            }
            return multiplier * Long.parseLong(numberString);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Unrecognized size %s", sizeString));
        }
    }

    private static long findMultiplier(char sizeSpecifier) {
        long multiplier = 1;
        for (int i = 1; i < SIZE_SPECIFIERS.length; i++) {
            multiplier *= 1024;
            if (sizeSpecifier == SIZE_SPECIFIERS[i]) {
                return multiplier;
            }
        }
        // not found
        return 1;
    }

    /**
     * Returns all jar files found in given directory
     */
    public static List<File> collectJars(File dir) {
        List<File> list = new ArrayList<File>();
        File[] jarFiles = dir.listFiles(new JarFilter());
        if (jarFiles != null) {
            list.addAll(Arrays.asList(dir.listFiles(new JarFilter())));
        }
        return list;
    }

    private static class JarFilter implements FilenameFilter {
        /**
         * {@inheritDoc}
         */
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".jar");
        }
    }


    // Backwards-compatibility section
    /**
     * Utility method to extract entire contents of zip file into given directory
     *
     * @param zipFile the {@link ZipFile} to extract
     * @param destDir the local dir to extract file to
     * @throws IOException if failed to extract file
     * @deprecated Moved to {@link ZipUtil#extractZip(ZipFile, File)}.
     */
    @Deprecated
    public static void extractZip(ZipFile zipFile, File destDir) throws IOException {
        ZipUtil.extractZip(zipFile, destDir);
    }

    /**
     * Utility method to extract one specific file from zip file into a tmp file
     *
     * @param zipFile  the {@link ZipFile} to extract
     * @param filePath the filePath of to extract
     * @return the {@link File} or null if not found
     * @throws IOException if failed to extract file
     * @deprecated Moved to {@link ZipUtil#extractFileFromZip(ZipFile, String)}.
     */
    @Deprecated
    public static File extractFileFromZip(ZipFile zipFile, String filePath) throws IOException {
        return ZipUtil.extractFileFromZip(zipFile, filePath);
    }

    /**
     * Utility method to create a temporary zip file containing the given directory and
     * all its contents.
     *
     * @param dir the directory to zip
     * @return a temporary zip {@link File} containing directory contents
     * @throws IOException if failed to create zip file
     * @deprecated Moved to {@link ZipUtil#createZip(File)}.
     */
    @Deprecated
    public static File createZip(File dir) throws IOException {
        return ZipUtil.createZip(dir);
    }

    /**
     * Utility method to create a zip file containing the given directory and
     * all its contents.
     *
     * @param dir the directory to zip
     * @param zipFile the zip file to create - it should not already exist
     * @throws IOException if failed to create zip file
     * @deprecated Moved to {@link ZipUtil#createZip(File, File)}.
     */
    @Deprecated
    public static void createZip(File dir, File zipFile) throws IOException {
        ZipUtil.createZip(dir, zipFile);
    }

    /**
     * Close an open {@link ZipFile}, ignoring any exceptions.
     *
     * @param zipFile the file to close
     * @deprecated Moved to {@link ZipUtil#closeZip(ZipFile)}.
     */
    @Deprecated
    public static void closeZip(ZipFile zipFile) {
        ZipUtil.closeZip(zipFile);
    }

    /**
     * Helper method to create a gzipped version of a single file.
     *
     * @param file     the original file
     * @param gzipFile the file to place compressed contents in
     * @throws IOException
     * @deprecated Moved to {@link ZipUtil#gzipFile(File, File)}.
     */
    @Deprecated
    public static void gzipFile(File file, File gzipFile) throws IOException {
        ZipUtil.gzipFile(file, gzipFile);
    }

    /**
     * Helper method to calculate md5 for a file.
     *
     * @param file
     * @return md5 of the file
     * @throws IOException
     */
    public static String calculateMd5(File file) throws IOException {
        FileInputStream inputSource = new FileInputStream(file);
        return StreamUtil.calculateMd5(inputSource);
    }
}
