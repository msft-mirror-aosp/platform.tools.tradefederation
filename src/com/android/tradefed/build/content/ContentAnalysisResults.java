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
package com.android.tradefed.build.content;

import build.bazel.remote.execution.v2.Digest;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Summary of the content analysis. */
public class ContentAnalysisResults {

    private long unchangedFiles = 0;
    private long modifiedFiles = 0;
    private long sharedFolderChanges = 0;
    private long modifiedModules = 0;
    private long buildKeyChanges = 0;
    private long deviceImageChanges = 0;
    private Set<String> unchangedModules = new HashSet<>();
    private Set<String> modifiedModuleNames = new HashSet<>();
    private Map<String, Digest> imageToDigest = new LinkedHashMap<>();

    public ContentAnalysisResults() {}

    public ContentAnalysisResults addUnchangedFile() {
        unchangedFiles++;
        return this;
    }

    public ContentAnalysisResults addModifiedFile() {
        modifiedFiles++;
        return this;
    }

    public ContentAnalysisResults addModifiedSharedFolder(int modifCount) {
        sharedFolderChanges += modifCount;
        return this;
    }

    public ContentAnalysisResults addUnchangedModule(String moduleBaseName) {
        unchangedModules.add(moduleBaseName);
        return this;
    }

    public ContentAnalysisResults addModifiedModule(String moduleBaseName) {
        modifiedModuleNames.add(moduleBaseName);
        modifiedModules++;
        return this;
    }

    public ContentAnalysisResults addImageDigestMapping(String imageFileName, Digest digest) {
        imageToDigest.put(imageFileName, digest);
        return this;
    }

    public ContentAnalysisResults addChangedBuildKey(long count) {
        buildKeyChanges += count;
        return this;
    }

    public ContentAnalysisResults addDeviceImageChanges(long count) {
        deviceImageChanges += count;
        return this;
    }

    /** Returns true if any tests artifact was modified between the base build and current build. */
    public boolean hasAnyTestsChange() {
        if (modifiedFiles > 0
                || sharedFolderChanges > 0
                || modifiedModuleNames.size() > 0
                || buildKeyChanges > 0) {
            return true;
        }
        return false;
    }

    public boolean hasAnyBuildKeyChanges() {
        return buildKeyChanges > 0;
    }

    public boolean hasDeviceImageChanges() {
        return deviceImageChanges > 0;
    }

    public boolean hasSharedFolderChanges() {
        return sharedFolderChanges > 0;
    }

    public Set<String> getUnchangedModules() {
        return unchangedModules;
    }

    public Map<String, Digest> getImageToDigest() {
        return imageToDigest;
    }

    @Override
    public String toString() {
        return "ContentAnalysisResults [unchangedFiles="
                + unchangedFiles
                + ", modifiedFiles="
                + modifiedFiles
                + ", sharedFolderChanges="
                + sharedFolderChanges
                + ", modifiedModules="
                + modifiedModules
                + ", buildKeyChanges="
                + buildKeyChanges
                + ", unchangedModules="
                + unchangedModules
                + "]";
    }

    /** Merges a list of multiple analysis together. */
    public static ContentAnalysisResults mergeResults(List<ContentAnalysisResults> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        ContentAnalysisResults mergedResults = new ContentAnalysisResults();
        for (ContentAnalysisResults res : results) {
            mergedResults.unchangedFiles += res.unchangedFiles;
            mergedResults.modifiedFiles += res.modifiedFiles;
            mergedResults.sharedFolderChanges += res.sharedFolderChanges;
            mergedResults.modifiedModules += res.modifiedModules;
            mergedResults.buildKeyChanges += res.buildKeyChanges;
            mergedResults.unchangedModules.addAll(res.unchangedModules);
            mergedResults.modifiedModuleNames.addAll(res.modifiedModuleNames);
            mergedResults.deviceImageChanges += res.deviceImageChanges;
            mergedResults.imageToDigest.putAll(res.imageToDigest);
        }
        // Re-align what didn't change across analysis.
        mergedResults.unchangedModules.removeAll(mergedResults.modifiedModuleNames);
        return mergedResults;
    }
}
