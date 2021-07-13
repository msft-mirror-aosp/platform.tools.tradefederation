/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.testtype;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

/** Unit tests for {@link DeviceTestSuite}. */
@RunWith(JUnit4.class)
public class DeviceTestSuiteTest {

    public static class MockTest extends DeviceTestCase {

        public void test1() {
            // Metrics are also available for test within Suite
            addTestMetric("key1", "metric1");
        }

        public void test2() {}
    }

    public static class MockAbortTest extends DeviceTestCase {

        private static final String EXCEP_MSG = "failed";
        private static final String FAKE_SERIAL = "fakeserial";

        public void test1() throws DeviceNotAvailableException {
            throw new DeviceNotAvailableException(EXCEP_MSG, FAKE_SERIAL);
        }
    }

    private TestInformation mTestInfo;

    @Before
    public void setUp() {
        mTestInfo = TestInformation.newBuilder().build();
    }

    /** Verify that calling run on a DeviceTestSuite will run all test methods. */
    @Test
    public void testRun_suite() throws Exception {
        DeviceTestSuite suite = new DeviceTestSuite();
        suite.addTestSuite(MockTest.class);

        ITestInvocationListener listener = mock(ITestInvocationListener.class);

        final TestDescription test1 = new TestDescription(MockTest.class.getName(), "test1");
        final TestDescription test2 = new TestDescription(MockTest.class.getName(), "test2");

        Map<String, String> metrics = new HashMap<>();
        metrics.put("key1", "metric1");

        suite.run(mTestInfo, listener);

        verify(listener).testRunStarted(DeviceTestSuite.class.getName(), 2);
        verify(listener).testStarted(test1);
        verify(listener).testEnded(test1, TfMetricProtoUtil.upgradeConvert(metrics));
        verify(listener).testStarted(test2);
        verify(listener).testEnded(test2, new HashMap<String, Metric>());
        verify(listener).testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
    }

    /** Verify that a device not available exception is thrown up. */
    @Test
    public void testRun_deviceNotAvail() {
        DeviceTestSuite suite = new DeviceTestSuite();
        suite.addTestSuite(MockAbortTest.class);

        // create a mock ITestInvocationListener, because results are easier to verify
        ITestInvocationListener listener = mock(ITestInvocationListener.class);

        final TestDescription test1 = new TestDescription(MockAbortTest.class.getName(), "test1");

        try {
            suite.run(mTestInfo, listener);
            fail("DeviceNotAvailableException not thrown");
        } catch (DeviceNotAvailableException e) {
            // expected
        }

        verify(listener).testRunStarted(DeviceTestSuite.class.getName(), 1);
        verify(listener).testStarted(test1);
        verify(listener).testFailed(Mockito.eq(test1), Mockito.contains(MockAbortTest.EXCEP_MSG));
        verify(listener).testEnded(test1, new HashMap<String, Metric>());
        verify(listener).testRunFailed(Mockito.contains(MockAbortTest.EXCEP_MSG));
        verify(listener).testRunEnded(Mockito.anyLong(), Mockito.<HashMap<String, Metric>>any());
    }
}
