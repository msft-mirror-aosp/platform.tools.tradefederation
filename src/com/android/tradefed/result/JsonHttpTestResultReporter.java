/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.DeviceBuildDescriptor;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.net.HttpHelper;
import com.android.tradefed.util.net.IHttpHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A result reporter that encode test metrics results and branch, device info into JSON and POST
 * into an HTTP service endpoint
 */
@OptionClass(alias = "json-reporter")
public class JsonHttpTestResultReporter extends CollectingTestListener {

    /** separator for class name and method name when encoding test identifier */
    private final static String SEPARATOR = "#";
    private final static String RESULT_SEPARATOR = "##";

    /** constants used as keys in JSON results to be posted to remote end */
    private final static String KEY_METRICS = "metrics";
    private final static String KEY_BRANCH = "branch";
    private final static String KEY_BUILD_FLAVOR = "build_flavor";
    private final static String KEY_BUILD_ID = "build_id";
    private final static String KEY_RESULTS_NAME = "results_name";
    private final static String KEY_DEVICE_NAME = "device_name";
    private final static String KEY_SDK_RELEASE_NAME = "sdk_release_name";
    private final static String DEVICE_NAME_PROPERTY = "ro.product.device";
    private final static String SDK_VERSION_PROPERTY = "ro.build.version.sdk";
    private final static String BUILD_ID_PROPERTY = "ro.build.id";
    private final static String SDK_BUILDID_FORMAT = "API_%s_%c";

    /** timeout for HTTP connection to posting endpoint */
    private final static int CONNECTION_TIMEOUT_MS = 60 * 1000;

    @Option(name = "include-run-name", description = "include test run name in reporting unit")
    private boolean mIncludeRunName = false;

    @Option(name = "posting-endpoint", description = "url for the HTTP data posting endpoint",
            importance = Importance.ALWAYS)
    private String mPostingEndpoint;

    @Option(name = "reporting-unit-key-suffix",
            description = "suffix to append after the regular reporting unit key")
    private String mReportingUnitKeySuffix = null;

    @Option(
        name = "skip-failed-runs",
        description = "flag to skip reporting results from failed runs"
    )
    private boolean mSkipFailedRuns = false;

    @Option(name = "include-device-details", description = "Enabling this flag will parse"
            + " additional device details such as device name, sdk version and build id.")
    private boolean mDeviceDetails = false;

    @Option(
            name = "additional-key-value-pairs",
            description = "Map of additional key/value pairs to be added to the results.")
    private Map<String, String> mAdditionalKeyValuePairs = new LinkedHashMap<>();

    private boolean mHasInvocationFailures = false;
    private IInvocationContext mInvocationContext = null;
    private String mDeviceName = null;
    private String mSdkBuildId = null;

    @Override
    public void invocationStarted(IInvocationContext context) {
        super.invocationStarted(context);
        mInvocationContext = context;
    }

    @Override
    public void invocationFailed(Throwable cause) {
        super.invocationFailed(cause);
        mHasInvocationFailures = true;
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        super.invocationEnded(elapsedTime);

        if (mHasInvocationFailures) {
            CLog.d("Skipping reporting beacuse there are invocation failures.");
        } else {
            try {
                if (mDeviceDetails) {
                    parseAdditionalDeviceDetails(getDevice(mInvocationContext));
                }
                postResults(convertMetricsToJson(getMergedTestRunResults()));
            } catch (JSONException e) {
                CLog.e("JSONException while converting test metrics.");
                CLog.e(e);
            }
        }
    }

    protected ITestDevice getDevice(IInvocationContext context) {
        return context.getDevices().get(0);
    }

    /**
     * Retrieves the device name, sdk version number and the build id from
     * the test device.
     *
     * @param testDevice device to collect the information from.
     */
    protected void parseAdditionalDeviceDetails(ITestDevice testDevice) {
        try {
            // Get the device name.
            mDeviceName = testDevice.getProperty(DEVICE_NAME_PROPERTY);

            // Get the version name and the first letter of the build id.
            // Sample output: API_29_Q
            mSdkBuildId = String.format(SDK_BUILDID_FORMAT,
                    testDevice.getProperty(SDK_VERSION_PROPERTY),
                    testDevice.getProperty(BUILD_ID_PROPERTY).charAt(0));
        } catch (DeviceNotAvailableException e) {
            CLog.e("Error in parsing additional additional device info.");
            CLog.e(e);
        }
    }

    /**
     * Post data to the specified HTTP endpoint
     * @param postData data to be posted
     */
    protected void postResults(JSONObject postData) {
        IHttpHelper helper = new HttpHelper();
        OutputStream outputStream = null;
        String data = postData.toString();
        CLog.d("Attempting to post %s: Data: '%s'", mPostingEndpoint, data);
        try {
            HttpURLConnection conn = helper.createJsonConnection(
                    new URL(mPostingEndpoint), "POST");
            conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            conn.setReadTimeout(CONNECTION_TIMEOUT_MS);
            outputStream = conn.getOutputStream();
            outputStream.write(data.getBytes());

            String response = StreamUtil.getStringFromStream(conn.getInputStream()).trim();
            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                // log an error but don't do any explicit exceptions if response code is not 2xx
                CLog.e("Posting failure. code: %d, response: %s", responseCode, response);
            } else {
                CLog.d("Successfully posted results, raw data: %s", postData);
            }
        } catch (IOException e) {
            CLog.e("IOException occurred while reporting to HTTP endpoint: %s", mPostingEndpoint);
            CLog.e(e);
        } finally {
            StreamUtil.close(outputStream);
        }
    }

    /**
     * A util method that converts test metrics and invocation context to json format
     */
    JSONObject convertMetricsToJson(Collection<TestRunResult> runResults) throws JSONException {
        JSONObject allTestMetrics = new JSONObject();

        StringBuffer resultsName = new StringBuffer();
        // loops over all test runs
        for (TestRunResult runResult : runResults) {
            // If the option to skip failed runs is set, skip failed runs.
            if (mSkipFailedRuns && runResult.isRunFailure()) {
                continue;
            }

            // Parse run metrics
            if (runResult.getRunMetrics().size() > 0) {
                JSONObject runResultMetrics = new JSONObject(
                        getValidMetrics(runResult.getRunMetrics()));
                String reportingUnit = runResult.getName();
                if (mReportingUnitKeySuffix != null && !mReportingUnitKeySuffix.isEmpty()) {
                    reportingUnit += mReportingUnitKeySuffix;
                }
                allTestMetrics.put(reportingUnit, runResultMetrics);
                resultsName.append(String.format("%s%s", reportingUnit, RESULT_SEPARATOR));
            }

            // Parse test metrics
            Map<TestDescription, TestResult> testResultMap = runResult.getTestResults();
            for (Entry<TestDescription, TestResult> entry : testResultMap.entrySet()) {
                TestDescription testDescription = entry.getKey();
                TestResult testResult = entry.getValue();
                List<String> reportingUnitParts =
                        Arrays.asList(
                                testDescription.getClassName(), testDescription.getTestName());
                if (mIncludeRunName) {
                    reportingUnitParts.add(0, runResult.getName());
                }
                String reportingUnit = String.join(SEPARATOR, reportingUnitParts);
                if (mReportingUnitKeySuffix != null && !mReportingUnitKeySuffix.isEmpty()) {
                    reportingUnit += mReportingUnitKeySuffix;
                }
                resultsName.append(String.format("%s%s", reportingUnit, RESULT_SEPARATOR));
                if (testResult.getMetrics().size() > 0) {
                    JSONObject testResultMetrics = new JSONObject(
                            getValidMetrics(testResult.getMetrics()));
                    allTestMetrics.put(reportingUnit, testResultMetrics);
                }
            }
        }
        // get build info, and throw an exception if there are multiple (not supporting multi-device
        // result reporting
        List<IBuildInfo> buildInfos = mInvocationContext.getBuildInfos();
        if (buildInfos.isEmpty()) {
            throw new IllegalArgumentException("There is no build info");
        }
        IBuildInfo buildInfo = buildInfos.get(0);
        String buildBranch = buildInfo.getBuildBranch();
        String buildFlavor = buildInfo.getBuildFlavor();
        String buildId = buildInfo.getBuildId();
        if (DeviceBuildDescriptor.describesDeviceBuild(buildInfo)) {
            DeviceBuildDescriptor deviceBuild = new DeviceBuildDescriptor(buildInfo);
            buildBranch = deviceBuild.getDeviceBuildBranch();
            buildFlavor = deviceBuild.getDeviceBuildFlavor();
            buildId = deviceBuild.getDeviceBuildId();
        }
        JSONObject result = new JSONObject();
        result.put(KEY_RESULTS_NAME, resultsName);
        result.put(KEY_METRICS, allTestMetrics);
        result.put(KEY_BRANCH, buildBranch);
        result.put(KEY_BUILD_FLAVOR, buildFlavor);
        result.put(KEY_BUILD_ID, buildId);
        if(mDeviceDetails) {
            result.put(KEY_DEVICE_NAME, mDeviceName);
            result.put(KEY_SDK_RELEASE_NAME, mSdkBuildId);
        }

        if (!mAdditionalKeyValuePairs.isEmpty()) {
            for (Map.Entry<String, String> pair : mAdditionalKeyValuePairs.entrySet()) {
                result.put(pair.getKey(), pair.getValue());
            }
        }

        return result;
    }

    /**
     * Add only the numeric metrics and skip posting the non-numeric metrics.
     *
     * @param collectedMetrics contains all the metrics.
     * @return only the numeric metrics.
     */
    public Map<String, String> getValidMetrics(Map<String, String> collectedMetrics) {
        Map<String, String> validMetrics = new HashMap<>();
        for (Map.Entry<String, String> entry : collectedMetrics.entrySet()) {
            try {
                Double.parseDouble(entry.getValue());
                validMetrics.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                // Skip adding the non numeric metric.
            }
        }
        return validMetrics;
    }
}
