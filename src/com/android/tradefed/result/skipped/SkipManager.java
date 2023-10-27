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

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.skipped.SkipReason.DemotionTrigger;
import com.android.tradefed.service.TradefedFeatureClient;
import com.android.tradefed.util.IDisableable;

import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.PartResponse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Based on a variety of criteria the skip manager helps to decide what should be skipped at
 * different levels: invocation, modules and tests.
 */
@OptionClass(alias = "skip-manager")
public class SkipManager implements IDisableable {

    @Option(name = "disable-skip-manager", description = "Disable the skip manager feature.")
    private boolean mIsDisabled = false;

    @Option(
            name = "demotion-filters",
            description =
                    "An option to manually inject demotion filters. Intended for testing and"
                            + " validation, not for production demotion.")
    private Map<String, String> mDemotionFilterOption = new LinkedHashMap<>();

    @Option(
            name = "silent-invocation-skip",
            description =
                    "Only report a property for when we would have skipped the invocation instead"
                            + " of actually skipping.")
    private boolean mSilentInvocationSkip = true;

    // Contains the filter and reason for demotion
    private final Map<String, SkipReason> mDemotionFilters = new LinkedHashMap<>();

    private boolean mNoTestsDiscovered = false;

    /** Setup and initialize the skip manager. */
    public void setup(IConfiguration config, IInvocationContext context) {
        if (TestInvocation.isSubprocess(config)) {
            // Information is going to flow through GlobalFilters mechanism
            return;
        }
        for (Entry<String, String> filterReason : mDemotionFilterOption.entrySet()) {
            mDemotionFilters.put(
                    filterReason.getKey(),
                    new SkipReason(filterReason.getValue(), DemotionTrigger.UNKNOWN_TRIGGER));
        }
        fetchDemotionInformation(context);
    }

    /** Returns the demoted tests and the reason for demotion */
    public Map<String, SkipReason> getDemotedTests() {
        return mDemotionFilters;
    }

    /**
     * In the early download and discovery process, report to the skip manager that no tests are
     * expected to be run. This should lead to skipping the invocation.
     */
    public void reportDiscoveryWithNoTests() {
        CLog.d("Test discovery reported that no tests were found.");
        mNoTestsDiscovered = true;
    }

    /** Reports whether we should skip the current invocation. */
    public boolean shouldSkipInvocation(TestInformation information) {
        boolean shouldskip = mNoTestsDiscovered;
        if (!shouldskip) {
            ArtifactsAnalyzer analyzer = new ArtifactsAnalyzer(information);
            shouldskip = buildAnalysisDecision(information, analyzer.analyzeArtifacts());
        }
        // Build heuristic for skipping invocation
        if (mSilentInvocationSkip && shouldskip) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.SILENT_INVOCATION_SKIP_COUNT, 1);
            return false;
        }
        return shouldskip;
    }

    /**
     * Request to fetch the demotion information for the invocation. This should only be done once
     * in the parent process.
     */
    private void fetchDemotionInformation(IInvocationContext context) {
        if (isDisabled()) {
            return;
        }
        if (!"WORK_NODE".equals(context.getAttribute("trigger"))) {
            CLog.d("Skip fetching demotion information in non-presubmit.");
            return;
        }
        try (TradefedFeatureClient client = new TradefedFeatureClient()) {
            Map<String, String> args = new HashMap<>();
            FeatureResponse response = client.triggerFeature("FetchDemotionInformation", args);
            if (!response.hasErrorInfo()) {
                for (PartResponse part : response.getMultiPartResponse().getResponsePartList()) {
                    String filter = part.getKey();
                    mDemotionFilters.put(filter, SkipReason.fromString(part.getValue()));
                }
            }
        }
        if (!mDemotionFilters.isEmpty()) {
            CLog.d("Demotion filters size '%s': %s", mDemotionFilters.size(), mDemotionFilters);
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.DEMOTION_FILTERS_RECEIVED_COUNT, mDemotionFilters.size());
        }
    }

    /** Based on environment of the run and the build analysis, decide to skip or not. */
    private boolean buildAnalysisDecision(TestInformation information, BuildAnalysis results) {
        if (results == null) {
            return false;
        }
        if (!"WORK_NODE".equals(information.getContext().getAttribute("trigger"))) {
            // Eventually support postsubmit analysis.
            return false;
        }
        // Presubmit analysis
        if (results.hasTestsArtifacts()) {
            // TODO: Eventually make the analysis granular to tests artifacts
            return false;
        }
        if (results.deviceImageChanged()) {
            return false;
        }
        return true;
    }

    public void clearManager() {
        mDemotionFilters.clear();
        mDemotionFilterOption.clear();
    }

    @Override
    public boolean isDisabled() {
        return mIsDisabled;
    }

    @Override
    public void setDisable(boolean isDisabled) {
        mIsDisabled = isDisabled;
    }

    public void setSilentInvocationSkip(boolean silentSkip) {
        mSilentInvocationSkip = silentSkip;
    }
}
