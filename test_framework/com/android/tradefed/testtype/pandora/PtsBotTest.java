/*
 *
 * Copyright 2018 The Android Open Source Project
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

package com.android.tradefed.testtype.pandora;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.skipped.SkipReason;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PythonVirtualenvHelper;
import com.android.tradefed.util.RunUtil;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Run PTS-bot tests. PTS-bot is a complete automation of the Bluetooth Profile Tuning Suite, which
 * is the testing tool provided by the Bluetooth standard to run Bluetooth Host certification tests
 * (see
 * https://www.bluetooth.com/develop-with-bluetooth/qualification-listing/qualification-test-tools/profile-tuning-suite/).
 */
public class PtsBotTest implements IRemoteTest, ITestFilterReceiver, IShardableTest, ITestLogger {
    private static final String TAG = "PandoraPtsBot";

    // This is the fixed port of the Pandora gRPC server running on the DUT.
    private static final int PANDORA_SERVER_PORT = 8999;

    // This is the fixed port where pts-bot is sending HCI traffic for physical
    // tests. This can typically redirect to a Bluetooth dongle on the host
    // (i.e. the machine running pts-bot) which will connect to the DUT.
    private static final int HCI_PROXY_PORT = 1234;

    // These are Rootcanal CF Host fixed ports as specified at Cuttlefish
    // startup, in assemble_cvd/flags.cc.
    private static final int HCI_ROOTCANAL_PORT_CF = 7300;
    private static final int CONTROL_ROOTCANAL_PORT_CF = 7500;
    private static final int ROOTCANAL_VSOCK_CID = 2;

    // This is the vsock port where the modem simulator is exposed in the
    // Cuttlefish guest. It is specified in
    // cs/android/device/google/cuttlefish/host/commands/assemble_cvd/flags.cc;l=1224
    private static final int MODEM_SIMULATOR_VSOCK_CID = 2;
    private static final int MODEM_SIMULATOR_VSOCK_PORT = 9600;

    // These are the host (i.e. the machine running pts-bot) ports on which we
    // forward Pandora Server, Rootcanal HCI and Control ports of the DUT, and
    // the modem_simulator port. These ports are not fixed because when
    // sharding, multiple hosts (or a single host) can share the same physical
    // resources and are thus determined dynamically.
    private int hostPandoraServerPort;
    private int hostHciRootcanalPort;
    private int hostControlRootcanalPort;
    private int hostModemSimulatorPort;

    // PTS inactivity timeout in seconds.
    // Must be above 60s to avoid conflict with PTS timeout for triggering
    // disconnections. This notably happens on Android which closes GATT but
    // does not explicitly close the underlying BLE ACL link.
    private static final int PTS_INACTIVITY_TIMEOUT = 90;

    private static final String NATIVE_BLUETOOTH_FLAG =
            "setprop persist.device_config.aconfig_flags.bluetooth.com.android.bluetooth.flags";

    public class TestFlagConfiguration {
        List<FlagConfig> flags;

        public class FlagConfig {
            List<String> flags;
            List<String> tests;
        }
    }

    public class TestSyspropConfiguration {
        List<SyspropConfig> system_properties; // snake_case naming to match the json

        public class SyspropConfig {
            Map<String, String> system_properties;
            List<String> tests;
        }
    }

    private TestFlagConfiguration mTestFlagConfiguration;
    private TestSyspropConfiguration mTestSyspropConfiguration;

    private IRunUtil mRunUtil = new RunUtil();

    @Option(name = "pts-bot-path", description = "pts-bot binary path.")
    private File ptsBotPath = new File("pts-bot");

    @Option(name = "pts-setup-path", description = "Bluetooth SIG pts setup path.")
    private File ptsSetupPath = null;

    @Option(
            name = "create-bin-temp-dir",
            description =
                    "Create a temporary directory to store pts-bot binaries and avoid conflicts"
                            + " when multiple runners are on the same machine")
    private boolean createBinTempDir = false;

    private File binTempDir = null;

    @Option(name = "python-home", description = "PYTHONHOME value to use while running pts-bot.")
    private File pythonHome = null;

    @Option(name = "mmi2grpc", description = "mmi2grpc python module path.")
    private File mmi2grpc = null;

    @Option(
            name = "tests-config-file",
            description = "Tests config file.",
            importance = Importance.ALWAYS)
    private File testsConfigFile = null;

    @Option(name = "profile", description = "Profile to be tested.", importance = Importance.ALWAYS)
    private SortedSet<String> profiles = new TreeSet<>();

    @Option(
            name = "physical",
            description = "Run PTS-bot with a physical Bluetooth communication.",
            importance = Importance.ALWAYS)
    private boolean physical = false;

    @Option(
            name = "max-flaky-tests",
            description = "Maximum number of flaky tests for the entire run.")
    private int maxFlakyTests = 0;

    private int flakyCount = 0;

    @Option(
            name = "max-retries-per-test",
            description = "Maximum number of retries for a flaky test.")
    private int maxRetriesPerTest = 0;

    private final Set<String> includeFilters = new LinkedHashSet<>();
    private final Set<String> excludeFilters = new LinkedHashSet<>();

    @Override
    public void addIncludeFilter(String filter) {
        includeFilters.add(filter);
    }

    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        includeFilters.addAll(filters);
    }

    @Override
    public void addExcludeFilter(String filter) {
        excludeFilters.add(filter);
    }

    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        excludeFilters.addAll(filters);
    }

    @Override
    public Set<String> getIncludeFilters() {
        return includeFilters;
    }

    @Override
    public Set<String> getExcludeFilters() {
        return excludeFilters;
    }

    @Override
    public void clearIncludeFilters() {
        includeFilters.clear();
    }

    @Override
    public void clearExcludeFilters() {
        excludeFilters.clear();
    }

    public TestFlagConfiguration getTestFlagConfiguration() {
        return mTestFlagConfiguration;
    }

    public TestSyspropConfiguration getSyspropConfiguration() {
        return mTestSyspropConfiguration;
    }

    private int shardIndex = 0;
    private int totalShards = 1;

    @Override
    public Collection<IRemoteTest> split(int shardCountHint) {
        if (physical || shardCountHint <= 1) {
            // We cannot share the Bluetooth dongle across multiple tests in
            // parallel, and there's no point "sharding" with just one shard.
            // For now, sharding only works on ATP and not locally.
            return null;
        }

        Collection<IRemoteTest> shards = new ArrayList<>(shardCountHint);

        // Split tests between shards.
        int startIndex = 0;
        for (int i = 0; i < shardCountHint; i++) {
            PtsBotTest shard = new PtsBotTest();
            shard.addAllIncludeFilters(getIncludeFilters());
            shard.addAllExcludeFilters(getExcludeFilters());
            // Copy all options.
            try {
                OptionCopier.copyOptions(this, shard);
            } catch (ConfigurationException e) {
                CLog.e("Failed to copy options: %s", e.getMessage());
            }
            shard.shardIndex = i;
            shard.totalShards = shardCountHint;
            shards.add(shard);
        }

        return shards;
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {

        if (createBinTempDir) {
            try {
                // Create a temp directory with a randomized name (fixed prefix).
                binTempDir = FileUtil.createTempDir("pts-bot");
            } catch (IOException e) {
                throw new RuntimeException("Not able to create temp directory");
            }
        }

        // If tests config file cannot be found using full path, search in
        // dependencies.
        if (!testsConfigFile.exists()) {
            try {
                testsConfigFile = testInfo.getDependencyFile(testsConfigFile.getName(), false);
            } catch (FileNotFoundException e) {
                throw new RuntimeException("Tests config file does not exist");
            }
        }

        // If mmi2grpc cannot be found using full path, search in
        // dependencies.
        // As mmi2grpc is a folder, we cannot use getDependencyFile.
        if (!mmi2grpc.exists()) {
            try {
                File testsDir = testInfo.executionFiles().get(FilesKey.TESTS_DIRECTORY);
                mmi2grpc = FileUtil.findDirectory(mmi2grpc.getName(), testsDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (mmi2grpc == null) {
                throw new RuntimeException("mmi2grpc folder does not exist");
            }
        }

        // Test resources files are not executable.
        ptsBotPath.setExecutable(true);

        displayPtsBotVersion();

        CLog.i("Tests config file: %s", testsConfigFile.getPath());
        CLog.i("Profiles to be tested: %s", profiles);

        ITestDevice testDevice = testInfo.getDevice();

        // Parse the flags config file and initialize the default
        // flags value.
        initFlagsConfig(testDevice, testsConfigFile);

        // Parse the properties config file and initialize the default
        // sysprops' values.
        initSystemPropertiesConfig(testsConfigFile);

        // Forward allocated host Pandora Server port to actual DUT Pandora
        // Server ports.
        hostPandoraServerPort = adbForwardPort(testDevice, 0, PANDORA_SERVER_PORT);

        CLog.i("PTS HCI port: %s", getHciPort());

        if (!physical) {
            // Forward allocated host Rootcanal ports to DUT Rootcanal ports.
            hostHciRootcanalPort =
                    adbForwardVsockPort(testDevice, 0, ROOTCANAL_VSOCK_CID, HCI_ROOTCANAL_PORT_CF);
            hostControlRootcanalPort =
                    adbForwardVsockPort(
                            testDevice, 0, ROOTCANAL_VSOCK_CID, CONTROL_ROOTCANAL_PORT_CF);

            // forward host port to DUT modem_simulator vsock
            hostModemSimulatorPort =
                    adbForwardVsockPort(
                            testDevice, 0, MODEM_SIMULATOR_VSOCK_CID, MODEM_SIMULATOR_VSOCK_PORT);
        }

        // List all applicable tests in a sorted fashion.
        String[] allFilteredTests = getAllFilteredTests(testInfo).toArray(new String[0]);

        // Split tests across shards.
        int chunkSize = allFilteredTests.length / totalShards;
        if (allFilteredTests.length % totalShards > 0) chunkSize++;
        int startIndex = shardIndex * chunkSize;
        int endIndex =
                (totalShards == 1 || shardIndex == totalShards - 1)
                        ? allFilteredTests.length
                        : (shardIndex + 1) * chunkSize;
        String[] tests = Arrays.copyOfRange(allFilteredTests, startIndex, endIndex);

        // Execute tests.
        runPtsBotTests(tests, testInfo, listener);

        // Remove forwarded ports.
        adbForwardRemovePort(testDevice, hostPandoraServerPort);
        if (!physical) {
            adbForwardRemovePort(testDevice, hostHciRootcanalPort);
            adbForwardRemovePort(testDevice, hostControlRootcanalPort);
            adbForwardRemovePort(testDevice, hostModemSimulatorPort);
        }

        // Clean up temp directory.
        if (binTempDir != null) FileUtil.recursiveDelete(binTempDir);
    }

    private void displayPtsBotVersion() {
        CommandResult c;
        c = mRunUtil.runTimedCmd(5000, "which", ptsBotPath.getPath());
        if (c.getStatus() != CommandStatus.SUCCESS) {
            CLog.e("Failed to get pts-bot path");
            CLog.e(
                    "Status: %s\nStdout: %s\nStderr: %s",
                    c.getStatus(), c.getStdout(), c.getStderr());
            throw new RuntimeException("Failed to get pts-bot path. Error:\n" + c.getStderr());
        }
        String ptsBotAbsolutePath = c.getStdout().trim();
        c = mRunUtil.runTimedCmd(5000, ptsBotAbsolutePath, "--version");
        if (c.getStatus() != CommandStatus.SUCCESS) {
            CLog.e("Failed to get pts-bot version");
            CLog.e(
                    "Status: %s\nStdout: %s\nStderr: %s",
                    c.getStatus(), c.getStdout(), c.getStderr());
            throw new RuntimeException("Failed to get pts-bot version. Error:\n" + c.getStderr());
        }
        CLog.d("pts-bot version: %s", c.getStdout().trim());
    }

    /**
     * The port that the PTS bot writes HCI commands to. When running virtual tests, it is the
     * Rootcanal HCI port exposed on the host (i.e. the machine running PTS-bot), which corresponds
     * to the port on which the DUT Rootcanal port is forwarded to. For physical tests, this port is
     * fixed, which means that sharding cannot be supported.
     */
    private int getHciPort() {
        return physical ? HCI_PROXY_PORT : hostHciRootcanalPort;
    }

    private SortedSet<String> getAllFilteredTests(TestInformation testInfo) {
        SortedSet<String> allFilteredTests = new TreeSet<>();
        for (String profile : profiles) {
            // Check whether we should skip the entire profile.
            if (shouldSkipProfileOrTest(profile)) continue;

            allFilteredTests.addAll(
                    Arrays.stream(listPtsBotTestsForProfile(profile, testInfo))
                            .filter(testName -> !shouldSkipProfileOrTest(testName))
                            .collect(Collectors.toList()));
        }
        return allFilteredTests;
    }

    private boolean shouldSkipProfileOrTest(String profileName) {
        // Note that while the logic is described in terms of profiles, we can think
        // of tests as "leaf" profiles, so the same reasoning applies

        for (var filter : excludeFilters) {
            if (profileName.startsWith(filter)) {
                // If we have an exclude filter X/Y and profile X/Y/Z, our
                // profile can never be run, so skip
                return true;
            }
            // If we have an exclude filter X/Y/Z and profile X/Y, our profile
            // may still be run, so continue
        }

        for (var filter : includeFilters) {
            if (profileName.startsWith(filter)) {
                // If we have an include filter X/Y and profile X/Y/Z, our
                // profile will always be run (fully)
                return false;
            }
            if (filter.startsWith(profileName)) {
                // If we have an include filter X/Y/Z and profile X/Y, our
                // profile may be partially run, so include
                return false;
            }
        }

        // No include filters imply running all tests / all profiles
        if (includeFilters.isEmpty()) {
            return false;
        }

        // If we aren't explicitly included in any include filter, then skip
        return true;
    }

    private String[] listPtsBotTestsForProfile(String profile, TestInformation testInfo) {
        try {
            ProcessBuilder processBuilder = ptsBot(testInfo, "--list", profile);

            CLog.i("Running command: %s", String.join(" ", processBuilder.command()));
            Process process = processBuilder.start();

            BufferedReader stdInput =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError =
                    new BufferedReader(new InputStreamReader(process.getErrorStream()));

            stdError.lines().forEach(line -> CLog.e(line));
            stdError.close();

            String line =
                    stdInput.lines().filter(l -> l.startsWith("Tests:")).findFirst().orElse(null);
            stdInput.close();

            if (line != null) {
                String testsStr = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
                if (!testsStr.equals("")) {
                    return testsStr.replaceAll("\"", "").split(", ");
                } else {
                    return new String[0];
                }
            }

        } catch (IOException e) {
            CLog.e(e);
            throw new RuntimeException("Cannot run pts-bot, make sure it is properly installed");
        }

        throw new RuntimeException(String.format("Cannot list tests for %s", profile));
    }

    private Map<String, String> setPropertiesBeforeTest(String testName, ITestDevice testDevice) {
        Map<String, String> restoreSyspropConfiguration = new HashMap<>();

        for (TestSyspropConfiguration.SyspropConfig syspropConfig :
                mTestSyspropConfiguration.system_properties) {
            if (syspropConfig.tests.stream().anyMatch(testName::startsWith)
                    || syspropConfig.tests.stream().anyMatch("all"::equals)) {
                for (Map.Entry<String, String> entry : syspropConfig.system_properties.entrySet()) {
                    String currentValue = getProperty(testDevice, entry.getKey());
                    // Save current value to restore it after the test
                    restoreSyspropConfiguration.put(entry.getKey(), currentValue);
                    // Set the value from the config
                    setProperty(testDevice, entry.getKey(), entry.getValue());
                }
            }
        }
        return restoreSyspropConfiguration;
    }

    private void restorePropertiesAfterTest(
            ITestDevice testDevice, Map<String, String> restoreSyspropConfiguration) {
        if (restoreSyspropConfiguration == null) {
            CLog.e("Unable to restore sysprops - configuration is null");
            return;
        }
        for (Map.Entry<String, String> entry : restoreSyspropConfiguration.entrySet()) {
            setProperty(testDevice, entry.getKey(), entry.getValue());
        }
    }

    private void runPtsBotTests(
            String[] tests, TestInformation testInfo, ITestInvocationListener listener) {

        if (tests.length == 0) {
            CLog.w("No tests to execute");
            return;
        }

        // Grouping tests by profile.
        for (String profile : profiles) {
            String[] profileTests =
                    Arrays.stream(tests)
                            .filter(testName -> testName.startsWith(profile))
                            .toArray(String[]::new);

            if (profileTests.length == 0) {
                CLog.i("No tests applicable for %s", profile);
                continue;
            }
            Map<String, String> runMetrics = new HashMap<>();

            listener.testRunStarted(profile, profileTests.length);
            ITestDevice testDevice = testInfo.getDevice();
            long startTimestamp = System.currentTimeMillis();
            for (String testName : profileTests) {
                Map<String, String> restoreSyspropConfiguration =
                        setPropertiesBeforeTest(testName, testDevice);
                runPtsBotTestWithFlag(profile, testName, testInfo, listener);
                restorePropertiesAfterTest(testDevice, restoreSyspropConfiguration);
                try {
                    File snoopFile = FileUtil.createTempFile("android_snoop_log", ".log");
                    testDevice.pullFile("/data/misc/bluetooth/logs/btsnoop_hci.log", snoopFile);
                    try (InputStreamSource source = new FileInputStreamSource(snoopFile, true)) {
                        listener.testLog(
                                String.format("android_btsnoop_%s", testName),
                                LogDataType.BT_SNOOP_LOG,
                                source);
                    }
                } catch (DeviceNotAvailableException | IOException e) {
                    CLog.e("Cannot fetch Android snoop logs: " + e.toString());
                }
            }
            long endTimestamp = System.currentTimeMillis();
            listener.testRunEnded(endTimestamp - startTimestamp, runMetrics);
        }
    }

    private void runPtsBotTestWithFlag(
            String profile,
            String testName,
            TestInformation testInfo,
            ITestInvocationListener listener) {
        ITestDevice testDevice = testInfo.getDevice();
        boolean matchingFlagConfig = false;
        // If specified in the config, will run the test both with and without flags.
        // This allow to test "no-op" flag before releasing them
        boolean unflagged = false;
        for (TestFlagConfiguration.FlagConfig flagConfig : mTestFlagConfiguration.flags) {
            if (!flagConfig.tests.stream().anyMatch(testName::startsWith)) {
                continue;
            }
            if (flagConfig.flags.stream().anyMatch("unflagged"::equals)) {
                unflagged = true;
            }
            matchingFlagConfig = true;
            if (flagConfig.flags.stream().anyMatch(f -> isFlagDisabledAndReadOnly(testDevice, f))) {
                TestDescription testDescription = new TestDescription(profile, testName);
                SkipReason reason =
                        new SkipReason("The test device doesn't support flag override", "");
                Log.w(testDevice, "Test Ended [Skipped]: " + testName + " " + reason);
                listener.testSkipped(testDescription, reason);
                continue;
            }
            flagConfig.flags.forEach(flag -> enableBluetoothFlag(testDevice, flag));
            runPtsBotTest(profile, testName, testInfo, listener);
            flagConfig.flags.forEach(flag -> restoreBluetoothFlag(testDevice, flag));
        }
        if (!matchingFlagConfig || unflagged) {
            runPtsBotTest(profile, testName, testInfo, listener);
        }
    }

    private static void shell(ITestDevice testDevice, String cmd) {
        try {
            CommandResult cmdResult = testDevice.executeShellV2Command(cmd);
            if (cmdResult.getExitCode() != 0) {
                CLog.e("Failed to run_cmd: " + cmdResult.getStderr());
            }
        } catch (DeviceNotAvailableException e) {
            CLog.e("Device not available: " + e);
        }
    }

    private boolean isFlagEnabledAndReadOnly(ITestDevice testDevice, String flag) {
        try {
            String cmd = "aflags list -c com.android.bt | grep 'flags." + flag + " '";
            CommandResult cmdResult = testDevice.executeShellV2Command(cmd);
            if (cmdResult.getExitCode() != 0) {
                CLog.e("Failed to run" + cmd + ": " + cmdResult.getStderr());
                return false;
            }
            String[] out = cmdResult.getStdout().trim().toLowerCase().split("\\R");
            if (out.length != 1) {
                CLog.e(
                        "Check the config or the target for flag=%s. out=[%s]",
                        flag, Arrays.toString(out));
                return false;
            }
            CLog.v("Flag is: [[" + out[0] + "]]");
            return out[0].matches(
                    "com.android.bluetooth.flags." + flag + " .*enabled.*read-only.*");
        } catch (DeviceNotAvailableException e) {
            CLog.e("Device not available: " + e);
            return false;
        }
    }

    private boolean isFlagDisabledAndReadOnly(ITestDevice testDevice, String flag) {
        try {
            String cmd = "aflags list -c com.android.bt | grep 'flags." + flag + " '";
            CommandResult cmdResult = testDevice.executeShellV2Command(cmd);
            if (cmdResult.getExitCode() != 0) {
                CLog.e("Failed to run" + cmd + ": " + cmdResult.getStderr());
                return true;
            }
            String[] out = cmdResult.getStdout().trim().split("\\R");
            if (out.length != 1) {
                CLog.e(
                        "Check the config or the target for flag=%s. out=[%s]",
                        flag, Arrays.toString(out));
                return true;
            }
            CLog.v("Flag is: [[" + out[0] + "]]");
            return !out[0].matches(
                    "com.android.bluetooth.flags." + flag + " .*(enabled.*read-only|read-write).*");
        } catch (DeviceNotAvailableException e) {
            CLog.e("Device not available: " + e);
            return true;
        }
    }

    private void enableBluetoothFlag(ITestDevice testDevice, String flag) {
        if (isFlagEnabledAndReadOnly(testDevice, flag)) {
            Log.i(testDevice, "enableBluetoothFlag: skip %s: already ENABLED + READ_ONLY", flag);
            return;
        }
        Log.i(testDevice, "enableBluetoothFlag: %s", flag);
        String setNativeFlagCmd = String.format("%s.%s true", NATIVE_BLUETOOTH_FLAG, flag);
        shell(testDevice, setNativeFlagCmd);

        String overrideFlagCmd =
                String.format(
                        "device_config override bluetooth com.android.bluetooth.flags.%s true",
                        flag);
        shell(testDevice, overrideFlagCmd);
    }

    private void restoreBluetoothFlag(ITestDevice testDevice, String flag) {
        if (isFlagEnabledAndReadOnly(testDevice, flag)) {
            Log.i(testDevice, "enableBluetoothFlag: skip %s: already ENABLED + READ_ONLY", flag);
            return;
        }
        Log.i(testDevice, "restoreBluetoothFlag: %s", flag);
        String clearOverrideCmd =
                String.format(
                        "device_config clear_override bluetooth com.android.bluetooth.flags.%s",
                        flag);
        shell(testDevice, clearOverrideCmd);

        String restoreNativeFlagCmd = String.format("%s.%s \\\"\\\"", NATIVE_BLUETOOTH_FLAG, flag);
        shell(testDevice, restoreNativeFlagCmd);
    }

    public void initFlagsConfig(ITestDevice testDevice, File testConfigFile) {
        CLog.i("initFlagsConfig");
        try {
            Gson gson = new Gson();
            FileReader reader = new FileReader(testConfigFile);
            mTestFlagConfiguration = gson.fromJson(reader, TestFlagConfiguration.class);
        } catch (IOException | JsonSyntaxException e) {
            CLog.e("Error initFlagsConfig: " + e);
        }
    }

    public void initSystemPropertiesConfig(File testConfigFile) {
        CLog.i("initSystemPropertiesConfig");
        try {
            Gson gson = new Gson();
            FileReader reader = new FileReader(testConfigFile);
            mTestSyspropConfiguration = gson.fromJson(reader, TestSyspropConfiguration.class);
        } catch (IOException | JsonSyntaxException e) {
            CLog.e("Error initSystemPropertiesConfig: " + e);
        }
    }

    private void setProperty(ITestDevice testDevice, String property, String value) {
        Log.i(testDevice, "setProperty: " + property + " value: " + value);
        try {
            String cmd = String.format("setprop %s %s", property, (value != null ? value : ""));
            CommandResult result = testDevice.executeShellV2Command(cmd);
            if (result.getExitCode() != 0) {
                CLog.e("Failed to set property: " + property + ": " + result.getStderr());
            }
        } catch (DeviceNotAvailableException e) {
            CLog.e("setProperty error: " + e);
        }
    }

    private String getProperty(ITestDevice testDevice, String property) {
        Log.i(testDevice, "getProperty: " + property);
        try {
            String cmd = String.format("getprop %s", property);
            CommandResult result = testDevice.executeShellV2Command(cmd);
            if (result.getExitCode() != 0) {
                CLog.e("Failed to get property: " + property + ": " + result.getStderr());
            } else {
                return result.getStdout();
            }
        } catch (DeviceNotAvailableException e) {
            CLog.e("getProperty error: " + e);
        }
        return "";
    }

    private boolean runPtsBotTest(
            String profile,
            String testName,
            TestInformation testInfo,
            ITestInvocationListener listener) {
        TestDescription testDescription = new TestDescription(profile, testName);

        listener.testStarted(testDescription);
        Log.i(testInfo.getDevice(), "Test Started: " + testName);

        boolean success = false;
        boolean inconclusive = false;

        int retryCount = 0;
        while (true) {
            try {
                // The last three arguments are the Pandora gRPC server port,
                // the Rootcanal control port which pts-bot provides to
                // mmi2grpc, and the modem simulator port that is forwarded from
                // the guest
                ProcessBuilder processBuilder =
                        ptsBot(
                                testInfo,
                                testName,
                                String.valueOf(hostPandoraServerPort),
                                String.valueOf(hostControlRootcanalPort),
                                String.valueOf(hostModemSimulatorPort));

                CLog.i("Running command: %s", String.join(" ", processBuilder.command()));
                Process process = processBuilder.start();
                // Note: there is no need to implement a timeout here since it
                // is handled in pts-bot.

                BufferedReader stdInput =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));

                BufferedReader stdError =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()));

                CompletableFuture<Void> future =
                        CompletableFuture.runAsync(
                                () -> {
                                    stdError.lines().forEach(line -> CLog.e(line));
                                    try {
                                        stdError.close();
                                    } catch (IOException e) {
                                        throw new CompletionException(e);
                                    }
                                });

                Optional<String> lastLine =
                        stdInput.lines().peek(line -> CLog.i(line)).reduce((last, value) -> value);

                // Last line is providing success/inconclusive information.
                if (lastLine.isPresent()) {
                    try {
                        success =
                                Integer.parseInt(lastLine.get().split(", ")[1].substring(0, 1))
                                        == 1;
                        inconclusive =
                                Integer.parseInt(lastLine.get().split(", ")[3].substring(0, 1))
                                        == 1;
                    } catch (Exception e) {
                        CLog.e("Failed to parse status");
                    }
                }

                stdInput.close();
                future.join();

            } catch (Exception e) {
                CLog.e(e);
                CLog.e("Cannot run pts-bot, make sure it is properly installed");
            }

            if (success) break;

            // Retry in case of inconclusive or failure.
            retryCount++;
            // At the first retry, increment flaky tests count.
            if (retryCount == 1) flakyCount++;
            if (flakyCount <= maxFlakyTests && retryCount <= maxRetriesPerTest) {
                Log.w(
                        testInfo.getDevice(),
                        String.format(
                                "Test %s: %s, retrying [count=%s]",
                                inconclusive ? "Inconclusive" : "Failed", testName, retryCount));
            } else {
                break;
            }
        }

        if (success) {
            Log.i(testInfo.getDevice(), "Test Ended [Success]: " + testName);
        } else {
            Log.e(testInfo.getDevice(), "Test Ended [Failed]: " + testName);
            listener.testFailed(
                    testDescription,
                    String.format(
                            "Test case %s failed, please route bugs to"
                                    + " android-bluetooth@google.com\n"
                                    + "Refer to host_log files to find the test logs",
                            testName));
        }

        listener.testEnded(testDescription, Collections.emptyMap());

        return success;
    }

    private ProcessBuilder ptsBot(TestInformation testInfo, String... args) {
        List<String> command = new ArrayList<>();

        command.add(ptsBotPath.getPath());
        command.add("--config");
        command.add(testsConfigFile.getPath());
        command.add("--hci");
        command.add(String.valueOf(getHciPort()));
        command.add("--inactivity-timeout");
        command.add(String.valueOf(PTS_INACTIVITY_TIMEOUT));

        if (ptsSetupPath != null) {
            command.add("--pts-setup");
            command.add(ptsSetupPath.getPath());
        }

        command.addAll(Arrays.asList(args));

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        if (binTempDir != null) builder.environment().put("XDG_CACHE_HOME", binTempDir.getPath());
        if (pythonHome != null) builder.environment().put("PYTHONHOME", pythonHome.getPath());

        String pythonPath = mmi2grpc.getPath();
        File venvDir = testInfo.getBuildInfo().getFile(PythonVirtualenvHelper.VIRTUAL_ENV);
        if (venvDir != null) {
            String packagePath =
                    PythonVirtualenvHelper.getPackageInstallLocation(
                            mRunUtil, venvDir.getAbsolutePath());
            pythonPath += ":" + packagePath;
        }

        // Add mmi2grpc python module path to process builder environment.
        builder.environment().put("PYTHONPATH", pythonPath);

        return builder;
    }

    private static int adbForwardPort(ITestDevice testDevice, int hostPort, int dutPort)
            throws DeviceNotAvailableException {
        return Integer.parseInt(
                testDevice
                        .executeAdbCommand(
                                "forward",
                                String.format("tcp:%s", hostPort),
                                String.format("tcp:%s", dutPort))
                        .trim());
    }

    private static int adbForwardVsockPort(
            ITestDevice testDevice, int hostPort, int dutCid, int dutPort)
            throws DeviceNotAvailableException {
        return Integer.parseInt(
                testDevice
                        .executeAdbCommand(
                                "forward",
                                String.format("tcp:%s", hostPort),
                                String.format("vsock:%s:%s", dutCid, dutPort))
                        .trim());
    }

    private static void adbForwardRemovePort(ITestDevice testDevice, int hostPort)
            throws DeviceNotAvailableException {
        testDevice.executeAdbCommand("forward", "--remove", String.format("tcp:%s", hostPort));
    }

    private static void androidLog(ITestDevice testDevice, String priority, String content) {
        try {
            String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            String command =
                    String.format(
                            "log -t %s -p %s '%s (%s host time)'",
                            TAG, priority, content, timeStamp);
            CommandResult result = testDevice.executeShellV2Command(command);
            if (result.getStatus() != CommandStatus.SUCCESS) {
                CLog.w(
                        String.format(
                                "Command '%s' exited with status %s (code %s)",
                                command, result.getStatus(), result.getExitCode()));
            }
        } catch (DeviceNotAvailableException e) {
            CLog.w("Failed to send android log, device not available: " + e);
        }
    }

    // Log simultaneously in host and in android
    static class Log {
        public static void i(ITestDevice testDevice, String format, Object... args) {
            String content = String.format(format, args);
            androidLog(testDevice, "i", content);
            CLog.i(content);
        }

        public static void w(ITestDevice testDevice, String format, Object... args) {
            String content = String.format(format, args);
            androidLog(testDevice, "w", content);
            CLog.w(content);
        }

        public static void e(ITestDevice testDevice, String format, Object... args) {
            String content = String.format(format, args);
            androidLog(testDevice, "e", content);
            CLog.e(content);
        }
    }
}
