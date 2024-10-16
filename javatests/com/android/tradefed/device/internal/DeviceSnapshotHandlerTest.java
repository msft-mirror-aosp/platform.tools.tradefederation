/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tradefed.device.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.error.HarnessRuntimeException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.service.TradefedFeatureClient;
import com.android.tradefed.util.SerializationUtil;

import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link DeviceSnapshotHandler}. */
@RunWith(JUnit4.class)
public class DeviceSnapshotHandlerTest {

    private DeviceSnapshotHandler mHandler;
    private IInvocationContext mContext;
    @Mock TradefedFeatureClient mMockClient;
    @Mock ITestDevice mMockDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = new InvocationContext();
        mHandler = new DeviceSnapshotHandler(mMockClient, mContext);
    }

    @Test
    public void testSnapshot() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        mHandler.snapshotDevice(mMockDevice, "random_id");
    }

    @Test
    public void testSnapshot_error() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(ErrorInfo.newBuilder().setErrorTrace("random error"));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.snapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getMessage().contains("random error"));
        }
    }

    @Test
    public void testSnapshot_dnae() throws Exception {
        DeviceNotAvailableException e = new DeviceNotAvailableException("dnae", "serial");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.snapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (DeviceNotAvailableException expected) {
            // Expected
        }
    }

    @Test
    public void testSnapshot_runtime() throws Exception {
        Exception e = new RuntimeException("runtime");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.snapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getCause() instanceof RuntimeException);
        }
    }

    @Test
    public void testSnapshot_parseDuration() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setResponse(
                "Attempting snapshot device on device-1.  Snapshot finished in 999 ms.");
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        InvocationMetricLogger.clearInvocationMetrics();
        mHandler.snapshotDevice(mMockDevice, "random_id");
        mHandler.snapshotDevice(mMockDevice, "random_id2");
        String durations =
                InvocationMetricLogger.getInvocationMetrics()
                        .getOrDefault(
                                InvocationMetricLogger.InvocationMetricKey.DEVICE_SNAPSHOT_DURATIONS
                                        .toString(),
                                "0");
        assertEquals("999,999", durations);
    }

    @Test
    public void testRestoreSnapshot() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
    }

    @Test
    public void testRestoreSnapshot_error() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(ErrorInfo.newBuilder().setErrorTrace("random error"));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getMessage().contains("random error"));
        }
    }

    @Test
    public void testRestoreSnapshot_dnae() throws Exception {
        DeviceNotAvailableException e = new DeviceNotAvailableException("dnae", "serial");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (DeviceNotAvailableException expected) {
            // Expected
        }
    }

    @Test
    public void testRestoreSnapshot_runtime() throws Exception {
        Exception e = new RuntimeException("runtime");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getCause() instanceof RuntimeException);
        }
    }

    @Test
    public void testRestoreSnapshot_parseDuration() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setResponse(
                "Attempting restore device snapshot on device-1.  Restoring snapshot finished in"
                        + " 999 ms.");
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        InvocationMetricLogger.clearInvocationMetrics();
        mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
        mHandler.restoreSnapshotDevice(mMockDevice, "random_id");
        String durations =
                InvocationMetricLogger.getInvocationMetrics()
                        .getOrDefault(
                                InvocationMetricLogger.InvocationMetricKey
                                        .DEVICE_SNAPSHOT_RESTORE_DURATIONS
                                        .toString(),
                                "0");
        assertEquals("999,999", durations);
        String count =
                InvocationMetricLogger.getInvocationMetrics()
                        .getOrDefault(
                                InvocationMetricLogger.InvocationMetricKey
                                        .DEVICE_SNAPSHOT_RESTORE_SUCCESS_COUNT
                                        .toString(),
                                "0");
        assertEquals("2", count);
    }

    @Test
    public void testDeleteSnapshot() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        mHandler.snapshotDevice(mMockDevice, "random_id");
    }

    @Test
    public void testDeleteSnapshot_error() throws Exception {
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(ErrorInfo.newBuilder().setErrorTrace("random error"));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.deleteSnapshot(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getMessage().contains("random error"));
        }
    }

    @Test
    public void testDeleteSnapshot_dnae() throws Exception {
        DeviceNotAvailableException e = new DeviceNotAvailableException("dnae", "serial");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.deleteSnapshot(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (DeviceNotAvailableException expected) {
            // Expected
        }
    }

    @Test
    public void testDeleteSnapshot_runtime() throws Exception {
        Exception e = new RuntimeException("runtime");
        FeatureResponse.Builder responseBuilder = FeatureResponse.newBuilder();
        responseBuilder.setErrorInfo(
                ErrorInfo.newBuilder().setErrorTrace(SerializationUtil.serializeToString(e)));
        when(mMockClient.triggerFeature(any(), any())).thenReturn(responseBuilder.build());

        try {
            mHandler.deleteSnapshot(mMockDevice, "random_id");
            fail("Should have thrown an exception");
        } catch (HarnessRuntimeException expected) {
            // Expected
            assertTrue(expected.getCause() instanceof RuntimeException);
        }
    }
}
