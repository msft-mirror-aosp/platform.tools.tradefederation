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
package com.android.tradefed.result.skipped;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.service.IRemoteFeature;
import com.android.tradefed.service.TradefedFeatureClient;
import com.android.tradefed.testtype.ITestInformationReceiver;

import build.bazel.remote.execution.v2.Digest;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.MultiPartResponse;
import com.proto.tradefed.feature.PartResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** A feature allowing to access some of the {@link SkipManager} information. */
public class SkipFeature
        implements IRemoteFeature, IConfigurationReceiver, ITestInformationReceiver {
    public static final String SKIP_FEATURE = "skipFeature";
    public static final String SKIPPED_MODULES = "skipModules";
    public static final String IMAGE_DIGESTS = "imageDigests";
    public static final String PRESUBMIT = "presubmit";
    public static final String DELIMITER_NAME = "delimiter";
    private static final String DELIMITER = "+,";
    private static final String ESCAPED_DELIMITER = "\\+,";
    private IConfiguration mConfig;
    private TestInformation mInfo;

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfig = configuration;
    }

    @Override
    public void setTestInformation(TestInformation testInformation) {
        mInfo = testInformation;
    }

    @Override
    public TestInformation getTestInformation() {
        return mInfo;
    }

    @Override
    public String getName() {
        return SKIP_FEATURE;
    }

    @Override
    public FeatureResponse execute(FeatureRequest request) {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        if (mConfig != null) {
            // Currently only support presubmit
            boolean presubmit = "WORK_NODE".equals(mInfo.getContext().getAttribute("trigger"));
            if (mConfig.getSkipManager().reportSkippedModule()) {
                MultiPartResponse.Builder multiPartBuilder = MultiPartResponse.newBuilder();
                multiPartBuilder.addResponsePart(
                        PartResponse.newBuilder()
                                .setKey(DELIMITER_NAME)
                                .setValue(ESCAPED_DELIMITER));
                multiPartBuilder.addResponsePart(
                        PartResponse.newBuilder()
                                .setKey(PRESUBMIT)
                                .setValue(Boolean.toString(presubmit)));
                multiPartBuilder.addResponsePart(
                        PartResponse.newBuilder()
                                .setKey(SKIPPED_MODULES)
                                .setValue(
                                        Joiner.on(DELIMITER)
                                                .join(
                                                        mConfig.getSkipManager()
                                                                .getUnchangedModules())));
                multiPartBuilder.addResponsePart(
                        PartResponse.newBuilder()
                                .setKey(IMAGE_DIGESTS)
                                .setValue(
                                        Joiner.on(DELIMITER)
                                                .join(
                                                        serializeDigest(
                                                                mConfig.getSkipManager()
                                                                        .getImageToDigest()))));
                responseBuilder.setMultiPartResponse(multiPartBuilder);
            } else {
                responseBuilder.setErrorInfo(
                        ErrorInfo.newBuilder().setErrorTrace("report-module-skipped is disabled."));
            }
        } else {
            responseBuilder.setErrorInfo(
                    ErrorInfo.newBuilder().setErrorTrace("Configuration not set."));
        }
        return responseBuilder.build();
    }

    /** Fetch and populate unchanged modules if needed. */
    public static Set<String> getUnchangedModules() {
        boolean isPresubmit = false;
        Set<String> unchangedModulesSet = new HashSet<>();
        try (TradefedFeatureClient client = new TradefedFeatureClient()) {
            FeatureResponse unchangedModules =
                    client.triggerFeature(SkipFeature.SKIP_FEATURE, new HashMap<String, String>());
            if (unchangedModules.hasMultiPartResponse()) {
                String delimiter = DELIMITER;
                for (PartResponse rep :
                        unchangedModules.getMultiPartResponse().getResponsePartList()) {
                    if (rep.getKey().equals(DELIMITER_NAME)) {
                        delimiter = rep.getValue().trim();
                    }
                }
                for (PartResponse rep :
                        unchangedModules.getMultiPartResponse().getResponsePartList()) {
                    if (rep.getKey().equals(SKIPPED_MODULES)) {
                        unchangedModulesSet.addAll(splitStringFilters(delimiter, rep.getValue()));
                    } else if (rep.getKey().equals(PRESUBMIT)) {
                        isPresubmit = Boolean.parseBoolean(rep.getValue());
                    } else if (rep.getKey().equals(IMAGE_DIGESTS)) {
                        // TODO: parse the digests
                    } else if (rep.getKey().equals(DELIMITER_NAME)) {
                        // Ignore
                    } else {
                        CLog.w("Unexpected response key '%s' for unchanged modules", rep.getKey());
                    }
                }
            } else {
                CLog.w("Unexpected response for unchanged modules: %s", unchangedModules);
            }
        } catch (Exception e) {
            CLog.e(e);
        }
        if (!isPresubmit) {
            return new HashSet<String>();
        }
        return unchangedModulesSet;
    }

    private static List<String> splitStringFilters(String delimiter, String value) {
        if (Strings.isNullOrEmpty(value)) {
            return new ArrayList<String>();
        }
        return Arrays.asList(value.split(delimiter));
    }

    private static List<String> serializeDigest(Map<String, Digest> imageToDigest) {
        List<String> serializedItems = new ArrayList<>();
        for (Entry<String, Digest> entry : imageToDigest.entrySet()) {
            if (entry.getValue() == null) {
                serializedItems.add(String.format("%s=null=null", entry.getKey()));
            } else {
                serializedItems.add(
                        String.format(
                                "%s=%s=%s",
                                entry.getKey(),
                                entry.getValue().getHash(),
                                entry.getValue().getSizeBytes()));
            }
        }
        return serializedItems;
    }

    public static Map<String, Digest> parseDigests(String delimiter, String serializedString)
            throws InvalidProtocolBufferException {
        Map<String, Digest> imageToDigest = new LinkedHashMap<>();
        if (Strings.isNullOrEmpty(serializedString)) {
            return imageToDigest;
        }
        for (String sub : serializedString.split(delimiter)) {
            String[] keyValue = sub.split("=");
            if ("null".equals(keyValue[1])) {
                imageToDigest.put(keyValue[0], null);
            } else {
                imageToDigest.put(
                        keyValue[0],
                        Digest.newBuilder()
                                .setHash(keyValue[1])
                                .setSizeBytes(Long.parseLong(keyValue[2]))
                                .build());
            }
        }
        return imageToDigest;
    }
}
