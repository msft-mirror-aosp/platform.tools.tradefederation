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
package com.android.tradefed.result;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.util.SubprocessTestResultsParser;
import com.android.tradefed.util.proto.TfMetricProtoUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit Tests for {@link LegacySubprocessResultsReporter} The tests are copied from {@link
 * SubprocessResultsReporterTest}
 */
@RunWith(JUnit4.class)
public class LegacySubprocessResultsReporterTest {

    private LegacySubprocessResultsReporter mReporter;

    @Before
    public void setUp() {
        mReporter = new LegacySubprocessResultsReporter(false);
    }

    /** Test deprecated method that when none of the option for reporting is set, nothing happen. */
    @Test
    public void testPrintEvent_Inop() {
        TestIdentifier testId = new TestIdentifier("com.fakeclass", "faketest");
        mReporter.testStarted(testId);
        mReporter.testFailed(testId, "fake failure");
        mReporter.testEnded(testId, Collections.emptyMap());
        mReporter.printEvent(null, null);
    }

    /**
     * Test deprecate method that events sent through the socket reporting part are received on the
     * other hand.
     */
    @Test
    public void testPrintEvent_printToSocket() throws Exception {
        TestIdentifier testId = new TestIdentifier("com.fakeclass", "faketest");
        TestDescription testDescrip = new TestDescription("com.fakeclass", "faketest");
        ITestInvocationListener mMockListener = mock(ITestInvocationListener.class);
        SubprocessTestResultsParser receiver =
                new SubprocessTestResultsParser(mMockListener, true, new InvocationContext());
        try {
            OptionSetter setter = new OptionSetter(mReporter);
            setter.setOptionValue(
                    "subprocess-report-port", Integer.toString(receiver.getSocketServerPort()));
            // mirror calls between receiver and sender.

            mReporter.testIgnored(testId);
            mReporter.testAssumptionFailure(testId, "fake trace");
            mReporter.testRunFailed("no reason");
            mReporter.invocationFailed(new Throwable());
            mReporter.close();
            receiver.joinReceiver(500);

            verify(mMockListener).testIgnored(testDescrip);
            verify(mMockListener).testAssumptionFailure(testDescrip, "fake trace");
            verify(mMockListener)
                    .testRunFailed(FailureDescription.create("no reason", FailureStatus.UNSET));
            verify(mMockListener).invocationFailed((Throwable) Mockito.any());
        } finally {
            receiver.close();
        }
    }

    /**
     * Test deprecate method that events sent through the socket reporting part are received on the
     * other hand.
     */
    @Test
    public void testPrintEvent_legacyMethodCalls() throws Exception {
        ITestInvocationListener mMockListener = mock(ITestInvocationListener.class);
        SubprocessTestResultsParser receiver =
                new SubprocessTestResultsParser(mMockListener, true, new InvocationContext());
        try {
            OptionSetter setter = new OptionSetter(mReporter);
            setter.setOptionValue(
                    "subprocess-report-port", Integer.toString(receiver.getSocketServerPort()));
            // mirror calls between receiver and sender.
            Map<String, String> map = new HashMap<>();
            map.put("key1", "value1");
            map.put("key2", "value2");

            mReporter.testRunStarted("test run", 2);
            mReporter.testRunEnded(50, map);
            mReporter.close();
            receiver.joinReceiver(500);

            verify(mMockListener)
                    .testRunStarted(
                            Mockito.eq("test run"),
                            Mockito.eq(2),
                            Mockito.eq(0),
                            Mockito.anyLong());
            verify(mMockListener).testRunEnded(50, TfMetricProtoUtil.upgradeConvert(map));
        } finally {
            receiver.close();
        }
    }
}
