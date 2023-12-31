/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.build;

import com.android.tradefed.command.FatalHostError;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.invoker.logger.CurrentInvocation;
import com.android.tradefed.invoker.logger.CurrentInvocation.InvocationInfo;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A helper class that maintains a local filesystem LRU cache of downloaded files.
 */
public class FileDownloadCache {

    private static final char REL_PATH_SEPARATOR = '/';

    /** fixed location of download cache. */
    private final File mCacheRoot;

    /**
     * The map of remote file paths to local files, stored in least-recently-used order.
     *
     * <p>Used for performance reasons. Functionally speaking, this data structure is not needed,
     * since all info could be obtained from inspecting the filesystem.
     */
    private final Map<String, File> mCacheMap = new CollapsedKeyMap<>();

    /** the lock for <var>mCacheMap</var> */
    private final ReentrantLock mCacheMapLock = new ReentrantLock();

    /** A map of remote file paths to locks. */
    private final Map<String, ReentrantLock> mFileLocks = new CollapsedKeyMap<>();

    private final Map<String, FileLock> mJvmLocks = new CollapsedKeyMap<>();

    private long mCurrentCacheSize = 0;

    /** The approximate maximum allowed size of the local file cache. Default to 20 gig */
    private long mMaxFileCacheSize =
            GlobalConfiguration.getInstance().getHostOptions().getCacheSizeLimit();

    /**
     * Struct for a {@link File} and its remote relative path
     */
    private static class FilePair {
        final String mRelPath;
        final File mFile;

        FilePair(String relPath, File file) {
            mRelPath = relPath;
            mFile = file;
        }
    }

    /**
     * A {@link Comparator} for comparing {@link File}s based on {@link File#lastModified()}.
     */
    private static class FileTimeComparator implements Comparator<FilePair> {
        @Override
        public int compare(FilePair o1, FilePair o2) {
            Long timestamp1 = Long.valueOf(o1.mFile.lastModified());
            Long timestamp2 = o2.mFile.lastModified();
            return timestamp1.compareTo(timestamp2);
        }
    }

    /**
     * Create a {@link FileDownloadCache}, deleting any previous cache contents from disk.
     * <p/>
     * Assumes that the current process has exclusive access to the <var>cacheRoot</var> directory.
     * <p/>
     * Essentially, the LRU cache is a mirror of a given remote file path hierarchy.
     */
    FileDownloadCache(File cacheRoot) {
        mCacheRoot = cacheRoot;
        if (!mCacheRoot.exists()) {
            CLog.d("Creating file cache at %s", mCacheRoot.getAbsolutePath());
            if (!mCacheRoot.mkdirs()) {
                throw new FatalHostError(
                        String.format(
                                "Could not create cache directory at %s",
                                mCacheRoot.getAbsolutePath()),
                        InfraErrorIdentifier.LAB_HOST_FILESYSTEM_ERROR);
            }
        } else {
            mCacheMapLock.lock();
            try {
                CLog.d("Building file cache from contents at %s", mCacheRoot.getAbsolutePath());
                // create an unsorted list of all the files in mCacheRoot. Need to create list first
                // rather than inserting in Map directly because Maps cannot be sorted
                List<FilePair> cacheEntryList = new LinkedList<FilePair>();
                addFiles(mCacheRoot, new Stack<String>(), cacheEntryList);
                // now sort them based on file timestamp, to get them in LRU order
                Collections.sort(cacheEntryList, new FileTimeComparator());
                // now insert them into the map
                for (FilePair cacheEntry : cacheEntryList) {
                    mCacheMap.put(cacheEntry.mRelPath, cacheEntry.mFile);
                    mCurrentCacheSize += cacheEntry.mFile.length();
                }
                // this would be an unusual situation, but check if current cache is already too big
                if (mCurrentCacheSize > getMaxFileCacheSize()) {
                    incrementAndAdjustCache(0);
                }
            } finally {
                mCacheMapLock.unlock();
            }
        }
    }

    /**
     * Recursive method for adding a directory's contents to the cache map
     * <p/>
     * cacheEntryList will contain results of all files found in cache, in no guaranteed order.
     *
     * @param dir the parent directory to search
     * @param relPathSegments the current filesystem path of <var>dir</var>, relative to
     *            <var>mCacheRoot</var>
     * @param cacheEntryList the list of files discovered
     */
    private void addFiles(File dir, Stack<String> relPathSegments,
            List<FilePair> cacheEntryList) {

        File[] fileList = dir.listFiles();
        if (fileList == null) {
            CLog.e("Unable to list files in cache dir %s", dir.getAbsolutePath());
            return;
        }
        for (File childFile : fileList) {
            if (childFile.isDirectory()) {
                relPathSegments.push(childFile.getName());
                addFiles(childFile, relPathSegments, cacheEntryList);
                relPathSegments.pop();
            } else if (childFile.isFile()) {
                StringBuffer relPath = new StringBuffer();
                for (String pathSeg : relPathSegments) {
                    relPath.append(pathSeg);
                    relPath.append(REL_PATH_SEPARATOR);
                }
                relPath.append(childFile.getName());
                cacheEntryList.add(new FilePair(relPath.toString(), childFile));
            } else {
                CLog.w("Unrecognized file type %s in cache", childFile.getAbsolutePath());
            }
        }
    }

    /** Acquires the lock for a file. */
    protected void lockFile(String remoteFilePath) {
        // Get a JVM level lock first
        synchronized (mJvmLocks) {
            FileLock fLock = mJvmLocks.get(remoteFilePath);
            if (fLock == null) {
                File f = new File(mCacheRoot, convertPath(remoteFilePath));
                // We can't lock a directory
                if (!f.isDirectory()) {
                    try {
                        f.getParentFile().mkdirs();
                        f.createNewFile();
                        fLock = FileChannel.open(f.toPath(), StandardOpenOption.WRITE).lock();
                        mJvmLocks.put(remoteFilePath, fLock);
                    } catch (IOException e) {
                        CLog.e(e);
                    }
                }
            }
        }
        // Get concurrent lock for inside the JVM
        ReentrantLock fileLock;
        synchronized (mFileLocks) {
            fileLock = mFileLocks.get(remoteFilePath);
            if (fileLock == null) {
                fileLock = new ReentrantLock();
                mFileLocks.put(remoteFilePath, fileLock);
            }
        }
        fileLock.lock();
    }

    /**
     * Acquire the lock for a file only if it is not held by another thread.
     *
     * @return true if the lock was acquired, and false otherwise.
     */
    protected boolean tryLockFile(String remoteFilePath) {
        synchronized (mJvmLocks) {
            FileLock fLock = mJvmLocks.get(remoteFilePath);
            if (fLock == null) {
                File f = new File(mCacheRoot, convertPath(remoteFilePath));
                // We can't lock a directory
                if (f.exists() && !f.isDirectory()) {
                    try {
                        fLock = FileChannel.open(f.toPath(), StandardOpenOption.WRITE).tryLock();
                        mJvmLocks.put(remoteFilePath, fLock);
                    } catch (IOException e) {
                        CLog.e(e);
                    }
                }
            }
            if (fLock == null) {
                return false;
            }
        }
        synchronized (mFileLocks) {
            ReentrantLock fileLock = mFileLocks.get(remoteFilePath);
            if (fileLock == null) {
                fileLock = new ReentrantLock();
                mFileLocks.put(remoteFilePath, fileLock);
            }
            try {
                return fileLock.tryLock(0, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /** Attempt to release a lock for a file. */
    protected void unlockFile(String remoteFilePath) {
        synchronized (mFileLocks) {
            ReentrantLock fileLock = mFileLocks.get(remoteFilePath);
            if (fileLock != null) {
                if (!fileLock.hasQueuedThreads()) {
                    mFileLocks.remove(remoteFilePath);
                }
                if (fileLock.isHeldByCurrentThread()) {
                    fileLock.unlock();
                }
            }
        }
        // Release the JVM level lock
        synchronized (mJvmLocks) {
            FileLock fLock = mJvmLocks.get(remoteFilePath);
            if (fLock != null) {
                mJvmLocks.remove(remoteFilePath);
                try {
                    fLock.release();
                } catch (IOException e) {
                    CLog.e(e);
                } finally {
                    StreamUtil.close(fLock.channel());
                }
            }
        }
    }

    /**
     * Set the maximum size of the local file cache.
     *
     * <p>Cache will not be adjusted immediately if set to a smaller size than current, but will
     * take effect on next file download.
     *
     * @param numBytes
     */
    public void setMaxCacheSize(long numBytes) {
        // for simplicity, get global lock
        mCacheMapLock.lock();
        mMaxFileCacheSize = numBytes;
        mCacheMapLock.unlock();
    }

    /**
     * Download the file or link the cache to the destination file.
     *
     * @param downloader the {@link IFileDownloader}
     * @param remoteFilePath the remote file.
     * @param destFile The destination file of the download.
     * @throws BuildRetrievalError
     */
    public void fetchRemoteFile(IFileDownloader downloader, String remoteFilePath, File destFile)
            throws BuildRetrievalError {
        internalfetchRemoteFile(downloader, remoteFilePath, destFile);
    }

    /**
     * Returns a local file corresponding to the given <var>remotePath</var>
     *
     * <p>The local {@link File} will be copied from the cache if it exists, otherwise will be
     * downloaded via the given {@link IFileDownloader}.
     *
     * @param downloader the {@link IFileDownloader}
     * @param remoteFilePath the remote file.
     * @return a local {@link File} containing contents of remotePath
     * @throws BuildRetrievalError if file could not be retrieved
     */
    public File fetchRemoteFile(IFileDownloader downloader, String remoteFilePath)
            throws BuildRetrievalError {
        return internalfetchRemoteFile(downloader, remoteFilePath, null);
    }

    private File internalfetchRemoteFile(
            IFileDownloader downloader, String remotePath, File destFile)
            throws BuildRetrievalError {
        boolean download = false;
        File cachedFile;
        File copyFile;
        if (remotePath == null) {
            throw new BuildRetrievalError(
                    "remote path was null.", InfraErrorIdentifier.ARTIFACT_REMOTE_PATH_NULL);
        }

        long start = System.currentTimeMillis();
        CloseableTraceScope scope = new CloseableTraceScope("cache_lock");
        lockFile(remotePath);
        try {
            mCacheMapLock.lock();
            try {
                cachedFile = mCacheMap.remove(remotePath);
                if (cachedFile == null) {
                    download = true;
                    String localRelativePath = convertPath(remotePath);
                    cachedFile = new File(mCacheRoot, localRelativePath);
                }
                mCacheMap.put(remotePath, cachedFile);
            } finally {
                mCacheMapLock.unlock();
            }
            scope.close();
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CACHE_WAIT_FOR_LOCK, System.currentTimeMillis() - start);
            try {
                if (!download
                        && cachedFile.exists()
                        && (cachedFile.length() == 0L
                                || !downloader.isFresh(cachedFile, remotePath))) {
                    CLog.d(
                            "Cached file %s for %s is out of date, re-download.",
                            cachedFile, remotePath);
                    FileUtil.recursiveDelete(cachedFile);
                    download = true;
                }
                if (download || !cachedFile.exists()) {
                    cachedFile.getParentFile().mkdirs();
                    // TODO: handle folder better
                    if (cachedFile.exists()) {
                        cachedFile.delete();
                    }
                    downloadFile(downloader, remotePath, cachedFile);
                } else {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.CACHE_HIT_COUNT, 1);
                    CLog.d(
                            "Retrieved remote file %s from cached file %s",
                            remotePath, cachedFile.getAbsolutePath());
                }
                copyFile = copyFile(remotePath, cachedFile, destFile);
            } catch (BuildRetrievalError | RuntimeException e) {
                // cached file is likely incomplete, delete it.
                deleteCacheEntry(remotePath);
                throw e;
            }

            // Only the thread that first downloads the file should increment the cache.
            if (download) {
               incrementAndAdjustCache(cachedFile.length());
            }
        } finally {
            unlockFile(remotePath);
        }
        return copyFile;
    }

    /** Do the actual file download, clean up on exception is done by the caller. */
    private void downloadFile(IFileDownloader downloader, String remotePath, File cachedFile)
            throws BuildRetrievalError {
        CLog.d("Downloading %s to cache", remotePath);
        downloader.downloadFile(remotePath, cachedFile);
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.ARTIFACTS_DOWNLOAD_SIZE, cachedFile.length());
    }

    @VisibleForTesting
    File copyFile(String remotePath, File cachedFile, File destFile) throws BuildRetrievalError {
        // attempt to create a local copy of cached file with meaningful name
        File hardlinkFile = destFile;
        try {
            if (hardlinkFile == null) {
                hardlinkFile = FileUtil.createTempFileForRemote(remotePath, getWorkFolder());
            }
            hardlinkFile.delete();
            CLog.d(
                    "Creating hardlink '%s' to '%s'",
                    hardlinkFile.getAbsolutePath(), cachedFile.getAbsolutePath());
            if (cachedFile.isDirectory()) {
                FileUtil.recursiveHardlink(cachedFile, hardlinkFile, false);
            } else {
                FileUtil.hardlinkFile(cachedFile, hardlinkFile);
            }
            return hardlinkFile;
        } catch (IOException e) {
            FileUtil.deleteFile(hardlinkFile);
            // cached file might be corrupt or incomplete, delete it
            FileUtil.deleteFile(cachedFile);
            throw new BuildRetrievalError(
                    String.format("Failed to copy cached file %s", cachedFile),
                    e,
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    @VisibleForTesting
    File getWorkFolder() {
        return CurrentInvocation.getInfo(InvocationInfo.WORK_FOLDER);
    }

    /**
     * Convert remote relative path into an equivalent local path
     * @param remotePath
     * @return the local relative path
     */
    private String convertPath(String remotePath) {
        if (FileDownloadCache.REL_PATH_SEPARATOR != File.separatorChar) {
            return remotePath.replace(FileDownloadCache.REL_PATH_SEPARATOR , File.separatorChar);
        } else {
            // no conversion necessary
            return remotePath;
        }
    }

    /**
     * Adjust file cache size to mMaxFileCacheSize if necessary by deleting old files
     */
    private void incrementAndAdjustCache(long length) {
        mCacheMapLock.lock();
        try {
            mCurrentCacheSize += length;
            Iterator<String> keyIterator = mCacheMap.keySet().iterator();
            while (mCurrentCacheSize > getMaxFileCacheSize() && keyIterator.hasNext()) {
                String remotePath = keyIterator.next();
                // Only delete the file if it is not being used by another thread.
                if (tryLockFile(remotePath)) {
                    try {
                        File file = mCacheMap.get(remotePath);
                        mCurrentCacheSize -= file.length();
                        file.delete();
                        keyIterator.remove();
                    } finally {
                        unlockFile(remotePath);
                    }
                } else {
                    CLog.i(
                            String.format(
                                    "File %s is being used by another invocation. Skipping.",
                                    remotePath));
                }
            }
            // audit cache size
            if (mCurrentCacheSize < 0) {
                // should never happen
                CLog.e("Cache size is less than 0!");
                // TODO: throw fatal error?
            } else if (mCurrentCacheSize > getMaxFileCacheSize()) {
                // May occur if the cache is configured to be too small or if mCurrentCacheSize is
                // accounting for non-existent files.
                CLog.w("File cache is over-capacity.");
            }
        } finally {
            mCacheMapLock.unlock();
        }
    }

    /**
     * Returns the cached file for given remote path, or <code>null</code> if no cached file exists.
     * <p/>
     * Exposed for unit testing
     *
     * @param remoteFilePath the remote file path
     * @return the cached {@link File} or <code>null</code>
     */
     File getCachedFile(String remoteFilePath) {
        mCacheMapLock.lock();
        try {
            return mCacheMap.get(remoteFilePath);
        } finally {
            mCacheMapLock.unlock();
        }
     }

    /**
     * Empty the cache, deleting all files.
     * <p/>
     * exposed for unit testing
     */
     void empty() {
        long currentMax = getMaxFileCacheSize();
        // reuse incrementAndAdjustCache to clear cache, by setting cache cap to 0
        setMaxCacheSize(0L);
        incrementAndAdjustCache(0);
        setMaxCacheSize(currentMax);
    }

    /**
     * Retrieve the oldest remotePath from cache.
     * <p/>
     * Exposed for unit testing
     *
     * @return the remote path or <code>null</null> if cache is empty
     */
    String getOldestEntry() {
        mCacheMapLock.lock();
        try {
            if (!mCacheMap.isEmpty()) {
                return mCacheMap.keySet().iterator().next();
            } else {
                return null;
            }
        } finally {
            mCacheMapLock.unlock();
        }
    }

    /**
     * Get the current max size of file cache.
     * <p/>
     * exposed for unit testing.
     *
     * @return the mMaxFileCacheSize
     */
    long getMaxFileCacheSize() {
        return mMaxFileCacheSize;
    }

    /**
     * Allow deleting an entry from the cache. In case the entry is invalid or corrupted.
     */
    public void deleteCacheEntry(String remoteFilePath) {
        lockFile(remoteFilePath);
        try {
            mCacheMapLock.lock();
            try {
                File file = mCacheMap.remove(remoteFilePath);
                if (file != null) {
                    FileUtil.recursiveDelete(file);
                } else {
                    CLog.i("No cache entry to delete for %s", remoteFilePath);
                }
            } finally {
                mCacheMapLock.unlock();
            }
        } finally {
            unlockFile(remoteFilePath);
        }
    }

    /**
     * Class that ensure the remote file path as the key is always similar to an actual folder
     * hierarchy.
     */
    private static class CollapsedKeyMap<V> extends LinkedHashMap<String, V> {
        @Override
        public V put(String key, V value) {
            return super.put(new File(key).getPath(), value);
        }

        @Override
        public V get(Object key) {
            if (key instanceof String) {
                return super.get(new File((String) key).getPath());
            }
            return super.get(key);
        }

        @Override
        public V remove(Object key) {
            if (key instanceof String) {
                return super.remove(new File((String) key).getPath());
            }
            return super.remove(key);
        }
    }
}
