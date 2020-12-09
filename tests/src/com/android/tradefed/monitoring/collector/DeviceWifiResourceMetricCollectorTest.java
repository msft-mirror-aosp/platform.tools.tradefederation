/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tradefed.monitoring.collector;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static org.mockito.Mockito.*;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import com.google.dualhomelab.monitoringagent.resourcemonitoring.Resource;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** Tests for {@link DeviceWifiResourceMetricCollector}. */
public class DeviceWifiResourceMetricCollectorTest {
    private static final String MOCK_NO_WIFI_RESPONSE =
            String.join("\n", "RSSI=-9999", "LINKSPEED=0", "NOISE=-119", "FREQUENCY=2412");
    private static final String MOCK_WIFI_RESPONSE =
            String.join(
                    "\n",
                    "RSSI=-76",
                    "LINKSPEED=165",
                    "NOISE=-189",
                    "FREQUENCY=2412",
                    "AVG_RSSI=-77");
    @Mock private IDeviceManager mDeviceManager;
    @Rule public MockitoRule rule = MockitoJUnit.rule();
    private final DeviceWifiResourceMetricCollector mCollector =
            new DeviceWifiResourceMetricCollector();
    private final DeviceDescriptor mDescriptor =
            new DeviceDescriptor() {
                @Override
                public String getSerial() {
                    return "foo";
                }
            };

    /** Tests getting metrics when no wifi connection. */
    @Test
    public void testGetDeviceResourceMetrics_noWifi() {
        when(mDeviceManager.executeCmdOnAvailableDevice(
                        "foo",
                        DeviceWifiResourceMetricCollector.WIFI_METRIC_CMD,
                        500,
                        TimeUnit.MILLISECONDS))
                .thenReturn(
                        new CommandResult() {
                            @Override
                            public CommandStatus getStatus() {
                                return CommandStatus.SUCCESS;
                            }

                            @Override
                            public String getStdout() {
                                return MOCK_NO_WIFI_RESPONSE;
                            }
                        });
        Collection<Resource> resources =
                mCollector.getDeviceResourceMetrics(mDescriptor, mDeviceManager);
        Assert.assertEquals(1, resources.size());
        Resource resource = resources.iterator().next();
        Assert.assertEquals("wifi", resource.getResourceName());
        Assert.assertEquals(1, resource.getMetricCount());
        Assert.assertEquals("speed", resource.getMetric(0).getTag());
        Assert.assertEquals(0.f, resource.getMetric(0).getValue(), 0.f);
    }

    /** Tests getting metrics with full wifi info. */
    @Test
    public void testGetDeviceResourceMetrics_success() {
        when(mDeviceManager.executeCmdOnAvailableDevice(
                        "foo",
                        DeviceWifiResourceMetricCollector.WIFI_METRIC_CMD,
                        500,
                        TimeUnit.MILLISECONDS))
                .thenReturn(
                        new CommandResult() {
                            @Override
                            public CommandStatus getStatus() {
                                return CommandStatus.SUCCESS;
                            }

                            @Override
                            public String getStdout() {
                                return MOCK_WIFI_RESPONSE;
                            }
                        });
        Collection<Resource> resources =
                mCollector.getDeviceResourceMetrics(mDescriptor, mDeviceManager);
        Assert.assertEquals(1, resources.size());
        Resource resource = resources.iterator().next();
        Assert.assertEquals("wifi", resource.getResourceName());
        Assert.assertEquals(165.f, resource.getMetric(0).getValue(), 0.f);
        Assert.assertEquals(-76.f, resource.getMetric(1).getValue(), 0.f);
        Assert.assertEquals(-189.f, resource.getMetric(2).getValue(), 0.f);
    }
}
