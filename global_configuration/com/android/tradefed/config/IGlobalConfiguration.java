/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.tradefed.auth.ICredentialFactory;
import com.android.tradefed.command.ICommandScheduler;
import com.android.tradefed.device.DeviceManager;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceSelection;
import com.android.tradefed.device.IMultiDeviceRecovery;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.host.IHostResourceManager;
import com.android.tradefed.host.LocalHostResourceManager;
import com.android.tradefed.invoker.shard.IShardHelper;
import com.android.tradefed.log.ITerribleFailureHandler;
import com.android.tradefed.monitoring.collector.IResourceMetricCollector;
import com.android.tradefed.sandbox.ISandboxFactory;
import com.android.tradefed.service.TradefedFeatureServer;
import com.android.tradefed.service.management.DeviceManagementGrpcServer;
import com.android.tradefed.service.management.TestInvocationManagementServer;
import com.android.tradefed.util.hostmetric.IHostMonitor;
import com.android.tradefed.util.keystore.IKeyStoreFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * A class to encompass global configuration information for a single Trade Federation instance
 * (encompassing any number of invocations of actual configurations).
 */
public interface IGlobalConfiguration {
    /**
     * Gets the {@link IHostOptions} to use from the configuration.
     *
     * @return the {@link IDeviceManager} provided in the configuration.
     */
    public IHostOptions getHostOptions();

    /**
     * Gets the {@link IHostResourceManager} from the global config.
     *
     * @return the {@link IHostResourceManager} from the global config, or default implementation
     *     {@link LocalHostResourceManager} if none is specified in host config.
     */
    public IHostResourceManager getHostResourceManager();

    /**
     * Gets the list of {@link IDeviceMonitor} from the global config.
     *
     * @return the list of {@link IDeviceMonitor} from the global config, or <code>null</code> if
     *     none was specified.
     */
    public List<IDeviceMonitor> getDeviceMonitors();

    /**
     * Gets the list of {@link IHostMonitor} from the global config.
     *
     * @return the list of {@link IHostMonitor} from the global config, or <code>null</code> if none
     *         was specified.
     */
    public List<IHostMonitor> getHostMonitors();

    /**
     * Gets the list of {@link IResourceMetricCollector} from the global config.
     *
     * @return the list of {@link IResourceMetricCollector} from the global config or <code>null
     *     </code> if none was specified.
     */
    public List<IResourceMetricCollector> getResourceMetricCollectors();

    /**
     * Gets the {@link ICredentialFactory} for creating credentials.
     *
     * @return {@link ICredentialFactory} or <code>null</code> if none was specified.
     */
    public ICredentialFactory getCredentialFactory();

    /**
     * Set the {@link IDeviceMonitor}.
     *
     * @param deviceMonitor The monitor
     * @throws ConfigurationException if an {@link IDeviceMonitor} has already been set.
     */
    public void setDeviceMonitor(IDeviceMonitor deviceMonitor) throws ConfigurationException;

    /**
     * Set the {@link IHostMonitor} list.
     *
     * @param hostMonitors The list of monitors
     * @throws ConfigurationException if an {@link IHostMonitor} has already been set.
     */
    public void setHostMonitors(List<IHostMonitor> hostMonitors) throws ConfigurationException;

    /**
     * Set the {@link ITerribleFailureHandler}.
     *
     * @param wtfHandler the WTF handler
     * @throws ConfigurationException if an {@link ITerribleFailureHandler} has
     *             already been set.
     */
    public void setWtfHandler(ITerribleFailureHandler wtfHandler) throws ConfigurationException;

    /**
     * Generic method to set the config object list for the given name, replacing any existing
     * value.
     *
     * @param typeName the unique name of the config object type.
     * @param configList the config object list
     * @throws ConfigurationException if any objects in the list are not the correct type
     */
    public void setConfigurationObjectList(String typeName, List<?> configList)
            throws ConfigurationException;

    /**
     * Inject a option value into the set of configuration objects.
     * <p/>
     * Useful to provide values for options that are generated dynamically.
     *
     * @param optionName the option name
     * @param optionValue the option value(s)
     * @throws ConfigurationException if failed to set the option's value
     */
    public void injectOptionValue(String optionName, String optionValue)
            throws ConfigurationException;

    /**
     * Inject a option value into the set of configuration objects.
     * <p/>
     * Useful to provide values for options that are generated dynamically.
     *
     * @param optionName the map option name
     * @param optionKey the map option key
     * @param optionValue the map option value
     * @throws ConfigurationException if failed to set the option's value
     */
    public void injectOptionValue(String optionName, String optionKey, String optionValue)
            throws ConfigurationException;

    /**
     * Get a list of option's values.
     *
     * @param optionName the map option name
     * @return a list of the given option's values. <code>null</code> if the option name does not
     *          exist.
     */
    public List<String> getOptionValues(String optionName);

    /**
     * Set the global config {@link Option} fields with given set of command line arguments
     * <p/>
     * See {@link ArgsOptionParser} for expected format
     *
     * @param listArgs the command line arguments
     * @return the unconsumed arguments
     */
    public List<String> setOptionsFromCommandLineArgs(List<String> listArgs)
            throws ConfigurationException;

    /**
     * Set the {@link IDeviceSelection}, replacing any existing values.  This sets a global device
     * filter on which devices the {@link DeviceManager} can see.
     *
     * @param deviceSelection
     */
    public void setDeviceRequirements(IDeviceSelection deviceSelection);

    /**
     * Gets the {@link IDeviceSelection} to use from the configuration.  Represents a global filter
     * on which devices the {@link DeviceManager} can see.
     *
     * @return the {@link IDeviceSelection} provided in the configuration.
     */
    public IDeviceSelection getDeviceRequirements();

    /**
     * Gets the {@link IDeviceManager} to use from the configuration. Manages the set of available
     * devices for testing
     *
     * @return the {@link IDeviceManager} provided in the configuration.
     */
    public IDeviceManager getDeviceManager();

    /**
     * Gets the {@link ITerribleFailureHandler} to use from the configuration.
     * Handles what to do in the event that a WTF (What a Terrible Failure)
     * occurs.
     *
     * @return the {@link ITerribleFailureHandler} provided in the
     *         configuration, or null if no handler is set
     */
    public ITerribleFailureHandler getWtfHandler();

    /**
     * Gets the {@link ICommandScheduler} to use from the configuration.
     *
     * @return the {@link ICommandScheduler}. Will never return null.
     */
    public ICommandScheduler getCommandScheduler();

    /**
     * Gets the list of {@link IMultiDeviceRecovery} to use from the configuration.
     *
     * @return the list of {@link IMultiDeviceRecovery}, or <code>null</code> if not set.
     */
    public List<IMultiDeviceRecovery> getMultiDeviceRecoveryHandlers();


    /**
     * Gets the {@link IKeyStoreFactory} to use from the configuration.
     *
     * @return the {@link IKeyStoreFactory} or null if no key store factory is set.
     */
    public IKeyStoreFactory getKeyStoreFactory();

    /** Returns the {@link IShardHelper} that defines the way to shard a configuration. */
    public IShardHelper getShardingStrategy();

    /** Returns the {@link TradefedFeatureServer} or null if undefined. */
    public TradefedFeatureServer getFeatureServer();

    /** Returns the {@link TestInvocationManagementServer} or null if undefined. */
    public TestInvocationManagementServer getTestInvocationManagementSever();

    /** Returns the {@link DeviceManagementGrpcServer} or null if undefined. */
    public DeviceManagementGrpcServer getDeviceManagementServer();

    /**
     * Set the {@link IHostOptions}, replacing any existing values.
     *
     * @param hostOptions
     */
    public void setHostOptions(IHostOptions hostOptions);

    /**
     * Set the {@link IHostResourceManager}, replacing any existing values.
     *
     * @param hostResourceManager
     */
    public void setHostResourceManager(IHostResourceManager hostResourceManager);

    /**
     * Set the {@link IDeviceManager}, replacing any existing values. This sets the manager for the
     * test devices
     *
     * @param deviceManager
     */
    public void setDeviceManager(IDeviceManager deviceManager);

    /**
     * Set the {@link ICommandScheduler}, replacing any existing values.
     *
     * @param scheduler
     */
    public void setCommandScheduler(ICommandScheduler scheduler);

    /**
     * Set the {@link ISandboxFactory}, replacing any existing values.
     *
     * @param factory
     */
    public void setSandboxFactory(ISandboxFactory factory);

    /**
     * Set the {@link IKeyStoreFactory}, replacing any existing values.
     *
     * @param factory
     */
    public void setKeyStoreFactory(IKeyStoreFactory factory);

    /** Sets the {@link IShardHelper} to be used when sharding a configuration. */
    public void setShardingStrategy(IShardHelper sharding);

    /** Sets the {@link IResourceMetricCollector}. */
    public void setResourceMetricCollector(IResourceMetricCollector collector);

    /** Sets the {@link TradefedFeatureServer}. */
    public void setTradefedFeatureServer(TradefedFeatureServer server);

    /** Sets the {@link TestInvocationManagementServer}. */
    public void setInvocationServer(TestInvocationManagementServer server);

    /** Sets the {@link DeviceManagementGrpcServer}. */
    public void setDeviceManagementServer(DeviceManagementGrpcServer server);

    /**
     * Generic method to set the config object with the given name, replacing any existing value.
     *
     * @param name the unique name of the config object type.
     * @param configObject the config object
     * @throws ConfigurationException if the configObject was not the correct type
     */
    public void setConfigurationObject(String name, Object configObject)
            throws ConfigurationException;

    /**
     * Gets the custom configuration object with given name.
     *
     * @param typeName the unique type of the configuration object
     * @return the object or null if object with that name is not found
     */
    public Object getConfigurationObject(String typeName);

    /**
     * Gets global config server. Global config server is used to get host configs from a server
     * instead of getting it from local files.
     */
    public IConfigurationServer getGlobalConfigServer();

    /** Get a sandbox factory that can be used to run an invocation */
    public ISandboxFactory getSandboxFactory();

    /**
     * Validate option values.
     *
     * <p>Currently this will just validate that all mandatory options have been set
     *
     * @throws ConfigurationException if configuration is missing mandatory fields
     */
    public void validateOptions() throws ConfigurationException;

    /**
     * Filter the GlobalConfiguration based on a allowed list and output to an XML file.
     *
     * <p>For example, for following configuration:
     * {@code
     * <xml>
     *     <configuration>
     *         <device_monitor class="com.android.tradefed.device.DeviceMonitorMultiplexer" />
     *         <wtf_handler class="com.android.tradefed.log.TerribleFailureEmailHandler" />
     *         <key_store class="com.android.tradefed.util.keystore.JSONFileKeyStoreFactory" />
     *     </configuration>
     * </xml>
     * }
     *
     * <p>all config except "key_store" will be filtered out, and result a config file with
     * following content:
     * {@code
     * <xml>
     *     <configuration>
     *         <key_store class="com.android.tradefed.util.keystore.JSONFileKeyStoreFactory" />
     *     </configuration>
     * </xml>
     * }
     *
     * @param allowlistConfigs a {@link String} array of configs to be included in the new XML file.
     *     If it's set to <code>null<code/>, a default list should be used.
     * @return the File containing the new filtered global config.
     * @throws IOException
     */
    public File cloneConfigWithFilter(String... allowlistConfigs) throws IOException;

    /**
     * Filter the GlobalConfiguration based on a white list and output to an XML file.
     * @see #cloneConfigWithFilter(String...)
     *
     * @param exclusionPatterns The pattern of class name to exclude from the dump.
     * @param allowlistConfigs a {@link String} array of configs to be included in the new XML file.
     *     If it's set to <code>null<code/>, a default list should be used.
     * @return the File containing the new filtered global config.
     * @throws IOException
     */
    public File cloneConfigWithFilter(Set<String> exclusionPatterns, String... allowlistConfigs)
            throws IOException;

    /**
     * Filter the GlobalConfiguration based on a white list while allowing for manipulation of
     * option values and output to an XML file.
     *
     * @param exclusionPatterns The pattern of class name to exclude from the dump.
     * @param allowlistConfigs  a {@link String} array of configs to be included in the new XML
     *                          file.
     *                          If it's set to <code>null<code/>, a default list should be used.
     * @return the File containing the new filtered global config.
     * @see #cloneConfigWithFilter(String...)
     */
    public File cloneConfigWithFilter(
            Set<String> exclusionPatterns,
            IConfigOptionValueTransformer transformer,
            boolean deepCopy,
            String... allowlistConfigs)
            throws IOException;

    /**
     * Proper setup at the start of tradefed.
     *
     * @throws ConfigurationException
     */
    public void setup() throws ConfigurationException;

    /** Proper cleanup when tradefed shutdown. */
    public void cleanup();

    /** Sets the original config used to create the global configuration. */
    public void setOriginalConfig(String config);

    /** Set the {@link IConfigurationFactory} for this configuration. */
    public void setConfigurationFactory(IConfigurationFactory configFactory);
}
