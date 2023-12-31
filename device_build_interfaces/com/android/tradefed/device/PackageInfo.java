/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tradefed.device;

import java.util.HashMap;
import java.util.Map;

/**
 * Container for an application's package info parsed from device.
 */
public class PackageInfo {

    // numeric flag constants. Copied from
    // frameworks/base/core/java/android/content/pm/ApplicationInfo.java
    private static final int FLAG_UPDATED_SYSTEM_APP = 1 << 7;
    private static final int FLAG_SYSTEM = 1 << 0;
    public static final int FLAG_PERSISTENT = 1 << 3;

    // string flag constants. Used for newer platforms
    private static final String FLAG_UPDATED_SYSTEM_APP_TEXT = " UPDATED_SYSTEM_APP ";
    private static final String FLAG_SYSTEM_TEXT = " SYSTEM ";
    private static final String FLAG_PERSISTENT_TEXT = " PERSISTENT ";

    private final String mPackageName;
    private boolean mIsSystemApp;
    private boolean mIsUpdatedSystemApp;
    private boolean mIsPersistentApp;
    private Map<String, String> mAttributes = new HashMap<String, String>();
    private Map<Integer, String> mPerUserFirstInstallTime = new HashMap<>();

    PackageInfo(String pkgName) {
        mPackageName = pkgName;
    }

    /**
     * Returns <code>true</code> if this is a system app that has been updated.
     */
    public boolean isUpdatedSystemApp() {
        return mIsUpdatedSystemApp;
    }

    /**
     * Returns <code>true</code> if this is a system app.
     */
    public boolean isSystemApp() {
        return mIsSystemApp;
    }

    /**
     * Returns <code>true</code> if this is a persistent app.
     */
    public boolean isPersistentApp() {
        return mIsPersistentApp;
    }

    /**
     * Returns the package name of the application.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the version name of the application.
     * Note: this will return <code>null</code> if 'versionName' attribute was not found, such as
     * on froyo devices.
     */
    public String getVersionName() {
        return mAttributes.get("versionName");
    }

    /**
     * Returns the version name of the application. Note: this will return <code>null</code> if
     * 'versionCode' attribute was not found
     */
    public String getVersionCode() {
        return mAttributes.get("versionCode");
    }

    /** Returns where the package is located in the filesystem. */
    public String getCodePath() {
        return mAttributes.get("codePath");
    }

    void setIsUpdatedSystemApp(boolean isUpdatedSystemApp) {
        mIsUpdatedSystemApp = isUpdatedSystemApp;
    }

    void addAttribute(String name, String value) {
        mAttributes.put(name, value);
        if (name.equals("pkgFlags") || name.equals("flags")) {
            // do special handling of pkgFlags, which can be either hex or string form depending on
            // device OS version
            if (!parseFlagsAsInt(value)) {
                parseFlagsAsString(value);
            }
        }
    }

    private void parseFlagsAsString(String flagString) {
        mIsSystemApp = flagString.contains(FLAG_SYSTEM_TEXT);
        mIsUpdatedSystemApp = flagString.contains(FLAG_UPDATED_SYSTEM_APP_TEXT);
        mIsPersistentApp = flagString.contains(FLAG_PERSISTENT_TEXT);
    }

    private boolean parseFlagsAsInt(String value) {
        try {
            int flags =  Integer.decode(value);
            mIsSystemApp = (flags & FLAG_SYSTEM) != 0;
            // note: FLAG_UPDATED_SYSTEM_APP never seems to be set. Rely on parsing hidden system
            // packages
            mIsUpdatedSystemApp = (flags & FLAG_UPDATED_SYSTEM_APP) != 0;
            mIsPersistentApp = (flags & FLAG_PERSISTENT) != 0;
            return true;
        } catch (NumberFormatException e) {
            // not an int, fall through
        }
        return false;
    }

    public void addPerUserAttribute(int userId, String attr, String value) {
        if (!attr.equals("firstInstallTime")) {
            return;
        }
        mPerUserFirstInstallTime.put(userId, value);
    }

    public String getFirstInstallTime(int userId) {
        return mPerUserFirstInstallTime.get(userId);
    }
}
