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

import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.skipped.AnalysisHeuristic;

import com.google.api.client.util.Joiner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Analyzer for device image content analysis */
public class ImageContentAnalyzer {

    private final boolean presubmitMode;
    private final List<ContentAnalysisContext> contexts;
    private final AnalysisHeuristic mAnalysisLevel;

    public ImageContentAnalyzer(
            boolean presubmitMode,
            List<ContentAnalysisContext> contexts,
            AnalysisHeuristic analysisLevel) {
        this.presubmitMode = presubmitMode;
        this.contexts = contexts;
        this.mAnalysisLevel = analysisLevel;
    }

    public ContentAnalysisResults evaluate() {
        List<ContentAnalysisContext> activeContexts = new ArrayList<>(contexts);
        try (CloseableTraceScope ignored = new CloseableTraceScope("image_analysis")) {
            if (presubmitMode) {
                for (ContentAnalysisContext context : contexts) {
                    if (context.contentInformation() != null
                            && !context.contentInformation().currentBuildId.startsWith("P")) {
                        activeContexts.remove(context);
                        CLog.d(
                                "Removing context '%s' from content analysis in presubmit as it's"
                                        + " not a moving head.",
                                context.contentEntry());
                    }
                }
            }
            List<ContentAnalysisContext> buildKeyAnalysis =
                    activeContexts.stream()
                            .filter(
                                    c ->
                                            (AnalysisMethod.BUILD_KEY.equals(c.analysisMethod())
                                                    || AnalysisMethod.DEVICE_IMAGE.equals(
                                                            c.analysisMethod())))
                            .collect(Collectors.toList());
            // Handle invalidation should it be set for a device image.
            for (ContentAnalysisContext context : buildKeyAnalysis) {
                if (AnalysisMethod.DEVICE_IMAGE.equals(context.analysisMethod())
                        && context.abortAnalysis()) {
                    CLog.w(
                            "Analysis was aborted: %s for %s",
                            context.abortReason(), context.contentEntry());
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.ABORT_CONTENT_ANALYSIS, 1);
                    return null;
                }
            }
            ContentAnalysisResults results = new ContentAnalysisResults();
            for (ContentAnalysisContext context : buildKeyAnalysis) {
                switch (context.analysisMethod()) {
                    case BUILD_KEY:
                        boolean hasChanged = buildKeyAnalysis(context);
                        if (hasChanged) {
                            CLog.d(
                                    "build key '%s' has changed or couldn't be evaluated.",
                                    context.contentEntry());
                            results.addChangedBuildKey(1);
                            InvocationMetricLogger.addInvocationMetrics(
                                    InvocationMetricKey.BUILD_KEY_WITH_DIFFS, 1);
                        } else {
                            CLog.d(
                                    "build key '%s' was unchanged.",
                                    context.contentEntry());
                        }
                        break;
                    case DEVICE_IMAGE:
                        long changeCount = deviceImageAnalysis(context);
                        if (changeCount > 0) {
                            CLog.d("device image '%s' has changed.", context.contentEntry());
                            results.addDeviceImageChanges(changeCount);
                        }
                        break;
                    default:
                        break;
                }
            }
            return results;
        }
    }

    /** Returns true if the analysis has differences */
    private boolean buildKeyAnalysis(ContentAnalysisContext context) {
        if (context.abortAnalysis()) {
            CLog.w(
                    "Analysis was aborted for build key %s: %s",
                    context.contentEntry(), context.abortReason());
            return true;
        }
        try {
            List<ArtifactFileDescriptor> diffs =
                    TestContentAnalyzer.analyzeContentDiff(
                            context.contentInformation(), context.contentEntry());
            if (diffs == null) {
                return true;
            }
            // Remove paths that are ignored
            diffs.removeIf(d -> context.ignoredChanges().contains(d.path));
            return !diffs.isEmpty();
        } catch (RuntimeException e) {
            CLog.e(e);
        }
        return true;
    }

    // Analyze the target files as proxy for the device image
    private long deviceImageAnalysis(ContentAnalysisContext context) {
        try {
            List<ArtifactFileDescriptor> diffs =
                    TestContentAnalyzer.analyzeContentDiff(
                            context.contentInformation(), context.contentEntry());
            // Remove paths that are ignored
            diffs.removeIf(d -> context.ignoredChanges().contains(d.path));
            // Remove all build.prop paths
            diffs.removeIf(d -> d.path.endsWith("/build.prop"));
            diffs.removeIf(d -> d.path.endsWith("/prop.default"));
            diffs.removeIf(d -> d.path.endsWith("/default.prop"));
            // Remove all notices they don't change the image
            diffs.removeIf(d -> d.path.endsWith("/etc/NOTICE.xml.gz"));
            // Remove build time flags, we will catch other files that are changing
            diffs.removeIf(d -> d.path.endsWith("/etc/build_flags.json"));
            // Remove all IMAGES/ paths
            diffs.removeIf(d -> d.path.startsWith("IMAGES/"));
            diffs.removeIf(d -> d.path.startsWith("META/"));
            diffs.removeIf(d -> d.path.startsWith("PREBUILT_IMAGES/"));
            diffs.removeIf(d -> d.path.startsWith("RADIO/"));
            if (mAnalysisLevel.ordinal() >= AnalysisHeuristic.REMOVE_EXEMPTION.ordinal()) {
                boolean removed = false;
                // b/335722003
                boolean ota4k =
                        diffs.removeIf(d -> d.path.equals("SYSTEM/boot_otas/boot_ota_4k.zip"));
                boolean ota16k =
                        diffs.removeIf(d -> d.path.equals("SYSTEM/boot_otas/boot_ota_16k.zip"));
                if (ota4k || ota16k) {
                    removed = true;
                }
                if (removed) {
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.DEVICE_IMAGE_USED_HEURISTIC, mAnalysisLevel.name());
                }
            }
            if (diffs.isEmpty()) {
                CLog.d("Device image from '%s' is unchanged", context.contentEntry());
            } else {
                List<String> paths = diffs.stream().map(d -> d.path).collect(Collectors.toList());
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.DEVICE_IMAGE_FILE_CHANGES, Joiner.on(',').join(paths));
            }
            return diffs.size();
        } catch (RuntimeException e) {
            CLog.e(e);
        }
        return 1; // In case of error, skew toward image changing
    }
}
