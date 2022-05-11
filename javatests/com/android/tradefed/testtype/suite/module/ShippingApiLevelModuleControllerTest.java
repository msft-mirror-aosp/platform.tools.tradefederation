/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
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

/** Unit tests for {@link ShippingApiLevelModuleController}. */
@RunWith(JUnit4.class)
public class ShippingApiLevelModuleControllerTest {
    private ShippingApiLevelModuleController mController;
    private IInvocationContext mContext;
    @Mock ITestDevice mMockDevice;
    @Mock IDevice mMockIDevice;

    private static final String SYSTEM_SHIPPING_API_LEVEL_PROP = "ro.product.first_api_level";
    private static final String SYSTEM_API_LEVEL_PROP = "ro.build.version.sdk";
    private static final String VENDOR_SHIPPING_API_LEVEL_PROP = "ro.board.first_api_level";
    private static final String VENDOR_API_LEVEL_PROP = "ro.board.api_level";
    private static final long API_LEVEL_CURRENT = 10000;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mController = new ShippingApiLevelModuleController();

        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_ABI, "arm64-v8a");
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_NAME, "module1");
    }

    /**
     * min-api-level > ro.product.first_api_level. No need to check vendor api levels. The test will
     * be skipped.
     */
    @Test
    public void testMinApiLevelHigherThanProductFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(28L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "29");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
    }

    /**
     * min-api-level == ro.product.first_api_level. min-api-level > ro.board.api_level. The test
     * will be run as min-api-level does not check the vendor api level.
     */
    @Test
    public void testMinApiLevelHigherThanBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT)).thenReturn(30L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * min-api-level < ro.product.first_api_level. Vendor api levels are not defined. The test will
     * be run.
     */
    @Test
    public void testBoardApiLevelsNotFound()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(28L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "27");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * No properties are defined. But the device api level is less than the min-api-level. The test
     * will be skipped.
     */
    @Test
    public void testApiLevelsNotFound() throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(SYSTEM_API_LEVEL_PROP, API_LEVEL_CURRENT)).thenReturn(26L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "27");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
    }

    /**
     * vsr-min-api-level == ro.product.first_api_level. vsr-min-api-level > ro.board.api_level. The
     * test will be skipped.
     */
    @Test
    public void testVsrMinApiLevelHigherThanBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT)).thenReturn(30L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "31");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * vsr-min-api-level < ro.product.first_api_level. Vendor api levels are not defined. The test
     * will be run.
     */
    @Test
    public void testVsrBoardApiLevelsNotFound()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(28L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "27");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * vsr-min-api-level < ro.product.first_api_level. vsr-min-api-level > ro.board.first_api_level.
     * ro.board.api_level is not defined. The test will be skipped.
     */
    @Test
    public void testMinApiLevelHigherThanBoardFirstApiLevel2()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(32L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(30L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "31");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * vsr-min-api-level == ro.product.first_api_level. vsr-min-api-level == ro.board.api_level. The
     * test will be run.
     */
    @Test
    public void testMinApiLevelEqualToBoardApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT)).thenReturn(31L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * vsr-min-api-level == ro.product.first_api_level. vsr-min-api-level ==
     * ro.board.first_api_level. ro.board.api_level is not defined. The test will be run.
     */
    @Test
    public void testMinApiLevelEqualToBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * vsr-min-api-level == ro.product.first_api_level. vsr-min-api-level <
     * ro.board.first_api_level. ro.board.api_level is not defined. The test will be run.
     */
    @Test
    public void testMinApiLevelLessThanBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(31L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(32L);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }

    /**
     * No properties are defined. But the device api level is eqaul to the vsr-min-api-level. The
     * test will be run.
     */
    @Test
    public void testVsrApiLevelsNotFound()
            throws DeviceNotAvailableException, ConfigurationException {
        when(mMockDevice.getIDevice()).thenReturn(mMockIDevice);
        when(mMockDevice.getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(SYSTEM_API_LEVEL_PROP, API_LEVEL_CURRENT)).thenReturn(27L);
        when(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);
        when(mMockDevice.getIntProperty(VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .thenReturn(API_LEVEL_CURRENT);

        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("vsr-min-api-level", "27");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        verify(mMockDevice, times(2))
                .getIntProperty(SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT);
        verify(mMockDevice, times(2)).getIntProperty(SYSTEM_API_LEVEL_PROP, API_LEVEL_CURRENT);
    }
}
