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
import com.android.tradefed.invoker.TestInvocation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.service.TradefedFeatureClient;
import com.android.tradefed.util.IDisableable;

import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.PartResponse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Based on a variety of criteria the skip manager helps to decide what should be skipped at
 * different levels: invocation, modules and tests.
 */
@OptionClass(alias = "skip-manager")
public class SkipManager implements IDisableable {

    @Option(name = "disable-skip-manager", description = "Disable the skip manager feature.")
    private boolean mIsDisabled = false;

    // Contains the filter and reason for demotion
    private final Map<String, SkipReason> mDemotionFilters = new LinkedHashMap<>();

    /** Setup and initialize the skip manager. */
    public void setup(IConfiguration config, IInvocationContext context) {
        if (TestInvocation.isSubprocess(config)) {
            // Information is going to flow through GlobalFilters mechanism
            return;
        }
        fetchDemotionInformation(context);
    }

    /** Returns the demoted tests and the reason for demotion */
    public Map<String, SkipReason> getDemotedTests() {
        return mDemotionFilters;
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
                    // TODO: Eventually parse the skip reason
                    mDemotionFilters.put(filter, null);
                }
            }
        }
        if (!mDemotionFilters.isEmpty()) {
            CLog.d("Demotion filters size '%s': %s", mDemotionFilters.size(), mDemotionFilters);
        }
    }

    @Override
    public boolean isDisabled() {
        return mIsDisabled;
    }

    @Override
    public void setDisable(boolean isDisabled) {
        mIsDisabled = isDisabled;
    }
}