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
package com.android.tradefed.invoker.shard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationFactory;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IGlobalConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.metric.BaseDeviceMetricCollector;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.IRescheduler;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.suite.checker.KeyguardStatusChecker;
import com.android.tradefed.testtype.HostTest;
import com.android.tradefed.testtype.HostTestTest.SuccessTestCase;
import com.android.tradefed.testtype.HostTestTest.TestMetricTestCase;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.StubTest;
import com.android.tradefed.testtype.suite.BaseTestSuite;
import com.android.tradefed.testtype.suite.ITestSuite;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.keystore.IKeyStoreClient;
import com.android.tradefed.util.keystore.KeyStoreException;
import com.android.tradefed.util.keystore.StubKeyStoreFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link ShardHelper}. */
@RunWith(JUnit4.class)
public class ShardHelperTest {

    private static final String TEST_CONFIG =
            "<configuration description=\"shard config test\">\n"
                    + "    <%s class=\"%s\" />\n"
                    + "</configuration>";

    private ShardHelper mHelper;
    private IConfiguration mConfig;
    private ILogSaver mMockLogSaver;
    private TestInformation mTestInfo;
    private IInvocationContext mContext;
    private IRescheduler mRescheduler;
    private IBuildInfo mBuildInfo;

    @Before
    public void setUp() {
        mHelper =
                new ShardHelper() {
                    @Override
                    protected IGlobalConfiguration getGlobalConfiguration() {
                        try {
                            return ConfigurationFactory.getInstance()
                                    .createGlobalConfigurationFromArgs(
                                            new String[] {"empty"}, new ArrayList<>());
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    protected void validateOptions(
                            TestInformation testInfo, IConfiguration config) {
                        // Skip to avoid call to global configuration
                    }
                };
        mConfig = new Configuration("fake_sharding_config", "desc");
        mContext = new InvocationContext();
        mBuildInfo = new BuildInfo();
        mContext.addDeviceBuildInfo(ConfigurationDef.DEFAULT_DEVICE_NAME, mBuildInfo);
        mContext.addAllocatedDevice(
                ConfigurationDef.DEFAULT_DEVICE_NAME, Mockito.mock(ITestDevice.class));
        mTestInfo = TestInformation.newBuilder().setInvocationContext(mContext).build();
        mRescheduler = Mockito.mock(IRescheduler.class);
        mMockLogSaver = Mockito.mock(ILogSaver.class);
        mConfig.setLogSaver(mMockLogSaver);
    }

    @After
    public void tearDown() {
        mBuildInfo.cleanUp();
    }

    /**
     * Tests that when --shard-count is given to local sharding we create shard-count number of
     * shards and not one shard per IRemoteTest.
     */
    @Test
    public void testSplitWithShardCount() throws Exception {
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        // shard-count is the number of shards we are requesting
        setter.setOptionValue("shard-count", "3");
        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"empty"});
        StubTest test = new StubTest();
        setter = new OptionSetter(test);
        // num-shards is a {@link StubTest} option that specify how many tests can stubtest split
        // into.
        setter.setOptionValue("num-shards", "5");
        mConfig.setTest(test);
        assertEquals(1, mConfig.getTests().size());
        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        // Ensure that we did split 1 tests per shard rescheduled.
        Mockito.verify(mRescheduler, Mockito.times(3))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        assertEquals(1, argument.getTests().size());
                                        return true;
                                    }
                                }));
    }

    /** Tests that when --shard-count is not provided we create one shard per IRemoteTest. */
    @Test
    public void testSplit_noShardCount() throws Exception {
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"empty"});
        StubTest test = new StubTest();
        setter = new OptionSetter(test);
        setter.setOptionValue("num-shards", "5");
        mConfig.setTest(test);
        assertEquals(1, mConfig.getTests().size());
        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        // Ensure that we did split 1 tests per shard rescheduled.
        Mockito.verify(mRescheduler, Mockito.times(5))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        assertEquals(1, argument.getTests().size());
                                        return true;
                                    }
                                }));
    }

    /**
     * Tests that when a --shard-count 10 is requested but there is only 5 sub tests after sharding
     * there is no point in rescheduling 10 times so we limit to the number of tests.
     */
    @Test
    public void testSplitWithShardCount_notEnoughTest() throws Exception {
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        setter.setOptionValue("shard-count", "10");
        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"empty"});
        StubTest test = new StubTest();
        setter = new OptionSetter(test);
        // num-shards is a {@link StubTest} option that specify how many tests can stubtest split
        // into.
        setter.setOptionValue("num-shards", "5");
        mConfig.setTest(test);
        assertEquals(1, mConfig.getTests().size());
        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        // We only reschedule 5 times and not 10 like --shard-count because there is not enough
        // tests to put at least 1 test per shard. So there is no point in rescheduling on new
        // devices.
        Mockito.verify(mRescheduler, Mockito.times(5))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        assertEquals(1, argument.getTests().size());
                                        return true;
                                    }
                                }));
    }

    private File createTmpConfig(String objType, Object obj) throws IOException {
        File configFile = FileUtil.createTempFile("shard-helper-test", ".xml");
        String content = String.format(TEST_CONFIG, objType, obj.getClass().getCanonicalName());
        FileUtil.writeToFile(content, configFile);
        return configFile;
    }

    /**
     * Test that some objects are being cloned to the shards in order to avoid shared state issues.
     */
    @Test
    public void testCloneStatusChecker() throws Exception {
        KeyguardStatusChecker checker = new KeyguardStatusChecker();
        File configFile = createTmpConfig(Configuration.SYSTEM_STATUS_CHECKER_TYPE_NAME, checker);
        try {
            CommandOptions options = new CommandOptions();
            OptionSetter setter = new OptionSetter(options);
            // shard-count is the number of shards we are requesting
            setter.setOptionValue("shard-count", "3");
            mConfig.setCommandOptions(options);
            mConfig.setCommandLine(new String[] {configFile.getAbsolutePath()});
            mConfig.setSystemStatusChecker(checker);
            StubTest test = new StubTest();
            setter = new OptionSetter(test);
            // num-shards is a {@link StubTest} option that specify how many tests can stubtest split
            // into.
            setter.setOptionValue("num-shards", "5");
            mConfig.setTest(test);
            assertEquals(1, mConfig.getTests().size());
            assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
            // Ensure that we did split 1 tests per shard rescheduled.
            Mockito.verify(mRescheduler, Mockito.times(3))
                    .scheduleConfig(
                            Mockito.argThat(
                                    new ArgumentMatcher<IConfiguration>() {
                                        @Override
                                        public boolean matches(IConfiguration argument) {
                                            assertEquals(1, argument.getTests().size());
                                            // Status checker is in all shard and a new one has been
                                            // created
                                            assertEquals(
                                                    1, argument.getSystemStatusCheckers().size());
                                            assertNotSame(
                                                    checker,
                                                    argument.getSystemStatusCheckers().get(0));
                                            return true;
                                        }
                                    }));
        } finally {
            FileUtil.deleteFile(configFile);
        }
    }

    /**
     * Test that some objects are being cloned to the shards in order to avoid shared state issues.
     */
    @Test
    public void testCloneMetricCollector() throws Exception {
        BaseDeviceMetricCollector collector = new BaseDeviceMetricCollector();
        File configFile =
                createTmpConfig(Configuration.DEVICE_METRICS_COLLECTOR_TYPE_NAME, collector);
        try {
            CommandOptions options = new CommandOptions();
            OptionSetter setter = new OptionSetter(options);
            // shard-count is the number of shards we are requesting
            setter.setOptionValue("shard-count", "3");
            mConfig.setCommandOptions(options);
            mConfig.setCommandLine(new String[] {configFile.getAbsolutePath()});
            mConfig.setDeviceMetricCollectors(Arrays.asList(collector));
            StubTest test = new StubTest();
            setter = new OptionSetter(test);
            // num-shards is a {@link StubTest} option that specify how many tests can stubtest split
            // into.
            setter.setOptionValue("num-shards", "5");
            mConfig.setTest(test);
            assertEquals(1, mConfig.getTests().size());
            assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
            // Ensure that we did split 1 tests per shard rescheduled.
            Mockito.verify(mRescheduler, Mockito.times(3))
                    .scheduleConfig(
                            Mockito.argThat(
                                    new ArgumentMatcher<IConfiguration>() {
                                        @Override
                                        public boolean matches(IConfiguration argument) {
                                            assertEquals(1, argument.getTests().size());
                                            // Status checker is in all shard and a new one has been
                                            // created
                                            assertEquals(1, argument.getMetricCollectors().size());
                                            assertNotSame(
                                                    collector,
                                                    argument.getMetricCollectors().get(0));
                                            return true;
                                        }
                                    }));
        } finally {
            FileUtil.deleteFile(configFile);
        }
    }

    /**
     * Test that even when sharding, configuration are loaded with the global keystore if needed.
     */
    @Test
    public void testClone_withKeystore() throws Exception {
        IKeyStoreClient mockClient = Mockito.mock(IKeyStoreClient.class);
        mHelper =
                new ShardHelper() {
                    @Override
                    protected IGlobalConfiguration getGlobalConfiguration() {
                        try {
                            IGlobalConfiguration config =
                                    ConfigurationFactory.getInstance()
                                            .createGlobalConfigurationFromArgs(
                                                    new String[] {"empty"}, new ArrayList<>());
                            config.setKeyStoreFactory(
                                    new StubKeyStoreFactory() {
                                        @Override
                                        public IKeyStoreClient createKeyStoreClient()
                                                throws KeyStoreException {
                                            return mockClient;
                                        }
                                    });
                            return config;
                        } catch (ConfigurationException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    protected void validateOptions(
                            TestInformation testInfo, IConfiguration config) {
                        // Skip to avoid call to global configuration
                    }
                };
        CommandOptions options = new CommandOptions();
        HostTest stubTest = new HostTest();
        OptionSetter setter = new OptionSetter(options, stubTest);
        // shard-count is the number of shards we are requesting
        setter.setOptionValue("shard-count", "2");
        setter.setOptionValue("class", SuccessTestCase.class.getName());
        setter.setOptionValue("class", TestMetricTestCase.class.getName());
        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"host", "--class", "USE_KEYSTORE@test"});
        mConfig.setTest(stubTest);

        assertEquals(1, mConfig.getTests().size());

        doReturn(true).when(mockClient).isAvailable();
        doReturn(SuccessTestCase.class.getName()).when(mockClient).fetchKey("test");

        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        // Ensure that we did split 1 tests per shard rescheduled.
        Mockito.verify(mRescheduler, Mockito.times(2))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        assertEquals(1, argument.getTests().size());
                                        return true;
                                    }
                                }));
    }

    /**
     * Test split when a token test is present. The token pool of each shard should be populated.
     */
    @Test
    public void testSplitWithTokens() throws Exception {
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        // shard-count is the number of shards we are requesting
        setter.setOptionValue("shard-count", "3");
        // Enable token sharding
        setter.setOptionValue("enable-token-sharding", "true");

        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"empty"});
        List<IRemoteTest> tests = new ArrayList<>();
        StubTest test = new StubTest();
        setter = new OptionSetter(test);
        // num-shards is a {@link StubTest} option that specify how many tests can stubtest split
        // into.
        setter.setOptionValue("num-shards", "5");
        tests.add(test);
        // Add a token test
        tests.add(new TokenTestClass());
        mConfig.setTests(tests);
        assertEquals(2, mConfig.getTests().size());
        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        // Ensure that we did split 1 tests per shard rescheduled.
        Mockito.verify(mRescheduler, Mockito.times(3))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        assertEquals(1, argument.getTests().size());
                                        IRemoteTest test = argument.getTests().get(0);
                                        assertTrue(test instanceof TestsPoolPoller);
                                        TestsPoolPoller poller = (TestsPoolPoller) test;
                                        // Token pool has the test
                                        assertEquals(1, poller.peekTokenPoolSize());
                                        return true;
                                    }
                                }));
    }

    @Test
    public void testSplitWithMultiDevices() throws Exception {
        CommandOptions options = new CommandOptions();
        OptionSetter setter = new OptionSetter(options);
        setter.setOptionValue("shard-count", "3");
        mConfig.setCommandOptions(options);
        mConfig.setCommandLine(new String[] {"empty"});
        StubTest test = new StubTest();
        setter = new OptionSetter(test);
        setter.setOptionValue("num-shards", "3");
        List<IRemoteTest> tests = new ArrayList<>();
        tests.add(test);
        // Two modules need 2 devices
        tests.add(createShardedModule(2));
        tests.add(createShardedModule(2));
        // One module needs 3 devices
        tests.add(createShardedModule(3));
        mConfig.setTests(tests);
        assertEquals(4, mConfig.getTests().size());
        assertTrue(mHelper.shardConfig(mConfig, mTestInfo, mRescheduler, null));
        List<IConfiguration> capturedConfig = new ArrayList<>();
        Mockito.verify(mRescheduler, Mockito.times(5))
                .scheduleConfig(
                        Mockito.argThat(
                                new ArgumentMatcher<IConfiguration>() {
                                    @Override
                                    public boolean matches(IConfiguration argument) {
                                        capturedConfig.add(argument);
                                        return true;
                                    }
                                }));
        IConfiguration neededDevice2 = capturedConfig.get(0);
        assertEquals(2, neededDevice2.getTests().size());
        assertTrue(neededDevice2.getTests().get(0) instanceof BaseTestSuite);
        assertTrue(neededDevice2.getTests().get(1) instanceof BaseTestSuite);

        IConfiguration neededDevice3 = capturedConfig.get(1);
        assertEquals(1, neededDevice3.getTests().size());
        assertTrue(neededDevice3.getTests().get(0) instanceof BaseTestSuite);

        IConfiguration stub1 = capturedConfig.get(2);
        assertEquals(1, stub1.getTests().size());
        assertTrue(stub1.getTests().get(0) instanceof TestsPoolPoller);
        IConfiguration stub2 = capturedConfig.get(3);
        assertEquals(1, stub2.getTests().size());
        assertTrue(stub2.getTests().get(0) instanceof TestsPoolPoller);
        IConfiguration stub3 = capturedConfig.get(4);
        assertEquals(1, stub3.getTests().size());
        assertTrue(stub3.getTests().get(0) instanceof TestsPoolPoller);
    }

    private ITestSuite createShardedModule(int neededDevice) {
        BaseTestSuite suite = new BaseTestSuite();
        ModuleDefinition module =
                new ModuleDefinition() {
                    @Override
                    public int neededDevices() {
                        return neededDevice;
                    }
                };
        suite.setDirectModule(module);
        return suite;
    }
}
