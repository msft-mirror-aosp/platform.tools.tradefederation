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
package com.android.tradefed.cache;

import com.android.tradefed.util.FileUtil;

import com.google.common.collect.ImmutableSet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Class responsible to parse and extract information from modified_files.json. */
public class ModifiedFilesParser {

    private static final Set<String> INOP_IMAGE_CHANGES =
            ImmutableSet.of("build.prop", "prop.default");

    private final File mModifiedFilesJson;
    private final boolean mIsDeviceImage;
    private final List<String> added;
    private final List<String> changed;
    private final List<String> removed;

    private boolean mParsingDone = false;

    public ModifiedFilesParser(File modifiedFilesJson, boolean isDeviceImage) {
        mModifiedFilesJson = modifiedFilesJson;
        mIsDeviceImage = isDeviceImage;

        added = new ArrayList<>();
        changed = new ArrayList<>();
        removed = new ArrayList<>();
    }

    /**
     * @throws IOException
     * @throws JSONException
     */
    public void parse() throws IOException, JSONException {
        if (mParsingDone) {
            throw new IllegalStateException("parse() was called twice.");
        }
        JSONObject content = new JSONObject(FileUtil.readStringFromFile(mModifiedFilesJson));
        JSONArray addedArray = content.getJSONArray("added");
        for (int i = 0; i < addedArray.length(); i++) {
            String file = (String) addedArray.opt(i);
            added.add(file);
        }

        JSONArray changedArray = content.getJSONArray("changed");
        for (int i = 0; i < changedArray.length(); i++) {
            String file = (String) changedArray.opt(i);
            changed.add(file);
        }

        JSONArray removedArray = content.getJSONArray("removed");
        for (int i = 0; i < removedArray.length(); i++) {
            String file = (String) removedArray.opt(i);
            removed.add(file);
        }
        mParsingDone = true;
    }

    /** */
    public boolean hasImageChanged() {
        if (!mParsingDone) {
            throw new IllegalStateException("parse() hasn't been called yet.");
        }
        if (!mIsDeviceImage) {
            throw new IllegalStateException("modified_files was not marked as device image.");
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            return true;
        }
        for (String files : changed) {
            // This is a heuristic of always changing build-id and date.
            // Eventually this needs improvement to ensure the only modification
            // is really inop
            if (INOP_IMAGE_CHANGES.contains(new File(files).getName())) {
                continue;
            }
            return true;
        }
        return false;
    }
}
