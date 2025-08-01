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
package com.android.tradefed.result.skipped;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.content.ContentAnalysisContext;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.build.content.ContentAnalysisResults;
import com.android.tradefed.build.content.ImageContentAnalyzer;
import com.android.tradefed.build.content.TestContentAnalyzer;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NullDevice;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.suite.SuiteResultCacheUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.SystemUtil;

import build.bazel.remote.execution.v2.Digest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** A utility that helps analyze the build artifacts for insight. */
public class ArtifactsAnalyzer {
    // A build attribute describing that the device image didn't change from base build
    public static final String DEVICE_IMAGE_NOT_CHANGED = "DEVICE_IMAGE_NOT_CHANGED";
    private final TestInformation information;
    private final IConfiguration configuration;
    private final MultiMap<ITestDevice, ContentAnalysisContext> mImageAnalysis;
    private final List<ContentAnalysisContext> mTestArtifactsAnalysisContent;
    private final List<String> mModulesDiscovered;
    private final List<String> mDependencyFiles;
    private final AnalysisHeuristic mAnalysisLevel;

    public ArtifactsAnalyzer(
            TestInformation information,
            IConfiguration configuration,
            MultiMap<ITestDevice, ContentAnalysisContext> imageAnalysis,
            List<ContentAnalysisContext> testAnalysisContexts,
            List<String> moduleDiscovered,
            List<String> dependencyFiles,
            AnalysisHeuristic analysisLevel) {
        this.information = information;
        this.configuration = configuration;
        this.mImageAnalysis = imageAnalysis;
        this.mTestArtifactsAnalysisContent = testAnalysisContexts;
        this.mModulesDiscovered = moduleDiscovered;
        this.mDependencyFiles = dependencyFiles;
        this.mAnalysisLevel = analysisLevel;
    }

    public BuildAnalysis analyzeArtifacts() {
        if (SystemUtil.isLocalMode()) {
            return null;
        }
        List<BuildAnalysis> reports = new ArrayList<>();
        int i = 0;
        for (Entry<ITestDevice, IBuildInfo> deviceBuild :
                information.getContext().getDeviceBuildMap().entrySet()) {
            BuildAnalysis report =
                    analyzeArtifact(
                            deviceBuild,
                            mImageAnalysis.get(deviceBuild.getKey()),
                            configuration.getDeviceConfig().get(i).isFake());
            reports.add(report);
            i++;
        }
        if (reports.size() > 1) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.MULTI_DEVICES_CONTENT_ANALYSIS, reports.size());
        }
        BuildAnalysis finalReport = BuildAnalysis.mergeReports(reports);
        CLog.d("Build analysis report: %s", finalReport.toString());
        boolean presubmit = InvocationContext.isPresubmit(information.getContext());
        // Do the analysis regardless
        if (finalReport.hasTestsArtifacts()) {
            if (mTestArtifactsAnalysisContent.isEmpty()) {
                // Couldn't do analysis, assume changes
                finalReport.setChangesInTests(true);
            } else {
                try (CloseableTraceScope ignored =
                        new CloseableTraceScope(
                                InvocationMetricKey.TestContentAnalyzer.toString())) {
                    TestContentAnalyzer analyzer =
                            new TestContentAnalyzer(
                                    information,
                                    presubmit,
                                    mTestArtifactsAnalysisContent,
                                    mModulesDiscovered,
                                    mDependencyFiles);
                    ContentAnalysisResults analysisResults = analyzer.evaluate();
                    if (analysisResults == null) {
                        finalReport.setChangesInTests(true);
                    } else {
                        CLog.d("%s", analysisResults.toString());
                        finalReport.setChangesInTests(analysisResults.hasAnyTestsChange());
                        if (!analysisResults.hasSharedFolderChanges()) {
                            finalReport.addUnchangedModules(analysisResults.getUnchangedModules());
                        }
                        finalReport.addImageDigestMapping(analysisResults.getImageToDigest());
                    }
                } catch (RuntimeException e) {
                    CLog.e(e);
                    return null;
                }
            }
        }
        CLog.d("Analysis report after test analysis: %s", finalReport.toString());
        return finalReport;
    }

    private BuildAnalysis analyzeArtifact(
            Entry<ITestDevice, IBuildInfo> deviceBuild,
            List<ContentAnalysisContext> context,
            boolean isFake) {
        ITestDevice device = deviceBuild.getKey();
        IBuildInfo build = deviceBuild.getValue();
        Map<String, Digest> imageToDigest = new LinkedHashMap<>();
        boolean deviceImageChanged = true; // anchor toward changing
        if (!isFake
                && device.getIDevice() != null
                && device.getIDevice().getClass().isAssignableFrom(NullDevice.class)) {
            deviceImageChanged = false; // No device image
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEVICELESS_CONTENT_ANALYSIS, 1);
        } else {
            deviceImageChanged =
                    !"true".equals(build.getBuildAttributes().get(DEVICE_IMAGE_NOT_CHANGED));
            if (context != null) {
                boolean presubmit = InvocationContext.isPresubmit(information.getContext());
                boolean hasOneDeviceAnalysis =
                        context.stream()
                                .anyMatch(
                                        c ->
                                                c.analysisMethod()
                                                        .equals(AnalysisMethod.DEVICE_IMAGE));
                ImageContentAnalyzer analyze =
                        new ImageContentAnalyzer(presubmit, context, mAnalysisLevel);
                ContentAnalysisResults res = analyze.evaluate();
                if (res == null) {
                    deviceImageChanged = true;
                    imageToDigest.put(SuiteResultCacheUtil.DEVICE_IMAGE_KEY, null);
                } else {
                    imageToDigest.putAll(res.getImageToDigest());
                    if (hasOneDeviceAnalysis) {
                        if (res.hasDeviceImageChanges()) {
                            CLog.d("Changes in device image.");
                            deviceImageChanged = true;
                        } else {
                            deviceImageChanged = false;
                            InvocationMetricLogger.addInvocationMetrics(
                                    InvocationMetricKey.DEVICE_IMAGE_NOT_CHANGED, 1);
                        }
                    } else if (!deviceImageChanged) {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.DEVICE_IMAGE_NOT_CHANGED, 1);
                    }
                    if (res.hasAnyBuildKeyChanges()) {
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.IMAGE_CHANGES_IN_KEY_FILE, 1);
                        CLog.d("Changes in build key for device image.");
                        deviceImageChanged = true;
                    }
                }
            }
        }
        boolean hasTestsArtifacts = true;
        if (build.getFile(BuildInfoFileKey.TESTDIR_IMAGE) == null
                && build.getFile(BuildInfoFileKey.ROOT_DIRECTORY) == null) {
            hasTestsArtifacts = false;
        }
        return new BuildAnalysis(deviceImageChanged, hasTestsArtifacts)
                .addImageDigestMapping(imageToDigest);
    }
}
