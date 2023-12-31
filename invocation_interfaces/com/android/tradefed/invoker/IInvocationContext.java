/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationDescriptor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.RecoveryMode;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.UniqueMultiMap;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds information about the Invocation for the tests to access if needed. Tests should not modify
 * the context contained here so only getters will be available, except for the context attributes
 * for reporting purpose.
 */
public interface IInvocationContext extends Serializable {

    /** Key used for storing associated invocation ID. */
    public static final String INVOCATION_ID = "invocation-id";

    @Deprecated
    public enum TimingEvent {
        FETCH_BUILD,
        SETUP;
    }

    /** @return associated invocation ID or {@code null} if not linked to an invocation */
    public String getInvocationId();

    /**
     * Return the number of devices allocated for the invocation.
     */
    public int getNumDevicesAllocated();

    /**
     * Add a ITestDevice to be tracked by the meta data when the device is allocated.
     * will set the build info to null in the map.
     *
     * @param deviceName the device configuration name to associate with the {@link ITestDevice}
     * @param testDevice to be added to the allocated devices.
     */
    public void addAllocatedDevice(String deviceName, ITestDevice testDevice);

    /**
     * Track a map of configuration device name associated to a {@link ITestDevice}. Doesn't clear
     * the previous tracking before adding.
     *
     * @param deviceWithName the {@link Map} of additional device to track
     */
    public void addAllocatedDevice(Map<String, ITestDevice> deviceWithName);

    /**
     * Return the map of Device/build info association
     */
    public Map<ITestDevice, IBuildInfo> getDeviceBuildMap();

    /**
     * Return all the allocated device tracked for this invocation.
     */
    public List<ITestDevice> getDevices();

    /**
     * Return all the {@link IBuildInfo} tracked for this invocation.
     */
    public List<IBuildInfo> getBuildInfos();

    /**
     * Return the list of serials of the device tracked in this invocation
     */
    public List<String> getSerials();

    /**
     * Return the list of device config names of the device tracked in this invocation
     */
    public List<String> getDeviceConfigNames();

    /**
     * Return the {@link ITestDevice} associated with the device configuration name provided.
     */
    public ITestDevice getDevice(String deviceName);

    /**
     * Returns the {@link ITestDevice} associated with the serial provided.
     * Refrain from using too much as it's not the fastest lookup.
     */
    public ITestDevice getDeviceBySerial(String serial);

    /**
     * Returns the name of the device set in the xml configuration from the {@link ITestDevice}.
     * Returns null, if ITestDevice cannot be matched.
     */
    public String getDeviceName(ITestDevice device);

    /**
     * Returns the name of device set in the xml configuration from the {@link IBuildInfo}. Returns
     * null if the IBuildInfo cannot be matched
     */
    public String getBuildInfoName(IBuildInfo info);

    /**
     * Return the {@link IBuildInfo} associated with the device configuration name provided. Returns
     * null, if the deviceName cannot be matched.
     */
    public IBuildInfo getBuildInfo(String deviceName);

    /**
     * Return the {@link IBuildInfo} associated with the {@link ITestDevice}
     */
    public IBuildInfo getBuildInfo(ITestDevice testDevice);

    /**
     * Add a {@link IBuildInfo} to be tracked with the device configuration name.
     *
     * @param deviceName the device configuration name
     * @param buildinfo a {@link IBuildInfo} associated to the device configuration name.
     */
    public void addDeviceBuildInfo(String deviceName, IBuildInfo buildinfo);

    /**
     * Add an Invocation attribute.
     */
    public void addInvocationAttribute(String attributeName, String attributeValue);

    /** Add several invocation attributes at once through a {@link UniqueMultiMap}. */
    public void addInvocationAttributes(MultiMap<String, String> attributesMap);

    /** Returns a copy of the map containing all the invocation attributes. */
    public MultiMap<String, String> getAttributes();

    /** Add a invocation timing metric. */
    @Deprecated
    public default void addInvocationTimingMetric(TimingEvent timingEvent, Long durationMillis) {
        // Do nothing
    }

    /** Returns the map containing the invocation timing metrics. */
    @Deprecated
    public default Map<TimingEvent, Long> getInvocationTimingMetrics() {
        return new HashMap<>();
    }

    /** Sets the descriptor associated with the test configuration that launched the invocation */
    public void setConfigurationDescriptor(ConfigurationDescriptor configurationDescriptor);

    /**
     * Returns the descriptor associated with the test configuration that launched the invocation
     */
    public ConfigurationDescriptor getConfigurationDescriptor();

    /** Sets the invocation context of module while being executed as part of a suite. */
    public void setModuleInvocationContext(IInvocationContext invocationContext);

    /** Returns the invocation context of module while being executed as part of a suite. */
    public IInvocationContext getModuleInvocationContext();

    /** Returns the invocation test-tag. */
    public String getTestTag();

    /**
     * Sets the invocation test-tag.
     */
    public void setTestTag(String testTag);

    /** Returns whether or not the devices were released early and won't be used anymore. */
    public boolean wasReleasedEarly();

    /** Mark the devices are getting released early. */
    public void markReleasedEarly();

    /**
     * Sets the {@link RecoveryMode} of all the devices part of the context
     */
    public void setRecoveryModeForAllDevices(RecoveryMode mode);

    /**
     * Add a serial to be tracked as assigned to one of the shard running some tests.
     *
     * @param index the index of the shard using the serials
     * @param serials The list of serials to be tracked.
     */
    public void addSerialsFromShard(Integer index, List<String> serials);

    /**
     * Returns the Map of all tracked serials and their shard involved in sharding. Empty if not a
     * sharded invocation.
     */
    public Map<Integer, List<String>> getShardsSerials();

    /** Serialize a the context instance into a protobuf. */
    public com.android.tradefed.invoker.proto.InvocationContext.Context toProto();

    /**
     * A convenient method for retrieving invocation context attribute as a single key-value pair.
     * Never return null.
     *
     * @return empty string when key doesn't exist, the value otherwise.
     */
    public default String getAttribute(String key) {
        List<String> values = getAttributes().get(key);
        if (values == null || values.isEmpty()) {
            return "";
        }
        String value = values.get(0);
        if (value == null) {
            return "";
        }
        return value;
    }
}
