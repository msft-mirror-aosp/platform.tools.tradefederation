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
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The analyzer takes context for the analysis and determine what is interesting. */
public class TestContentAnalyzer {

    private final TestInformation information;
    private final ContentAnalysisContext context;

    public TestContentAnalyzer(TestInformation information, ContentAnalysisContext context) {
        this.information = information;
        this.context = context;
    }

    public ContentAnalysisResults evaluate() {
        if (information.getContext().getBuildInfos().size() > 1) {
            CLog.d("Analysis doesn't currently support multi-builds.");
            return null;
        }
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CONTENT_BASED_ANALYSIS_ATTEMPT, 1);
        AnalysisMethod method = context.analysisMethod();
        switch (method) {
            case MODULE_XTS:
                return xtsAnalysis(information.getBuildInfo(), context);
            case FILE:
                return fileAnalysis(information.getBuildInfo(), context);
            default:
                // do nothing for the rest for now.
                return null;
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
        commonDiff.remove(rootPackage + "tools/version.txt");
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

    private List<ArtifactFileDescriptor> analyzeContentDiff(
            ContentInformation information, String entry) {
        try {
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
