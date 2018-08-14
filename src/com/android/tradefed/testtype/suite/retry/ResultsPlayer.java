/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.testtype.suite.retry;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogFile;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.IRemoteTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Special runner that replays the results given to it. */
public final class ResultsPlayer implements IRemoteTest, IInvocationContextReceiver {

    private class ReplayModuleHolder {
        public IInvocationContext mModuleContext;
        public List<Entry<TestDescription, TestResult>> mResults = new ArrayList<>();
    }

    private IInvocationContext mContext;
    private Map<TestRunResult, ReplayModuleHolder> mModuleResult;

    /** Ctor. */
    public ResultsPlayer() {
        mModuleResult = new LinkedHashMap<>();
    }

    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        for (TestRunResult module : mModuleResult.keySet()) {
            ReplayModuleHolder holder = mModuleResult.get(module);

            IInvocationContext moduleContext = holder.mModuleContext;
            if (moduleContext != null) {
                for (String deviceName : mContext.getDeviceConfigNames()) {
                    moduleContext.addAllocatedDevice(deviceName, mContext.getDevice(deviceName));
                    moduleContext.addDeviceBuildInfo(deviceName, mContext.getBuildInfo(deviceName));
                }
                listener.testModuleStarted(moduleContext);
            }

            // Replay full or partial results
            Collection<Entry<TestDescription, TestResult>> testSet = holder.mResults;
            if (testSet.isEmpty()) {
                testSet = module.getTestResults().entrySet();
            }

            forwardTestResults(module, testSet, listener);

            if (moduleContext != null) {
                listener.testModuleEnded();
            }
        }
    }

    /**
     * Register a module to be replayed.
     *
     * @param moduleContext The Context of the module. Or null if it's a simple test run.
     * @param module The results of the test run or module.
     * @param testResult The particular test and its result to replay. Can be null if the full
     *     module should be replayed.
     */
    void addToReplay(
            IInvocationContext moduleContext,
            TestRunResult module,
            Entry<TestDescription, TestResult> testResult) {
        ReplayModuleHolder holder = mModuleResult.get(module);
        if (holder == null) {
            holder = new ReplayModuleHolder();
            holder.mModuleContext = moduleContext;
            mModuleResult.put(module, holder);
        }
        if (testResult != null) {
            holder.mResults.add(testResult);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        mContext = invocationContext;
    }

    private void forwardTestResults(
            TestRunResult module,
            Collection<Entry<TestDescription, TestResult>> testSet,
            ITestInvocationListener listener) {
        listener.testRunStarted(module.getName(), module.getNumTests());
        for (Map.Entry<TestDescription, TestResult> testEntry : testSet) {
            listener.testStarted(testEntry.getKey(), testEntry.getValue().getStartTime());
            switch (testEntry.getValue().getStatus()) {
                case FAILURE:
                    listener.testFailed(testEntry.getKey(), testEntry.getValue().getStackTrace());
                    break;
                case ASSUMPTION_FAILURE:
                    listener.testAssumptionFailure(
                            testEntry.getKey(), testEntry.getValue().getStackTrace());
                    break;
                case IGNORED:
                    listener.testIgnored(testEntry.getKey());
                    break;
                case INCOMPLETE:
                    listener.testFailed(
                            testEntry.getKey(), "Test did not complete due to exception.");
                    break;
                default:
                    break;
            }
            // Provide a strong association of the test to its logs.
            for (Entry<String, LogFile> logFile :
                    testEntry.getValue().getLoggedFiles().entrySet()) {
                if (listener instanceof ILogSaverListener) {
                    ((ILogSaverListener) listener)
                            .logAssociation(logFile.getKey(), logFile.getValue());
                }
            }
            listener.testEnded(
                    testEntry.getKey(),
                    testEntry.getValue().getEndTime(),
                    testEntry.getValue().getProtoMetrics());
        }
        if (module.isRunFailure()) {
            listener.testRunFailed(module.getRunFailureMessage());
        }
        listener.testRunEnded(module.getElapsedTime(), module.getRunProtoMetrics());
    }
}
