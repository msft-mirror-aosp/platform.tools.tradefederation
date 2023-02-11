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
package com.android.tradefed.invoker.logger;

import com.android.tradefed.log.LogUtil.CLog;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A utility class for an invocation to log some metrics. */
public class InvocationMetricLogger {

    /** Some special named key that we will always populate for the invocation. */
    public enum InvocationMetricKey {
        WIFI_AP_NAME("wifi_ap_name", false),
        WIFI_CONNECT_TIME("wifi_connect_time", true),
        WIFI_CONNECT_COUNT("wifi_connect_count", true),
        WIFI_CONNECT_RETRY_COUNT("wifi_connect_retry_count", true),
        // Bugreport time and count
        BUGREPORT_TIME("bugreport_time", true),
        BUGREPORT_COUNT("bugreport_count", true),
        ANR_TIME("anr_time", true),
        ANR_COUNT("anr_count", true),
        // Logcat dump time and count
        LOGCAT_DUMP_TIME("logcat_dump_time", true),
        LOGCAT_DUMP_COUNT("logcat_dump_count", true),
        CLEARED_RUN_ERROR("cleared_run_error", true),
        FETCH_BUILD("fetch_build_time_ms", true),
        SETUP("setup_time_ms", true),
        SHARDING_DEVICE_SETUP_TIME("remote_device_sharding_setup_ms", true),
        AUTO_RETRY_TIME("auto_retry_time_ms", true),
        BACKFILL_BUILD_INFO("backfill_build_info", false),
        STAGE_TESTS_TIME("stage_tests_time_ms", true),
        STAGE_REMOTE_TIME("stage_remote_time_ms", true),
        STAGE_TESTS_BYTES("stage_tests_bytes", true),
        STAGE_TESTS_INDIVIDUAL_DOWNLOADS("stage_tests_individual_downloads", true),
        SERVER_REFERENCE("server_reference", false),
        INSTRUMENTATION_RERUN_FROM_FILE("instrumentation_rerun_from_file", true),
        INSTRUMENTATION_RERUN_SERIAL("instrumentation_rerun_serial", true),
        DOWNLOAD_RETRY_COUNT("download_retry_count", true),
        METADATA_RETRY_COUNT("metadata_retry_count", true),
        XTS_STAGE_TESTS_TIME("xts_stage_tests_time_ms", true),
        XTS_STAGE_TESTS_BYTES("xts_stage_tests_bytes", true),
        XTS_PARTIAL_DOWNLOAD_SUCCESS_COUNT("xts_partial_download_success_count", true),
        XTS_PARTIAL_DOWNLOAD_UNSUPPORTED_FILTER_FALLBACK_COUNT(
                "xts_partial_download_unsupported_filter_fallback_count", true),
        XTS_PARTIAL_DOWNLOAD_FALLBACK_COUNT("xts_partial_download_fallback_count", true),
        XTS_PARTIAL_DOWNLOAD_UNFOUND_MODULES("xts_partial_download_unfound_modules", true),
        XTS_PARTIAL_DOWNLOAD_TOTAL_COUNT("xts_partial_download_total_count", true),
        SANDBOX_JAR_STAGING_PARTIAL_DOWNLOAD_FEATURE_COUNT(
                "sandbox_jar_staging_partial_download_FEATURE_count", true),
        SANDBOX_JAR_STAGING_PARTIAL_DOWNLOAD_SUCCESS_COUNT(
                "sandbox_jar_staging_partial_download_SUCCESS_count", true),
        // -- Disk memory usage --
        // Approximate peak disk space usage of the invocation
        // Represent files that would usually live for the full invocation (min usage)
        TEAR_DOWN_DISK_USAGE("teardown_disk_usage_bytes", false),
        // Recovery Mode
        AUTO_RECOVERY_MODE_COUNT("recovery_mode_count", true),
        ATTEMPT_RECOVERY_LOG_COUNT("attempt_pull_recovery_log", true),
        // Represents the time we spend attempting to recover a device.
        RECOVERY_TIME("recovery_time", true),
        // Represents how often we enter the recover device routine.
        RECOVERY_ROUTINE_COUNT("recovery_routine_count", true),
        // Represents the time we spend attempting to "adb root" a device.
        ADB_ROOT_TIME("adb_root_time", true),
        // Represents how often we enter the "adb root" device routine.
        ADB_ROOT_ROUTINE_COUNT("adb_root_routine_count", true),
        // Represents the time we spend attempting to reboot a device.
        ADB_REBOOT_TIME("adb_reboot_time", true),
        // Represents how often we attempt to reboot the device.
        ADB_REBOOT_ROUTINE_COUNT("adb_reboot_routine_count", true),
        // Represents the time attempting to reboot a device into bootloader
        BOOTLOADER_REBOOT_TIME("bootloader_reboot_time", true),
        // Represents how often we attempt to reboot the device into bootloader
        BOOTLOADER_REBOOT_COUNT("bootloader_reboot_count", true),
        // Represents the time attempting to reboot a device into fastbootd
        FASTBOOTD_REBOOT_TIME("fastbootd_reboot_time", true),
        // Represents how often we attempt to reboot the device into fastbootd
        FASTBOOTD_REBOOT_COUNT("fastbootd_reboot_count", true),
        // Represents how often we reboot a device already in bootloader
        BOOTLOADER_SAME_STATE_REBOOT("bootloader_same_state_reboot", true),
        // Represents the time we spend during postboot setup
        POSTBOOT_SETUP_TIME("postboot_setup_time", true),
        // Represents how often we go through postboot setup
        POSTBOOT_SETUP_COUNT("postboot_setup_count", true),
        // Represents the time we spend during postboot wifi setup
        POSTBOOT_WIFI_SETUP_TIME("postboot_wifi_setup_time", true),
        // Represents how often we go through postboot wifi setup
        POSTBOOT_WIFI_SETUP_COUNT("postboot_wifi_setup_count", true),
        // Represents the time we spend during md5 calculation
        MD5_CALCULATION_TIME("md5_calculation_time", true),
        // Represents how often we go through md5 calculation
        MD5_CALCULATION_COUNT("md5_calculation_count", true),

        // Represents the time we spend pulling file from device.
        PULL_FILE_TIME("pull_file_time_ms", true),
        // Represents how many times we pulled file from the device.
        PULL_FILE_COUNT("pull_file_count", true),
        // Represents the time we spend pulling dir from device.
        PULL_DIR_TIME("pull_dir_time_ms", true),
        // Represents how many times we pulled dir from the device.
        PULL_DIR_COUNT("pull_dir_count", true),
        // Represents the time we spend pushing file from device.
        PUSH_FILE_TIME("push_file_time_ms", true),
        // Represents how many times we pushed file from the device.
        PUSH_FILE_COUNT("push_file_count", true),
        // Represents the time we spend pushing dir from device.
        PUSH_DIR_TIME("push_dir_time_ms", true),
        // Represents how many times we pushing dir from the device.
        PUSH_DIR_COUNT("push_dir_count", true),
        // Represents the time we spent deleting file on device
        DELETE_DEVICE_FILE_TIME("delete_device_file_time_ms", true),
        // Represents how many times we call the delete file method
        DELETE_DEVICE_FILE_COUNT("delete_device_file_count", true),
        DOES_FILE_EXISTS_TIME("does_file_exists_time_ms", true),
        DOES_FILE_EXISTS_COUNT("does_file_exists_count", true),
        // Represents the time and count for installing packages
        PACKAGE_INSTALL_TIME("package_install_time_ms", true),
        PACKAGE_INSTALL_COUNT("package_install_count", true),
        // Capture the time spent isolating a retry with reset
        RESET_RETRY_ISOLATION_PAIR("reset_isolation_timestamp_pair", true),
        // Capture the time spent isolating a retry with reboot
        REBOOT_RETRY_ISOLATION_PAIR("reboot_isolation_timestamp_pair", true),
        // The time spent inside metric collectors
        COLLECTOR_TIME("collector_time_ms", true),
        // Track if soft restart is occurring after test module
        SOFT_RESTART_AFTER_MODULE("soft_restart_after_module", true),
        CLOUD_DEVICE_PROJECT("cloud_device_project", false),
        CLOUD_DEVICE_MACHINE_TYPE("cloud_device_machine_type", false),
        CLOUD_DEVICE_ZONE("cloud_device_zone", false),
        CLOUD_DEVICE_STABLE_HOST_IMAGE("stable_host_image_name", false),
        CLOUD_DEVICE_STABLE_HOST_IMAGE_PROJECT("stable_host_image_project", false),

        SHUTDOWN_BEFORE_TEST("shutdown_before_test", false),
        SHUTDOWN_AFTER_TEST("shutdown_after_test", false),
        SHUTDOWN_LATENCY("shutdown_latency_ms", false),
        SHUTDOWN_HARD_LATENCY("shutdown_hard_latency_ms", false),
        DEVICE_COUNT("device_count", false),
        DEVICE_DONE_TIMESTAMP("device_done_timestamp", false),
        DEVICE_RELEASE_STATE("device_release_state", false),
        DEVICE_LOST_DETECTED("device_lost_detected", false),
        VIRTUAL_DEVICE_LOST_DETECTED("virtual_device_lost_detected", false),
        // Count the number of time device recovery like usb reset are successful.
        DEVICE_RECOVERY("device_recovery", true),
        DEVICE_RECOVERY_FROM_RECOVERY("device_recovery_from_recovery", true),
        DEVICE_RECOVERY_FAIL("device_recovery_fail", true),
        SANDBOX_EXIT_CODE("sandbox_exit_code", false),
        CF_FETCH_ARTIFACT_TIME("cf_fetch_artifact_time_ms", false),
        CF_GCE_CREATE_TIME("cf_gce_create_time_ms", false),
        CF_LAUNCH_CVD_TIME("cf_launch_cvd_time_ms", false),
        CF_INSTANCE_COUNT("cf_instance_count", false),
        CF_OXYGEN_SERVER_URL("cf_oxygen_server_url", false),
        CF_OXYGEN_SESSION_ID("cf_oxygen_session_id", false),
        CF_OXYGEN_VERSION("cf_oxygen_version", false),
        CRASH_FAILURES("crash_failures", true),
        UNCAUGHT_CRASH_FAILURES("uncaught_crash_failures", true),
        TEST_CRASH_FAILURES("test_crash_failures", true),
        UNCAUGHT_TEST_CRASH_FAILURES("uncaught_test_crash_failures", true),
        DEVICE_RESET_COUNT("device_reset_count", true),
        DEVICE_RESET_MODULES("device_reset_modules", true),
        DEVICE_RESET_MODULES_FOR_TARGET_PREPARER("device_reset_modules_for_target_preparer", true),
        NONPERSISTENT_DEVICE_PROPERTIES("nonpersistent_device_properties", true),
        PERSISTENT_DEVICE_PROPERTIES("persistent_device_properties", true),
        INVOCATION_START("tf_invocation_start_timestamp", false),
        LOAD_TEST_CONFIGS_TIME("load_test_configs_time_ms", true),
        // Track the way of requesting Oxygen device lease/release.
        OXYGEN_DEVICE_LEASE_THROUGH_ACLOUD_COUNT("oxygen_device_lease_through_acloud_count", true),
        OXYGEN_DEVICE_RELEASE_THROUGH_ACLOUD_COUNT(
                "oxygen_device_release_through_acloud_count", true),
        OXYGEN_DEVICE_DIRECT_LEASE_COUNT("oxygen_device_direct_lease_count", true),
        OXYGEN_DEVICE_DIRECT_RELEASE_COUNT("oxygen_device_direct_release_count", true),

        DYNAMIC_FILE_RESOLVER_PAIR("tf_dynamic_resolver_pair_timestamp", true),
        ARTIFACTS_DOWNLOAD_SIZE("tf_artifacts_download_size_bytes", true),
        ARTIFACTS_UPLOAD_SIZE("tf_artifacts_upload_size_bytes", true),
        LOG_SAVING_TIME("log_saving_time", true),
        LOG_SAVING_COUNT("log_saving_count", true),
        // TODO: Delete start/end timestamp in favor of pair.
        FETCH_BUILD_START("tf_fetch_build_start_timestamp", false),
        FETCH_BUILD_END("tf_fetch_build_end_timestamp", false),
        FETCH_BUILD_PAIR("tf_fetch_build_pair_timestamp", true),
        // TODO: Delete start/end timestamp in favor of pair.
        SETUP_START("tf_setup_start_timestamp", false),
        SETUP_END("tf_setup_end_timestamp", false),
        SETUP_PAIR("tf_setup_pair_timestamp", true),
        TEST_SETUP_PAIR("tf_test_setup_pair_timestamp", true),
        FLASHING_FROM_FASTBOOTD("flashing_from_fastbootd", true),
        FLASHING_TIME("flashing_time_ms", true),
        FLASHING_PERMIT_LATENCY("flashing_permit_latency_ms", true),
        FLASHING_METHOD("flashing_method", false),
        DOWNLOAD_PERMIT_LATENCY("download_permit_latency_ms", true),
        // Unzipping metrics
        UNZIP_TESTS_DIR_TIME("unzip_tests_dir_time_ms", true),
        UNZIP_TESTS_DIR_COUNT("unzip_tests_dir_count", true),
        // Don't aggregate test pair, latest report wins because it's the closest to
        // the execution like in a subprocess.
        TEST_PAIR("tf_test_pair_timestamp", false),
        // TODO: Delete start/end timestamp in favor of pair.
        TEARDOWN_START("tf_teardown_start_timestamp", false),
        TEARDOWN_END("tf_teardown_end_timestamp", false),
        TEARDOWN_PAIR("tf_teardown_pair_timestamp", true),
        TEST_TEARDOWN_PAIR("tf_test_teardown_pair_timestamp", true),

        INVOCATION_END("tf_invocation_end_timestamp", false),

        MODULE_SETUP_PAIR("tf_module_setup_pair_timestamp", true),
        MODULE_TEARDOWN_PAIR("tf_module_teardown_pair_timestamp", true),

        LAB_PREPARER_NOT_ILAB("lab_preparer_not_ilab", true),
        TARGET_PREPARER_IS_ILAB("target_preparer_is_ilab", true),

        ART_RUN_TEST_CHECKER_COMMAND_TIME_MS("art_run_test_checker_command_time_ms", true),

        // CAS downloader metrics
        // Name of files downloaded by CAS downloader.
        CAS_DOWNLOAD_FILES("cas_download_files", true),
        CAS_DOWNLOAD_FILE_SUCCESS_COUNT("cas_download_file_success_count", true),
        CAS_DOWNLOAD_FILE_FAIL_COUNT("cas_download_file_fail_count", true),
        CAS_DOWNLOAD_TIME("cas_download_time_ms", true),
        // Records the wait time caused by CAS downloader concurrency limitation.
        CAS_DOWNLOAD_WAIT_TIME("cas_download_wait_time_ms", true),
        // Records cache hit metrics
        CAS_DOWNLOAD_HOT_BYTES("cas_download_hot_bytes", true),
        CAS_DOWNLOAD_COLD_BYTES("cas_download_cold_bytes", true),

        // Download Cache
        CACHE_HIT_COUNT("cache_hit_count", true),

        // CF Cache metrics
        CF_CACHE_WAIT_TIME("cf_cache_wait_time_sec", false),
        CF_ARTIFACTS_FETCH_SOURCE("cf_artifacts_fetch_source", false),

        // Ab downloader metrics
        AB_DOWNLOAD_SIZE_ELAPSED_TIME("ab_download_size_elapsed_time", true),

        HAS_ANY_RUN_FAILURES("has_any_run_failures", false),
        TOTAL_TEST_COUNT("total_test_count", true),

        // Metrics to store Device failure signatures
        DEVICE_ERROR_SIGNATURES("device_failure_signatures", true),

        // Following are trace events also reporting as metrics
        invocation_warm_up("invocation_warm_up", true),
        dynamic_download("dynamic_download", true),
        fetch_artifact("fetch_artifact", true),
        start_logcat("start_logcat", true),
        pre_sharding_required_setup("pre_sharding_required_setup", true),
        sharding("sharding", true),
        pre_multi_preparer("pre_multi_preparer", true),
        lab_setup("lab_setup", true),
        test_setup("test_setup", true),
        test_execution("test_execution", true),
        check_device_availability("check_device_availability", true),
        bugreport("bugreport", true),
        host_sleep("host_sleep", true),
        test_teardown("test_teardown", true),
        test_cleanup("test_cleanup", true),
        log_and_release_device("log_and_release_device", true),
        invocation_events_processing("invocation_events_processing", true),
        stage_suite_test_artifacts("stage_suite_test_artifacts", true),
        ;

        private final String mKeyName;
        // Whether or not to add the value when the key is added again.
        private final boolean mAdditive;

        private InvocationMetricKey(String key, boolean additive) {
            mKeyName = key;
            mAdditive = additive;
        }

        @Override
        public String toString() {
            return mKeyName;
        }

        public boolean shouldAdd() {
            return mAdditive;
        }
    }

    /** Grouping allows to log several groups under a same key. */
    public enum InvocationGroupMetricKey {
        TEST_TYPE_COUNT("test-type-count", true),
        TARGET_PREPARER_SETUP_LATENCY("target-preparer-setup-latency", true),
        TARGET_PREPARER_TEARDOWN_LATENCY("target-preparer-teardown-latency", true),
        LAB_PREPARER_SETUP_LATENCY("lab-preparer-setup-latency", true),
        LAB_PREPARER_TEARDOWN_LATENCY("lab-preparer-teardown-latency", true),
        MULTI_TARGET_PREPARER_TEARDOWN_LATENCY("multi-target-preparer-teardown-latency", true);

        private final String mGroupName;
        // Whether or not to add the value when the key is added again.
        private final boolean mAdditive;

        private InvocationGroupMetricKey(String groupName, boolean additive) {
            mGroupName = groupName;
            mAdditive = additive;
        }

        @Override
        public String toString() {
            return mGroupName;
        }

        public boolean shouldAdd() {
            return mAdditive;
        }
    }

    private InvocationMetricLogger() {}

    /**
     * Track metrics per ThreadGroup as a proxy to invocation since an invocation run within one
     * threadgroup.
     */
    private static final Map<ThreadGroup, Map<String, String>> mPerGroupMetrics =
            Collections.synchronizedMap(new HashMap<ThreadGroup, Map<String, String>>());

    /**
     * Add one key-value to be tracked at the invocation level.
     *
     * @param key The key under which the invocation metric will be tracked.
     * @param value The value of the invocation metric.
     */
    public static void addInvocationMetrics(InvocationMetricKey key, long value) {
        if (key.shouldAdd()) {
            String existingVal = getInvocationMetrics().get(key.toString());
            long existingLong = 0L;
            if (existingVal != null) {
                try {
                    existingLong = Long.parseLong(existingVal);
                } catch (NumberFormatException e) {
                    CLog.e(
                            "%s is expected to contain a number, instead found: %s",
                            key.toString(), existingVal);
                }
            }
            value += existingLong;
        }
        addInvocationMetrics(key.toString(), Long.toString(value));
    }

    /**
     * Add one key-value for a given group
     *
     * @param groupKey The key of the group
     * @param group The group name associated with the key
     * @param value The value for the group
     */
    public static void addInvocationMetrics(
            InvocationGroupMetricKey groupKey, String group, String value) {
        String key = groupKey.toString() + ":" + group;
        if (groupKey.shouldAdd()) {
            String existingVal = getInvocationMetrics().get(key.toString());
            if (existingVal != null) {
                value = String.format("%s,%s", existingVal, value);
            }
        }
        addInvocationMetrics(key, value);
    }

    /**
     * Add one key-value to be tracked at the invocation level. Don't expose the String key yet to
     * avoid abuse, stick to the official {@link InvocationMetricKey} to start with.
     *
     * @param key The key under which the invocation metric will be tracked.
     * @param value The value of the invocation metric.
     */
    private static void addInvocationMetrics(String key, String value) {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupMetrics) {
            if (mPerGroupMetrics.get(group) == null) {
                mPerGroupMetrics.put(group, new HashMap<>());
            }
            mPerGroupMetrics.get(group).put(key, value);
        }
    }

    /**
     * Add one key-value to be tracked at the invocation level for a given group.
     *
     * @param groupKey The key of the group
     * @param group The group name associated with the key
     * @param value The value for the group
     */
    public static void addInvocationMetrics(
            InvocationGroupMetricKey groupKey, String group, long value) {
        String key = groupKey.toString() + ":" + group;
        if (groupKey.shouldAdd()) {
            String existingVal = getInvocationMetrics().get(key);
            long existingLong = 0L;
            if (existingVal != null) {
                try {
                    existingLong = Long.parseLong(existingVal);
                } catch (NumberFormatException e) {
                    CLog.e(
                            "%s is expected to contain a number, instead found: %s",
                            key.toString(), existingVal);
                }
            }
            value += existingLong;
        }
        addInvocationMetrics(key, Long.toString(value));
    }

    /**
     * Add one key-value to be tracked at the invocation level.
     *
     * @param key The key under which the invocation metric will be tracked.
     * @param value The value of the invocation metric.
     */
    public static void addInvocationMetrics(InvocationMetricKey key, String value) {
        if (key.shouldAdd()) {
            String existingVal = getInvocationMetrics().get(key.toString());
            if (existingVal != null) {
                value = String.format("%s,%s", existingVal, value);
            }
        }
        addInvocationMetrics(key.toString(), value);
    }

    /**
     * Add a pair of value associated with the same key. Usually used for timestamp start and end.
     *
     * @param key The key under which the invocation metric will be tracked.
     * @param start The start value of the invocation metric.
     * @param end The end value of the invocation metric.
     */
    public static void addInvocationPairMetrics(InvocationMetricKey key, long start, long end) {
        String value = start + ":" + end;
        if (key.shouldAdd()) {
            String existingVal = getInvocationMetrics().get(key.toString());
            if (existingVal != null) {
                value = String.format("%s,%s", existingVal, value);
            }
        }
        addInvocationMetrics(key.toString(), value);
    }

    /** Returns the Map of invocation metrics for the invocation in progress. */
    public static Map<String, String> getInvocationMetrics() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupMetrics) {
            if (mPerGroupMetrics.get(group) == null) {
                mPerGroupMetrics.put(group, new HashMap<>());
            }
        return new HashMap<>(mPerGroupMetrics.get(group));
        }
    }

    /** Clear the invocation metrics for an invocation. */
    public static void clearInvocationMetrics() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        synchronized (mPerGroupMetrics) {
            mPerGroupMetrics.remove(group);
        }
    }
}
