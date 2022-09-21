/*
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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.PythonVirtualenvHelper;
import com.android.tradefed.util.RunUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Run PTS-bot tests. PTS-bot is a complete automation of the Bluetooth Profile Tuning Suite, which
 * is the testing tool provided by the Bluetooth standard to run Bluetooth Host certification tests
 * (see
 * https://www.bluetooth.com/develop-with-bluetooth/qualification-listing/qualification-test-tools/profile-tuning-suite/).
 */
public class PtsBotTest implements IRemoteTest, ITestFilterReceiver {

    private static final String TAG = "PandoraPtsBot";
    private static final int PANDORA_SERVER_PORT = 8999;
    private static final int HCI_PROXY_PORT = 1234;

    // These are the ports on the device where we expect to find RootCanal.
    // We forward the host-side ports to these ports on the device if we are
    // running on Cuttlefish, and RootCanal will be available here by
    // default if we are using a physical default.
    private static final int HCI_ROOTCANAL_PORT = 6211;
    private static final int CONTROL_ROOTCANAL_PORT = 6212;

    // The emulator runs in a Guest VM on the Cuttlefish VM, but some services
    // (notably RootCanal) run on the Host side. From the Guest, the Host is
    // accessible with this IP.
    private static final String HOST_IP_CF = "192.168.97.1";

    // Host-side ports are specified at Cuttlefish startup, in
    // assemble_cvd/flags.cc. Note! the local machine is *not* the host, it uses
    // the *device* port numbers, the host is the machine running Cuttlefish.
    private static final int HCI_ROOTCANAL_PORT_CF = 7300;
    private static final int CONTROL_ROOTCANAL_PORT_CF = 7500;

    private IRunUtil mRunUtil = new RunUtil();

    @Option(name = "pts-bot-path", description = "pts-bot binary path.")
    private File ptsBotPath = new File("pts-bot");

    @Option(name = "pts-setup-path", description = "Bluetooth SIG pts setup path.")
    private File ptsSetupPath = null;

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
    private Set<String> profiles = new HashSet<>();

    @Option(
            name = "physical",
            description = "Run PTS-bot with a physical Bluetooth communication.",
            importance = Importance.ALWAYS)
    private boolean physical = false;

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

    /**
     * The port that the PTS bot will write HCI commands to. If we are using RootCanal, we will
     * connect to its HCI port. Otherwise, we will connect to the port where a physical Bluetooth
     * dongle is listening.
     */
    private int getHciPort() {
        return physical ? HCI_PROXY_PORT : HCI_ROOTCANAL_PORT;
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

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {

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
        // As mmi2grpc is a folder we cannot use getDependencyFile
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

        // Test resources files are not executable
        ptsBotPath.setExecutable(true);

        displayPtsBotVersion();

        CLog.i("Tests config file: %s", testsConfigFile.getPath());
        CLog.i("Profiles to be tested: %s", profiles);

        ITestDevice testDevice = testInfo.getDevice();

        // Forward Pandora Server port.
        adbForwardPort(testDevice, PANDORA_SERVER_PORT);

        CLog.i("PTS HCI port: %s", getHciPort());

        Thread killHciPassthrough = null;
        Thread killControlPassthrough = null;

        if (!physical) {
            boolean isCuttlefish = testDevice.getProductType().equals("cutf");

            if (isCuttlefish) {
                // forward HCI + Control ("test") ports from host-side rootcanal
                // to device
                killHciPassthrough =
                        createHostToDevicePassthrough(
                                testDevice, HCI_ROOTCANAL_PORT_CF, HCI_ROOTCANAL_PORT);
                killControlPassthrough =
                        createHostToDevicePassthrough(
                                testDevice, CONTROL_ROOTCANAL_PORT_CF, CONTROL_ROOTCANAL_PORT);
            }

            // Forward Root Canal ports from device to local test host.
            adbForwardPort(testDevice, HCI_ROOTCANAL_PORT);
            adbForwardPort(testDevice, CONTROL_ROOTCANAL_PORT);
        }

        // Run tests.
        for (String profile : profiles) {
            runPtsBotTestsForProfile(profile, testInfo, listener);
        }

        // Kill passthroughs, if initialized
        if (killHciPassthrough != null) {
            completeShutdownHook(killHciPassthrough);
        }
        if (killControlPassthrough != null) {
            completeShutdownHook(killControlPassthrough);
        }

        // Remove forwarded ports.
        adbForwardRemovePort(testDevice, PANDORA_SERVER_PORT);
        if (!physical) {
            adbForwardRemovePort(testDevice, HCI_ROOTCANAL_PORT);
            adbForwardRemovePort(testDevice, CONTROL_ROOTCANAL_PORT);
        }
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

    private void runPtsBotTestsForProfile(
            String profile, TestInformation testInfo, ITestInvocationListener listener) {
        String[] tests = listPtsBotTestsForProfile(profile, testInfo);

        CLog.i("Available tests for %s: [%s]", profile, String.join(", ", tests));

        List<String> filteredTests = new ArrayList();
        for (int i = 0; i < tests.length; i++) {
            String testName = tests[i];
            if (!shouldSkipTest(testName)) {
                filteredTests.add(testName);
            }
        }

        if (!filteredTests.isEmpty()) {

            Map<String, String> runMetrics = new HashMap<>();

            listener.testRunStarted(profile, filteredTests.size());
            long startTimestamp = System.currentTimeMillis();
            for (String testName : filteredTests) {
                runPtsBotTest(profile, testName, testInfo, listener);
            }
            long endTimestamp = System.currentTimeMillis();
            listener.testRunEnded(endTimestamp - startTimestamp, runMetrics);
        } else {
            CLog.w("No tests applicable for %s", profile);
        }
    }

    private boolean shouldSkipTest(String testName) {
        // If the test or one of its parent test group is included in
        // exclude filters, then skip it.
        if (excludeFilters.stream().anyMatch(testName::contains)) return true;

        // If the test or one of its parent test group is included in
        // include filters, then don't skip it.
        if (includeFilters.stream().anyMatch(testName::contains)) return false;

        // If include filters are provided, and if the test or one of its
        // parent test group is not included, then skip it.
        if (!includeFilters.isEmpty()) return true;

        return false;
    }

    private void androidLog(ITestDevice testDevice, String content) {
        try {
            String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            String command =
                    String.format("log -t %s '%s (%s host time)'", TAG, content, timeStamp);
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

    private boolean runPtsBotTest(
            String profile,
            String testName,
            TestInformation testInfo,
            ITestInvocationListener listener) {
        TestDescription testDescription = new TestDescription(profile, testName);
        boolean success = false;

        listener.testStarted(testDescription);
        CLog.i(testName);
        androidLog(testInfo.getDevice(), "Test Started: " + testName);
        try {
            ProcessBuilder processBuilder = ptsBot(testInfo, testName);

            CLog.i("Running command: %s", String.join(" ", processBuilder.command()));
            Process process = processBuilder.start();
            // Note: there is no need to implement a timeout here since it is handled in pts-bot.

            BufferedReader stdInput =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));

            BufferedReader stdError =
                    new BufferedReader(new InputStreamReader(process.getErrorStream()));

            Optional<String> lastLine =
                    stdInput.lines().peek(line -> CLog.i(line)).reduce((last, value) -> value);

            // Last line is providing success information.
            if (lastLine.isPresent()) {
                try {
                    success = Integer.parseInt(lastLine.get().split(", ")[1].substring(0, 1)) == 1;
                } catch (Exception e) {
                    CLog.e("Failed to parse success");
                }
            }

            stdInput.close();

            stdError.lines().forEach(line -> CLog.e(line));
            stdError.close();

        } catch (Exception e) {
            CLog.e(e);
            CLog.e("Cannot run pts-bot, make sure it is properly installed");
        }

        if (!success) {
            listener.testFailed(testDescription, "Unknown");
        }

        listener.testEnded(testDescription, Collections.emptyMap());
        androidLog(testInfo.getDevice(), "Test Ended: " + testName);

        return success;
    }

    private ProcessBuilder ptsBot(TestInformation testInfo, String... args) {
        List<String> command = new ArrayList<>();

        command.add(ptsBotPath.getPath());
        command.add("-c");
        command.add(testsConfigFile.getPath());
        command.add("--hci");
        command.add(String.valueOf(getHciPort()));

        if (ptsSetupPath != null) {
            command.add("--pts-setup");
            command.add(ptsSetupPath.getPath());
        }

        command.addAll(Arrays.asList(args));

        ProcessBuilder builder = new ProcessBuilder(command);

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

    /**
     * Some services (notably RootCanal) run on the Host side of Cuttlefish, whereas we only have
     * access to Device ports to use for ADB port forwarding. Therefore, we need to forward ports
     * from the Device into the Host, so we can reach them from the local machine.
     */
    private Thread createHostToDevicePassthrough(
            ITestDevice testDevice, int hostPort, int devicePort)
            throws DeviceNotAvailableException {
        String command =
                String.format(
                        "nohup nc -L -p %s nc %s %s 2>/dev/null 1>/dev/null & echo $!",
                        devicePort, HOST_IP_CF, hostPort);
        CommandResult result = testDevice.executeShellV2Command(command);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Port passthrough command failed: " + result.getExitCode());
        }

        int pid = Integer.parseInt(result.getStdout().trim());

        CLog.i("Port passthrough pid: %s for RootCanal port: %s", pid, hostPort);

        // Wait a bit and see if pid is still there
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            CLog.e("sleep interrupted");
        }
        CommandResult psResult = testDevice.executeShellV2Command(String.format("ps -p %s", pid));
        if (psResult.getExitCode() != 0) {
            throw new RuntimeException(
                    String.format("Port passthrough for RootCanal port %d died", hostPort));
        }

        // Since our test may be interrupted, we want to do our best to avoid
        // polluting the test device, as it persists across tests
        Thread hook =
                new Thread(
                        () -> {
                            try {
                                testDevice.executeShellV2Command(String.format("kill %s", pid));
                            } catch (DeviceNotAvailableException e) {
                                CLog.e(
                                        "Can not kill passthrough for RootCanal port %s,"
                                                + " device not found",
                                        hostPort);
                            }
                        });
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /**
     * Execute a shutdown hook and remove it from the runtime's shutdown hooks, to clean it up while
     * keeping the JVM running.
     */
    private void completeShutdownHook(Thread hook) {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
            hook.start();
        } catch (IllegalStateException e) {
            // If we are already in the process of shutting down do nothing
        }
    }

    private void adbForwardPort(ITestDevice testDevice, int port)
            throws DeviceNotAvailableException {
        testDevice.executeAdbCommand(
                "forward", String.format("tcp:%s", port), String.format("tcp:%s", port));
    }

    private void adbForwardRemovePort(ITestDevice testDevice, int port)
            throws DeviceNotAvailableException {
        testDevice.executeAdbCommand("forward", "--remove", String.format("tcp:%s", port));
    }
}
