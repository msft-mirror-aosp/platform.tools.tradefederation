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

    private final LoadingCache<String, File> mImageCache;
    private final File mCacheDir;

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
        RemovalListener<String, File> listener =
                new RemovalListener<String, File>() {
                    @Override
                    public void onRemoval(RemovalNotification<String, File> n) {
                        if (n.wasEvicted()) {
                            FileUtil.deleteFile(n.getValue());
                        }
                    }
                };
        mImageCache =
                CacheBuilder.newBuilder()
                        .maximumSize(10)
                        .expireAfterAccess(1, TimeUnit.DAYS)
                        .removalListener(listener)
                        .build(
                                new CacheLoader<String, File>() {
                                    @Override
                                    public File load(String key) throws IOException {
                                        File copyInCache = new File(mCacheDir, key);
                                        if (copyInCache.exists()) {
                                            return copyInCache;
                                        }
                                        return null;
                                    }
                                });
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread() {
                            @Override
                            public void run() {
                                FileUtil.recursiveDelete(mCacheDir);
                                mImageCache.invalidateAll();
                            }
                        });
    }

    /**
     * Tracks a given device image to the device serial that was flashed with it
     *
     * @param serial The device that was flashed with the image.
     * @param deviceImage The image flashed onto the device.
     */
    public void trackUpdatedDeviceImage(String serial, File deviceImage) {
        File copyInCache = new File(mCacheDir, serial);
        try {
            FileUtil.hardlinkFile(deviceImage, copyInCache);
            mImageCache.put(serial, copyInCache);
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    public void invalidateTracking(String serial) {
        mImageCache.invalidate(serial);
    }

    /** Returns the device image that was tracked for the device. Null if none was tracked. */
    public File getBaselineDeviceImage(String serial) {
        return mImageCache.getIfPresent(serial);
    }
}
