/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.postprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link BasePostProcessor}. */
@RunWith(JUnit4.class)
public class BasePostProcessorTest {

    private class TestablePostProcessor extends BasePostProcessor {
        @Override
        public Map<String, Metric.Builder> processTestMetrics(HashMap<String, Metric> rawMetrics) {
            HashMap<String, Metric.Builder> newMap = new HashMap<>();
            for (String key : rawMetrics.keySet()) {
                // Change, e.g. "value" to "value2".
                Metric.Builder newBuilder = Metric.newBuilder();
                newBuilder
                        .getMeasurementsBuilder()
                        .setSingleString(
                                rawMetrics.get(key).getMeasurements().getSingleString() + "2");
                // Attempt to overwrite the original metric; should not appear in final result.
                newMap.put(key, newBuilder);
                // Write a new metric.
                newMap.put(key + "2", newBuilder);
            }
            return newMap;
        }

        @Override
        public Map<String, Metric.Builder> processRunMetrics(HashMap<String, Metric> rawMetrics) {
            HashMap<String, Metric.Builder> newMap = new HashMap<>();
            for (String key : rawMetrics.keySet()) {
                // Change, e.g. "value" to "value2".
                Metric.Builder newBuilder = Metric.newBuilder();
                newBuilder
                        .getMeasurementsBuilder()
                        .setSingleString(
                                rawMetrics.get(key).getMeasurements().getSingleString() + "2");
                // Attempt to overwrite the original metric; should not appear in final result.
                newMap.put(key, newBuilder);
                // Write a new metric.
                newMap.put(key + "2", newBuilder);
            }
            return newMap;
        }
    }

    private BasePostProcessor mProcessor;
    private ITestInvocationListener mMockListener;

    @Before
    public void setUp() {
        mProcessor = new TestablePostProcessor();
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
    }

    /** Test that the post processing metrics are found in the final callback. */
    @Test
    public void testPostProcessing() {
        ITestInvocationListener listener = mProcessor.init(mMockListener);
        HashMap<String, Metric> initialMetrics = new HashMap<>();
        initialMetrics.put("test", TfMetricProtoUtil.stringToMetric("value"));

        Capture<HashMap<String, Metric>> capture = new Capture<>();
        mMockListener.testRunEnded(EasyMock.anyLong(), EasyMock.capture(capture));

        EasyMock.replay(mMockListener);
        listener.testRunEnded(0L, initialMetrics);
        EasyMock.verify(mMockListener);

        HashMap<String, Metric> finalMetrics = capture.getValue();
        // Check that original key is still here
        assertTrue(finalMetrics.containsKey("test"));
        // Check that original key still has the original value
        assertTrue(finalMetrics.get("test").getMeasurements().getSingleString().equals("value"));
        // Check that our new metric was added
        assertTrue(finalMetrics.containsKey("test2"));
        assertEquals(DataType.PROCESSED, finalMetrics.get("test2").getType());
        assertTrue(finalMetrics.get("test2").getMeasurements().getSingleString().equals("value2"));
    }

    /** Test that the test metrics are found in the after-test callback. */
    @Test
    public void testPerTestPostProcessing() {
        ITestInvocationListener listener = mProcessor.init(mMockListener);
        HashMap<String, Metric> initialMetrics = new HashMap<>();
        initialMetrics.put("test", TfMetricProtoUtil.stringToMetric("value"));

        Capture<HashMap<String, Metric>> capture = new Capture<>();
        mMockListener.testEnded(
                EasyMock.anyObject(), EasyMock.anyLong(), EasyMock.capture(capture));

        EasyMock.replay(mMockListener);
        listener.testEnded(null, 0L, initialMetrics);
        EasyMock.verify(mMockListener);

        HashMap<String, Metric> processedMetrics = capture.getValue();
        // Check that original key is still here
        assertTrue(processedMetrics.containsKey("test"));
        // Check that original key still has the original value
        assertTrue(
                processedMetrics.get("test").getMeasurements().getSingleString().equals("value"));
        // Check that our new metric was added
        assertTrue(processedMetrics.containsKey("test2"));
        assertEquals(DataType.PROCESSED, processedMetrics.get("test2").getType());
        assertTrue(
                processedMetrics.get("test2").getMeasurements().getSingleString().equals("value2"));
    }
}
