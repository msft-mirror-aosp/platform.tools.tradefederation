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

package com.android.tradefed.device;

import com.android.ddmlib.IDevice;
import com.android.tradefed.device.IDeviceSelection.BaseDeviceType;
import com.android.tradefed.util.ConditionPriorityBlockingQueue.IMatcher;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Interface for device selection criteria.
 */
public interface IDeviceSelection extends IMatcher<IDevice> {

    public enum BaseDeviceType {
        NATIVE_DEVICE,
        FULL_DEVICE;
    }

    /**
     * Gets a copy of the serial numbers
     *
     * @param device The {@link IDevice} representing the device considered for selection.
     * @return a {@link Collection} of serial numbers
     */
    public Collection<String> getSerials(IDevice device);

    /** Returns the list of requested serials. */
    public List<String> getSerials();

    /**
     * Gets a copy of the serial numbers exclusion list
     *
     * @return a {@link Collection} of serial numbers
     */
    public Collection<String> getExcludeSerials();

    /**
     * Gets a copy of the product type list
     *
     * @return a {@link Collection} of product types
     */
    public Collection<String> getProductTypes();

    /**
     * Returns a map of the property list
     *
     * @return a {@link Map} of device property names to values
     */
    public Map<String, String> getProperties();

    /**
     * @return <code>true</code> if an emulator has been requested
     */
    public boolean emulatorRequested();

    /**
     * @return <code>true</code> if a device has been requested
     */
    public boolean deviceRequested();

    /**
     * @return <code>true</code> if an stub emulator has been requested. A stub emulator is a
     *         placeholder to be used when config has to launch an emulator.
     */
    public boolean stubEmulatorRequested();

    /**
     * @return <code>true</code> if a null device (aka no device required) has been requested
     */
    public boolean nullDeviceRequested();

    /**
     * @return <code>true</code> if a tcp device (aka a adb connected device) has been requested
     */
    @Deprecated
    public default boolean tcpDeviceRequested() {
        return false;
    }

    /** @return <code>true</code> if a gce device (aka a remote device) has been requested */
    public boolean gceDeviceRequested();

    /**
     * Gets the given devices product type
     *
     * @param device the {@link IDevice}
     * @return the device product type or <code>null</code> if unknown
     */
    public String getDeviceProductType(IDevice device);

    /**
     * Gets the given devices product variant
     *
     * @param device the {@link IDevice}
     * @return the device product variant or <code>null</code> if unknown
     */
    public String getDeviceProductVariant(IDevice device);

    /**
     * Retrieves the battery level for the given device
     *
     * @param device the {@link IDevice}
     * @return the device battery level or <code>null</code> if unknown
     */
    public Integer getBatteryLevel(IDevice device);

    /**
     * Set the serial numbers inclusion list, replacing any existing values.
     */
    public void setSerial(String... serialNumber);

    /**
     * Returns the reason for which the device was not matched.
     *
     * @return a Map of serial number to reason for which it wasn't allocated
     */
    public Map<String, String> getNoMatchReason();

    /** Returns the device type we should use. */
    public BaseDeviceType getBaseDeviceTypeRequested();

    /** Sets the device type we should use. */
    public void setBaseDeviceTypeRequested(BaseDeviceType type);

    /** Sets whether or not we want to do the battery check. */
    public void setRequireBatteryCheck(boolean requireCheck);
}
