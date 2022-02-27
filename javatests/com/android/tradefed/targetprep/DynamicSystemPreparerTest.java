/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.targetprep;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;

import com.android.tradefed.build.BuildInfo;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.CollectingOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.ZipUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Unit tests for {@link DynamicSystemPreparer}. */
@RunWith(JUnit4.class)
public class DynamicSystemPreparerTest {
    // Input build info.
    private static final String SYSTEM_IMAGE_NAME = "system.img";
    private static final String SYSTEM_EXT_IMAGE_NAME = "system_ext.img";
    private static final String PRODUCT_IMAGE_NAME = "product.img";
    private static final String SYSTEM_IMAGE_ZIP_NAME = "system-img.zip";

    private IBuildInfo mBuildInfo;
    private ITestDevice mMockDevice;
    private TestInformation mTestInfo;
    private File mSystemImageDir;
    private File mSystemImageZip;
    private File mMultiSystemImageZip;
    // The object under test.
    private DynamicSystemPreparer mPreparer;

    @Before
    public void setUp() throws IOException {
        mMockDevice = Mockito.mock(ITestDevice.class);
        mSystemImageDir =
                createImageDir(SYSTEM_IMAGE_NAME, SYSTEM_EXT_IMAGE_NAME, PRODUCT_IMAGE_NAME);
        mSystemImageZip =
                ZipUtil.createZip(
                        Arrays.asList(new File(mSystemImageDir, SYSTEM_IMAGE_NAME)),
                        "DynamicSystem");
        mMultiSystemImageZip =
                ZipUtil.createZip(
                        Arrays.asList(
                                new File(mSystemImageDir, SYSTEM_IMAGE_NAME),
                                new File(mSystemImageDir, SYSTEM_EXT_IMAGE_NAME),
                                new File(mSystemImageDir, PRODUCT_IMAGE_NAME)),
                        "DynamicSystem_multi");
        mBuildInfo = new BuildInfo();

        mPreparer = new DynamicSystemPreparer();

        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        context.addDeviceBuildInfo("device", mBuildInfo);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
    }

    @After
    public void tearDown() {
        if (mBuildInfo != null) {
            mBuildInfo.cleanUp();
            mBuildInfo = null;
        }
        FileUtil.recursiveDelete(mSystemImageDir);
        FileUtil.deleteFile(mSystemImageZip);
        FileUtil.deleteFile(mMultiSystemImageZip);
    }

    private File createImageDir(String... fileNames) throws IOException {
        File tempDir = FileUtil.createTempDir("createImageDir");
        for (String fileName : fileNames) {
            new File(tempDir, fileName).createNewFile();
        }
        return tempDir;
    }

    private void mockGsiToolStatus(String status) throws DeviceNotAvailableException {
        doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) {
                                byte[] outputBytes = status.getBytes();
                                ((CollectingOutputReceiver) invocation.getArguments()[1])
                                        .addOutput(outputBytes, 0, outputBytes.length);
                                return null;
                            }
                        })
                .when(mMockDevice)
                .executeShellCommand(
                        matches("gsi_tool status"), any(CollectingOutputReceiver.class));
    }

    @Test
    public void testSetUp_imageDir()
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageDir, "0");
        Mockito.when(mMockDevice.pushFile(Mockito.any(), Mockito.eq("/sdcard/system.raw.gz")))
                .thenReturn(Boolean.TRUE);
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        mockGsiToolStatus("running");
        CommandResult res = new CommandResult();
        res.setStdout("");
        res.setStatus(CommandStatus.SUCCESS);
        Mockito.when(mMockDevice.executeShellV2Command("gsi_tool enable")).thenReturn(res);
        mPreparer.setUp(mTestInfo);
        Assert.assertTrue(new File(mSystemImageDir, SYSTEM_IMAGE_NAME).exists());
    }

    @Test
    public void testSetUp_imageZip()
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageZip, "0");
        Mockito.when(mMockDevice.pushFile(Mockito.any(), Mockito.eq("/sdcard/system.raw.gz")))
                .thenReturn(Boolean.TRUE);
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        mockGsiToolStatus("running");
        CommandResult res = new CommandResult();
        res.setStdout("");
        res.setStatus(CommandStatus.SUCCESS);
        Mockito.when(mMockDevice.executeShellV2Command("gsi_tool enable")).thenReturn(res);
        mPreparer.setUp(mTestInfo);
    }

    @Test
    public void testSetUp_installationFail() throws BuildError, DeviceNotAvailableException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageZip, "0");
        Mockito.when(mMockDevice.pushFile(Mockito.any(), Mockito.eq("/sdcard/system.raw.gz")))
                .thenReturn(Boolean.TRUE);
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(false);
        try {
            mPreparer.setUp(mTestInfo);
            Assert.fail("setUp() should have thrown.");
        } catch (TargetSetupError e) {
            Assert.assertEquals(
                    "Timed out waiting for DSU installation to complete and reboot",
                    e.getMessage());
        }
    }

    @Test
    public void testSetUp_rebootFail() throws BuildError, DeviceNotAvailableException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageZip, "0");
        Mockito.when(mMockDevice.pushFile(Mockito.any(), Mockito.eq("/sdcard/system.raw.gz")))
                .thenReturn(Boolean.TRUE);
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        Mockito.doThrow(new DeviceNotAvailableException()).when(mMockDevice).waitForDeviceOnline();
        try {
            mPreparer.setUp(mTestInfo);
            Assert.fail("setUp() should have thrown.");
        } catch (TargetSetupError e) {
            Assert.assertEquals("Timed out booting into DSU", e.getMessage());
        }
    }

    @Test
    public void testSetUp_noDsuRunningAfterRebootFail()
            throws BuildError, DeviceNotAvailableException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageZip, "0");
        Mockito.when(mMockDevice.pushFile(Mockito.any(), Mockito.eq("/sdcard/system.raw.gz")))
                .thenReturn(Boolean.TRUE);
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        mockGsiToolStatus("normal");
        try {
            mPreparer.setUp(mTestInfo);
            Assert.fail("setUp() should have thrown.");
        } catch (TargetSetupError e) {
            Assert.assertEquals("Failed to boot into DSU", e.getMessage());
        }
    }

    @Test
    public void testSetUp_dsuImageZip()
            throws BuildError, DeviceNotAvailableException, IOException, TargetSetupError,
                    ZipException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mSystemImageZip, "0");
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        mockGsiToolStatus("running");
        CommandResult res = new CommandResult();
        res.setStdout("");
        res.setStatus(CommandStatus.SUCCESS);
        Mockito.when(mMockDevice.executeShellV2Command("gsi_tool enable")).thenReturn(res);
        Mockito.when(mMockDevice.getApiLevel()).thenReturn(30);
        doAnswer(
                        new Answer<Boolean>() {
                            @Override
                            public Boolean answer(InvocationOnMock invocation)
                                    throws IOException, ZipException {
                                File dsuZip = (File) invocation.getArguments()[0];
                                Assert.assertNotNull(dsuZip);
                                try (ZipFile zipFile = new ZipFile(dsuZip)) {
                                    Assert.assertNotNull(zipFile.getEntry(SYSTEM_IMAGE_NAME));
                                }
                                return true;
                            }
                        })
                .when(mMockDevice)
                .pushFile(Mockito.any(), Mockito.any());

        mPreparer.setUp(mTestInfo);

        Mockito.verify(mMockDevice).pushFile(Mockito.any(), eq("/sdcard/system.zip"));
    }

    @Test
    public void testSetUp_dsuMultiImageZip()
            throws BuildError, DeviceNotAvailableException, IOException, TargetSetupError,
                    ZipException {
        mBuildInfo.setFile(SYSTEM_IMAGE_ZIP_NAME, mMultiSystemImageZip, "0");
        Mockito.when(mMockDevice.waitForDeviceNotAvailable(Mockito.anyLong())).thenReturn(true);
        mockGsiToolStatus("running");
        CommandResult res = new CommandResult();
        res.setStdout("");
        res.setStatus(CommandStatus.SUCCESS);
        Mockito.when(mMockDevice.executeShellV2Command("gsi_tool enable")).thenReturn(res);
        Mockito.when(mMockDevice.getApiLevel()).thenReturn(30);
        doAnswer(
                        new Answer<Boolean>() {
                            @Override
                            public Boolean answer(InvocationOnMock invocation)
                                    throws IOException, ZipException {
                                File dsuZip = (File) invocation.getArguments()[0];
                                Assert.assertNotNull(dsuZip);
                                try (ZipFile zipFile = new ZipFile(dsuZip)) {
                                    Assert.assertNotNull(zipFile.getEntry(SYSTEM_IMAGE_NAME));
                                    Assert.assertNotNull(zipFile.getEntry(SYSTEM_EXT_IMAGE_NAME));
                                    Assert.assertNotNull(zipFile.getEntry(PRODUCT_IMAGE_NAME));
                                }
                                return true;
                            }
                        })
                .when(mMockDevice)
                .pushFile(Mockito.any(), Mockito.any());

        mPreparer.setUp(mTestInfo);

        Mockito.verify(mMockDevice).pushFile(Mockito.any(), eq("/sdcard/system.zip"));
    }
}
