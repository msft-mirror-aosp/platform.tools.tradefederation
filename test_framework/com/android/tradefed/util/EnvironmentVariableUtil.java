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

import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/** A collection of helper methods to prepare environment variables. */
public class EnvironmentVariableUtil {

    /**
     * Builds the value of PATH with relative paths to the {@code workingDir}.
     *
     * @param workingDir The root of the relative paths in the return.
     * @param tools A list of tools that will be linked to a folder named `runtime_deps` under the
     *     {@code workingDir} and included in the return.
     * @param addition The String that will be appended to the end of the return.
     * @return The value of PATH.
     */
    public static String buildPathWithRelativePaths(
            File workingDir, Set<String> tools, String addition) {
        String runtimeDepsFolderName = "runtime_deps";
        for (String t : tools) {
            try {
                File tool = new File(t);
                RunUtil.linkFile(
                        workingDir,
                        runtimeDepsFolderName,
                        tool.exists() ? tool : DeviceActionUtil.findExecutableOnPath(t));
            } catch (IOException | DeviceActionUtil.DeviceActionConfigError e) {
                CLog.e("Failed to link %s to working dir %s", t, workingDir);
                CLog.e(e);
            }
        }

        return String.format(".:%s:%s", runtimeDepsFolderName, addition);
    }
}
