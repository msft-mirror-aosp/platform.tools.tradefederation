/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.config.Option;
import com.android.tradefed.util.ArrayUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container for {@link ITestDevice} {@link Option}s
 */
public class TestDeviceOptions {

    public enum InstanceType {
        /** A device that we remotely access via ssh and adb connect */
        GCE,
        REMOTE_AVD,
        /**
         * A remote device inside an emulator that we access via ssh to the instance hosting the
         * emulator then adb connect.
         */
        CUTTLEFISH,
        REMOTE_NESTED_AVD,
        /** An android emulator. */
        EMULATOR,
    }

    public static final int DEFAULT_ADB_PORT = 5555;
    public static final String INSTANCE_TYPE_OPTION = "instance-type";

    /** Do not provide a setter method for that Option as it might be misused. */
    @Option(name = "enable-root", description = "enable adb root on boot.")
    private boolean mEnableAdbRoot = true;

    @Option(name = "disable-keyguard",
            description = "attempt to disable keyguard once boot is complete.")
    private boolean mDisableKeyguard = true;

    @Option(name = "enable-logcat", description =
            "Enable background logcat capture when invocation is running.")
    private boolean mEnableLogcat = true;

    @Option(name = "max-tmp-logcat-file", description =
        "The maximum size of tmp logcat data to retain, in bytes. " +
        "Only used if --enable-logcat is set")
    private long mMaxLogcatDataSize = 20 * 1024 * 1024;

    @Option(name = "logcat-options", description =
            "Options to be passed down to logcat command, if unspecified, \"-v threadtime\" will " +
            "be used. Only used if --enable-logcat is set")
    private String mLogcatOptions = null;

    @Option(name = "fastboot-timeout", description =
            "time in ms to wait for a device to boot into fastboot.")
    private int mFastbootTimeout = 1 * 60 * 1000;

    @Option(name = "adb-recovery-timeout", description =
            "time in ms to wait for a device to boot into recovery.")
    private int mAdbRecoveryTimeout = 1 * 60 * 1000;

    @Option(name = "reboot-timeout", description =
            "time in ms to wait for a device to reboot to full system.")
    private int mRebootTimeout = 2 * 60 * 1000;

    @Option(name = "use-fastboot-erase", description =
            "use fastboot erase instead of fastboot format to wipe partitions")
    private boolean mUseFastbootErase = false;

    @Option(name = "unencrypt-reboot-timeout", description = "time in ms to wait for the device to "
            + "format the filesystem and reboot after unencryption")
    private int mUnencryptRebootTimeout = 0;

    @Option(name = "online-timeout", description = "default time in ms to wait for the device to "
            + "be visible on adb.", isTimeVal = true)
    private long mOnlineTimeout = 1 * 60 * 1000;

    @Option(name = "available-timeout", description = "default time in ms to wait for the device "
            + "to be available aka fully boot.")
    private long mAvailableTimeout = 6 * 60 * 1000;

    @Option(name = "conn-check-url",
            description = "default URL to be used for connectivity checks.")
    private String mConnCheckUrl = "http://www.google.com";

    @Option(name = "wifi-attempts",
            description = "default number of attempts to connect to wifi network.")
    private int mWifiAttempts = 5;

    @Option(name = "wifi-retry-wait-time",
            description = "the base wait time in ms between wifi connect retries. "
            + "The actual wait time would be a multiple of this value.")
    private int mWifiRetryWaitTime = 60 * 1000;

    @Option(
        name = "max-wifi-connect-time",
        isTimeVal = true,
        description = "the maximum amount of time to attempt to connect to wifi."
    )
    private long mMaxWifiConnectTime = 10 * 60 * 1000;

    @Option(name = "wifi-exponential-retry",
            description = "Change the wifi connection retry strategy from a linear wait time into"
                    + " a binary exponential back-offs when retrying.")
    private boolean mWifiExpoRetryEnabled = true;

    @Option(name = "wifiutil-apk-path", description = "path to the wifiutil APK file")
    private String mWifiUtilAPKPath = null;

    @Option(name = "post-boot-command",
            description = "shell command to run after reboots during invocation")
    private List<String> mPostBootCommands = new ArrayList<String>();

    @Option(name = "disable-reboot",
            description = "disables device reboots globally, making them no-ops")
    private boolean mDisableReboot = false;

    @Option(name = "cutoff-battery", description =
            "the minimum battery level required to continue the invocation. Scale: 0-100")
    private Integer mCutoffBattery = null;

    // ====================== Options Related to Virtual Devices ======================
    @Option(
            name = INSTANCE_TYPE_OPTION,
            description = "The type of virtual device instance to create")
    private InstanceType mInstanceType = InstanceType.GCE;

    @Option(
            name = "gce-boot-timeout",
            description = "timeout to wait in ms for GCE to be online.",
            isTimeVal = true)
    private long mGceCmdTimeout = 30 * 60 * 1000; // 30 minutes.

    @Option(name = "gce-driver-path", description = "path of the binary to launch GCE devices")
    private File mAvdDriverBinary = null;

    @Option(
            name = "gce-driver-config-path",
            description = "path of the config to use to launch GCE devices.")
    private File mAvdConfigFile = null;

    @Option(
            name = "gce-driver-config-test-resource-name",
            description = "Test resource name of the config to use to launch GCE devices.")
    private String mAvdConfigTestResourceName;

    @Option(
            name = "gce-driver-service-account-json-key-path",
            description = "path to the service account json key location.")
    private File mJsonKeyFile = null;

    @Option(
            name = "gce-private-key-path",
            description = "path to the ssh key private key location.")
    private File mSshPrivateKeyPath = new File("~/.ssh/id_rsa");

    @Option(name = "gce-driver-log-level", description = "Log level for gce driver")
    private LogLevel mGceDriverLogLevel = LogLevel.DEBUG;

    @Option(
        name = "gce-driver-param",
        description = " Additional key-value pairs to pass down to " + "gce driver as parameters."
    )
    private List<String> mGceDriverParams = new ArrayList<>();

    @Option(
        name = "gce-driver-build-id-param",
        description =
                "The parameter to be paired with "
                        + "build id from build info when passed down to gce driver"
    )
    private String mGceDriverBuildIdParam = "build_id";

    @Option(name = "gce-account", description = "email account to use with GCE driver.")
    private String mGceAccount = null;

    @Option(
            name = "max-gce-attempt",
            description =
                    "Maximum number of attempts to start Gce " + "before throwing an exception.")
    private int mGceMaxAttempt = 1;

    @Option(
            name = "skip-gce-teardown",
            description =
                    "Whether or not to skip the GCE tear down. Skipping tear down will "
                            + "result in the instance being left.")
    private boolean mSkipTearDown = false;

    @Option(
            name = "wait-gce-teardown",
            description = "Whether or not to block on gce teardown before proceeding.")
    private boolean mWaitForGceTearDown = false;

    @Option(
            name = "instance-user",
            description =
                    "The account to be used to interact with the "
                            + "outer layer of the GCE VM, e.g. to SSH in")
    private String mInstanceUser = "root";

    @Option(
            name = "remote-adb-port",
            description = "The port on remote instance where the adb " + "server listens to.")
    private int mRemoteAdbPort = DEFAULT_ADB_PORT;

    @Option(
            name = "base-host-image",
            description = "The base image to be used for the GCE VM to host emulator.")
    private String mBaseImage = null;

    /**
     * This command line argument is used to specify an emulator system image branch (e.g.
     * git_pi-dev) that needs to be tested against an emulator binary build specified by
     * "--build-id" argument.
     *
     * <p>This argument is to be used only in a case when tradefed is evoked for testing an emulator
     * binary build.
     */
    @Option(
            name = "test-sysimage-branch",
            description = "The emulator system image branch that needs to be tested.")
    private String mTestBranch = null;

    /**
     * This command line argument is used to specify an emulator system image target (e.g.
     * sdk_gphone_x86_64-userdebug) that needs to be tested against an emulator binary build
     * specified by "--build-id" argument.
     *
     * <p>This argument is to be used only in a case when tradefed is evoked for testing an emulator
     * binary build.
     */
    @Option(
            name = "test-sysimage-target",
            description = "The emulator system image target that needs to be tested.")
    private String mTestTarget = null;

    // END ====================== Options Related to Virtual Devices ======================

    /** Check whether adb root should be enabled on boot for this device */
    public boolean isEnableAdbRoot() {
        return mEnableAdbRoot;
    }

    /**
     * Check whether or not we should attempt to disable the keyguard once boot has completed
     */
    public boolean isDisableKeyguard() {
        return mDisableKeyguard;
    }

    /**
     * Set whether or not we should attempt to disable the keyguard once boot has completed
     */
    public void setDisableKeyguard(boolean disableKeyguard) {
        mDisableKeyguard = disableKeyguard;
    }

    /**
     * Get the approximate maximum size of a tmp logcat data to retain, in bytes.
     */
    public long getMaxLogcatDataSize() {
        return mMaxLogcatDataSize;
    }

    /**
     * Set the approximate maximum size of a tmp logcat to retain, in bytes
     */
    public void setMaxLogcatDataSize(long maxLogcatDataSize) {
        mMaxLogcatDataSize = maxLogcatDataSize;
    }

    /**
     * @return the timeout to boot into fastboot mode in msecs.
     */
    public int getFastbootTimeout() {
        return mFastbootTimeout;
    }

    /**
     * @param fastbootTimeout the timout in msecs to boot into fastboot mode.
     */
    public void setFastbootTimeout(int fastbootTimeout) {
        mFastbootTimeout = fastbootTimeout;
    }

    /**
     * @return the timeout in msecs to boot into recovery mode.
     */
    public int getAdbRecoveryTimeout() {
        return mAdbRecoveryTimeout;
    }

    /**
     * @param adbRecoveryTimeout the timeout in msecs to boot into recovery mode.
     */
    public void setAdbRecoveryTimeout(int adbRecoveryTimeout) {
        mAdbRecoveryTimeout = adbRecoveryTimeout;
    }

    /**
     * @return the timeout in msecs for the full system boot.
     */
    public int getRebootTimeout() {
        return mRebootTimeout;
    }

    /**
     * @param rebootTimeout the timeout in msecs for the system to fully boot.
     */
    public void setRebootTimeout(int rebootTimeout) {
        mRebootTimeout = rebootTimeout;
    }

    /**
     * @return whether to use fastboot erase instead of fastboot format to wipe partitions.
     */
    public boolean getUseFastbootErase() {
        return mUseFastbootErase;
    }

    /**
     * @param useFastbootErase whether to use fastboot erase instead of fastboot format to wipe
     * partitions.
     */
    public void setUseFastbootErase(boolean useFastbootErase) {
        mUseFastbootErase = useFastbootErase;
    }

    /**
     * @return the timeout in msecs for the filesystem to be formatted and the device to reboot
     * after unencryption.
     */
    public int getUnencryptRebootTimeout() {
        return mUnencryptRebootTimeout;
    }

    /**
     * @param unencryptRebootTimeout the timeout in msecs for the filesystem to be formatted and
     * the device to reboot after unencryption.
     */
    public void setUnencryptRebootTimeout(int unencryptRebootTimeout) {
        mUnencryptRebootTimeout = unencryptRebootTimeout;
    }

    /**
     * @return the default time in ms to to wait for a device to be online.
     */
    public long getOnlineTimeout() {
        return mOnlineTimeout;
    }

    public void setOnlineTimeout(long onlineTimeout) {
        mOnlineTimeout = onlineTimeout;
    }

    /**
     * @return the default time in ms to to wait for a device to be available.
     */
    public long getAvailableTimeout() {
        return mAvailableTimeout;
    }

    /**
     * @return the default URL to be used for connectivity tests.
     */
    public String getConnCheckUrl() {
        return mConnCheckUrl;
    }

    public void setConnCheckUrl(String url) {
      mConnCheckUrl = url;
    }

    /**
     * @return true if background logcat capture is enabled
     */
    public boolean isLogcatCaptureEnabled() {
        return mEnableLogcat;
    }

    /**
     * @return the default number of attempts to connect to wifi network.
     */
    public int getWifiAttempts() {
        return mWifiAttempts;
    }

    public void setWifiAttempts(int wifiAttempts) {
        mWifiAttempts = wifiAttempts;
    }

    /**
     * @return the base wait time between wifi connect retries.
     */
    public int getWifiRetryWaitTime() {
        return mWifiRetryWaitTime;
    }

    /** @return the maximum time to attempt to connect to wifi. */
    public long getMaxWifiConnectTime() {
        return mMaxWifiConnectTime;
    }

    /**
     * @return a list of shell commands to run after reboots.
     */
    public List<String> getPostBootCommands() {
        return mPostBootCommands;
    }

    /**
     * @return the minimum battery level to continue the invocation.
     */
    public Integer getCutoffBattery() {
        return mCutoffBattery;
    }

    /**
     * set the minimum battery level to continue the invocation.
     */
    public void setCutoffBattery(int cutoffBattery) {
        if (cutoffBattery < 0 || cutoffBattery > 100) {
            // Prevent impossible value.
            throw new RuntimeException(String.format("Battery cutoff wasn't changed,"
                    + "the value %s isn't within possible range (0-100).", cutoffBattery));
        }
        mCutoffBattery = cutoffBattery;
    }

    /**
     * @return the configured logcat options
     */
    public String getLogcatOptions() {
        return mLogcatOptions;
    }

    /**
     * Set the options to be passed down to logcat
     */
    public void setLogcatOptions(String logcatOptions) {
        mLogcatOptions = logcatOptions;
    }

    /**
     * @return if device reboot should be disabled
     */
    public boolean shouldDisableReboot() {
        return mDisableReboot;
    }

    /**
     * @return if the exponential retry strategy should be used.
     */
    public boolean isWifiExpoRetryEnabled() {
        return mWifiExpoRetryEnabled;
    }

    /** @return the wifiutil apk path */
    public String getWifiUtilAPKPath() {
        return mWifiUtilAPKPath;
    }

    /** Returns the instance type of virtual device that should be created */
    public InstanceType getInstanceType() {
        return mInstanceType;
    }

    // =========================== Getter and Setter for Virtual Devices
    /** Return the Gce Avd timeout for the instance to come online. */
    public long getGceCmdTimeout() {
        return mGceCmdTimeout;
    }

    /** Set the Gce Avd timeout for the instance to come online. */
    public void setGceCmdTimeout(long gceCmdTimeout) {
        mGceCmdTimeout = gceCmdTimeout;
    }

    /** Return the path to the binary to start the Gce Avd instance. */
    public File getAvdDriverBinary() {
        return mAvdDriverBinary;
    }

    /** Set the path to the binary to start the Gce Avd instance. */
    public void setAvdDriverBinary(File avdDriverBinary) {
        mAvdDriverBinary = avdDriverBinary;
    }

    /** Return the Gce Avd config file to start the instance. */
    public File getAvdConfigFile() {
        return mAvdConfigFile;
    }

    /** Set the Gce Avd config file to start the instance. */
    public void setAvdConfigFile(File avdConfigFile) {
        mAvdConfigFile = avdConfigFile;
    }

    /** Return the Gce Avd config test resource name to start the instance. */
    public String getAvdConfigTestResourceName() {
        return mAvdConfigTestResourceName;
    }

    /** @return the service account json key file. */
    public File getSerivceAccountJsonKeyFile() {
        return mJsonKeyFile;
    }

    /**
     * Set the service account json key file.
     *
     * @param jsonKeyFile the key file.
     */
    public void setServiceAccountJsonKeyFile(File jsonKeyFile) {
        mJsonKeyFile = jsonKeyFile;
    }

    /** Return the path of the ssh key to use for operations with the Gce Avd instance. */
    public File getSshPrivateKeyPath() {
        return mSshPrivateKeyPath;
    }

    /** Set the path of the ssh key to use for operations with the Gce Avd instance. */
    public void setSshPrivateKeyPath(File sshPrivateKeyPath) {
        mSshPrivateKeyPath = sshPrivateKeyPath;
    }

    /** Return the log level of the Gce Avd driver. */
    public LogLevel getGceDriverLogLevel() {
        return mGceDriverLogLevel;
    }

    /** Set the log level of the Gce Avd driver. */
    public void setGceDriverLogLevel(LogLevel mGceDriverLogLevel) {
        this.mGceDriverLogLevel = mGceDriverLogLevel;
    }

    /** Return the additional GCE driver parameters provided via option */
    public List<String> getGceDriverParams() {
        return mGceDriverParams;
    }

    /** Set the GCE driver parameter that should be paired with the build id from build info */
    public void setGceDriverBuildIdParam(String gceDriverBuildIdParam) {
        mGceDriverBuildIdParam = gceDriverBuildIdParam;
    }

    /** Return the GCE driver parameter that should be paired with the build id from build info */
    public String getGceDriverBuildIdParam() {
        return mGceDriverBuildIdParam;
    }

    /** Return the gce email account to use with the driver */
    public String getGceAccount() {
        return mGceAccount;
    }

    /** Return the max number of attempts to start a gce device */
    public int getGceMaxAttempt() {
        if (mGceMaxAttempt < 1) {
            throw new RuntimeException("--max-gce-attempt cannot be bellow 1 attempt.");
        }
        return mGceMaxAttempt;
    }

    /** Set the max number of attempts to start a gce device */
    public void setGceMaxAttempt(int gceMaxAttempt) {
        mGceMaxAttempt = gceMaxAttempt;
    }

    /** Returns true if GCE tear down should be skipped. False otherwise. */
    public boolean shouldSkipTearDown() {
        return mSkipTearDown;
    }

    /** Returns true if we should block on GCE tear down completion before proceeding. */
    public boolean waitForGceTearDown() {
        return mWaitForGceTearDown;
    }

    /** Returns the instance type of GCE virtual device that should be created */
    public String getInstanceUser() {
        return mInstanceUser;
    }

    /** Returns the remote port in instance that the adb server listens to */
    public int getRemoteAdbPort() {
        return mRemoteAdbPort;
    }

    /** Returns the base image name to be used for the current instance */
    public String getBaseImage() {
        return mBaseImage;
    }

    /** Returns the system image branch to be used for the current instance */
    public String getSystemImageBranch() {
        return mTestBranch;
    }

    /** Returns the system image target to be used for the current instance */
    public String getSystemImageTarget() {
        return mTestTarget;
    }

    public static String getCreateCommandByInstanceType(InstanceType type) {
        switch (type) {
            case GCE:
            case REMOTE_AVD:
                return "create";
            case CUTTLEFISH:
            case REMOTE_NESTED_AVD:
                return "create_cf";
            case EMULATOR:
                return "create_gf";
        }
        throw new RuntimeException("Unexpected InstanceType: " + type);
    }

    public static List<String> getExtraParamsByInstanceType(InstanceType type, String baseImage) {
        if (InstanceType.EMULATOR.equals(type)) {
            // TODO(b/119440413) remove when base image can be passed via extra gce driver params
            List<String> params = ArrayUtil.list();
            if (baseImage != null) {
                params.add("--base_image");
                params.add(baseImage);
            }
            return params;
        }
        return Collections.emptyList();
    }
}
