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
package com.android.tradefed.testtype.suite.module;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.testtype.suite.module.IModuleController.RunStrategy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;

/** Unit tests for {@link MainlineTestModuleController}. */
@RunWith(JUnit4.class)
public class MainlineTestModuleControllerTest {
    private MainlineTestModuleController mController;
    private IInvocationContext mContext;
    @Mock ITestDevice mMockDevice;
    @Mock IDevice mMockIDevice;
    private ApexInfo mFakeApexInfo;
    private Set<ApexInfo> mFakeApexes;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mController = new MainlineTestModuleController();
        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_ABI, "arm64-v8a");
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_NAME, "module1");
        mFakeApexes = new HashSet<ITestDevice.ApexInfo>();
    }

    /** Test mainline module is installed and test should run. */
    @Test
    public void testModuleExistsRun() throws DeviceNotAvailableException, ConfigurationException {
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("enable", "true");
        setter.setOptionValue("mainline-module-package-name", "com.google.android.fakeapex");
        mFakeApexInfo = new ApexInfo("com.google.android.fakeapex", 1, "fakeDir");
        mFakeApexes.add(mFakeApexInfo);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getActiveApexes()).thenReturn(mFakeApexes);

        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
    }

    /** Test go mainline module is installed and test should run. */
    @Test
    public void testGoModuleExistsRun() throws DeviceNotAvailableException, ConfigurationException {
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("enable", "true");
        setter.setOptionValue("mainline-module-package-name", "com.google.android.fakeapex");
        mFakeApexInfo = new ApexInfo("com.google.android.go.fakeapex", 1, "fakeDir");
        mFakeApexes.add(mFakeApexInfo);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getActiveApexes()).thenReturn(mFakeApexes);

        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
    }

    /** Test mainline module is not installed and test should not run. */
    @Test
    public void testModuleNotInstalledTestRun()
            throws DeviceNotAvailableException, ConfigurationException {
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("enable", "true");
        setter.setOptionValue("mainline-module-package-name", "com.google.android.fakeapex");
        ApexInfo fakeApexInfo = new ApexInfo("com.google.android.fake1apex", 1, "fakeDir");
        mFakeApexes.add(fakeApexInfo);
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getActiveApexes()).thenReturn(mFakeApexes);

        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
    }

    /** Test {@link MainlineTestModuleController} is disabled and test should run anyway. */
    @Test
    public void testControllerDisabled()
            throws DeviceNotAvailableException, ConfigurationException {
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("enable", "false");

        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
    }

    /** Test {@link MainlineTestModuleController} is enabled but no mainline module specified. */
    @Test
    public void testControllerEnabledNoMainlineModuleSpecified()
            throws DeviceNotAvailableException, ConfigurationException {
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("enable", "true");

        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
    }
}
