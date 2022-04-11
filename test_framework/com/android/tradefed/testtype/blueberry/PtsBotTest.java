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

package com.android.tradefed.testtype.blueberry;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.IRemoteTest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Run PTS-bot tests. PTS-bot is a complete automation of the Bluetooth Profile Tuning Suite, which
 * is the testing tool provided by the Bluetooth standard to run Bluetooth Host certification tests
 * (see
 * https://www.bluetooth.com/develop-with-bluetooth/qualification-listing/qualification-test-tools/profile-tuning-suite/).
 */
public class PtsBotTest implements IRemoteTest {

    private static final int BLUEBERRY_SERVER_PORT = 8999;
    private static final int HCI_ROOTCANAL_PORT_CUTTLEFISH = 7300;
    private static final int HCI_ROOTCANAL_PORT = 6211;
    private static final int HCI_PROXY_PORT = 1234;

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

    private int hciPort;

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

        CLog.i("Tests config file: %s", testsConfigFile.getPath());
        CLog.i("Profiles to be tested: %s", profiles);

        ITestDevice testDevice = testInfo.getDevice();

        // Forward Blueberry Server port.
        adbForwardPort(testDevice, BLUEBERRY_SERVER_PORT);

        if (!physical) {
            // Check product type to determine Root Canal port.
            hciPort = HCI_ROOTCANAL_PORT_CUTTLEFISH;
            if (!testDevice.getProductType().equals("cutf")) {
                hciPort = HCI_ROOTCANAL_PORT;

                // Forward Root Canal port.
                adbForwardPort(testDevice, hciPort);
            }
        } else {
            hciPort = HCI_PROXY_PORT;
        }

        CLog.i("HCI port: %s", hciPort);

        // Run tests.
        for (String profile : profiles) {
            runPtsBotTestsForProfile(profile, listener);
        }

        // Remove forwarded ports.
        adbForwardRemovePort(testDevice, BLUEBERRY_SERVER_PORT);
        if (!physical && !testDevice.getProductType().equals("cutf")) {
            adbForwardRemovePort(testDevice, HCI_ROOTCANAL_PORT);
        }
    }

    private String[] listPtsBotTestsForProfile(String profile) {
        try {
            ProcessBuilder processBuilder =
                    new ProcessBuilder(
                            "pts-bot", "-c", testsConfigFile.getPath(), "--list", profile);

            CLog.i("Running command: %s", String.join(" ", processBuilder.command()));
            Process process = processBuilder.start();

            BufferedReader stdInput =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line =
                    stdInput.lines().filter(l -> l.startsWith("Tests:")).findFirst().orElse(null);
            stdInput.close();

            if (line != null) {
                String[] tests =
                        line.substring(line.indexOf("[") + 1, line.indexOf("]"))
                                .replaceAll("\"", "")
                                .split(", ");
                return tests;
            }

        } catch (IOException e) {
            CLog.e(e);
            CLog.e("Cannot run pts-bot, make sure it is properly installed");
        }

        CLog.e("No tests have been found in tests config file");
        return null;
    }

    private void runPtsBotTestsForProfile(String profile, ITestInvocationListener listener) {
        String[] tests = listPtsBotTestsForProfile(profile);
        if (tests == null || tests.length == 0) {
            CLog.e("Cannot run PTS-bot for %s, no tests found", profile);
            return;
        } else {
            CLog.i("Available tests for %s: [%s]", profile, String.join(", ", tests));
        }
        Map<String, String> runMetrics = new HashMap<>();

        listener.testRunStarted(profile, tests.length);
        long startTimestamp = System.currentTimeMillis();
        for (int i = 0; i < tests.length; i++) {
            runPtsBotTest(profile, tests[i], listener);
        }
        long endTimestamp = System.currentTimeMillis();
        listener.testRunEnded(endTimestamp - startTimestamp, runMetrics);
    }

    private boolean runPtsBotTest(
            String profile, String testName, ITestInvocationListener listener) {
        TestDescription testDescription = new TestDescription(profile, testName);
        boolean success = false;

        listener.testStarted(testDescription);
        CLog.i(testName);
        try {

            ProcessBuilder processBuilder;
            processBuilder =
                    new ProcessBuilder(
                            "pts-bot",
                            "-c",
                            testsConfigFile.getPath(),
                            "--hci",
                            String.valueOf(hciPort),
                            testName);

            if (mmi2grpc.exists()) {
                // Add mmi2grpc python module path to process builder environment.
                Map<String, String> env = processBuilder.environment();
                env.put("PYTHONPATH", mmi2grpc.getPath());
            }

            CLog.i("Running command: %s", String.join(" ", processBuilder.command()));
            Process process = processBuilder.start();
            // Note: there is no need to implement a timeout here since it is handled in pts-bot.

            BufferedReader stdInput =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));

            BufferedReader stdError =
                    new BufferedReader(new InputStreamReader(process.getErrorStream()));

            Optional<String> lastLine =
                    stdInput.lines().peek(CLog::i).reduce((last, value) -> value);
            // Last line is providing success information.
            success =
                    lastLine.map(
                                    (line) -> {
                                        try {
                                            return Integer.parseInt(
                                                            line.split(", ")[1].substring(0, 1))
                                                    == 1;
                                        } catch (Exception e) {
                                            CLog.e("Failed to parse success");
                                            return false;
                                        }
                                    })
                            .orElse(false);
            stdInput.close();

            stdError.lines().forEach(CLog::e);
            stdError.close();

        } catch (Exception e) {
            CLog.e(e);
            CLog.e("Cannot run pts-bot, make sure it is properly installed");
        }

        if (!success) {
            listener.testFailed(testDescription, "Unknown");
        }

        listener.testEnded(testDescription, Collections.emptyMap());

        return success;
    }

    private void adbForwardPort(ITestDevice testDevice, int port)
            throws DeviceNotAvailableException {
        testDevice.executeAdbCommand(
                1000L, "forward", String.format("tcp:%s", port), String.format("tcp:%s", port));
    }

    private void adbForwardRemovePort(ITestDevice testDevice, int port)
            throws DeviceNotAvailableException {
        testDevice.executeAdbCommand(1000L, "forward", "--remove", String.format("tcp:%s", port));
    }
}
