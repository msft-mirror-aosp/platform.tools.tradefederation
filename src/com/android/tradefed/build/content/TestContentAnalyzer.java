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

import java.io.IOException;
import java.util.List;

/** The analyzer takes context for the analysis and determine what is interesting. */
public class TestContentAnalyzer {

    private final TestInformation information;
    private final ContentAnalysisContext context;

    public TestContentAnalyzer(TestInformation information, ContentAnalysisContext context) {
        this.information = information;
        this.context = context;
    }

    public void evaluate() {
        if (information.getContext().getBuildInfos().size() > 1) {
            CLog.d("Analysis doesn't currently support multi-builds.");
            return;
        }
        InvocationMetricLogger.addInvocationMetrics(
                InvocationMetricKey.CONTENT_BASED_ANALYSIS_ATTEMPT, 1);
        AnalysisMethod method = context.analysisMethod();
        switch (method) {
            case MODULE_XTS:
                xtsAnalysis(information.getBuildInfo(), context);
                break;
            default:
                // do nothing for the rest for now.
        }
    }

    private void xtsAnalysis(IBuildInfo build, ContentAnalysisContext context) {
        if (build.getFile(BuildInfoFileKey.ROOT_DIRECTORY) == null) {
            CLog.d("Mismatch: we would expect a root directory for MODULE_XTS analysis");
            return;
        }
        List<ArtifactFileDescriptor> diffs =
                analyzeContentDiff(context.contentInformation(), context.contentEntry());
        if (diffs == null) {
            CLog.d("Analysis failed.");
            return;
        }
        // TODO: Continue analysis
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
