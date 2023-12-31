/*
 * Copyright (C) 2017 Google Inc.
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
package com.android.tradefed.testtype.junit4;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ITestLifeCycleReceiver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A builder class for options related to running device tests through BaseHostJUnit4Test. */
public class DeviceTestRunOptions {
    private ITestDevice mDevice; // optional
    private String mRunner = null; // optional
    private final String mPackageName; // required

    private String mTestClassName; // optional
    private String mTestMethodName; // optional
    private String mApkFileName; // optional
    private String[] mInstallArgs; // optional
    private Integer mUserId; // optional
    private Long mTestTimeoutMs = BaseHostJUnit4Test.DEFAULT_TEST_TIMEOUT_MS; // optional
    private Long mMaxTimeToOutputMs =
            BaseHostJUnit4Test.DEFAULT_MAX_TIMEOUT_TO_OUTPUT_MS; // optional
    private Long mMaxInstrumentationTimeoutMs; // optional
    private boolean mCheckResults = true; // optional
    private boolean mDisableHiddenApiCheck = false; // optional
    private boolean mDisableTestApiCheck = true; // optional
    private boolean mDisableIsolatedStorage = false; // optional
    private boolean mDisableWindowAnimation = false; // optional
    private boolean mDisableRestart = false; // optional
    private boolean mGrantPermission = false; // optional
    private boolean mForceQueryable = true; // optional
    private Map<String, String> mInstrumentationArgs = new LinkedHashMap<>(); // optional
    private List<ITestLifeCycleReceiver> mExtraListeners = new ArrayList<>(); // optional

    public DeviceTestRunOptions(String packageName) {
        this.mPackageName = packageName;
    }

    public ITestDevice getDevice() {
        return mDevice;
    }

    public DeviceTestRunOptions setDevice(ITestDevice device) {
        this.mDevice = device;
        return this;
    }

    public String getRunner() {
        return mRunner;
    }

    /**
     * Sets the instrumentation runner that should be used to run the instrumentation. Default
     * runner is 'android.support.test.runner.AndroidJUnitRunner'. Optional.
     */
    public DeviceTestRunOptions setRunner(String runner) {
        this.mRunner = runner;
        return this;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public String getTestClassName() {
        return mTestClassName;
    }

    /**
     * Sets the classname that the instrumentation should run. The instrumentation will be filtered
     * to only run the class. Can be used with {@link #setTestMethodName(String)}. Optional.
     */
    public DeviceTestRunOptions setTestClassName(String testClassName) {
        this.mTestClassName = testClassName;
        return this;
    }

    public String getTestMethodName() {
        return mTestMethodName;
    }

    /**
     * Sets the method name that the instrumentation should run. Requires {@link
     * #setTestClassName(String)} to be set in order to work properly. Optional.
     */
    public DeviceTestRunOptions setTestMethodName(String testMethodName) {
        this.mTestMethodName = testMethodName;
        return this;
    }

    public Integer getUserId() {
        return mUserId;
    }

    /** Sets the user id against which the instrumentation should run. Optional. */
    public DeviceTestRunOptions setUserId(Integer userId) {
        this.mUserId = userId;
        return this;
    }

    public Long getTestTimeoutMs() {
        return mTestTimeoutMs;
    }

    /**
     * Sets the maximum time (in milliseconds) a test can run before being interrupted. Set to 0 for
     * no timeout. Optional.
     */
    public DeviceTestRunOptions setTestTimeoutMs(Long testTimeoutMs) {
        this.mTestTimeoutMs = testTimeoutMs;
        return this;
    }

    public Long getMaxTimeToOutputMs() {
        return mMaxTimeToOutputMs;
    }

    /**
     * Sets the maximum time (in milliseconds) the instrumentation can stop outputting before being
     * stopped. Set to 0 for no timeout. Optional.
     */
    public DeviceTestRunOptions setMaxTimeToOutputMs(Long maxTimeToOutputMs) {
        this.mMaxTimeToOutputMs = maxTimeToOutputMs;
        return this;
    }

    public Long getMaxInstrumentationTimeoutMs() {
        return mMaxInstrumentationTimeoutMs;
    }

    /**
     * Sets the maximum time (in milliseconds) the complete instrumentation will have to run and
     * complete. Set to 0 for no timeout. Optional.
     */
    public DeviceTestRunOptions setMaxInstrumentationTimeoutMs(Long maxInstrumentationTimeoutMs) {
        this.mMaxInstrumentationTimeoutMs = maxInstrumentationTimeoutMs;
        return this;
    }

    public boolean shouldCheckResults() {
        return mCheckResults;
    }

    /**
     * Sets whether or not the results of the instrumentation run should be checked and ensure no
     * failures occured.
     */
    public DeviceTestRunOptions setCheckResults(boolean checkResults) {
        this.mCheckResults = checkResults;
        return this;
    }

    /**
     * sets whether or not to add the --no-hidden-api-checks to the 'am instrument' used from the
     * host side.
     */
    public DeviceTestRunOptions setDisableHiddenApiCheck(boolean disableHiddenApiCheck) {
        this.mDisableHiddenApiCheck = disableHiddenApiCheck;
        return this;
    }

    public boolean isHiddenApiCheckDisabled() {
        return mDisableHiddenApiCheck;
    }

    /**
     * sets whether or not to add the --no-test-api-access to the 'am instrument' used from the host
     * side.
     */
    public DeviceTestRunOptions setDisableTestApiCheck(boolean disableTestApiCheck) {
        this.mDisableTestApiCheck = disableTestApiCheck;
        return this;
    }

    public boolean isTestApiCheckDisabled() {
        return mDisableTestApiCheck;
    }

    /**
     * sets whether or not to add the --no-isolated-storage to the 'am instrument' used from the
     * host side.
     */
    public DeviceTestRunOptions setDisableIsolatedStorage(boolean disableIsolatedStorage) {
        this.mDisableIsolatedStorage = disableIsolatedStorage;
        return this;
    }

    public boolean isIsolatedStorageDisabled() {
        return mDisableIsolatedStorage;
    }

    /**
     * sets whether or not to add the --no-window-animation to the 'am instrument' used from the
     * host side.
     */
    public DeviceTestRunOptions setDisableWindowAnimation(boolean disableWindowAnimation) {
        this.mDisableWindowAnimation = disableWindowAnimation;
        return this;
    }

    public boolean isWindowAnimationDisabled() {
        return mDisableWindowAnimation;
    }

    /** Sets whether or not to add --no-restart to the 'am instrument' used from the host side. */
    public DeviceTestRunOptions setDisableRestart(boolean disableRestart) {
        this.mDisableRestart = disableRestart;
        return this;
    }

    public boolean isRestartDisabled() {
        return mDisableRestart;
    }

    /** Add an argument that will be passed to the instrumentation. */
    public DeviceTestRunOptions addInstrumentationArg(String key, String value) {
        this.mInstrumentationArgs.put(key, value);
        return this;
    }

    /** Add an extra listener to the instrumentation being run. */
    public DeviceTestRunOptions addExtraListener(ITestLifeCycleReceiver listener) {
        this.mExtraListeners.add(listener);
        return this;
    }

    /**
     * Clear all instrumentation arguments that have been set with {@link
     * #addInstrumentationArg(String, String)} previously.
     */
    public void clearInstrumentationArgs() {
        mInstrumentationArgs.clear();
    }

    public Map<String, String> getInstrumentationArgs() {
        return mInstrumentationArgs;
    }

    public List<ITestLifeCycleReceiver> getExtraListeners() {
        return mExtraListeners;
    }

    public void clearExtraListeners() {
        mExtraListeners.clear();
    }

    /** Returns the name of the apk file for the apk installation. */
    public String getApkFileName() {
        return mApkFileName;
    }

    /** Sets the name of the apk file for the apk installation. */
    public DeviceTestRunOptions setApkFileName(String apkFileName) {
        mApkFileName = apkFileName;
        return this;
    }

    /** Returns extra options of the install command. */
    public String[] getInstallArgs() {
        if (mInstallArgs == null) {
            return new String[] {};
        }
        return mInstallArgs;
    }

    /** Sets extra options of the install command. */
    public DeviceTestRunOptions setInstallArgs(String... installArgs) {
        mInstallArgs = installArgs;
        return this;
    }

    /** Whether to grant permissions for the apk installation. */
    public boolean isGrantPermission() {
        return mGrantPermission;
    }

    /** Grants permissions for the apk installation. */
    public DeviceTestRunOptions setGrantPermission(boolean grantPermission) {
        mGrantPermission = grantPermission;
        return this;
    }

    /** Whether or not the apk to be installed should be queryable. The default value is true. */
    public boolean isForceQueryable() {
        return mForceQueryable;
    }

    /** Sets {@code false} if the apk to be installed should not be queryable. */
    public DeviceTestRunOptions setForceQueryable(boolean forceQueryable) {
        mForceQueryable = forceQueryable;
        return this;
    }
}
