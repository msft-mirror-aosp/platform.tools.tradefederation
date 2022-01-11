/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.service.IRemoteFeature;
import com.android.tradefed.testtype.ITestInformationReceiver;
import com.android.tradefed.util.StreamUtil;

import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolve a partial download request. */
public class ResolvePartialDownload implements IRemoteFeature, ITestInformationReceiver {

    public static final String RESOLVE_PARTIAL_DOWNLOAD_FEATURE_NAME = "resolvePartialDownload";
    public static final String INCLUDE_FILTERS = "include_filters";
    public static final String EXCLUDE_FILTERS = "exclude_filters";
    public static final String DESTINATION_DIR = "destination_dir";

    private TestInformation mTestInformation;

    @Override
    public void setTestInformation(TestInformation testInformation) {
        mTestInformation = testInformation;
    }

    @Override
    public TestInformation getTestInformation() {
        return mTestInformation;
    }

    @Override
    public String getName() {
        return RESOLVE_PARTIAL_DOWNLOAD_FEATURE_NAME;
    }

    @Override
    public FeatureResponse execute(FeatureRequest request) {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        try {
            DynamicRemoteFileResolver dynamicResolver = new DynamicRemoteFileResolver();
            dynamicResolver.setDevice(mTestInformation.getDevice());

            Map<String, String> args = new HashMap<>(request.getArgsMap());
            String destDir = args.remove(DESTINATION_DIR);
            String includeFilter = args.remove(INCLUDE_FILTERS);
            List<String> includeFilterList = null;
            if (includeFilter != null) {
                includeFilterList = Arrays.asList(includeFilter);
            }
            String excludeFilter = args.remove(EXCLUDE_FILTERS);
            List<String> excludeFilterList = null;
            if (excludeFilter != null) {
                excludeFilterList = Arrays.asList(excludeFilter);
            }

            dynamicResolver.addExtraArgs(args);

            for (File remotePath : mTestInformation.getBuildInfo().getRemoteFiles()) {
                dynamicResolver.resolvePartialDownloadZip(
                        new File(destDir),
                        remotePath.toString(),
                        includeFilterList,
                        excludeFilterList);
            }
        } catch (RuntimeException | BuildRetrievalError e) {
            responseBuilder.setErrorInfo(
                    ErrorInfo.newBuilder().setErrorTrace(StreamUtil.getStackTrace(e)));
        }
        return responseBuilder.build();
    }
}
