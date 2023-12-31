/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.cluster;

import com.android.tradefed.build.DeviceFolderBuildInfo;
import com.android.tradefed.build.IBuildInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A {@link IBuildInfo} class for builds piped from TFC. */
public class ClusterBuildInfo extends DeviceFolderBuildInfo {

    private List<File> mZipMounts = new ArrayList<>();

    public ClusterBuildInfo(File rootDir, String buildId, String buildName) {
        super(buildId, buildName);
        setRootDir(rootDir);
    }

    /**
     * Return zip mount points associated with this build info.
     *
     * @return a list of zip mount points.
     */
    List<File> getZipMounts() {
        return Collections.unmodifiableList(mZipMounts);
    }

    /**
     * Add a zip mount point.
     *
     * @param dir a path where a zip file is mounted at.
     */
    void addZipMount(File dir) {
        mZipMounts.add(dir);
    }
}
