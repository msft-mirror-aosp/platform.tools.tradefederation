/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.config;

import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.ILabPreparer;
import com.android.tradefed.targetprep.ITargetPreparer;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Holds a record of a configuration, its associated objects and their options.
 */
public class ConfigurationDef {

    /**
     * a map of object type names to config object class name(s). Use LinkedHashMap to keep objects
     * in the same order they were added.
     */
    private final Map<String, List<ConfigObjectDef>> mObjectClassMap = new LinkedHashMap<>();

    /** a list of option name/value pairs. */
    private final List<OptionDef> mOptionList = new ArrayList<>();

    /** a cache of the frequency of every classname */
    private final Map<String, Integer> mClassFrequency = new HashMap<>();

    /** The set of files (and modification times) that were used to load this config */
    private final Map<File, Long> mSourceFiles = new HashMap<>();

    /**
     * Object to hold info for a className and the appearance number it has (e.g. if a config has
     * the same object twice, the first one will have the first appearance number).
     */
    public static class ConfigObjectDef {
        final String mClassName;
        final Integer mAppearanceNum;

        ConfigObjectDef(String className, Integer appearance) {
            mClassName = className;
            mAppearanceNum = appearance;
        }
    }

    private boolean mMultiDeviceMode = false;
    private boolean mFilteredObjects = false;
    private Map<String, Boolean> mExpectedDevices = new LinkedHashMap<>();
    private static final Pattern MULTI_PATTERN = Pattern.compile("(.*):(.*)");
    public static final String DEFAULT_DEVICE_NAME = "DEFAULT_DEVICE";

    /** the unique name of the configuration definition */
    private final String mName;

    /** a short description of the configuration definition */
    private String mDescription = "";

    public ConfigurationDef(String name) {
        mName = name;
    }

    /**
     * Returns a short description of the configuration
     */
    public String getDescription() {
        return mDescription;
    }

    /** Sets the configuration definition description */
    public void setDescription(String description) {
        mDescription = description;
    }

    /**
     * Adds a config object to the definition
     *
     * @param typeName the config object type name
     * @param className the class name of the config object
     * @return the number of times this className has appeared in this {@link ConfigurationDef},
     *     including this time. Because all {@link ConfigurationDef} methods return these classes
     *     with a constant ordering, this index can serve as a unique identifier for the just-added
     *     instance of <code>clasName</code>.
     */
    public int addConfigObjectDef(String typeName, String className) {
        List<ConfigObjectDef> classList = mObjectClassMap.get(typeName);
        if (classList == null) {
            classList = new ArrayList<ConfigObjectDef>();
            mObjectClassMap.put(typeName, classList);
        }

        // Increment and store count for this className
        Integer freq = mClassFrequency.get(className);
        freq = freq == null ? 1 : freq + 1;
        mClassFrequency.put(className, freq);
        classList.add(new ConfigObjectDef(className, freq));

        return freq;
    }

    /**
     * Adds option to the definition
     *
     * @param optionName the name of the option
     * @param optionValue the option value
     */
    public void addOptionDef(
            String optionName,
            String optionKey,
            String optionValue,
            String optionSource,
            String type) {
        mOptionList.add(new OptionDef(optionName, optionKey, optionValue, optionSource, type));
    }

    void addOptionDef(String optionName, String optionKey, String optionValue,
            String optionSource) {
        mOptionList.add(new OptionDef(optionName, optionKey, optionValue, optionSource, null));
    }

    /**
     * Registers a source file that was used while loading this {@link ConfigurationDef}.
     */
    void registerSource(File source) {
        mSourceFiles.put(source, source.lastModified());
    }

    /**
     * Determine whether any of the source files have changed since this {@link ConfigurationDef}
     * was loaded.
     */
    boolean isStale() {
        for (Map.Entry<File, Long> entry : mSourceFiles.entrySet()) {
            if (entry.getKey().lastModified() > entry.getValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the object type name-class map.
     *
     * <p>Exposed for unit testing
     */
    Map<String, List<ConfigObjectDef>> getObjectClassMap() {
        return mObjectClassMap;
    }

    /**
     * Get the option name-value map.
     * <p/>
     * Exposed for unit testing
     */
    List<OptionDef> getOptionList() {
        return mOptionList;
    }

    /**
     * Creates a configuration from the info stored in this definition, and populates its fields
     * with the provided option values.
     *
     * @return the created {@link IConfiguration}
     * @throws ConfigurationException if configuration could not be created
     */
    public IConfiguration createConfiguration() throws ConfigurationException {
        return createConfiguration(null);
    }

    /**
     * Creates a configuration from the info stored in this definition, and populates its fields
     * with the provided option values.
     *
     * @param allowedObjects the set of TF objects that we will create out of the full configuration
     * @return the created {@link IConfiguration}
     * @throws ConfigurationException if configuration could not be created
     */
    public IConfiguration createConfiguration(Set<String> allowedObjects)
            throws ConfigurationException {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("configdef.createConfiguration")) {
            mFilteredObjects = false;
            IConfiguration config = new Configuration(getName(), getDescription());
            List<IDeviceConfiguration> deviceObjectList = new ArrayList<IDeviceConfiguration>();
            IDeviceConfiguration defaultDeviceConfig =
                    new DeviceConfigurationHolder(DEFAULT_DEVICE_NAME);
            boolean hybridMultiDeviceHandling = false;

            if (!mMultiDeviceMode) {
                // We still populate a default device config to avoid special logic in the rest of
                // the
                // harness.
                deviceObjectList.add(defaultDeviceConfig);
            } else {
                // FIXME: handle this in a more generic way.
                // Get the number of real device (non build-only) device
                Long numDut =
                        mExpectedDevices.values().stream()
                                .filter(value -> (value == false))
                                .collect(Collectors.counting());
                Long numNonDut =
                        mExpectedDevices.values().stream()
                                .filter(value -> (value == true))
                                .collect(Collectors.counting());
                if (numDut == 0 && numNonDut == 0) {
                    throw new ConfigurationException("No device detected. Should not happen.");
                }
                if (numNonDut > 0 && numDut == 0) {
                    // if we only have fake devices, use the default device as real device, and add
                    // it
                    // first.
                    Map<String, Boolean> copy = new LinkedHashMap<>();
                    copy.put(DEFAULT_DEVICE_NAME, false);
                    copy.putAll(mExpectedDevices);
                    mExpectedDevices = copy;
                    numDut++;
                }
                if (numNonDut > 0 && numDut == 1) {
                    // If we have fake device but only a single real device, is the only use case to
                    // handle very differently: object at the root of the xml needs to be associated
                    // with the only DuT.
                    // All the other use cases can be handled the regular way.
                    CLog.d(
                            "One device is under tests while config '%s' requires some fake=true "
                                    + "devices. Using hybrid parsing of config.",
                            getName());
                    hybridMultiDeviceHandling = true;
                }
                for (String name : mExpectedDevices.keySet()) {
                    deviceObjectList.add(
                            new DeviceConfigurationHolder(name, mExpectedDevices.get(name)));
                }
            }

            Map<String, String> rejectedObjects = new HashMap<>();
            Throwable cause = null;

            for (Map.Entry<String, List<ConfigObjectDef>> objClassEntry :
                    mObjectClassMap.entrySet()) {
                List<Object> objectList = new ArrayList<Object>(objClassEntry.getValue().size());
                String entryName = objClassEntry.getKey();
                boolean shouldAddToFlatConfig = true;

                for (ConfigObjectDef configDef : objClassEntry.getValue()) {
                    String objectWithoutNamespace = objClassEntry.getKey();
                    if (objectWithoutNamespace.contains(":")) {
                        objectWithoutNamespace = objectWithoutNamespace.split(":")[1];
                    }
                    if (allowedObjects != null
                            && !allowedObjects.contains(objectWithoutNamespace)) {
                        CLog.d("Skipping creation of %s", objectWithoutNamespace);
                        mFilteredObjects = true;
                        continue;
                    }
                    Object configObject = null;
                    try {
                        configObject = createObject(objClassEntry.getKey(), configDef.mClassName);
                    } catch (ClassNotFoundConfigurationException e) {
                        // Store all the loading failure
                        cause = e.getCause();
                        rejectedObjects.putAll(e.getRejectedObjects());
                        CLog.e(e);
                        // Don't add in case of issue
                        shouldAddToFlatConfig = false;
                        continue;
                    }
                    Matcher matcher = null;
                    if (mMultiDeviceMode) {
                        matcher = MULTI_PATTERN.matcher(entryName);
                    }
                    if (mMultiDeviceMode && matcher.find()) {
                        // If we find the device namespace, fetch the matching device or create it
                        // if it doesn't exist.
                        IDeviceConfiguration multiDev = null;
                        shouldAddToFlatConfig = false;
                        for (IDeviceConfiguration iDevConfig : deviceObjectList) {
                            if (matcher.group(1).equals(iDevConfig.getDeviceName())) {
                                multiDev = iDevConfig;
                                break;
                            }
                        }
                        if (multiDev == null) {
                            multiDev = new DeviceConfigurationHolder(matcher.group(1));
                            deviceObjectList.add(multiDev);
                        }
                        // We reference the original object to the device and not to the flat list.
                        multiDev.addSpecificConfig(configObject, matcher.group(2));
                        multiDev.addFrequency(configObject, configDef.mAppearanceNum);
                    } else {
                        if (Configuration.doesBuiltInObjSupportMultiDevice(entryName)) {
                            if (hybridMultiDeviceHandling) {
                                // Special handling for a multi-device with one Dut and the rest are
                                // non-dut devices.
                                // At this point we are ensured to have only one Dut device. Object
                                // at
                                // the root should are associated with the only device under test
                                // (Dut).
                                List<IDeviceConfiguration> realDevice =
                                        deviceObjectList.stream()
                                                .filter(object -> (object.isFake() == false))
                                                .collect(Collectors.toList());
                                if (realDevice.size() != 1) {
                                    throw new ConfigurationException(
                                            String.format(
                                                    "Something went very bad, we found '%s' Dut "
                                                            + "device while expecting one only.",
                                                    realDevice.size()));
                                }
                                realDevice.get(0).addSpecificConfig(configObject, entryName);
                                realDevice
                                        .get(0)
                                        .addFrequency(configObject, configDef.mAppearanceNum);
                            } else {
                                // Regular handling of object for single device situation.
                                defaultDeviceConfig.addSpecificConfig(configObject, entryName);
                                defaultDeviceConfig.addFrequency(
                                        configObject, configDef.mAppearanceNum);
                            }
                        } else {
                            // Only add to flat list if they are not part of multi device config.
                            objectList.add(configObject);
                        }
                    }
                }
                if (shouldAddToFlatConfig) {
                    config.setConfigurationObjectList(entryName, objectList);
                }
            }

            checkRejectedObjects(rejectedObjects, cause);

            // We always add the device configuration list so we can rely on it everywhere
            config.setConfigurationObjectList(Configuration.DEVICE_NAME, deviceObjectList);
            injectOptions(config, mOptionList);

            List<ITargetPreparer> notILab = new ArrayList<>();
            for (IDeviceConfiguration deviceConfig : config.getDeviceConfig()) {
                for (ITargetPreparer labPreparer : deviceConfig.getLabPreparers()) {
                    if (!(labPreparer instanceof ILabPreparer)) {
                        notILab.add(labPreparer);
                    }
                }
            }
            if (!notILab.isEmpty()) {
                throw new ConfigurationException(
                        String.format(
                                "The following were specified as lab_preparer "
                                        + "but aren't ILabPreparer: %s",
                                notILab),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            return config;
        }
    }

    /** Evaluate rejected objects map, if any throw an exception. */
    protected void checkRejectedObjects(Map<String, String> rejectedObjects, Throwable cause)
            throws ClassNotFoundConfigurationException {
        // Send all the objects that failed the loading.
        if (!rejectedObjects.isEmpty()) {
            throw new ClassNotFoundConfigurationException(
                    String.format(
                            "Failed to load some objects in the configuration '%s': %s",
                            getName(), rejectedObjects),
                    cause,
                    InfraErrorIdentifier.CLASS_NOT_FOUND,
                    rejectedObjects);
        }
    }

    protected void injectOptions(IConfiguration config, List<OptionDef> optionList)
            throws ConfigurationException {
        if (mFilteredObjects) {
            // If we filtered out some objects, some options might not be injectable anymore, so
            // we switch to safe inject to avoid errors due to the filtering.
            config.safeInjectOptionValues(optionList);
        } else {
            config.injectOptionValues(optionList);
        }
    }

    /**
     * Creates a global configuration from the info stored in this definition, and populates its
     * fields with the provided option values.
     *
     * @return the created {@link IGlobalConfiguration}
     * @throws ConfigurationException if configuration could not be created
     */
    IGlobalConfiguration createGlobalConfiguration() throws ConfigurationException {
        try (CloseableTraceScope ignored =
                new CloseableTraceScope("createGlobalConfigurationObjects")) {
            IGlobalConfiguration config = new GlobalConfiguration(getName(), getDescription());

            for (Map.Entry<String, List<ConfigObjectDef>> objClassEntry :
                    mObjectClassMap.entrySet()) {
                List<Object> objectList = new ArrayList<Object>(objClassEntry.getValue().size());
                for (ConfigObjectDef configDef : objClassEntry.getValue()) {
                    Object configObject =
                            createObject(objClassEntry.getKey(), configDef.mClassName);
                    objectList.add(configObject);
                }
                config.setConfigurationObjectList(objClassEntry.getKey(), objectList);
            }
            for (OptionDef optionEntry : mOptionList) {
                config.injectOptionValue(optionEntry.name, optionEntry.key, optionEntry.value);
            }

            return config;
        }
    }

    /**
     * Gets the name of this configuration definition
     *
     * @return name of this configuration.
     */
    public String getName() {
        return mName;
    }

    public void setMultiDeviceMode(boolean multiDeviceMode) {
        mMultiDeviceMode = multiDeviceMode;
    }

    /** Returns whether or not the recorded configuration is multi-device or not. */
    public boolean isMultiDeviceMode() {
        return mMultiDeviceMode;
    }

    /** Add a device that needs to be tracked and whether or not it's real. */
    public String addExpectedDevice(String deviceName, boolean isFake) {
        Boolean previous = mExpectedDevices.put(deviceName, isFake);
        if (previous != null && previous != isFake) {
            return String.format(
                    "Mismatch for device '%s'. It was defined once as isFake=false, once as "
                            + "isFake=true",
                    deviceName);
        }
        return null;
    }

    /** Returns the current Map of tracked devices and if they are real or not. */
    public Map<String, Boolean> getExpectedDevices() {
        return mExpectedDevices;
    }

    /**
     * Creates a config object associated with this definition.
     *
     * @param objectTypeName the name of the object. Used to generate more descriptive error
     *            messages
     * @param className the class name of the object to load
     * @return the config object
     * @throws ConfigurationException if config object could not be created
     */
    private Object createObject(String objectTypeName, String className)
            throws ConfigurationException {
        try {
            Class<?> objectClass = getClassForObject(objectTypeName, className);
            Object configObject = objectClass.getDeclaredConstructor().newInstance();
            checkObjectValid(objectTypeName, className, configObject);
            return configObject;
        } catch (InstantiationException | InvocationTargetException | NoSuchMethodException e) {
            throw new ConfigurationException(String.format(
                    "Could not instantiate class %s for config object type %s", className,
                    objectTypeName), e);
        } catch (IllegalAccessException e) {
            throw new ConfigurationException(String.format(
                    "Could not access class %s for config object type %s", className,
                    objectTypeName), e);
        }
    }

    /**
     * Loads the class for the given the config object associated with this definition.
     *
     * @param objectTypeName the name of the config object type. Used to generate more descriptive
     *     error messages
     * @param className the class name of the object to load
     * @return the config object populated with default option values
     * @throws ClassNotFoundConfigurationException if config object could not be created
     */
    private Class<?> getClassForObject(String objectTypeName, String className)
            throws ClassNotFoundConfigurationException {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            ClassNotFoundConfigurationException exception =
                    new ClassNotFoundConfigurationException(
                            String.format(
                                    "Could not find class %s for config object type %s",
                                    className, objectTypeName),
                            e,
                            InfraErrorIdentifier.CLASS_NOT_FOUND,
                            className,
                            objectTypeName);
            throw exception;
        }
    }

    /**
     * Check that the loaded object does not present some incoherence. Some combination should not
     * be done. For example: metric_collectors does extend ITestInvocationListener and could be
     * declared as a result_reporter, but we do not allow it because it's not how it should be used
     * in the invocation.
     *
     * @param objectTypeName The type of the object declared in the xml.
     * @param className The string classname that was instantiated
     * @param configObject The instantiated object.
     * @throws ConfigurationException if we find an incoherence in the object.
     */
    private void checkObjectValid(String objectTypeName, String className, Object configObject)
            throws ConfigurationException {
        if (configObject == null) {
            throw new ConfigurationException(
                    String.format(
                            "Class %s for type %s didn't instantiate properly",
                            className, objectTypeName),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        if (Configuration.RESULT_REPORTER_TYPE_NAME.equals(objectTypeName)
                && configObject instanceof IMetricCollector) {
            // we do not allow IMetricCollector as result_reporter.
            throw new ConfigurationException(
                    String.format(
                            "Object of type %s was declared as %s.",
                            Configuration.DEVICE_METRICS_COLLECTOR_TYPE_NAME,
                            Configuration.RESULT_REPORTER_TYPE_NAME),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
    }
}
