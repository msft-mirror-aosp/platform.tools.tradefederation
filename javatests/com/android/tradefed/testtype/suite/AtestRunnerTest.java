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
package com.android.tradefed.testtype.suite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.incremental.IIncrementalSetup;
import com.android.tradefed.testtype.Abi;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.InstrumentationTest;
import com.android.tradefed.testtype.UiAutomatorTest;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for {@link AtestRunner}. */
@RunWith(JUnit4.class)
public class AtestRunnerTest {

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    private static final String TEST_CONFIG =
        "<configuration description=\"Runs a stub tests part of some suite\">\n"
            + "    <test class=\"com.android.tradefed.testtype.suite.SuiteModuleLoaderTest"
            + "$TestInject\" />\n"
            + "</configuration>";

    private static final String ABI = "armeabi-v7a";
    private static final String TEST_NAME_FMT = ABI + " %s";
    private static final String INSTRUMENTATION_TEST_NAME =
            String.format(TEST_NAME_FMT, "tf/instrumentation");

    private AbiAtestRunner mRunner;
    private OptionSetter setter;
    private IConfiguration mConfig;
    private IDeviceConfiguration mDeviceConfig;
    private IDeviceConfiguration mDeviceConfig2;
    private IDeviceBuildInfo mBuildInfo;
    private ITestDevice mMockDevice;
    private String classA = "fully.qualified.classA";
    private String classB = "fully.qualified.classB";
    private String method1 = "method1";

    /**
     * Test AtestRunner that hardcodes the abis to avoid failures related to running the tests
     * against a particular abi build of tradefed.
     */
    public static class AbiAtestRunner extends AtestRunner {
        @Override
        public Set<IAbi> getAbis(ITestDevice device) throws DeviceNotAvailableException {
            Set<IAbi> abis = new LinkedHashSet<>();
            abis.add(new Abi(ABI, AbiUtils.getBitness(ABI)));
            return abis;
        }
    }

    @Before
    public void setUp() throws Exception {
        mRunner = new AbiAtestRunner();
        mBuildInfo = mock(IDeviceBuildInfo.class);
        mMockDevice = mock(ITestDevice.class);
        mRunner.setBuild(mBuildInfo);
        mRunner.setDevice(mMockDevice);
        mConfig = mock(IConfiguration.class);
        mDeviceConfig = mock(IDeviceConfiguration.class);

        when(mBuildInfo.getTestsDir()).thenReturn(mTempFolder.newFolder());

        CommandResult result = new CommandResult(CommandStatus.SUCCESS);
        result.setStdout("Supported states: [\n" +
                " DeviceState{identifier=0, name='DEFAULT'},\n" +
                "]\n");
        when(mMockDevice.executeShellV2Command("cmd device_state print-states"))
                .thenReturn(result);
    }

    @Test
    public void testLoadTests_one() throws Exception {
        setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("include-filter", "tf/fake");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        assertTrue(configMap.containsKey(String.format(TEST_NAME_FMT, "tf/fake")));
    }

    @Test
    public void testLoadTests_two() throws Exception {
        setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("include-filter", "tf/fake");
        setter.setOptionValue("include-filter", "tf/func");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey(String.format(TEST_NAME_FMT, "tf/fake")));
        assertTrue(configMap.containsKey(String.format(TEST_NAME_FMT, "tf/func")));
    }

    @Test
    public void testLoadTests_filter() throws Exception {
        setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("include-filter", "tf/uiautomator");
        setter.setOptionValue("atest-include-filter", "tf/uiautomator:" + classA);
        setter.setOptionValue("atest-include-filter", "tf/uiautomator:" + classB + "#" + method1);
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        String testName = String.format(TEST_NAME_FMT, "tf/uiautomator");
        assertTrue(configMap.containsKey(testName));
        IConfiguration config = configMap.get(testName);
        List<IRemoteTest> tests = config.getTests();
        assertEquals(1, tests.size());
        UiAutomatorTest test = (UiAutomatorTest) tests.get(0);
        List<String> classFilters = new ArrayList<>();
        classFilters.add(classA);
        classFilters.add(classB + "#" + method1);
        assertEquals(classFilters, test.getClassNames());
    }

    @Test
    public void testLoadTests_WithTFConfigSpecified() throws Exception {
        setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "suite");
        setter.setOptionValue("tf-config-path", "suite/base-suite1");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        String testName = String.format(TEST_NAME_FMT, "suite/base-suite1");
        assertTrue(configMap.containsKey(testName));
    }

    @Test
    public void testLoadTests_WithModuleAndTFConfigSpecified() throws Exception {
        File tmpDir = FileUtil.createTempDir("some-dir");
        String filePath = createModuleConfig(tmpDir, "TestModule");
        try {
            mRunner.setupFilters(tmpDir);
            setter = new OptionSetter(mRunner);
            setter.setOptionValue("suite-config-prefix", "suite");
            setter.setOptionValue("tf-config-path", "suite/base-suite1");
            setter.setOptionValue("module-config-path", filePath);
            LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
            assertEquals(2, configMap.size());
            String testName = String.format(TEST_NAME_FMT, "TestModule");
            assertTrue(configMap.containsKey(testName));
            testName = String.format(TEST_NAME_FMT, "suite/base-suite1");
            assertTrue(configMap.containsKey(testName));
        } finally {
            FileUtil.recursiveDelete(tmpDir);
        }
    }

    @Test
    public void testLoadTests_WithModuleConfigSpecified() throws Exception {
        File tmpDir = FileUtil.createTempDir("some-dir");
        String filePath = createModuleConfig(tmpDir, "TestModule");
        try {
            mRunner.setupFilters(tmpDir);
            setter = new OptionSetter(mRunner);
            setter.setOptionValue("module-config-path", filePath);
            LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
            assertEquals(1, configMap.size());
            String testName = String.format(TEST_NAME_FMT, "TestModule");
            assertTrue(configMap.containsKey(testName));
        } finally {
            FileUtil.recursiveDelete(tmpDir);
        }
    }

    @Test
    public void testLoadTests_ignoreFilter() throws Exception {
        setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "suite");
        setter.setOptionValue("include-filter", "suite/base-suite1");
        setter.setOptionValue("atest-include-filter", "suite/base-suite1:" + classA);
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        String testName = String.format(TEST_NAME_FMT, "suite/base-suite1");
        assertTrue(configMap.containsKey(testName));
        IConfiguration config = configMap.get(testName);
        List<IRemoteTest> tests = config.getTests();
        assertEquals(1, tests.size());
        BaseTestSuite test = (BaseTestSuite) tests.get(0);
        assertEquals(new HashSet<String>(), test.getIncludeFilter());
    }

    @Test
    public void testWaitForDebugger() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("wait-for-debugger", "true");
        setter.setOptionValue("include-filter", "tf/instrumentation");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get(INSTRUMENTATION_TEST_NAME);
        IRemoteTest test = config.getTests().get(0);
        assertTrue(((InstrumentationTest) test).getDebug());
    }

    @Test
    public void testdisableTargetPreparers() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("disable-target-preparers", "true");
        setter.setOptionValue("include-filter", "tf/instrumentation");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get(INSTRUMENTATION_TEST_NAME);
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            assertTrue(targetPreparer.isDisabled());
        }
    }

    @Test
    public void testdisableTargetPreparersUnset() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("include-filter", "tf/instrumentation");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get(INSTRUMENTATION_TEST_NAME);
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            assertTrue(!targetPreparer.isDisabled());
        }
    }

    @Test
    public void testDisableTearDown() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("disable-teardown", "true");
        setter.setOptionValue("include-filter", "tf/instrumentation");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        IConfiguration config = configMap.get(INSTRUMENTATION_TEST_NAME);
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            assertTrue(targetPreparer.isTearDownDisabled());
        }
    }

    @Test
    public void testDisableTearDownUnset() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("suite-config-prefix", "tf");
        setter.setOptionValue("include-filter", "tf/instrumentation");
        LinkedHashMap<String, IConfiguration> configMap = mRunner.loadTests();
        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get(INSTRUMENTATION_TEST_NAME);
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            assertTrue(!targetPreparer.isTearDownDisabled());
        }
    }

    @Test
    public void testCreateModuleListener() throws Exception {
        OptionSetter setter = new OptionSetter(mRunner);
        setter.setOptionValue("subprocess-report-port", "55555");
        List<ITestInvocationListener> listeners = mRunner.createModuleListeners();
        assertEquals(1, listeners.size());
    }

    @Test
    public void testIncrementalSetup_defaultNoChangeExpectedForTargetPreparers() throws Exception {
        List<ITargetPreparer> targetPreparers = new ArrayList<>();
        PseudoTargetPreparer preparer = spy(new PseudoTargetPreparer());
        targetPreparers.add(preparer);
        List<ITargetPreparer> targetPreparers2 = new ArrayList<>();
        PseudoTargetPreparer preparer2 = spy(new PseudoTargetPreparer());
        targetPreparers2.add(preparer2);
        List<IDeviceConfiguration> deviceConfigs = new ArrayList<>();
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        when(deviceConfig.getTargetPreparers()).thenReturn(targetPreparers);
        deviceConfigs.add(deviceConfig);
        when(mDeviceConfig.getTargetPreparers()).thenReturn(targetPreparers2);
        deviceConfigs.add(mDeviceConfig);
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);
        when(mConfig.getName()).thenReturn("custom-configuration");
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);

        LinkedHashMap<String, IConfiguration> pseudoConfigMap = new LinkedHashMap<>();
        pseudoConfigMap.put("pseudo-config", mConfig);
        AbiAtestRunner runner = spy(mRunner);
        doReturn(pseudoConfigMap).when(runner).loadingStrategy(any(), any(), any(), any());

        OptionSetter setter = new OptionSetter(runner);

        LinkedHashMap<String, IConfiguration> configMap = runner.loadTests();

        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get("pseudo-config");
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            verify((IIncrementalSetup) targetPreparer, times(0))
                .setIncrementalSetupEnabled(false);
            verify((IIncrementalSetup) targetPreparer, times(0))
                .setIncrementalSetupEnabled(true);
        }
    }

    @Test
    public void testIncrementalSetup_disabledForTargetPreparers() throws Exception {
        List<ITargetPreparer> targetPreparers = new ArrayList<>();
        PseudoTargetPreparer preparer = spy(new PseudoTargetPreparer());
        targetPreparers.add(preparer);
        List<ITargetPreparer> targetPreparers2 = new ArrayList<>();
        PseudoTargetPreparer preparer2 = spy(new PseudoTargetPreparer());
        targetPreparers2.add(preparer2);
        List<IDeviceConfiguration> deviceConfigs = new ArrayList<>();
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        when(deviceConfig.getTargetPreparers()).thenReturn(targetPreparers);
        deviceConfigs.add(deviceConfig);
        when(mDeviceConfig.getTargetPreparers()).thenReturn(targetPreparers2);
        deviceConfigs.add(mDeviceConfig);
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);
        when(mConfig.getName()).thenReturn("custom-configuration");
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);

        LinkedHashMap<String, IConfiguration> pseudoConfigMap = new LinkedHashMap<>();
        pseudoConfigMap.put("pseudo-config", mConfig);
        AbiAtestRunner runner = spy(mRunner);
        doReturn(pseudoConfigMap).when(runner).loadingStrategy(any(), any(), any(), any());

        OptionSetter setter = new OptionSetter(runner);
        setter.setOptionValue("incremental-setup", "NO");

        LinkedHashMap<String, IConfiguration> configMap = runner.loadTests();

        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get("pseudo-config");
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            verify((IIncrementalSetup) targetPreparer).setIncrementalSetupEnabled(false);
            verify((IIncrementalSetup) targetPreparer, times(0))
                .setIncrementalSetupEnabled(true);
        }
    }

    @Test
    public void testIncrementalSetup_enabledForTargetPreparers() throws Exception {
        List<ITargetPreparer> targetPreparers = new ArrayList<>();
        PseudoTargetPreparer preparer = spy(new PseudoTargetPreparer());
        targetPreparers.add(preparer);
        List<ITargetPreparer> targetPreparers2 = new ArrayList<>();
        PseudoTargetPreparer preparer2 = spy(new PseudoTargetPreparer());
        targetPreparers2.add(preparer2);
        List<IDeviceConfiguration> deviceConfigs = new ArrayList<>();
        IDeviceConfiguration deviceConfig = mock(IDeviceConfiguration.class);
        when(deviceConfig.getTargetPreparers()).thenReturn(targetPreparers);
        deviceConfigs.add(deviceConfig);
        when(mDeviceConfig.getTargetPreparers()).thenReturn(targetPreparers2);
        deviceConfigs.add(mDeviceConfig);
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);
        when(mConfig.getName()).thenReturn("custom-configuration");
        when(mConfig.getDeviceConfig()).thenReturn(deviceConfigs);

        LinkedHashMap<String, IConfiguration> pseudoConfigMap = new LinkedHashMap<>();
        pseudoConfigMap.put("pseudo-config", mConfig);
        AbiAtestRunner runner = spy(mRunner);
        doReturn(pseudoConfigMap).when(runner).loadingStrategy(any(), any(), any(), any());

        OptionSetter setter = new OptionSetter(runner);
        setter.setOptionValue("incremental-setup", "YES");

        LinkedHashMap<String, IConfiguration> configMap = runner.loadTests();

        assertEquals(1, configMap.size());
        IConfiguration config = configMap.get("pseudo-config");
        for (ITargetPreparer targetPreparer : config.getTargetPreparers()) {
            verify((IIncrementalSetup) targetPreparer).setIncrementalSetupEnabled(true);
            verify((IIncrementalSetup) targetPreparer, times(0))
                .setIncrementalSetupEnabled(false);
        }
    }

    private String createModuleConfig(File dir, String moduleName) throws IOException {
        File moduleConfig = new File(dir, moduleName + SuiteModuleLoader.CONFIG_EXT);
        FileUtil.writeToFile(TEST_CONFIG, moduleConfig);
        return moduleConfig.getAbsolutePath();
    }

    /** A pseudo target preparer which is optimizable with incremental setup. */
    private static class PseudoTargetPreparer implements ITargetPreparer, IIncrementalSetup {
        @Override
        public void setIncrementalSetupEnabled(boolean shouldEnable) {
            // Intentionally left empty.
        }
    }
}
