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

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.content.ArtifactDetails.ArtifactFileDescriptor;
import com.android.tradefed.build.content.ContentAnalysisContext.AnalysisMethod;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The analyzer takes context for the analysis and determine what is interesting. */
public class TestContentAnalyzer {

    private static final Map<String, List<String>> WORKDIR_COMMON_DIR =
            ImmutableMap.of(
                    "tradefed.zip",
                    new ArrayList<>(),
                    "google-tradefed.zip",
                    new ArrayList<>(),
                    "host-unit-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"),
                    "robolectric-tests.zip",
                    Arrays.asList("host/testcases/android-all/"),
                    "device-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"),
                    "general-tests.zip",
                    Arrays.asList("host/testcases/lib/", "host/testcases/lib64/"));

    private final TestInformation information;
    private final List<ContentAnalysisContext> contexts;

    public TestContentAnalyzer(TestInformation information, List<ContentAnalysisContext> contexts) {
        this.information = information;
        this.contexts = contexts;
    }

    public ContentAnalysisResults evaluate() {
        if (information.getContext().getBuildInfos().size() > 1) {
            CLog.d("Analysis doesn't currently support multi-builds.");
            return null;
        }
        try (CloseableTraceScope ignored = new CloseableTraceScope("content_analysis")) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.CONTENT_BASED_ANALYSIS_ATTEMPT, 1);
            AnalysisMethod method = contexts.get(0).analysisMethod();
            switch (method) {
                case MODULE_XTS:
                    return xtsAnalysis(information.getBuildInfo(), contexts.get(0));
                case FILE:
                    return fileAnalysis(information.getBuildInfo(), contexts.get(0));
                case SANDBOX_WORKDIR:
                    return workdirAnalysis(information.getBuildInfo(), contexts);
                default:
                    // do nothing for the rest for now.
                    return null;
            }
        }
    }

    private ContentAnalysisResults xtsAnalysis(IBuildInfo build, ContentAnalysisContext context) {
        if (build.getFile(BuildInfoFileKey.ROOT_DIRECTORY) == null) {
            CLog.d("Mismatch: we would expect a root directory for MODULE_XTS analysis");
            return null;
        }
        List<ArtifactFileDescriptor> diffs =
                analyzeContentDiff(context.contentInformation(), context.contentEntry());
        if (diffs == null) {
            CLog.d("Analysis failed.");
            return null;
        }
        diffs.removeIf(d -> context.ignoredChanges().contains(d.path));
        return mapDiffsToModule(
                context.contentEntry(), diffs, build.getFile(BuildInfoFileKey.ROOT_DIRECTORY));
    }

    private ContentAnalysisResults mapDiffsToModule(
            String contentEntry, List<ArtifactFileDescriptor> diffs, File rootDir) {
        ContentAnalysisResults results = new ContentAnalysisResults();
        String rootPackage = contentEntry.replaceAll(".zip", "");
        Set<String> diffPaths = diffs.parallelStream().map(d -> d.path).collect(Collectors.toSet());
        // First check common packages
        Set<String> commonDiff =
                diffPaths.parallelStream()
                        .filter(p -> p.startsWith(rootPackage + "/tools/"))
                        .collect(Collectors.toSet());
        // Exclude version.txt has it always change
        commonDiff.remove(rootPackage + "/tools/version.txt");
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.XTS_DIFFS_IN_COMMON, commonDiff.size());
        results.addModifiedSharedFolder(commonDiff.size());
        if (!commonDiff.isEmpty()) {
            CLog.d("Tools folder has diffs: %s", commonDiff);
        }
        File testcasesRoot = FileUtil.findFile(rootDir, "testcases");
        if (testcasesRoot == null) {
            CLog.e("Could find a testcases directory, something went wrong.");
            return null;
        }
        // Then check changes in modules
        for (File rootFile : testcasesRoot.listFiles()) {
            if (rootFile.isDirectory()) {
                File moduleDir = rootFile;
                String relativeModulePath =
                        String.format("%s/testcases/%s/", rootPackage, moduleDir.getName());
                Set<String> moduleDiff =
                        diffPaths.parallelStream()
                                .filter(p -> p.startsWith(relativeModulePath))
                                .collect(Collectors.toSet());
                if (moduleDiff.isEmpty()) {
                    CLog.d("Module %s directory is unchanged.", moduleDir.getName());
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.XTS_UNCHANGED_MODULES, 1);
                    results.addUnchangedModule(moduleDir.getName());
                } else {
                    CLog.d("Module %s directory has changed: %s", moduleDir.getName(), moduleDiff);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.XTS_MODULE_WITH_DIFFS, 1);
                    results.addModifiedModule();
                }
            } else {
                String relativeRootFilePath =
                        String.format("%s/testcases/%s", rootPackage, rootFile.getName());
                Set<String> rootFileDiff =
                        diffPaths.parallelStream()
                                .filter(p -> p.equals(relativeRootFilePath))
                                .collect(Collectors.toSet());
                if (rootFileDiff.isEmpty()) {
                    CLog.d("File %s is unchanged.", rootFile.getName());
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.UNCHANGED_FILE, 1);
                    results.addUnchangedFile();
                } else {
                    CLog.d("File %s has changed: %s", rootFile.getName(), rootFileDiff);
                    InvocationMetricLogger.addInvocationMetrics(
                            InvocationMetricKey.FILE_WITH_DIFFS, 1);
                    results.addModifiedFile();
                }
            }
        }
        return results;
    }

    private ContentAnalysisResults fileAnalysis(IBuildInfo build, ContentAnalysisContext context) {
        if (build.getFile(BuildInfoFileKey.TESTDIR_IMAGE) == null) {
            CLog.w("Mismatch: we would expect a testsdir directory for FILE analysis");
            return null;
        }
        List<ArtifactFileDescriptor> diffs =
                analyzeContentDiff(context.contentInformation(), context.contentEntry());
        if (diffs == null) {
            CLog.w("Analysis failed.");
            return null;
        }
        diffs.removeIf(d -> context.ignoredChanges().contains(d.path));
        Set<String> diffPaths = diffs.parallelStream().map(d -> d.path).collect(Collectors.toSet());
        File rootDir = build.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        Set<Path> files = new HashSet<>();
        try (Stream<Path> stream =
                Files.walk(Paths.get(rootDir.getAbsolutePath()), FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(path -> !path.toFile().isDirectory()).forEach(path -> files.add(path));
        } catch (IOException e) {
            CLog.e("Analysis failed.");
            CLog.e(e);
            return null;
        }
        // Match the actual downloaded files with possible differences in content to under
        // how much of the downloaded artifacts actually changed.
        ContentAnalysisResults results = new ContentAnalysisResults();
        for (Path p : files) {
            Path relativeRootFilePath = rootDir.toPath().relativize(p);
            Set<String> fileDiff =
                    diffPaths.parallelStream()
                            .filter(diffPath -> diffPath.equals(relativeRootFilePath.toString()))
                            .collect(Collectors.toSet());
            if (fileDiff.isEmpty()) {
                CLog.d("File %s is unchanged.", p);
                InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.UNCHANGED_FILE, 1);
                results.addUnchangedFile();
            } else {
                CLog.d("File %s has changed: %s", p, fileDiff);
                InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.FILE_WITH_DIFFS, 1);
                results.addModifiedFile();
            }
        }
        return results;
    }

    private ContentAnalysisResults workdirAnalysis(
            IBuildInfo build, List<ContentAnalysisContext> contexts) {
        if (build.getFile(BuildInfoFileKey.TESTDIR_IMAGE) == null) {
            CLog.w("Mismatch: we would expect a testsdir directory for workdir analysis");
            return null;
        }
        File testsDirRoot = build.getFile(BuildInfoFileKey.TESTDIR_IMAGE);
        ContentAnalysisResults results = new ContentAnalysisResults();
        List<ArtifactFileDescriptor> diffs = new ArrayList<>();
        List<String> entryNames = new ArrayList<>();
        Set<String> AllCommonDirs = new HashSet<>();
        for (ContentAnalysisContext context : contexts) {
            if (context.abortAnalysis()) {
                CLog.w("Analysis was aborted.");
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.ABORT_CONTENT_ANALYSIS, 1);
                return null;
            }
            List<ArtifactFileDescriptor> diff =
                    analyzeContentDiff(context.contentInformation(), context.contentEntry());
            if (diff == null) {
                CLog.w("Analysis failed.");
                return null;
            }
            entryNames.add(context.contentEntry());
            diffs.addAll(diff);
            diffs.removeIf(d -> context.ignoredChanges().contains(d.path));
            AllCommonDirs.addAll(context.commonLocations());
        }
        // Obtain common dirs for situation
        for (String entry : entryNames) {
            List<String> commonDirs = WORKDIR_COMMON_DIR.get(entry);
            if (commonDirs == null) {
                CLog.w(
                        "Did not find a common dir for %s, we might not support this analysis yet.",
                        entry);
                return null;
            }
            AllCommonDirs.addAll(commonDirs);
        }
        Set<String> diffPaths = diffs.parallelStream().map(d -> d.path).collect(Collectors.toSet());
        // Check common dirs
        Set<String> commonDiff =
                diffPaths.parallelStream()
                        .filter(
                                p -> {
                                    for (String common : AllCommonDirs) {
                                        if (p.startsWith(common)) {
                                            return true;
                                        }
                                    }
                                    return false;
                                })
                        .collect(Collectors.toSet());
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.WORKDIR_DIFFS_IN_COMMON, commonDiff.size());
        results.addModifiedSharedFolder(commonDiff.size());
        if (!commonDiff.isEmpty()) {
            CLog.d("Common folder has diffs: %s", commonDiff);
        }
        // check diffs against modules
        try {
            Set<File> testCasesDirs = FileUtil.findFilesObject(testsDirRoot, "testcases");
            for (File testCasesDir : testCasesDirs) {
                if (!testCasesDir.isDirectory()) {
                    CLog.w("Found a non directory testcases directory: %s", testCasesDir);
                    continue;
                }
                Path relativeRootFilePath = testsDirRoot.toPath().relativize(testCasesDir.toPath());
                for (File moduleDir : testCasesDir.listFiles()) {
                    String relativeModulePath =
                            String.format(
                                    "%s/%s/", relativeRootFilePath.toString(), moduleDir.getName());
                    if (AllCommonDirs.contains(relativeModulePath)) {
                        continue;
                    }
                    Set<String> moduleDiff =
                            diffPaths.parallelStream()
                                    .filter(p -> p.startsWith(relativeModulePath))
                                    .collect(Collectors.toSet());
                    if (moduleDiff.isEmpty()) {
                        CLog.d("Module %s directory is unchanged.", moduleDir.getName());
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.WORKDIR_UNCHANGED_MODULES, 1);
                        results.addUnchangedModule(moduleDir.getName());
                    } else {
                        CLog.d(
                                "Module %s directory has changed: %s",
                                moduleDir.getName(), moduleDiff);
                        InvocationMetricLogger.addInvocationMetrics(
                                InvocationMetricKey.WOKRDIR_MODULE_WITH_DIFFS, 1);
                        results.addModifiedModule();
                    }
                }
            }
        } catch (IOException e) {
            CLog.e(e);
            return null;
        }
        return results;
    }

    private List<ArtifactFileDescriptor> analyzeContentDiff(
            ContentInformation information, String entry) {
        try (CloseableTraceScope ignored = new CloseableTraceScope("analyze_content_diff")) {
            ArtifactDetails base = ArtifactDetails.parseFile(information.baseContent, entry);
            ArtifactDetails presubmit =
                    ArtifactDetails.parseFile(information.currentContent, entry);
            List<ArtifactFileDescriptor> diffs = ArtifactDetails.diffContents(base, presubmit);
            CLog.d("Analysis of '%s' shows %s diffs with base", entry, diffs.size());
            return diffs;
        } catch (IOException | RuntimeException e) {
            CLog.e(e);
        }
        return null;
    }
}
