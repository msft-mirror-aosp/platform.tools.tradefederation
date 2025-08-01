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
package com.android.tradefed.invoker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationGroupMetricKey;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ILogSaver;
import com.android.tradefed.result.ILogSaverListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.LogSaverResultForwarder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link ShardMainResultForwarder}. */
@RunWith(JUnit4.class)
public class ShardMainResultForwarderTest {
    private ShardMainResultForwarder mShardPrimary;
    @Mock private ITestInvocationListener mMockListener;
    @Mock private LogListenerTestInterface mMockLogListener;
    @Mock private ILogSaver mMockLogSaver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        List<ITestInvocationListener> listListener = new ArrayList<>();
        listListener.add(mMockListener);
        mShardPrimary = new ShardMainResultForwarder(listListener, 2);
    }

    private InvocationContext createContext(int shardIndex) {
        InvocationContext main = new InvocationContext();
        main.setConfigurationDescriptor(new ConfigurationDescriptor());
        main.getConfigurationDescriptor().setShardIndex(shardIndex);
        return main;
    }

    /**
     * Test that build info attributes from each shard are carried to the main build info for the
     * same device.
     */
    @Test
    public void testForwardBuildInfo() {
        IInvocationContext main = new InvocationContext();
        IBuildInfo mainBuild = new BuildInfo();
        main.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        main.addDeviceBuildInfo("device1", mainBuild);
        assertTrue(mainBuild.getBuildAttributes().isEmpty());

        InvocationContext shard1 = createContext(0);
        IBuildInfo shardBuild1 = new BuildInfo();
        shard1.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard1.addDeviceBuildInfo("device1", shardBuild1);
        shardBuild1.addBuildAttribute("shard1", "value1");

        InvocationContext shard2 = createContext(1);
        IBuildInfo shardBuild2 = new BuildInfo();
        shard2.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard2.addDeviceBuildInfo("device1", shardBuild2);
        shardBuild2.addBuildAttribute("shard2", "value2");

        mShardPrimary.invocationStarted(main);
        mShardPrimary.invocationStarted(shard1);
        mShardPrimary.invocationStarted(shard2);
        mShardPrimary.invocationEnded(0L);
        mShardPrimary.invocationEnded(1L);

        assertEquals("value1", mainBuild.getBuildAttributes().get("shard1"));
        assertEquals("value2", mainBuild.getBuildAttributes().get("shard2"));
    }

    /**
     * Test to ensure that even with multi devices, the build attributes of each matching device are
     * copied to the main build.
     */
    @Test
    public void testForwardBuildInfo_multiDevice() {
        IInvocationContext main = new InvocationContext();
        IBuildInfo mainBuild1 = new BuildInfo();
        main.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        main.addDeviceBuildInfo("device1", mainBuild1);
        assertTrue(mainBuild1.getBuildAttributes().isEmpty());
        // Second device
        IBuildInfo mainBuild2 = new BuildInfo();
        main.addAllocatedDevice("device2", Mockito.mock(ITestDevice.class));
        main.addDeviceBuildInfo("device2", mainBuild2);
        assertTrue(mainBuild2.getBuildAttributes().isEmpty());

        InvocationContext shard1 = createContext(0);
        IBuildInfo shardBuild1 = new BuildInfo();
        shard1.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard1.addDeviceBuildInfo("device1", shardBuild1);
        shardBuild1.addBuildAttribute("shard1", "value1");
        // second device on shard 1
        IBuildInfo shardBuild1_2 = new BuildInfo();
        shard1.addAllocatedDevice("device2", Mockito.mock(ITestDevice.class));
        shard1.addDeviceBuildInfo("device2", shardBuild1_2);
        shardBuild1_2.addBuildAttribute("shard1_device2", "value1_device2");

        InvocationContext shard2 = createContext(1);
        IBuildInfo shardBuild2 = new BuildInfo();
        shard2.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard2.addDeviceBuildInfo("device1", shardBuild2);
        shardBuild2.addBuildAttribute("shard2", "value2");
        // second device on shard 2
        IBuildInfo shardBuild2_2 = new BuildInfo();
        shard2.addAllocatedDevice("device2", Mockito.mock(ITestDevice.class));
        shard2.addDeviceBuildInfo("device2", shardBuild2_2);
        shardBuild2_2.addBuildAttribute("shard2_device2", "value2_device2");

        mShardPrimary.invocationStarted(main);
        mShardPrimary.invocationStarted(shard1);
        mShardPrimary.invocationStarted(shard2);
        mShardPrimary.invocationEnded(0L);
        mShardPrimary.invocationEnded(1L);

        assertEquals("value1", mainBuild1.getBuildAttributes().get("shard1"));
        assertEquals("value2", mainBuild1.getBuildAttributes().get("shard2"));
        assertEquals(2, mainBuild1.getBuildAttributes().size());
        assertEquals("value1_device2", mainBuild2.getBuildAttributes().get("shard1_device2"));
        assertEquals("value2_device2", mainBuild2.getBuildAttributes().get("shard2_device2"));
        // Each build only received the matching device build from shards, nothing more.
        assertEquals(2, mainBuild2.getBuildAttributes().size());
    }

    /** Test interface to check a reporter implementing {@link ILogSaverListener}. */
    public interface LogListenerTestInterface extends ITestInvocationListener, ILogSaverListener {}

    /** Test that the log saver is only called once during a sharding setup. */
    @Test
    public void testForward_Sharded() throws Exception {
        // Setup the reporters like in a sharding session
        ShardMainResultForwarder reporter =
                new ShardMainResultForwarder(Arrays.asList(mMockLogListener), 1);
        ShardListener shardListener = new ShardListener(reporter);
        IConfiguration config = new Configuration("", "");
        config.setCommandOptions(new CommandOptions());

        LogSaverResultForwarder invocationLogger =
                new LogSaverResultForwarder(mMockLogSaver, Arrays.asList(shardListener), config);
        IInvocationContext main = new InvocationContext();
        IBuildInfo mainBuild1 = new BuildInfo();
        main.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        main.addDeviceBuildInfo("device1", mainBuild1);

        invocationLogger.invocationStarted(main);
        invocationLogger.testLog(
                "fakeData", LogDataType.TEXT, new ByteArrayInputStreamSource("test".getBytes()));
        invocationLogger.invocationEnded(500L);

        // Log saver only saved the file once.
        Mockito.verify(mMockLogSaver, times(2))
                .saveLogData(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(mMockLogListener, times(1)).invocationStarted(Mockito.eq(main));
        Mockito.verify(mMockLogListener, times(1))
                .testLog(Mockito.any(), Mockito.any(), Mockito.any());
        // The callback was received all the way to the last reporter.
        Mockito.verify(mMockLogListener, times(2))
                .testLogSaved(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(mMockLogListener, times(1)).logAssociation(Mockito.any(), Mockito.any());
        Mockito.verify(mMockLogListener, times(1)).invocationEnded(500L);
    }

    @Test
    public void testForward_contextAttributes() {
        IInvocationContext main = new InvocationContext();
        IBuildInfo mainBuild = new BuildInfo();
        main.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        main.addDeviceBuildInfo("device1", mainBuild);
        assertTrue(mainBuild.getBuildAttributes().isEmpty());

        InvocationContext shard1 = createContext(0);
        shard1.addInvocationAttribute(
                InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type1", "2");
        shard1.addInvocationAttribute(
                InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type2", "5");
        IBuildInfo shardBuild1 = new BuildInfo();
        shard1.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard1.addDeviceBuildInfo("device1", shardBuild1);

        InvocationContext shard2 = createContext(1);
        shard2.addInvocationAttribute(
                InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type1", "4");
        shard2.addInvocationAttribute(
                InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type3", "1");
        IBuildInfo shardBuild2 = new BuildInfo();
        shard2.addAllocatedDevice("device1", Mockito.mock(ITestDevice.class));
        shard2.addDeviceBuildInfo("device1", shardBuild2);

        mShardPrimary.invocationStarted(main);
        mShardPrimary.invocationStarted(shard1);
        mShardPrimary.invocationStarted(shard2);
        mShardPrimary.invocationEnded(0L, shard1);
        mShardPrimary.invocationEnded(1L, shard2);

        Map<String, String> attributes = main.getAttributes().getUniqueMap();
        assertEquals(
                "6",
                attributes.get(
                        InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type1"));
        assertEquals(
                "5",
                attributes.get(
                        InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type2"));
        assertEquals(
                "1",
                attributes.get(
                        InvocationGroupMetricKey.TEST_TYPE_COUNT.toString() + ":" + "type3"));
    }
}
