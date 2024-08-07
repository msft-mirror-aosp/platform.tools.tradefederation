/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.cluster;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.log.LogUtil.CLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A class to model a TestEnvironment message returned by TFC API. */
public class TestEnvironment {

    final Map<String, String> mEnvVars = new HashMap<>();
    final List<String> mSetupScripts = new ArrayList<>();
    final List<String> mOutputFilePatterns = new ArrayList<>();
    String mOutputFileUploadUrl = null;
    boolean mUseSubprocessReporting = false;
    long mInvocationTimeout = 0L;
    long mOutputIdleTimeout = 0L;
    final List<String> mJvmOptions = new ArrayList<>();
    final Map<String, String> mJavaProperties = new HashMap<>();
    String mContextFilePattern = null;
    private final List<String> mExtraContextFiles = new ArrayList<>();
    String mRetryCommandLine = null;
    String mLogLevel = null;
    final List<TradefedConfigObject> mTradefedConfigObjects = new ArrayList<>();
    boolean mUseParallelSetup = false;
    private final List<String> mExcludedFilesInJavaClasspath = new ArrayList<>();
    private final Map<String, String> mBuildAttributes = new HashMap<>();

    /**
     * Adds an environment variable.
     *
     * @param name a variable name.
     * @param value a variable value.
     */
    public void addEnvVar(final String name, final String value) {
        mEnvVars.put(name, value);
    }

    /**
     * Returns a {@link Map} object containing all env vars.
     *
     * @return unmodifiable map of all env vars.
     */
    public Map<String, String> getEnvVars() {
        return Collections.unmodifiableMap(mEnvVars);
    }

    /**
     * Adds a setup script command.
     *
     * @param s a setup script command.
     */
    public void addSetupScripts(final String s) {
        mSetupScripts.add(s);
    }

    /**
     * Returns a list of setup script commands.
     *
     * @return unmodifiable list of commands
     */
    public List<String> getSetupScripts() {
        return Collections.unmodifiableList(mSetupScripts);
    }

    /**
     * Adds an output file pattern.
     *
     * @param s a file pattern.
     */
    public void addOutputFilePattern(final String s) {
        mOutputFilePatterns.add(s);
    }

    /**
     * Returns a list of output file patterns.
     *
     * @return unmodifiable list of file patterns.
     */
    public List<String> getOutputFilePatterns() {
        return Collections.unmodifiableList(mOutputFilePatterns);
    }

    /**
     * Sets an output file upload URL.
     *
     * @param s a URL.
     */
    public void setOutputFileUploadUrl(final String s) {
        mOutputFileUploadUrl = s;
    }

    /**
     * Returns an output file upload URL.
     *
     * @return a URL.
     */
    public String getOutputFileUploadUrl() {
        return mOutputFileUploadUrl;
    }

    /**
     * Returns whether to use subprocess reporting.
     *
     * @return a boolean.
     */
    public boolean useSubprocessReporting() {
        return mUseSubprocessReporting;
    }

    public void setUseSubprocessReporting(boolean f) {
        mUseSubprocessReporting = f;
    }

    /**
     * @return maximum millis to wait for an invocation
     */
    public long getInvocationTimeout() {
        return mInvocationTimeout;
    }

    public void setInvocationTimeout(long value) {
        mInvocationTimeout = value;
    }

    /**
     * @return maximum millis to wait for an idle subprocess
     */
    public long getOutputIdleTimeout() {
        return mOutputIdleTimeout;
    }

    public void setOutputIdleTimeout(long outputIdleTimeout) {
        mOutputIdleTimeout = outputIdleTimeout;
    }

    /**
     * Adds a JVM option.
     *
     * @param s a JVM option.
     */
    public void addJvmOption(final String s) {
        mJvmOptions.add(s);
    }

    /**
     * Returns a list of JVM options.
     *
     * @return unmodifiable list of options
     */
    public List<String> getJvmOptions() {
        return Collections.unmodifiableList(mJvmOptions);
    }

    /**
     * Adds a java property.
     *
     * @param name a property name.
     * @param value a property value.
     */
    public void addJavaProperty(final String name, final String value) {
        mJavaProperties.put(name, value);
    }

    /**
     * Returns a {@link Map} object containing all Java properties.
     *
     * @return unmodifiable map of all runner properties.
     */
    public Map<String, String> getJavaProperties() {
        return Collections.unmodifiableMap(mJavaProperties);
    }

    public String getContextFilePattern() {
        return mContextFilePattern;
    }

    /** Adds a file path to append to the context file. */
    public void addExtraContextFile(String path) {
        mExtraContextFiles.add(path);
    }

    /**
     * @return list of additional file paths to append to context file
     */
    public List<String> getExtraContextFiles() {
        return Collections.unmodifiableList(mExtraContextFiles);
    }

    public String getRetryCommandLine() {
        return mRetryCommandLine;
    }

    public String getLogLevel() {
        return mLogLevel;
    }

    public List<TradefedConfigObject> getTradefedConfigObjects() {
        return Collections.unmodifiableList(mTradefedConfigObjects);
    }

    /**
     * Adds a {@link TradefedConfigObject}.
     *
     * @param obj a {@link TradefedConfigObject}.
     */
    @VisibleForTesting
    void addTradefedConfigObject(TradefedConfigObject obj) {
        mTradefedConfigObjects.add(obj);
    }

    /**
     * Returns whether to use parallel setup.
     *
     * @return a boolean.
     */
    public boolean useParallelSetup() {
        return mUseParallelSetup;
    }

    public void setUseParallelSetup(boolean f) {
        mUseParallelSetup = f;
    }

    public void addExcludedFileInJavaClasspath(final String s) {
        mExcludedFilesInJavaClasspath.add(s);
    }

    /**
     * Returns a list of excluded files in java classpath
     *
     * @return unmodifiable list of files
     */
    public List<String> getExcludedFilesInJavaClasspath() {
        return Collections.unmodifiableList(mExcludedFilesInJavaClasspath);
    }

    @VisibleForTesting
    void addBuildAttribute(String key, String value) {
        mBuildAttributes.put(key, value);
    }

    /**
     * Returns a {@link Map} object containing all build attrbitues.
     *
     * @return unmodifiable map of all build attributes
     */
    public Map<String, String> getBuildAttributes() {
        return Collections.unmodifiableMap(mBuildAttributes);
    }

    private static Map<String, String> loadKeyValuePairs(JSONArray pairs) throws JSONException {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length(); i++) {
            final JSONObject pair = pairs.getJSONObject(i);
            map.put(pair.getString("key"), pair.getString("value"));
        }
        return map;
    }

    public static TestEnvironment fromJson(JSONObject json) throws JSONException {
        TestEnvironment obj = new TestEnvironment();
        final JSONArray envVars = json.optJSONArray("env_vars");
        if (envVars != null) {
            obj.mEnvVars.putAll(loadKeyValuePairs(envVars));
        } else {
            CLog.w("env_vars is null");
        }
        JSONArray jvmOptions = json.optJSONArray("jvm_options");
        if (jvmOptions != null) {
            for (int i = 0; i < jvmOptions.length(); i++) {
                obj.addJvmOption(jvmOptions.getString(i));
            }
        } else {
            CLog.w("jvm_options is null");
        }
        final JSONArray javaProperties = json.optJSONArray("java_properties");
        if (javaProperties != null) {
            obj.mJavaProperties.putAll(loadKeyValuePairs(javaProperties));
        } else {
            CLog.w("java_properties is null");
        }
        final JSONArray scripts = json.optJSONArray("setup_scripts");
        if (scripts != null) {
            for (int i = 0; i < scripts.length(); i++) {
                obj.addSetupScripts(scripts.getString(i));
            }
        } else {
            CLog.w("setup_scripts is null");
        }
        final JSONArray patterns = json.optJSONArray("output_file_patterns");
        if (patterns != null) {
            for (int i = 0; i < patterns.length(); i++) {
                obj.addOutputFilePattern(patterns.getString(i));
            }
        } else {
            CLog.w("output_file_patterns is null");
        }
        final String url = json.optString("output_file_upload_url");
        if (url != null) {
            obj.setOutputFileUploadUrl(url);
        } else {
            CLog.w("output_file_upload_url is null");
        }
        obj.mUseSubprocessReporting = json.optBoolean("use_subprocess_reporting", true);
        obj.mInvocationTimeout = json.optLong("invocation_timeout_millis", 0L);
        obj.mOutputIdleTimeout = json.optLong("output_idle_timeout_millis", 0L);
        obj.mContextFilePattern = json.optString("context_file_pattern");
        JSONArray extraContextFiles = json.optJSONArray("extra_context_files");
        if (extraContextFiles != null) {
            for (int i = 0; i < extraContextFiles.length(); i++) {
                obj.addExtraContextFile(extraContextFiles.getString(i));
            }
        } else {
            CLog.w("extra_context_files is null");
        }
        obj.mRetryCommandLine = json.optString("retry_command_line");
        obj.mLogLevel = json.optString("log_level");
        final JSONArray arr = json.optJSONArray("tradefed_config_objects");
        if (arr != null) {
            obj.mTradefedConfigObjects.addAll(TradefedConfigObject.fromJsonArray(arr));
        }
        obj.mUseParallelSetup = json.optBoolean("use_parallel_setup", true);
        JSONArray excludedFiles = json.optJSONArray("excluded_files_in_java_classpath");
        if (excludedFiles != null) {
            for (int i = 0; i < excludedFiles.length(); i++) {
                obj.addExcludedFileInJavaClasspath(excludedFiles.getString(i));
            }
        } else {
            CLog.w("exclude_files_in_java_classpath is null");
        }
        final JSONArray buildAttributes = json.optJSONArray("build_attributes");
        if (buildAttributes != null) {
            obj.mBuildAttributes.putAll(loadKeyValuePairs(buildAttributes));
        } else {
            CLog.w("build_attributes is null");
        }
        return obj;
    }
}
