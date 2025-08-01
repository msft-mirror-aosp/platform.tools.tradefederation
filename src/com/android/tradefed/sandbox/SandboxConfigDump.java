/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.sandbox;

import com.android.tradefed.build.StubBuildProvider;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.SandboxConfigurationFactory;
import com.android.tradefed.device.IDeviceSelection;
import com.android.tradefed.log.FileLogger;
import com.android.tradefed.log.ILeveledLogOutput;
import com.android.tradefed.log.Log.LogLevel;
import com.android.tradefed.result.FileSystemLogSaver;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.SubprocessResultsReporter;
import com.android.tradefed.result.proto.StreamProtoResultReporter;
import com.android.tradefed.testtype.SubprocessTfLauncher;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.keystore.IKeyStoreClient;
import com.android.tradefed.util.keystore.KeyStoreException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runner class that creates a {@link IConfiguration} based on a command line and dump it to a file.
 * args: <DumpCmd> <output File> <remaing command line>
 */
public class SandboxConfigDump {

    public enum DumpCmd {
        /** The full xml based on the command line will be outputted */
        FULL_XML,
        /** Only non-versioned element of the xml will be outputted */
        NON_VERSIONED_CONFIG,
        /** A run-ready config will be outputted */
        RUN_CONFIG,
        /** Special mode that allows the sandbox to generate another layer of sandboxing. */
        TEST_MODE,
        /** Mode used to dump the test template only. */
        STRICT_TEST
    }

    /**
     * We do not output the versioned elements to avoid causing the parent process to have issues
     * with them when trying to resolve them
     */
    public static final Set<String> VERSIONED_ELEMENTS = new HashSet<>();

    static {
        VERSIONED_ELEMENTS.add(Configuration.SYSTEM_STATUS_CHECKER_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.DEVICE_METRICS_COLLECTOR_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.METRIC_POST_PROCESSOR_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.MULTI_PRE_TARGET_PREPARER_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.MULTI_PREPARER_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.TARGET_PREPARER_TYPE_NAME);
        VERSIONED_ELEMENTS.add(Configuration.TEST_TYPE_NAME);
    }

    /** Elements that are not related to tests of the config but to the surrounding invocation. */
    private static final Set<String> NON_TEST_ELEMENTS = new HashSet<>();

    static {
        NON_TEST_ELEMENTS.add(Configuration.BUILD_PROVIDER_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.LOGGER_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.DEVICE_RECOVERY_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.LOG_SAVER_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.RESULT_REPORTER_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.DEVICE_REQUIREMENTS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.DEVICE_OPTIONS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.COVERAGE_OPTIONS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.RETRY_DECISION_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.SANBOX_OPTIONS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.CMD_OPTIONS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.CONFIGURATION_DESCRIPTION_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.GLOBAL_FILTERS_TYPE_NAME);
        NON_TEST_ELEMENTS.add(Configuration.SKIP_MANAGER_TYPE_NAME);
    }

    /**
     * Parse the args and creates a {@link IConfiguration} from it then dumps it to the result file.
     */
    public int parse(String[] args) {
        // TODO: add some more checking
        List<String> argList = new ArrayList<>(Arrays.asList(args));
        DumpCmd cmd = DumpCmd.valueOf(argList.remove(0));
        File resFile = new File(argList.remove(0));
        SandboxConfigurationFactory factory = SandboxConfigurationFactory.getInstance();
        PrintWriter pw = null;
        try {
            if (DumpCmd.RUN_CONFIG.equals(cmd)
                    && GlobalConfiguration.getInstance().getKeyStoreFactory() != null) {
                IKeyStoreClient keyClient =
                        GlobalConfiguration.getInstance()
                                .getKeyStoreFactory()
                                .createKeyStoreClient();
                replaceKeystore(keyClient, argList);
            }
            IConfiguration config =
                    factory.createConfigurationFromArgs(argList.toArray(new String[0]), cmd);
            if (DumpCmd.RUN_CONFIG.equals(cmd) || DumpCmd.TEST_MODE.equals(cmd)) {
                config.getCommandOptions().setShouldUseSandboxing(false);
                config.getConfigurationDescription().setSandboxed(true);
                // Don't use replication in the sandbox
                config.getCommandOptions().setReplicateSetup(false);
                // Override build providers since they occur in parents
                for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
                    deviceConfig.addSpecificConfig(
                            new StubBuildProvider(), Configuration.BUILD_PROVIDER_TYPE_NAME);
                }
                // Set the reporter
                ITestInvocationListener reporter = null;
                if (getSandboxOptions(config).shouldUseProtoReporter()) {
                    reporter = new StreamProtoResultReporter();
                } else {
                    reporter = new SubprocessResultsReporter();
                    ((SubprocessResultsReporter) reporter).setOutputTestLog(true);
                }
                config.setTestInvocationListener(reporter);
                // Set log level for sandbox
                ILeveledLogOutput logger = config.getLogOutput();
                logger.setLogLevel(LogLevel.VERBOSE);
                if (logger instanceof FileLogger) {
                    // Ensure we get the stdout logging in FileLogger case.
                    ((FileLogger) logger).setLogLevelDisplay(LogLevel.VERBOSE);
                }

                ILogSaver logSaver = config.getLogSaver();
                if (logSaver instanceof FileSystemLogSaver) {
                    // Send the files directly, the parent will take care of compression if needed
                    ((FileSystemLogSaver) logSaver).setCompressFiles(false);
                }

                // Ensure in special conditions (placeholder devices) we can still allocate.
                secureDeviceAllocation(config);

                // Mark as subprocess
                config.getCommandOptions()
                        .getInvocationData()
                        .put(SubprocessTfLauncher.SUBPROCESS_TAG_NAME, "true");
            }
            if (DumpCmd.TEST_MODE.equals(cmd)) {
                // We allow one more layer of sandbox to be generated
                config.getCommandOptions().setShouldUseSandboxing(true);
                config.getConfigurationDescription().setSandboxed(false);
                // Ensure we turn off test mode afterward to avoid infinite sandboxing
                config.getCommandOptions().setUseSandboxTestMode(false);
            }
            pw = new PrintWriter(resFile);
            if (DumpCmd.NON_VERSIONED_CONFIG.equals(cmd)) {
                // Remove elements that are versioned.
                config.dumpXml(
                        pw,
                        new ArrayList<>(VERSIONED_ELEMENTS),
                        true,
                        /* Don't print unchanged options */ false);
            } else if (DumpCmd.STRICT_TEST.equals(cmd)) {
                config.dumpXml(
                        pw,
                        new ArrayList<>(NON_TEST_ELEMENTS),
                        true,
                        /* Don't print unchanged options */ false);
            } else {
                // FULL_XML in that case.
                config.dumpXml(pw, new ArrayList<>(), true,
                        /* Don't print unchanged options */ false);
            }
        } catch (ConfigurationException | IOException | KeyStoreException e) {
            e.printStackTrace();
            return 1;
        } finally {
            StreamUtil.close(pw);
        }
        return 0;
    }

    public static void main(final String[] mainArgs) {
        try {
            GlobalConfiguration.createGlobalConfiguration(new String[] {});
        } catch (ConfigurationException e) {
            e.printStackTrace();
            System.exit(1);
        }
        SandboxConfigDump configDump = new SandboxConfigDump();
        int code = configDump.parse(mainArgs);
        System.exit(code);
    }

    /** Replace keystore options in place. */
    public static void replaceKeystore(IKeyStoreClient keyClient, List<String> argList) {
        Pattern USE_KEYSTORE_REGEX = Pattern.compile("(.*)USE_KEYSTORE@([^:]*)(.*)");
        for (int i = 0; i < argList.size(); i++) {
            Matcher m = USE_KEYSTORE_REGEX.matcher(argList.get(i));
            if (m.matches() && m.groupCount() > 0) {
                String key = m.group(2);
                String keyValue = keyClient.fetchKey(key);
                String newValue = argList.get(i).replace("USE_KEYSTORE@" + key, keyValue);
                argList.set(i, newValue);
            }
        }
    }

    private SandboxOptions getSandboxOptions(IConfiguration config) {
        return (SandboxOptions)
                config.getConfigurationObject(Configuration.SANBOX_OPTIONS_TYPE_NAME);
    }

    private void secureDeviceAllocation(IConfiguration config) {
        for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
            IDeviceSelection requirements = deviceConfig.getDeviceRequirements();
            if (requirements.nullDeviceRequested()
                    || requirements.gceDeviceRequested()) {
                // Reset serials, ensure any null/gce-device can be selected.
                requirements.setSerial();
            }
            // Reset device requested type, we don't need it in the sandbox
            requirements.setBaseDeviceTypeRequested(null);
            // In sandbox it's pointless to check again for battery for allocation
            requirements.setRequireBatteryCheck(false);
        }
    }
}
