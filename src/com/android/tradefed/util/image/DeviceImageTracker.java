/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.util.image;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * For some of the incremental device update, we need the baseline files to compute diffs. This
 * utility helps keeping track of them.
 */
public class DeviceImageTracker {

    private static DeviceImageTracker sDefaultInstance;

    private final LoadingCache<String, FileCacheTracker> mImageCache;
    private final File mCacheDir;

    /** Track information of the device image cached and its metadata */
    public class FileCacheTracker {
        public File zippedDeviceImage;
        public String buildId;
        public String branch;
        public String flavor;

        FileCacheTracker(File zippedDeviceImage, String buildId, String branch, String flavor) {
            this.zippedDeviceImage = zippedDeviceImage;
            this.buildId = buildId;
            this.branch = branch;
            this.flavor = flavor;
        }
    }

    public static DeviceImageTracker getDefaultCache() {
        if (sDefaultInstance == null) {
            sDefaultInstance = new DeviceImageTracker();
        }
        return sDefaultInstance;
    }

    @VisibleForTesting
    protected DeviceImageTracker() {
        try {
            mCacheDir = FileUtil.createTempDir("image_file_cache_dir");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        RemovalListener<String, FileCacheTracker> listener =
                new RemovalListener<String, FileCacheTracker>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, FileCacheTracker> n) {
                        if (n.wasEvicted()) {
                            FileUtil.deleteFile(n.getValue().zippedDeviceImage);
                        }
                    }
                };
        mImageCache =
                CacheBuilder.newBuilder()
                        .maximumSize(10)
                        .expireAfterAccess(1, TimeUnit.DAYS)
                        .removalListener(listener)
                        .build(
                                new CacheLoader<String, FileCacheTracker>() {
                                    @Override
                                    public FileCacheTracker load(String key) throws IOException {
                                        // We manually seed and manage the cache
                                        // no need to load.
                                        return null;
                                    }
                                });
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread() {
                            @Override
                            public void run() {
                                cleanUp();
                            }
                        });
    }

    /**
     * Tracks a given device image to the device serial that was flashed with it
     *
     * @param serial The device that was flashed with the image.
     * @param deviceImage The image flashed onto the device.
     * @param buildId The build id associated with the device image.
     * @param branch The branch associated with the device image.
     * @param flavor The build flavor associated with the device image.
     */
    public void trackUpdatedDeviceImage(
            String serial, File deviceImage, String buildId, String branch, String flavor) {
        File copyInCache = new File(mCacheDir, serial);
        try {
            FileUtil.hardlinkFile(deviceImage, copyInCache);
            mImageCache.put(serial, new FileCacheTracker(copyInCache, buildId, branch, flavor));
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    public void invalidateTracking(String serial) {
        mImageCache.invalidate(serial);
    }

    @VisibleForTesting
    protected void cleanUp() {
        mImageCache.invalidateAll();
        FileUtil.recursiveDelete(mCacheDir);
    }

    /** Returns the device image that was tracked for the device. Null if none was tracked. */
    public FileCacheTracker getBaselineDeviceImage(String serial) {
        return mImageCache.getIfPresent(serial);
    }
}
