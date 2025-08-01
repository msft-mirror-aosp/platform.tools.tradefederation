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
package com.android.tradefed.testtype;

import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import com.google.common.base.Strings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the results of Google Benchmark that run from shell,
 * and return a map with all the results.
 */
public class GoogleBenchmarkResultParser {

    private String mTestClassName;
    private final ITestInvocationListener mTestListener;

    public GoogleBenchmarkResultParser(String testClassName, ITestInvocationListener listener) {
        mTestClassName = testClassName;
        mTestListener = listener;
    }

    /**
     * Parse an individual output line.
     * name,iterations,real_time,cpu_time,time_unit,bytes_per_second,items_per_second,label,
     * error_occurred,error_message
     *
     * @param cmd_result device command result that contains the test output
     * @return a map containing the number of tests that ran.
     */
    public Map<String, String> parse(CommandResult cmd_result) {
        String outputLogs = cmd_result.getStdout();
        Map<String, String> results = new HashMap<String, String>();
        JSONObject res = null;
        outputLogs = sanitizeOutput(outputLogs);
        try {
            res = new JSONObject(outputLogs);
            // Parse context first
            JSONObject context = res.getJSONObject("context");
            results = parseJsonToMap(context);
        } catch (JSONException e) {
            String testAbortedMessage = causeIfTestAborted(outputLogs);
            boolean isTestAborted = (testAbortedMessage != null);
            if (isTestAborted) {
                String errorMessage =
                        String.format("Test aborted with the error: '%s'", testAbortedMessage);
                CLog.e(errorMessage);
                FailureDescription failure =
                        FailureDescription.create(errorMessage)
                                .setErrorIdentifier(TestErrorIdentifier.TEST_ABORTED);
                mTestListener.testRunFailed(failure);
            } else if (!CommandStatus.SUCCESS.equals(cmd_result.getStatus())) {
                FailureDescription failure;
                if (CommandStatus.TIMED_OUT.equals(cmd_result.getStatus())) {
                    failure =
                            FailureDescription.create("Test timed out.")
                                    .setErrorIdentifier(TestErrorIdentifier.TEST_TIMEOUT);
                } else {
                    failure =
                            FailureDescription.create(cmd_result.getStderr())
                                    .setErrorIdentifier(TestErrorIdentifier.HOST_COMMAND_FAILED);
                }
                mTestListener.testRunFailed(failure);
            } else {
                String parserFailedMessage = "Failed to Parse context:";
                CLog.e(parserFailedMessage);
                CLog.e(e);
                FailureDescription failure =
                        FailureDescription.create(parserFailedMessage)
                                .setCause(e)
                                .setErrorIdentifier(TestErrorIdentifier.OUTPUT_PARSER_ERROR);
                mTestListener.testRunFailed(failure);
            }
            CLog.d("output was:\n%s\n", outputLogs);
            return results;
        }
        try {
            // Benchmark results next
            JSONArray benchmarks = res.getJSONArray("benchmarks");
            for (int i = 0; i < benchmarks.length(); i++) {
                Map<String, String> testResults = new HashMap<String, String>();
                JSONObject testRes = (JSONObject) benchmarks.get(i);
                String name = testRes.getString("name");
                TestDescription testId = new TestDescription(mTestClassName, name);
                mTestListener.testStarted(testId);
                try (CloseableTraceScope ignore = new CloseableTraceScope(testId.toString())) {
                    try {
                        testResults = parseJsonToMap(testRes);
                    } catch (JSONException e) {
                        CLog.e(e);
                        mTestListener.testFailed(
                                testId,
                                String.format(
                                        "Test failed to generate proper results: %s",
                                        e.getMessage()));
                    }
                    // Check iterations to make sure it actual ran
                    String iterations = testResults.get("iterations");
                    if (iterations != null && "0".equals(iterations.trim())) {
                        mTestListener.testIgnored(testId);
                    }

                    if (testRes.has("error_occurred")) {
                        boolean errorOccurred = testRes.getBoolean("error_occurred");
                        if (errorOccurred) {
                            String errorMessage = testResults.get("error_message");
                            if (Strings.isNullOrEmpty(errorMessage)) {
                                mTestListener.testFailed(
                                        testId, "Benchmark reported an unspecified error");
                            } else {
                                mTestListener.testFailed(
                                        testId,
                                        String.format(
                                                "Benchmark reported an error: %s", errorMessage));
                            }
                        }
                    }
                }
                mTestListener.testEnded(testId, TfMetricProtoUtil.upgradeConvert(testResults));
            }
            results.put("Pass", Integer.toString(benchmarks.length()));
        } catch (JSONException e) {
            CLog.e(e);
            results.put("ERROR", e.getMessage());
            mTestListener.testRunFailed(String.format("Failed to parse benchmarks results: %s", e));
        }
        return results;
    }

    /**
     * Check whether the test is aborted and returns the cause if so.
     *
     * @param outputLogs contains the test output
     * @return the cause if the test is aborted, or null otherwize.
     */
    private String causeIfTestAborted(String outputLogs) {
        String errorMessage = null;
        Pattern pattern = Pattern.compile("\\n(.*)\\nAborted\\s+$");
        Matcher matcher = pattern.matcher(outputLogs);
        if (matcher.find()) {
            errorMessage = matcher.group(1).trim();
        }
        return errorMessage;
    }

    /**
     * Helper that go over all json keys and put them in a map with their matching value.
     */
    protected Map<String, String> parseJsonToMap(JSONObject j) throws JSONException {
        Map<String, String> testResults = new HashMap<String, String>();
        Iterator<?> i = j.keys();
        while(i.hasNext()) {
            String key = (String) i.next();
            if (key.endsWith("time")) {
                testResults.put(key + "_" + j.get("time_unit").toString(), j.get(key).toString());
            } else {
                testResults.put(key, j.get(key).toString());
            }
        }
        return testResults;
    }

    /**
     * In some cases a warning is printed before the JSON output. We remove it to avoid parsing
     * failures.
     */
    private String sanitizeOutput(String output) {
        // If it already looks like a proper JSON.
        // TODO: Maybe parse first and if it fails sanitize. Could avoid some failures?
        if (output.startsWith("{")) {
            return output;
        }
        int indexStart = output.indexOf('{');
        if (indexStart == -1) {
            // Nothing we can do here, the parsing will most likely fail afterward.
            CLog.w("Output does not look like a proper JSON.");
            return output;
        }
        String newOuput = output.substring(indexStart);
        CLog.d("We removed the following from the output: '%s'", output.subSequence(0, indexStart));
        return newOuput;
    }
}
