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
package com.android.tradefed.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link RemoteAndroidDevice}. */
@RunWith(JUnit4.class)
public class RemoteAndroidDeviceTest {

    private static final String MOCK_DEVICE_SERIAL = "localhost:1234";
    @Mock IDevice mMockIDevice;
    @Mock IDeviceStateMonitor mMockStateMonitor;
    @Mock IRunUtil mMockRunUtil;
    @Mock IDeviceMonitor mMockDvcMonitor;
    @Mock IDeviceRecovery mMockRecovery;
    private RemoteAndroidDevice mTestDevice;

    /**
     * A {@link TestDevice} that is suitable for running tests against
     */
    private class TestableRemoteAndroidDevice extends RemoteAndroidDevice {
        public TestableRemoteAndroidDevice() {
            super(mMockIDevice, mMockStateMonitor, mMockDvcMonitor);
        }

        @Override
        protected IRunUtil getRunUtil() {
            return mMockRunUtil;
        }

        @Override
        public String getSerialNumber() {
            return MOCK_DEVICE_SERIAL;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockIDevice.getSerialNumber()).thenReturn(MOCK_DEVICE_SERIAL);

        // A TestDevice with a no-op recoverDevice() implementation
        mTestDevice = new TestableRemoteAndroidDevice();
        mTestDevice.setRecovery(mMockRecovery);
    }

    /** Test {@link RemoteAndroidDevice#adbTcpConnect(String, String)} in a success case. */
    @Test
    public void testAdbConnect() {
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("connected to");
        CommandResult adbResultConfirmation = new CommandResult();
        adbResultConfirmation.setStatus(CommandStatus.SUCCESS);
        adbResultConfirmation.setStdout("already connected to localhost:1234");
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResultConfirmation);

        assertTrue(mTestDevice.adbTcpConnect("localhost", "1234"));
    }

    /** Test {@link RemoteAndroidDevice#adbTcpConnect(String, String)} in a failure case. */
    @Test
    public void testAdbConnect_fails() {
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("cannot connect");
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);

        assertFalse(mTestDevice.adbTcpConnect("localhost", "1234"));
        verify(mMockRunUtil, times(RemoteAndroidDevice.MAX_RETRIES))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL));
        verify(mMockRunUtil, times(RemoteAndroidDevice.MAX_RETRIES)).sleep(Mockito.anyLong());
    }

    /**
     * Test {@link RemoteAndroidDevice#adbTcpConnect(String, String)} in a case where adb connect
     * always return connect success (never really connected so confirmation: "already connected"
     * fails.
     */
    @Test
    public void testAdbConnect_fails_confirmation() {
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        adbResult.setStdout("connected to");
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);

        assertFalse(mTestDevice.adbTcpConnect("localhost", "1234"));
        verify(mMockRunUtil, times(RemoteAndroidDevice.MAX_RETRIES * 2))
                .runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("connect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL));
        verify(mMockRunUtil, times(RemoteAndroidDevice.MAX_RETRIES)).sleep(Mockito.anyLong());
    }

    /** Test {@link RemoteAndroidDevice#adbTcpDisconnect(String, String)}. */
    @Test
    public void testAdbDisconnect() {
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.SUCCESS);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("disconnect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);

        assertTrue(mTestDevice.adbTcpDisconnect("localhost", "1234"));
    }

    /** Test {@link RemoteAndroidDevice#adbTcpDisconnect(String, String)} in a failure case. */
    @Test
    public void testAdbDisconnect_fails() {
        CommandResult adbResult = new CommandResult();
        adbResult.setStatus(CommandStatus.FAILED);
        when(mMockRunUtil.runTimedCmd(
                        Mockito.anyLong(),
                        Mockito.eq("adb"),
                        Mockito.eq("disconnect"),
                        Mockito.eq(MOCK_DEVICE_SERIAL)))
                .thenReturn(adbResult);

        assertFalse(mTestDevice.adbTcpDisconnect("localhost", "1234"));
    }

    @Test
    public void testCheckSerial() {

        assertEquals("localhost", mTestDevice.getHostName());
        assertEquals("1234", mTestDevice.getPortNum());
    }

    @Test
    public void testCheckSerial_invalid() {
        mTestDevice =
                new TestableRemoteAndroidDevice() {
                    @Override
                    public String getSerialNumber() {
                        return "wrongserial";
                    }
                };
        try {
            mTestDevice.getHostName();
        } catch (RuntimeException e) {
            // expected
            return;
        }
        fail("Wrong Serial should throw a RuntimeException");
    }

    /** Reject placeholder style device */
    @Test
    public void testCheckSerial_placeholder() {
        mTestDevice =
                new TestableRemoteAndroidDevice() {
                    @Override
                    public String getSerialNumber() {
                        return "gce-device:3";
                    }
                };
        try {
            mTestDevice.getHostName();
        } catch (RuntimeException e) {
            // expected
            return;
        }
        fail("Wrong Serial should throw a RuntimeException");
    }

    @Test
    public void testGetMacAddress() {
        assertNull(mTestDevice.getMacAddress());
    }
}
