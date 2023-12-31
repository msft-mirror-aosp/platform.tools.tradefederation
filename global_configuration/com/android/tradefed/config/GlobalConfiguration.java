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
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.command.CommandScheduler;
import com.android.tradefed.command.ICommandScheduler;
import com.android.tradefed.config.gcs.GCSConfigurationFactory;
import com.android.tradefed.device.DeviceManager;
import com.android.tradefed.device.DeviceSelectionOptions;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.IDeviceMonitor;
import com.android.tradefed.device.IDeviceSelection;
import com.android.tradefed.device.IMultiDeviceRecovery;
import com.android.tradefed.host.HostOptions;
import com.android.tradefed.host.IHostOptions;
import com.android.tradefed.host.IHostResourceManager;
import com.android.tradefed.host.LocalHostResourceManager;
import com.android.tradefed.invoker.shard.IShardHelper;
import com.android.tradefed.invoker.shard.StrictShardHelper;
import com.android.tradefed.log.ITerribleFailureHandler;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.monitoring.collector.IResourceMetricCollector;
import com.android.tradefed.sandbox.ISandboxFactory;
import com.android.tradefed.sandbox.TradefedSandboxFactory;
import com.android.tradefed.service.TradefedFeatureServer;
import com.android.tradefed.service.management.DeviceManagementGrpcServer;
import com.android.tradefed.service.management.TestInvocationManagementServer;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.hostmetric.IHostMonitor;
import com.android.tradefed.util.keystore.IKeyStoreFactory;
import com.android.tradefed.util.keystore.StubKeyStoreFactory;

import com.google.common.annotations.VisibleForTesting;

import org.kxml2.io.KXmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An {@link IGlobalConfiguration} implementation that stores the loaded config objects in a map
 */
public class GlobalConfiguration implements IGlobalConfiguration {
    // type names for built in configuration objects
    public static final String DEVICE_MONITOR_TYPE_NAME = "device_monitor";
    public static final String HOST_MONITOR_TYPE_NAME = "host_monitor";
    public static final String DEVICE_MANAGER_TYPE_NAME = "device_manager";
    public static final String WTF_HANDLER_TYPE_NAME = "wtf_handler";
    public static final String HOST_OPTIONS_TYPE_NAME = "host_options";
    public static final String HOST_RESOURCE_MANAGER_TYPE_NAME = "host_resource_manager";
    public static final String DEVICE_REQUIREMENTS_TYPE_NAME = "device_requirements";
    public static final String SCHEDULER_TYPE_NAME = "command_scheduler";
    public static final String MULTI_DEVICE_RECOVERY_TYPE_NAME = "multi_device_recovery";
    public static final String KEY_STORE_TYPE_NAME = "key_store";
    public static final String SHARDING_STRATEGY_TYPE_NAME = "sharding_strategy";
    public static final String GLOBAL_CONFIG_SERVER = "global_config_server";
    public static final String SANDBOX_FACTORY_TYPE_NAME = "sandbox_factory";
    public static final String RESOURCE_METRIC_COLLECTOR_TYPE_NAME = "resource_metric_collector";
    public static final String CREDENTIAL_FACTORY_TYPE_NAME = "credential_factory";
    public static final String TF_FEATURE_SERVER_NAME = "tf_feature_server";
    public static final String TF_INVOCATION_SERVER_NAME = "tf_invocation_server";
    public static final String TF_DEVICE_MANAGEMENT_SERVER_NAME = "tf_device_management_server";

    public static final String GLOBAL_CONFIG_VARIABLE = "TF_GLOBAL_CONFIG";
    public static final String GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE =
            "TF_GLOBAL_CONFIG_SERVER_CONFIG";
    private static final String GLOBAL_CONFIG_FILENAME = "tf_global_config.xml";

    private static Map<String, ObjTypeInfo> sObjTypeMap = null;
    private static IGlobalConfiguration sInstance = null;
    private static final Object sInstanceLock = new Object();

    // Empty embedded configuration available by default
    private static final String DEFAULT_EMPTY_CONFIG_NAME = "empty";

    // Configurations to be passed to subprocess: Typical object that are representing the host
    // level and the subprocess should follow too.
    private static final String[] CONFIGS_FOR_SUBPROCESS_ALLOW_LIST =
            new String[] {
                DEVICE_MANAGER_TYPE_NAME,
                KEY_STORE_TYPE_NAME,
                HOST_OPTIONS_TYPE_NAME,
                "android-build"
            };

    /** Mapping of config object type name to config objects. */
    private Map<String, List<Object>> mConfigMap;
    private MultiMap<String, String> mOptionMap;
    private String[] mOriginalArgs;
    private final String mName;
    private final String mDescription;
    private IConfigurationFactory mConfigFactory = null;

    /**
     * Returns a reference to the singleton {@link GlobalConfiguration} instance for this TF
     * instance.
     *
     * @throws IllegalStateException if {@link #createGlobalConfiguration(String[])} has not
     *         already been called.
     */
    public static IGlobalConfiguration getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("GlobalConfiguration has not yet been initialized!");
        }
        return sInstance;
    }

    /**
     * Returns a reference to the singleton {@link DeviceManager} instance for this TF
     * instance.
     *
     * @throws IllegalStateException if {@link #createGlobalConfiguration(String[])} has not
     *         already been called.
     */
    public static IDeviceManager getDeviceManagerInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("GlobalConfiguration has not yet been initialized!");
        }
        return sInstance.getDeviceManager();
    }

    public static List<IHostMonitor> getHostMonitorInstances() {
        if (sInstance == null) {
            throw new IllegalStateException("GlobalConfiguration has not yet been initialized!");
        }
        return sInstance.getHostMonitors();
    }

    /**
     * Sets up the {@link GlobalConfiguration} singleton for this TF instance.  Must be called
     * once and only once, before anything attempts to call {@link #getInstance()}
     *
     * @throws IllegalStateException if called more than once
     */
    public static List<String> createGlobalConfiguration(String[] args)
            throws ConfigurationException {
        synchronized (sInstanceLock) {
            if (sInstance != null) {
                throw new IllegalStateException("GlobalConfiguration is already initialized!");
            }
            List<String> nonGlobalArgs = new ArrayList<String>(args.length);
            List<String> nonConfigServerArgs = new ArrayList<String>(args.length);
            IConfigurationServer globalConfigServer =
                    createGlobalConfigServer(args, nonConfigServerArgs);
            if (globalConfigServer == null) {
                String path = getGlobalConfigPath();
                String[] arrayArgs = ArrayUtil.buildArray(new String[] {path}, args);
                IConfigurationFactory configFactory = ConfigurationFactory.getInstance();
                sInstance =
                        configFactory.createGlobalConfigurationFromArgs(arrayArgs, nonGlobalArgs);
                ((GlobalConfiguration) sInstance).mOriginalArgs = arrayArgs;
            } else {
                String currentHostConfig = globalConfigServer.getCurrentHostConfig();
                IConfigurationFactory configFactory =
                        GCSConfigurationFactory.getInstance(globalConfigServer);
                String[] arrayArgs =
                        ArrayUtil.buildArray(
                                new String[] {currentHostConfig},
                                nonConfigServerArgs.toArray(new String[0]));
                sInstance =
                        configFactory.createGlobalConfigurationFromArgs(arrayArgs, nonGlobalArgs);
                // Keep the original args, later if we want to clone the global config,
                // we will reuse GCSConfigurationFactory to download the config again.
                ((GlobalConfiguration) sInstance).mOriginalArgs = arrayArgs;
            }
            // Validate that madatory options have been set
            sInstance.validateOptions();

            return nonGlobalArgs;
        }
    }

    /**
     * Returns the path to a global config, if one exists, or <code>null</code> if none could be
     * found.
     * <p />
     * Search locations, in decreasing order of precedence
     * <ol>
     *   <li><code>$TF_GLOBAL_CONFIG</code> environment variable</li>
     *   <li><code>tf_global_config.xml</code> file in $PWD</li>
     *   <li>(FIXME) <code>tf_global_config.xml</code> file in dir where <code>tradefed.sh</code>
     *       lives</li>
     * </ol>
     */
    private static String getGlobalConfigPath() {
        String path = System.getenv(GLOBAL_CONFIG_VARIABLE);
        if (path != null) {
            // don't actually check for accessibility here, since the variable might be specifying
            // a java resource rather than a filename.  Even so, this can help the user figure out
            // which global config (if any) was picked up by TF.
            System.out.format(
                    "Attempting to use global config \"%s\" from variable $%s.\n",
                    path, GLOBAL_CONFIG_VARIABLE);
            return path;
        }

        File file = new File(GLOBAL_CONFIG_FILENAME);
        if (file.exists()) {
            path = file.getPath();
            System.out.format("Attempting to use autodetected global config \"%s\".\n", path);
            return path;
        }

        // FIXME: search in tradefed.sh launch dir (or classpath?)

        // Use default empty known global config
        return DEFAULT_EMPTY_CONFIG_NAME;
    }

    /**
     * Returns an {@link IConfigurationServer}, if one exists, or <code>null</code> if none could be
     * found.
     *
     * @param args for config server
     * @param nonConfigServerArgs a list which will be populated with the arguments that weren't
     *     processed as global arguments
     * @return an {@link IConfigurationServer}
     * @throws ConfigurationException
     */
    @VisibleForTesting
    static IConfigurationServer createGlobalConfigServer(
            String[] args, List<String> nonConfigServerArgs) throws ConfigurationException {
        String path = System.getenv(GLOBAL_CONFIG_SERVER_CONFIG_VARIABLE);
        if (path == null) {
            // No config server, should use config files.
            nonConfigServerArgs.addAll(Arrays.asList(args));
            return null;
        } else {
            System.out.format("Use global config server config %s.\n", path);
        }
        IConfigurationServer configServer = null;
        IConfigurationFactory configFactory = ConfigurationFactory.getInstance();
        IGlobalConfiguration configServerConfig =
                configFactory.createGlobalConfigurationFromArgs(
                        ArrayUtil.buildArray(new String[] {path}, args), nonConfigServerArgs);
        configServer = configServerConfig.getGlobalConfigServer();
        return configServer;
    }

    /**
     * Container struct for built-in config object type
     */
    private static class ObjTypeInfo {
        final Class<?> mExpectedType;
        /** true if a list (ie many objects in a single config) are supported for this type */
        final boolean mIsListSupported;

        ObjTypeInfo(Class<?> expectedType, boolean isList) {
            mExpectedType = expectedType;
            mIsListSupported = isList;
        }
    }

    /**
     * Determine if given config object type name is a built in object
     *
     * @param typeName the config object type name
     * @return <code>true</code> if name is a built in object type
     */
    static boolean isBuiltInObjType(String typeName) {
        return getObjTypeMap().containsKey(typeName);
    }

    private static synchronized Map<String, ObjTypeInfo> getObjTypeMap() {
        if (sObjTypeMap == null) {
            sObjTypeMap = new HashMap<String, ObjTypeInfo>();
            sObjTypeMap.put(HOST_OPTIONS_TYPE_NAME, new ObjTypeInfo(IHostOptions.class, false));
            sObjTypeMap.put(
                    HOST_RESOURCE_MANAGER_TYPE_NAME,
                    new ObjTypeInfo(IHostResourceManager.class, false));
            sObjTypeMap.put(DEVICE_MONITOR_TYPE_NAME, new ObjTypeInfo(IDeviceMonitor.class, true));
            sObjTypeMap.put(HOST_MONITOR_TYPE_NAME, new ObjTypeInfo(IHostMonitor.class, true));
            sObjTypeMap.put(DEVICE_MANAGER_TYPE_NAME, new ObjTypeInfo(IDeviceManager.class, false));
            sObjTypeMap.put(DEVICE_REQUIREMENTS_TYPE_NAME, new ObjTypeInfo(IDeviceSelection.class,
                    false));
            sObjTypeMap.put(WTF_HANDLER_TYPE_NAME,
                    new ObjTypeInfo(ITerribleFailureHandler.class, false));
            sObjTypeMap.put(SCHEDULER_TYPE_NAME, new ObjTypeInfo(ICommandScheduler.class, false));
            sObjTypeMap.put(
                    MULTI_DEVICE_RECOVERY_TYPE_NAME,
                    new ObjTypeInfo(IMultiDeviceRecovery.class, true));
            sObjTypeMap.put(KEY_STORE_TYPE_NAME, new ObjTypeInfo(IKeyStoreFactory.class, false));
            sObjTypeMap.put(
                    SHARDING_STRATEGY_TYPE_NAME, new ObjTypeInfo(IShardHelper.class, false));
            sObjTypeMap.put(
                    GLOBAL_CONFIG_SERVER, new ObjTypeInfo(IConfigurationServer.class, false));
            sObjTypeMap.put(
                    SANDBOX_FACTORY_TYPE_NAME, new ObjTypeInfo(ISandboxFactory.class, false));
            sObjTypeMap.put(
                    RESOURCE_METRIC_COLLECTOR_TYPE_NAME,
                    new ObjTypeInfo(IResourceMetricCollector.class, true));
            sObjTypeMap.put(
                    CREDENTIAL_FACTORY_TYPE_NAME, new ObjTypeInfo(ICredentialFactory.class, false));
        }
        return sObjTypeMap;
    }

    /**
     * Creates a {@link GlobalConfiguration} with default config objects
     */
    GlobalConfiguration(String name, String description) {
        mName = name;
        mDescription = description;
        mConfigMap = new LinkedHashMap<String, List<Object>>();
        mOptionMap = new MultiMap<String, String>();
        mOriginalArgs = new String[] {"empty"};
        setHostOptions(new HostOptions());
        setHostResourceManager(new LocalHostResourceManager());
        setDeviceRequirements(new DeviceSelectionOptions());
        setDeviceManager(new DeviceManager());
        setCommandScheduler(new CommandScheduler());
        setKeyStoreFactory(new StubKeyStoreFactory());
        setShardingStrategy(new StrictShardHelper());
        setSandboxFactory(new TradefedSandboxFactory());
    }

    /** {@inheritDoc} */
    @Override
    public void setOriginalConfig(String config) {
        mOriginalArgs = new String[] {config};
    }

    /** {@inheritDoc} */
    @Override
    public void setup() throws ConfigurationException {
        getHostResourceManager().setup();
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup() {
        getHostResourceManager().cleanup();
    }

    /**
     * @return the name of this {@link Configuration}
     */
    public String getName() {
        return mName;
    }

    /**
     * @return a short user readable description this {@link Configuration}
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IHostOptions getHostOptions() {
        return (IHostOptions) getConfigurationObject(HOST_OPTIONS_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    public IHostResourceManager getHostResourceManager() {
        return (IHostResourceManager) getConfigurationObject(HOST_RESOURCE_MANAGER_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public List<IDeviceMonitor> getDeviceMonitors() {
        return (List<IDeviceMonitor>) getConfigurationObjectList(DEVICE_MONITOR_TYPE_NAME);
    }

    @Override
    public IConfigurationServer getGlobalConfigServer() {
        return (IConfigurationServer) getConfigurationObject(GLOBAL_CONFIG_SERVER);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public List<IHostMonitor> getHostMonitors() {
        return (List<IHostMonitor>) getConfigurationObjectList(HOST_MONITOR_TYPE_NAME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITerribleFailureHandler getWtfHandler() {
        return (ITerribleFailureHandler) getConfigurationObject(WTF_HANDLER_TYPE_NAME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IKeyStoreFactory getKeyStoreFactory() {
        return (IKeyStoreFactory) getConfigurationObject(KEY_STORE_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    public IShardHelper getShardingStrategy() {
        return (IShardHelper) getConfigurationObject(SHARDING_STRATEGY_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    public IDeviceManager getDeviceManager() {
        return (IDeviceManager) getConfigurationObject(DEVICE_MANAGER_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    public ISandboxFactory getSandboxFactory() {
        return (ISandboxFactory) getConfigurationObject(SANDBOX_FACTORY_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    public IDeviceSelection getDeviceRequirements() {
        return (IDeviceSelection) getConfigurationObject(DEVICE_REQUIREMENTS_TYPE_NAME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ICommandScheduler getCommandScheduler() {
        return (ICommandScheduler)getConfigurationObject(SCHEDULER_TYPE_NAME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<IMultiDeviceRecovery> getMultiDeviceRecoveryHandlers() {
        return (List<IMultiDeviceRecovery>)getConfigurationObjectList(
                MULTI_DEVICE_RECOVERY_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public List<IResourceMetricCollector> getResourceMetricCollectors() {
        return (List<IResourceMetricCollector>)
                getConfigurationObjectList(RESOURCE_METRIC_COLLECTOR_TYPE_NAME);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public ICredentialFactory getCredentialFactory() {
        return (ICredentialFactory) getConfigurationObject(CREDENTIAL_FACTORY_TYPE_NAME);
    }

    /** Internal helper to get the list of config object */
    private List<?> getConfigurationObjectList(String typeName) {
        return mConfigMap.get(typeName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getConfigurationObject(String typeName) {
        List<?> configObjects = getConfigurationObjectList(typeName);
        if (configObjects == null) {
            return null;
        }
        ObjTypeInfo typeInfo = getObjTypeMap().get(typeName);
        if (typeInfo != null && typeInfo.mIsListSupported) {
            throw new IllegalStateException(
                    String.format(
                            "Wrong method call for type %s. Used getConfigurationObject() for a "
                                    + "config object that is stored as a list",
                            typeName));
        }
        if (configObjects.size() != 1) {
            throw new IllegalStateException(String.format(
                    "Attempted to retrieve single object for %s, but %d are present",
                    typeName, configObjects.size()));
        }
        return configObjects.get(0);
    }

    /**
     * Return a copy of all config objects
     */
    private Collection<Object> getAllConfigurationObjects() {
        Collection<Object> objectsCopy = new ArrayList<Object>();
        for (List<Object> objectList : mConfigMap.values()) {
            objectsCopy.addAll(objectList);
        }
        return objectsCopy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void injectOptionValue(String optionName, String optionValue)
            throws ConfigurationException {
        injectOptionValue(optionName, null, optionValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void injectOptionValue(String optionName, String optionKey, String optionValue)
            throws ConfigurationException {
        OptionSetter optionSetter = new OptionSetter(getAllConfigurationObjects());
        optionSetter.setOptionValue(optionName, optionKey, optionValue);

        if (optionKey != null) {
            mOptionMap.put(optionName, optionKey + "=" + optionValue);
        } else {
            mOptionMap.put(optionName, optionValue);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getOptionValues(String optionName) {
        return mOptionMap.get(optionName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHostOptions(IHostOptions hostOptions) {
        setConfigurationObjectNoThrow(HOST_OPTIONS_TYPE_NAME, hostOptions);
    }

    /** {@inheritDoc} */
    @Override
    public void setHostResourceManager(IHostResourceManager hostResourceManager) {
        setConfigurationObjectNoThrow(HOST_RESOURCE_MANAGER_TYPE_NAME, hostResourceManager);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDeviceMonitor(IDeviceMonitor monitor) {
        setConfigurationObjectNoThrow(DEVICE_MONITOR_TYPE_NAME, monitor);
    }

    /** {@inheritDoc} */
    @Override
    public void setHostMonitors(List<IHostMonitor> hostMonitors) {
        setConfigurationObjectListNoThrow(HOST_MONITOR_TYPE_NAME, hostMonitors);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWtfHandler(ITerribleFailureHandler wtfHandler) {
        setConfigurationObjectNoThrow(WTF_HANDLER_TYPE_NAME, wtfHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKeyStoreFactory(IKeyStoreFactory factory) {
        setConfigurationObjectNoThrow(KEY_STORE_TYPE_NAME, factory);
    }

    /** {@inheritDoc} */
    @Override
    public void setShardingStrategy(IShardHelper sharding) {
        setConfigurationObjectNoThrow(SHARDING_STRATEGY_TYPE_NAME, sharding);
    }

    /** {@inheritDoc} */
    @Override
    public void setDeviceManager(IDeviceManager manager) {
        setConfigurationObjectNoThrow(DEVICE_MANAGER_TYPE_NAME, manager);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDeviceRequirements(IDeviceSelection devRequirements) {
        setConfigurationObjectNoThrow(DEVICE_REQUIREMENTS_TYPE_NAME, devRequirements);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCommandScheduler(ICommandScheduler scheduler) {
        setConfigurationObjectNoThrow(SCHEDULER_TYPE_NAME, scheduler);
    }

    @Override
    public void setSandboxFactory(ISandboxFactory factory) {
        setConfigurationObjectNoThrow(SANDBOX_FACTORY_TYPE_NAME, factory);
    }

    /** {@inheritDoc} */
    @Override
    public void setResourceMetricCollector(IResourceMetricCollector collector) {
        setConfigurationObjectNoThrow(RESOURCE_METRIC_COLLECTOR_TYPE_NAME, collector);
    }

    /** {@inheritDoc} */
    @Override
    public void setTradefedFeatureServer(TradefedFeatureServer server) {
        setConfigurationObjectNoThrow(TF_FEATURE_SERVER_NAME, server);
    }

    @Override
    public void setInvocationServer(TestInvocationManagementServer server) {
        setConfigurationObjectNoThrow(TF_INVOCATION_SERVER_NAME, server);
    }

    @Override
    public void setDeviceManagementServer(DeviceManagementGrpcServer server) {
        setConfigurationObjectNoThrow(TF_DEVICE_MANAGEMENT_SERVER_NAME, server);
    }

    /** {@inheritDoc} */
    @Override
    public TradefedFeatureServer getFeatureServer() {
        List<?> configObjects = getConfigurationObjectList(TF_FEATURE_SERVER_NAME);
        if (configObjects == null) {
            return null;
        }
        if (configObjects.size() != 1) {
            return null;
        }
        return (TradefedFeatureServer) configObjects.get(0);
    }

    @Override
    public TestInvocationManagementServer getTestInvocationManagementSever() {
        List<?> configObjects = getConfigurationObjectList(TF_INVOCATION_SERVER_NAME);
        if (configObjects == null) {
            return null;
        }
        if (configObjects.size() != 1) {
            return null;
        }
        return (TestInvocationManagementServer) configObjects.get(0);
    }

    @Override
    public DeviceManagementGrpcServer getDeviceManagementServer() {
        List<?> configObjects = getConfigurationObjectList(TF_DEVICE_MANAGEMENT_SERVER_NAME);
        if (configObjects == null) {
            return null;
        }
        if (configObjects.size() != 1) {
            return null;
        }
        return (DeviceManagementGrpcServer) configObjects.get(0);
    }

    /** {@inheritDoc} */
    @Override
    public void setConfigurationObject(String typeName, Object configObject)
            throws ConfigurationException {
        if (configObject == null) {
            throw new IllegalArgumentException("configObject cannot be null");
        }
        mConfigMap.remove(typeName);
        addObject(typeName, configObject);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setConfigurationObjectList(String typeName, List<?> configList)
            throws ConfigurationException {
        if (configList == null) {
            throw new IllegalArgumentException("configList cannot be null");
        }
        mConfigMap.remove(typeName);
        for (Object configObject : configList) {
            addObject(typeName, configObject);
        }
    }

    /**
     * A wrapper around {@link #setConfigurationObjectList(String, List)} that will not throw {@link
     * ConfigurationException}.
     *
     * <p>Intended to be used in cases where its guaranteed that <var>configObject</var> is the
     * correct type
     */
    private void setConfigurationObjectListNoThrow(String typeName, List<?> configList) {
        try {
            setConfigurationObjectList(typeName, configList);
        } catch (ConfigurationException e) {
            // should never happen
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Adds a loaded object to this configuration.
     *
     * @param typeName the unique object type name of the configuration object
     * @param configObject the configuration object
     * @throws ConfigurationException if object was not the correct type
     */
    private void addObject(String typeName, Object configObject) throws ConfigurationException {
        List<Object> objList = mConfigMap.get(typeName);
        if (objList == null) {
            objList = new ArrayList<Object>(1);
            mConfigMap.put(typeName, objList);
        }
        ObjTypeInfo typeInfo = getObjTypeMap().get(typeName);
        if (typeInfo != null && !typeInfo.mExpectedType.isInstance(configObject)) {
            throw new ConfigurationException(String.format(
                    "The config object %s is not the correct type. Expected %s, received %s",
                    typeName, typeInfo.mExpectedType.getCanonicalName(),
                    configObject.getClass().getCanonicalName()));
        }
        if (typeInfo != null && !typeInfo.mIsListSupported && objList.size() > 0) {
            throw new ConfigurationException(String.format(
                    "Only one config object allowed for %s, but multiple were specified.",
                    typeName));
        }
        objList.add(configObject);
    }

    /**
     * A wrapper around {@link #setConfigurationObject(String, Object)} that will not throw
     * {@link ConfigurationException}.
     * <p/>
     * Intended to be used in cases where its guaranteed that <var>configObject</var> is the
     * correct type.
     *
     * @param typeName
     * @param configObject
     */
    private void setConfigurationObjectNoThrow(String typeName, Object configObject) {
        try {
            setConfigurationObject(typeName, configObject);
        } catch (ConfigurationException e) {
            // should never happen
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> setOptionsFromCommandLineArgs(List<String> listArgs)
            throws ConfigurationException {
        ArgsOptionParser parser = new ArgsOptionParser(getAllConfigurationObjects());
        return parser.parse(listArgs);
    }

    /**
     * Outputs a command line usage help text for this configuration to given printStream.
     *
     * @param out the {@link PrintStream} to use.
     * @throws ConfigurationException
     */
    public void printCommandUsage(boolean importantOnly, PrintStream out)
            throws ConfigurationException {
        out.println(String.format("'%s' configuration: %s", getName(), getDescription()));
        out.println();
        if (importantOnly) {
            out.println("Printing help for only the important options. " +
                    "To see help for all options, use the --help-all flag");
            out.println();
        }
        for (Map.Entry<String, List<Object>> configObjectsEntry : mConfigMap.entrySet()) {
            for (Object configObject : configObjectsEntry.getValue()) {
                String optionHelp = printOptionsForObject(importantOnly,
                        configObjectsEntry.getKey(), configObject);
                // only print help for object if optionHelp is non zero length
                if (optionHelp.length() > 0) {
                    String classAlias = "";
                    if (configObject.getClass().isAnnotationPresent(OptionClass.class)) {
                        final OptionClass classAnnotation = configObject.getClass().getAnnotation(
                                OptionClass.class);
                        classAlias = String.format("'%s' ", classAnnotation.alias());
                    }
                    out.printf("  %s%s options:", classAlias, configObjectsEntry.getKey());
                    out.println();
                    out.print(optionHelp);
                    out.println();
                }
            }
        }
    }

    /**
     * Prints out the available config options for given configuration object.
     *
     * @param importantOnly print only the important options
     * @param objectTypeName the config object type name. Used to generate more descriptive error
     *            messages
     * @param configObject the config object
     * @return a {@link String} of option help text
     * @throws ConfigurationException
     */
    private String printOptionsForObject(boolean importantOnly, String objectTypeName,
            Object configObject) throws ConfigurationException {
        return ArgsOptionParser.getOptionHelp(importantOnly, configObject);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateOptions() throws ConfigurationException {
        ArgsOptionParser argsParser = new ArgsOptionParser(getAllConfigurationObjects());
        argsParser.validateMandatoryOptions();

        getHostOptions().validateOptions();

        CLog.d("Resolve and remote files from @Option");
        // Setup and validate the GCS File paths, they will be deleted when TF ends
        List<File> remoteFiles = new ArrayList<>();
        try {
            remoteFiles.addAll(argsParser.validateRemoteFilePath(new DynamicRemoteFileResolver()));
        } catch (BuildRetrievalError e) {
            throw new ConfigurationException(e.getMessage(), e);
        }
        remoteFiles.forEach(File::deleteOnExit);
    }

    /** {@inheritDoc} */
    @Override
    public File cloneConfigWithFilter(String... allowlistConfigs) throws IOException {
        return cloneConfigWithFilter(new HashSet<>(), allowlistConfigs);
    }

    /** {@inheritDoc} */
    @Override
    public File cloneConfigWithFilter(Set<String> exclusionPatterns, String... allowlistConfigs)
            throws IOException {
        return cloneConfigWithFilter(
                exclusionPatterns, new NoOpConfigOptionValueTransformer(), true, allowlistConfigs);
    }

    /** {@inheritDoc} */
    @Override
    public File cloneConfigWithFilter(
            Set<String> exclusionPatterns,
            IConfigOptionValueTransformer transformer,
            boolean deepCopy,
            String... allowlistConfigs)
            throws IOException {
        IConfigurationFactory configFactory = getConfigurationFactory();
        IGlobalConfiguration copy = null;
        if (deepCopy) {
            try {
                // Use a copy with default original options
                copy =
                        configFactory.createGlobalConfigurationFromArgs(
                                mOriginalArgs, new ArrayList<>());
            } catch (ConfigurationException e) {
                throw new IOException(e);
            }
        } else {
            copy = this;
        }

        File filteredGlobalConfig = FileUtil.createTempFile("filtered_global_config", ".config");
        KXmlSerializer serializer = ConfigurationUtil.createSerializer(filteredGlobalConfig);
        serializer.startTag(null, ConfigurationUtil.CONFIGURATION_NAME);
        if (allowlistConfigs == null || allowlistConfigs.length == 0) {
            allowlistConfigs = CONFIGS_FOR_SUBPROCESS_ALLOW_LIST;
        }
        for (String config : allowlistConfigs) {
            Object configObj = copy.getConfigurationObject(config);
            if (configObj == null) {
                CLog.d("Object '%s' was not found in global config.", config);
                continue;
            }
            String name = configObj.getClass().getCanonicalName();
            if (!shouldDump(name, exclusionPatterns)) {
                continue;
            }
            boolean isGenericObject = false;
            if (getObjTypeMap().get(config) == null) {
                isGenericObject = true;
            }
            ConfigurationUtil.dumpClassToXml(
                    serializer,
                    config,
                    configObj,
                    isGenericObject,
                    new ArrayList<>(),
                    transformer,
                    true,
                    false);
        }
        serializer.endTag(null, ConfigurationUtil.CONFIGURATION_NAME);
        serializer.endDocument();
        return filteredGlobalConfig;
    }

    /** {@inheritDoc} */
    @Override
    public void setConfigurationFactory(IConfigurationFactory configFactory) {
        mConfigFactory = configFactory;
    }

    @VisibleForTesting
    protected IConfigurationFactory getConfigurationFactory() {
        return mConfigFactory;
    }

    private boolean shouldDump(String name, Set<String> patterns) {
        for (String pattern : patterns) {
            if (Pattern.matches(pattern, name)) {
                return false;
            }
        }
        return true;
    }
}
