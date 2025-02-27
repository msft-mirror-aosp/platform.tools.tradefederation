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
package com.android.tradefed.build.content;

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compute a module list from the context. */
public class ContentModuleLister {

    /** Builds the list of existing modules from the context or null in case of error */
    public static Set<String> buildModuleList(ContentAnalysisContext context) {
        try {
            ArtifactDetails currentContent =
                    ArtifactDetails.parseFile(
                            context.contentInformation().currentContent, context.contentEntry());
            List<ArtifactFileDescriptor> allFiles = currentContent.details;
            Set<String> moduleNames = new HashSet<>();
            for (ArtifactFileDescriptor afd : allFiles) {
                String filePath = afd.path;
                String[] pathSegments = filePath.split("/");
                if (filePath.startsWith("host/testcases/")) {
                    moduleNames.add(pathSegments[2]);
                } else if (filePath.startsWith("target/testcases/")) {
                    moduleNames.add(pathSegments[2]);
                }
                if (pathSegments.length == 4) {
                    String possibleConfig = pathSegments[2] + ".config";
                    if (pathSegments[3].endsWith(".config")
                            && !possibleConfig.equals(pathSegments[3])) {
                        moduleNames.add(FileUtil.getBaseName(pathSegments[3]));
                    }
                }
            }
            return moduleNames;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
            return null;
        }
    }
}
