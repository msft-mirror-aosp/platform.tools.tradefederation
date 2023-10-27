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
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NullDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.SystemUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/** A utility that helps analyze the build artifacts for insight. */
public class ArtifactsAnalyzer {

    // A build attribute describing that the device image didn't change from base build
    public static final String DEVICE_IMAGE_NOT_CHANGED = "DEVICE_IMAGE_NOT_CHANGED";

    private final TestInformation information;

    public ArtifactsAnalyzer(TestInformation information) {
        this.information = information;
    }

    public void analyzeArtifacts() {
        if (SystemUtil.isLocalMode()) {
            return;
        }
        List<BuildAnalysis> reports = new ArrayList<>();
        for (Entry<ITestDevice, IBuildInfo> deviceBuild :
                information.getContext().getDeviceBuildMap().entrySet()) {
            BuildAnalysis report = analyzeArtifact(deviceBuild);
            reports.add(report);
        }
        BuildAnalysis finalReport = BuildAnalysis.mergeReports(reports);
        CLog.d("Build analysis report: %s", finalReport.toString());
        if (finalReport.deviceImageChanged()) {
            if (finalReport.hasTestsArtifacts()) {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.TEST_ARTIFACT_CHANGE_ONLY, 1);
            } else {
                InvocationMetricLogger.addInvocationMetrics(
                        InvocationMetricKey.PURE_DEVICE_IMAGE_UNCHANGED, 1);
            }
        }
    }

    private BuildAnalysis analyzeArtifact(Entry<ITestDevice, IBuildInfo> deviceBuild) {
        ITestDevice device = deviceBuild.getKey();
        IBuildInfo build = deviceBuild.getValue();
        boolean deviceImageChanged;
        if (device.getIDevice() != null
                && device.getIDevice().getClass().isAssignableFrom(NullDevice.class)) {
            deviceImageChanged = false; // No device image
        } else {
            deviceImageChanged =
                    "true".equals(build.getBuildAttributes().get(DEVICE_IMAGE_NOT_CHANGED));
        }
        boolean hasTestsArtifacts = true;
        if (build.getFile(BuildInfoFileKey.TESTDIR_IMAGE) == null
                && build.getFile(BuildInfoFileKey.ROOT_DIRECTORY) == null) {
            hasTestsArtifacts = false;
        }
        return new BuildAnalysis(deviceImageChanged, hasTestsArtifacts);
    }
}
