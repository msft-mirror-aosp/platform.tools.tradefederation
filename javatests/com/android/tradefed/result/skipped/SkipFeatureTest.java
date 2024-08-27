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

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.service.TradefedFeatureClient;

import build.bazel.remote.execution.v2.Digest;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Truth;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.PartResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Set;

/** Unit tests for {@link SkipFeature}. */
@RunWith(JUnit4.class)
public class SkipFeatureTest {

    @Mock TradefedFeatureClient mMockClient;
    private SkipFeature mSkipGetter;
    private IConfiguration mConfiguration;
    private TestInformation mTestInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSkipGetter = new SkipFeature();
        mConfiguration = new Configuration("name", "description");
        mTestInfo =
                TestInformation.newBuilder().setInvocationContext(new InvocationContext()).build();
    }

    @Test
    public void testSkipFeature() throws Exception {
        Map<String, Digest> image =
                ImmutableMap.of(
                        "device_image",
                                Digest.newBuilder().setHash("fakehash").setSizeBytes(8).build(),
                        "cvd.tar",
                                Digest.newBuilder().setHash("fakehash2").setSizeBytes(9).build());
        FeatureRequest.Builder builder = FeatureRequest.newBuilder();
        SkipManager skipManager =
                new SkipManager() {
                    @Override
                    public Set<String> getUnchangedModules() {
                        return ImmutableSet.of("module1", "module2");
                    }

                    @Override
                    public Map<String, Digest> getImageToDigest() {
                        return image;
                    }
                };
        OptionSetter setter = new OptionSetter(skipManager);
        setter.setOptionValue("report-module-skipped", "true");

        mConfiguration.setConfigurationObject(Configuration.SKIP_MANAGER_TYPE_NAME, skipManager);
        mSkipGetter.setConfiguration(mConfiguration);
        mSkipGetter.setTestInformation(mTestInfo);
        FeatureResponse response = mSkipGetter.execute(builder.build());
        String skippedModules = null;
        String delimiter = null;
        String imageDigests = null;
        String presubmit = null;
        for (PartResponse partResponse : response.getMultiPartResponse().getResponsePartList()) {
            if (partResponse.getKey().equals(SkipFeature.SKIPPED_MODULES)) {
                skippedModules = partResponse.getValue();
            } else if (partResponse.getKey().equals(SkipFeature.DELIMITER_NAME)) {
                delimiter = partResponse.getValue();
            } else if (partResponse.getKey().equals(SkipFeature.IMAGE_DIGESTS)) {
                imageDigests = partResponse.getValue();
            } else if (partResponse.getKey().equals(SkipFeature.PRESUBMIT)) {
                presubmit = partResponse.getValue();
            }
        }
        Truth.assertThat(delimiter).isEqualTo("\\+,");
        Truth.assertThat(skippedModules).isEqualTo("module1+,module2");
        Truth.assertThat(presubmit).isEqualTo("false");
        Map<String, Digest> parsed = SkipFeature.parseDigests(delimiter, imageDigests);
        Truth.assertThat(parsed).isEqualTo(image);
    }
}
