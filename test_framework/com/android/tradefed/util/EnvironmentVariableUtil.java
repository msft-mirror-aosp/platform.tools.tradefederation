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

package com.android.tradefed.util;

import com.android.tradefed.log.LogUtil.CLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A collection of helper methods to prepare environment variables. */
public class EnvironmentVariableUtil {

    /**
     * Builds the value of PATH.
     *
     * @param tools A list of tools that will be added to PATH.
     * @param addition The String that will be appended to the end of the return.
     * @return The value of PATH.
     */
    public static String buildPath(Set<String> tools, String addition) {
        String runtimeDepsFolderName = "runtime_deps";
        List<String> paths = new ArrayList<>();
        for (String t : tools) {
            try {
                File tool = new File(t);
                paths.add(
                        tool.exists()
                                ? tool.getParent()
                                : DeviceActionUtil.findExecutableOnPath(t).getParent());
            } catch (DeviceActionUtil.DeviceActionConfigError e) {
                CLog.e("Failed to find %s!", t);
                CLog.e(e);
            }
        }

        paths.add(addition);
        return paths.stream()
                .distinct()
                .collect(Collectors.joining(System.getProperty("path.separator")));
    }
}
