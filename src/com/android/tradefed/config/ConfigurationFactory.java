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

import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.command.CommandOptions;
import com.android.tradefed.config.proxy.TradefedDelegator;
import com.android.tradefed.config.remote.ExtendedFile;
import com.android.tradefed.config.remote.IRemoteFileResolver.ResolvedFile;
import com.android.tradefed.config.yaml.ConfigurationYamlParser;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.ClassPathScanner;
import com.android.tradefed.util.ClassPathScanner.IClassPathFilter;
import com.android.tradefed.util.DirectedGraph;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SystemUtil;
import com.android.tradefed.util.keystore.DryRunKeyStore;
import com.android.tradefed.util.keystore.IKeyStoreClient;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedSet;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Factory for creating {@link IConfiguration}.
 */
public class ConfigurationFactory implements IConfigurationFactory {

    /** Currently supported extensions for Tradefed configurations */
    private static final Set<String> SUPPORTED_EXTENSIONS =
            ImmutableSortedSet.of(".xml", ".config");

    private static IConfigurationFactory sInstance = null;
    private static final String CONFIG_PREFIX = "config/";
    private static final String DRY_RUN_TEMPLATE_CONFIG = "empty";
    private static final String CONFIG_ERROR_PATTERN = "(Could not find option with name )(.*)";
    // TODO(murj) generalize this to a URI matcher
    private static final String DIRECT_CONFIG_PATTERN = "^(gs|file|http|https)://.*";

    private Map<ConfigId, ConfigurationDef> mConfigDefMap;

    /**
     * A simple struct-like class that stores a configuration's name alongside
     * the arguments for any {@code <template-include>} tags it may contain.
     * Because the actual bits stored by the configuration may vary with
     * template arguments, they must be considered as essential a part of the
     * configuration's identity as the filename.
     */
    static class ConfigId {
        public String name = null;
        public Map<String, String> templateMap = new HashMap<>();

        /**
         * No-op constructor
         */
        public ConfigId() {
        }

        /**
         * Convenience constructor. Equivalent to calling two-arg constructor
         * with {@code null} {@code templateMap}.
         */
        public ConfigId(String name) {
            this(name, null);
        }

        /**
         * Two-arg convenience constructor. {@code templateMap} may be null.
         */
        public ConfigId(String name, Map<String, String> templateMap) {
            this.name = name;
            if (templateMap != null) {
                this.templateMap.putAll(templateMap);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return 2 * ((name == null) ? 0 : name.hashCode()) + 3 * templateMap.hashCode();
        }

        private boolean matches(Object a, Object b) {
            if (a == null && b == null) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return a.equals(b);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (!(other instanceof ConfigId)) {
                return false;
            }

            final ConfigId otherConf = (ConfigId) other;
            return matches(name, otherConf.name) && matches(templateMap, otherConf.templateMap);
        }
    }

    /**
     * A {@link IClassPathFilter} for configuration XML files.
     */
    private class ConfigClasspathFilter implements IClassPathFilter {

        private String mPrefix = null;

        public ConfigClasspathFilter(String prefix) {
            mPrefix = getConfigPrefix();
            if (prefix != null) {
                mPrefix += prefix;
            }
            CLog.d("Searching the '%s' config path", mPrefix);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean accept(String pathName) {
            // only accept entries that match the pattern, and that we don't already know about
            final ConfigId pathId = new ConfigId(pathName);
            String extension = FileUtil.getExtension(pathName);
            return pathName.startsWith(mPrefix)
                    && SUPPORTED_EXTENSIONS.contains(extension)
                    && !mConfigDefMap.containsKey(pathId);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String transform(String pathName) {
            // strip off CONFIG_PREFIX and config extension
            int pathStartIndex = getConfigPrefix().length();
            String extension = FileUtil.getExtension(pathName);
            int pathEndIndex = pathName.length() - extension.length();
            return pathName.substring(pathStartIndex, pathEndIndex);
        }
    }

    /**
     * A {@link Comparator} for {@link ConfigurationDef} that sorts by
     * {@link ConfigurationDef#getName()}.
     */
    private static class ConfigDefComparator implements Comparator<ConfigurationDef> {

        /**
         * {@inheritDoc}
         */
        @Override
        public int compare(ConfigurationDef d1, ConfigurationDef d2) {
            return d1.getName().compareTo(d2.getName());
        }

    }

    /**
     * Get a list of {@link File} of the test cases directories
     *
     * <p>The wrapper function is for unit test to mock the system calls.
     *
     * @return a list of {@link File} of directories of the test cases folder of build output, based
     *     on the value of environment variables.
     */
    @VisibleForTesting
    List<File> getExternalTestCasesDirs() {
        return SystemUtil.getExternalTestCasesDirs();
    }

    /**
     * Get the path to the config file for a test case.
     *
     * <p>The given name in a test config can be the name of a test case located in an out directory
     * defined in the following environment variables:
     *
     * <p>ANDROID_TARGET_OUT_TESTCASES
     *
     * <p>ANDROID_HOST_OUT_TESTCASES
     *
     * <p>This method tries to locate the test config name in these directories. If no config is
     * found, return null.
     *
     * @param name Name of a config file.
     * @return A File object of the config file for the given test case.
     */
    @VisibleForTesting
    File getTestCaseConfigPath(String name) {
        String[] possibleConfigFileNames = {name};
        if (Strings.isNullOrEmpty(FileUtil.getExtension(name))) {
            possibleConfigFileNames = new String[SUPPORTED_EXTENSIONS.size()];
            int i = 0;
            for (String supportedExtension : SUPPORTED_EXTENSIONS) {
                possibleConfigFileNames[i] = (name + supportedExtension);
                i++;
            }
        }

        for (File testCasesDir : getExternalTestCasesDirs()) {
            for (String configFileName : possibleConfigFileNames) {
                File config = FileUtil.findFile(testCasesDir, configFileName);
                if (config != null) {
                    CLog.d("Using config: %s/%s", testCasesDir.getAbsoluteFile(), configFileName);
                    return config;
                }
            }
        }
        return null;
    }

    /**
     * Implementation of {@link IConfigDefLoader} that tracks the included configurations from one
     * root config, and throws an exception on circular includes.
     */
    protected class ConfigLoader implements IConfigDefLoader {

        private final boolean mIsGlobalConfig;
        private DirectedGraph<String> mConfigGraph = new DirectedGraph<String>();

        public ConfigLoader(boolean isGlobalConfig) {
            mIsGlobalConfig = isGlobalConfig;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ConfigurationDef getConfigurationDef(String name, Map<String, String> templateMap)
                throws ConfigurationException {

            String configName = findConfigName(name, null);
            final ConfigId configId = new ConfigId(name, templateMap);
            ConfigurationDef def = mConfigDefMap.get(configId);

            if (def == null || def.isStale()) {
                def = new ConfigurationDef(configName);
                loadConfiguration(configName, def, null, templateMap, null);
                mConfigDefMap.put(configId, def);
            } else {
                if (templateMap != null) {
                    // Clearing the map before returning the cached config to
                    // avoid seeing them as unused.
                    templateMap.clear();
                }
            }
            return def;
        }

        /** Returns true if it is a config file found inside the classpath. */
        protected boolean isBundledConfig(String name) {
            InputStream configStream = getBundledConfigStream(name);
            return configStream != null;
        }

        /**
         * Get the absolute path of a local config file.
         *
         * @param root parent path of config file
         * @param name config file
         * @return absolute path for local config file.
         * @throws ConfigurationException
         */
        private String getAbsolutePath(String root, String name) throws ConfigurationException {
            File file = new File(name);
            if (!file.isAbsolute()) {
                if (root == null) {
                    // if root directory was not specified, get the current
                    // working directory.
                    root = System.getProperty("user.dir");
                }
                file = new File(root, name);
            }
            try {
                return file.getCanonicalPath();
            } catch (IOException e) {
                throw new ConfigurationException(
                        String.format(
                                "Failure when trying to determine local file canonical path %s", e),
                        InfraErrorIdentifier.CONFIGURATION_NOT_FOUND);
            }
        }

        /**
         * Find config's name based on its name and its parent name. This is used to properly handle
         * bundle configs and local configs.
         *
         * @param name config's name
         * @param parentName config's parent's name.
         * @return the config's full name.
         * @throws ConfigurationException
         */
        protected String findConfigName(String name, String parentName)
                throws ConfigurationException {
            if (isBundledConfig(name)) {
                return name;
            }
            if (parentName == null || isBundledConfig(parentName)) {
                // Search files for config.
                String configName = getAbsolutePath(null, name);
                File localConfig = new File(configName);
                if (!localConfig.exists()) {
                    localConfig = getTestCaseConfigPath(name);
                }
                if (localConfig != null) {
                    return localConfig.getAbsolutePath();
                }
                // Can not find local config.
                if (parentName == null) {
                    throw new ConfigurationException(
                            String.format("Can not find local config %s.", name),
                            InfraErrorIdentifier.CONFIGURATION_NOT_FOUND);

                } else {
                    throw new ConfigurationException(
                            String.format(
                                    "Bundled config '%s' is including a config '%s' that's neither "
                                            + "local nor bundled.",
                                    parentName, name),
                            InfraErrorIdentifier.CONFIGURATION_NOT_FOUND);
                }
            }
            try {
                // Local configs' include should be relative to their parent's path.
                String parentRoot = new File(parentName).getParentFile().getCanonicalPath();
                return getAbsolutePath(parentRoot, name);
            } catch (IOException e) {
                throw new ConfigurationException(
                        e.getMessage(), e.getCause(), InfraErrorIdentifier.CONFIGURATION_NOT_FOUND);
            }
        }

        /**
         * Configs that are bundled inside the tradefed.jar can only include other configs also
         * bundled inside tradefed.jar. However, local (external) configs can include both local
         * (external) and bundled configs.
         */
        @Override
        public void loadIncludedConfiguration(
                ConfigurationDef def,
                String parentName,
                String name,
                String deviceTagObject,
                Map<String, String> templateMap,
                Set<String> templateSeen)
                throws ConfigurationException {

            String config_name = findConfigName(name, parentName);
            mConfigGraph.addEdge(parentName, config_name);
            // If the inclusion of configurations is a cycle we throw an exception.
            if (!mConfigGraph.isDag()) {
                CLog.e("%s", mConfigGraph);
                throw new ConfigurationException(
                        String.format(
                                "Circular configuration include: config '%s' is already included",
                                config_name),
                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
            }
            loadConfiguration(config_name, def, deviceTagObject, templateMap, templateSeen);
        }

        /**
         * Loads a configuration.
         *
         * @param name the name of a built-in configuration to load or a file path to configuration
         *     file to load
         * @param def the loaded {@link ConfigurationDef}
         * @param deviceTagObject name of the current deviceTag if we are loading from a config
         *     inside an <include>. Null otherwise.
         * @param templateMap map from template-include names to their respective concrete
         *     configuration files
         * @param templateSeen set of template placeholder name already encountered
         * @throws ConfigurationException if a configuration with given name/file path cannot be
         *     loaded or parsed
         */
        void loadConfiguration(
                String name,
                ConfigurationDef def,
                String deviceTagObject,
                Map<String, String> templateMap,
                Set<String> templateSeen)
                throws ConfigurationException {
            BufferedInputStream bufStream = getConfigStream(name);
            String extension = FileUtil.getExtension(name);
            switch (extension) {
                case ".xml":
                case ".config":
                case "":
                    ConfigurationXmlParser parser =
                            new ConfigurationXmlParser(this, deviceTagObject);
                    parser.parse(def, name, bufStream, templateMap, templateSeen);
                    break;
                case ".tf_yaml":
                    ConfigurationYamlParser yamlParser = new ConfigurationYamlParser();
                    yamlParser.parse(def, name, bufStream, false);
                    break;
                default:
                    throw new ConfigurationException(
                            String.format("The config format for %s is not supported.", name));
            }
            trackConfig(name, def);
        }

        /**
         * Track config for dynamic loading. Right now only local files are supported.
         *
         * @param name config's name
         * @param def config's def.
         */
        protected void trackConfig(String name, ConfigurationDef def) {
            // Track local config source files
            if (!isBundledConfig(name)) {
                def.registerSource(new File(name));
            }
        }

        /**
         * Should track the config's life cycle or not.
         *
         * @param name config's name
         * @return <code>true</code> if the config is trackable, otherwise <code>false</code>.
         */
        protected boolean isTrackableConfig(String name) {
            return !isBundledConfig(name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isGlobalConfig() {
            return mIsGlobalConfig;
        }

    }

    protected ConfigurationFactory() {
        mConfigDefMap = new Hashtable<ConfigId, ConfigurationDef>();
    }

    /**
     * Get the singleton {@link IConfigurationFactory} instance.
     */
    public static IConfigurationFactory getInstance() {
        if (sInstance == null) {
            sInstance = new ConfigurationFactory();
        }
        return sInstance;
    }

    /**
     * Retrieve the {@link ConfigurationDef} for the given name
     *
     * @param name the name of a built-in configuration to load or a file path to configuration file
     *     to load
     * @return {@link ConfigurationDef}
     * @throws ConfigurationException if an error occurred loading the config
     */
    protected ConfigurationDef getConfigurationDef(
            String name, boolean isGlobal, Map<String, String> templateMap)
            throws ConfigurationException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("getConfigurationDef")) {
            return new ConfigLoader(isGlobal).getConfigurationDef(name, templateMap);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IConfiguration createConfigurationFromArgs(String[] arrayArgs)
            throws ConfigurationException {
        return createConfigurationFromArgs(arrayArgs, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IConfiguration createConfigurationFromArgs(String[] arrayArgs,
            List<String> unconsumedArgs) throws ConfigurationException {
        return createConfigurationFromArgs(arrayArgs, unconsumedArgs, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IConfiguration createConfigurationFromArgs(String[] arrayArgs,
            List<String> unconsumedArgs, IKeyStoreClient keyStoreClient)
            throws ConfigurationException {
        if (arrayArgs.length == 0) {
            throw new ConfigurationException("Configuration to run was not specified");
        }

        List<String> listArgs = new ArrayList<String>(arrayArgs.length);
        // FIXME: Update parsing to not care about arg order.
        String[] reorderedArrayArgs = reorderArgs(arrayArgs);
        IConfiguration config =
                internalCreateConfigurationFromArgs(
                        reorderedArrayArgs, listArgs, keyStoreClient, null);
        config.setCommandLine(arrayArgs);
        if (listArgs.contains("--" + CommandOptions.DRY_RUN_OPTION)
                || listArgs.contains("--" + CommandOptions.NOISY_DRY_RUN_OPTION)) {
            // In case of dry-run, we replace the KeyStore by a dry-run one.
            CLog.w("dry-run detected, we are using a dryrun keystore");
            keyStoreClient = new DryRunKeyStore();
        }
        final List<String> tmpUnconsumedArgs =
                config.setOptionsFromCommandLineArgs(listArgs, keyStoreClient);

        if (unconsumedArgs == null && tmpUnconsumedArgs.size() > 0) {
            // (unconsumedArgs == null) is taken as a signal that the caller
            // expects all args to
            // be processed.
            throw new ConfigurationException(
                    String.format(
                            "Invalid arguments provided. Unprocessed arguments: %s",
                            tmpUnconsumedArgs),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        } else if (unconsumedArgs != null) {
            // Return the unprocessed args
            unconsumedArgs.addAll(tmpUnconsumedArgs);
        }

        return config;
    }

    /** {@inheritDoc} */
    @Override
    public IConfiguration createPartialConfigurationFromArgs(
            String[] arrayArgs,
            IKeyStoreClient keyStoreClient,
            Set<String> allowedObjects,
            TradefedDelegator delegator)
            throws ConfigurationException {
        if (arrayArgs.length == 0) {
            throw new ConfigurationException(
                    "Configuration to run was not specified",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        List<String> listArgs = new ArrayList<String>(arrayArgs.length);
        String[] reorderedArrayArgs = reorderArgs(arrayArgs);
        IConfiguration config =
                internalCreateConfigurationFromArgs(
                        reorderedArrayArgs, listArgs, keyStoreClient, allowedObjects);
        if (delegator != null) {
            config.setConfigurationObject(TradefedDelegator.DELEGATE_OBJECT, delegator);
        }
        config.setCommandLine(arrayArgs);
        List<String> leftOver =
                config.setBestEffortOptionsFromCommandLineArgs(listArgs, keyStoreClient);
        CLog.d("Non-applied arguments: %s", leftOver);
        return config;
    }

    @VisibleForTesting
    protected boolean isDirectConfiguration(String configName) {
        return Pattern.matches(DIRECT_CONFIG_PATTERN, configName);
    }

    /**
     * Creates a {@link Configuration} from the name given in arguments.
     *
     * <p>Note will not populate configuration with values from options
     *
     * @param arrayArgs the full list of command line arguments, including the config name
     * @param optionArgsRef an empty list, that will be populated with the option arguments left to
     *     be interpreted
     * @param keyStoreClient {@link IKeyStoreClient} keystore client to use if any.
     * @param allowedObjects config object that are allowed to be created.
     * @return An {@link IConfiguration} object representing the configuration that was loaded
     * @throws ConfigurationException
     */
    private IConfiguration internalCreateConfigurationFromArgs(
            String[] arrayArgs,
            List<String> optionArgsRef,
            IKeyStoreClient keyStoreClient,
            Set<String> allowedObjects)
            throws ConfigurationException {
        final List<String> listArgs = new ArrayList<>(Arrays.asList(arrayArgs));
        // first arg is config name
        final String configName = listArgs.remove(0);

        // ATTN This section short-circuts the rest of the configuration pipeline
        if (isDirectConfiguration(configName)) {
            return internalCreateDirectConfiguration(
                    configName, listArgs, optionArgsRef, keyStoreClient, allowedObjects);
        }

        Map<String, String> uniqueMap =
                extractTemplates(configName, listArgs, optionArgsRef, keyStoreClient);
        if (allowedObjects != null && !allowedObjects.isEmpty()) {
            ConfigLoader tmpLoader = new ConfigLoader(false);
            // For partial loading be lenient about templates and let the delegate deal with it.
            // In some cases this won't be 100% correct but it's better than failing on all new
            // configs.
            for (String key : uniqueMap.keySet()) {
                try {
                    tmpLoader.findConfigName(uniqueMap.get(key), null);
                } catch (ConfigurationException e) {
                    uniqueMap.put(key, "empty");
                }
            }
        }
        ConfigurationDef configDef = getConfigurationDef(configName, false, uniqueMap);
        if (!uniqueMap.isEmpty()) {
            // remove the bad ConfigDef from the cache.
            for (ConfigId cid : mConfigDefMap.keySet()) {
                if (mConfigDefMap.get(cid) == configDef) {
                    CLog.d("Cleaning the cache for this configdef");
                    mConfigDefMap.remove(cid);
                    break;
                }
            }
            throw new ConfigurationException(
                    String.format("Unused template:map parameters: %s", uniqueMap.toString()),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        return configDef.createConfiguration(allowedObjects);
    }

    private Map<String, String> extractTemplates(
            String configName,
            List<String> listArgs,
            List<String> optionArgsRef,
            IKeyStoreClient keyStoreClient)
            throws ConfigurationException {
        try (CloseableTraceScope ignored = new CloseableTraceScope("extractTemplates")) {
            final String extension = FileUtil.getExtension(configName);
            switch (extension) {
                case ".xml":
                case ".config":
                case "":
                    final ConfigurationXmlParserSettings parserSettings =
                            new ConfigurationXmlParserSettings();
                    final ArgsOptionParser templateArgParser = new ArgsOptionParser(parserSettings);
                    if (keyStoreClient != null) {
                        templateArgParser.setKeyStore(keyStoreClient);
                    }
                    optionArgsRef.addAll(templateArgParser.parseBestEffort(listArgs));
                    // Check that the same template is not attempted to be loaded twice.
                    for (String key : parserSettings.templateMap.keySet()) {
                        if (parserSettings.templateMap.get(key).size() > 1) {
                            throw new ConfigurationException(
                                    String.format(
                                            "More than one template specified for key '%s'", key),
                                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                        }
                    }
                    return parserSettings.templateMap.getUniqueMap();
                case ".tf_yaml":
                    // We parse the arguments but don't support template for YAML
                    final ArgsOptionParser allArgsParser = new ArgsOptionParser();
                    if (keyStoreClient != null) {
                        allArgsParser.setKeyStore(keyStoreClient);
                    }
                    optionArgsRef.addAll(allArgsParser.parseBestEffort(listArgs));
                    return new HashMap<>();
                default:
                    return new HashMap<>();
            }
        }
    }

    /**
     * A delegation method from the other `internalCreateConfiguration...` methods for direct
     * configs
     *
     * <p>This method encapsulates the direct configuration flow so that we can separate it from the
     * legacy flows for future refactoring.
     *
     * @param listArgs list of command arguments **not including the config name**
     * @param optionArgsRef an empty list, that will be populated with the option arguments left to
     *     be interpreted (should be populated with all non-template option arguments)
     * @param keyStoreClient {@link IKeyStoreClient} keystore client to use if any.
     * @param allowedObjects config object that are allowed to be created.
     * @return An {@link IConfiguration} object representing the configuration that was loaded
     * @throws ConfigurationException
     */
    @VisibleForTesting
    private IConfiguration internalCreateDirectConfiguration(
            String configName,
            List<String> listArgs,
            List<String> optionArgsRef,
            IKeyStoreClient keyStoreClient,
            Set<String> allowedObjects)
            throws ConfigurationException {
        // Download the file and do some error handling here
        try {
            URI configURI = new URI(configName);
            String name =
                    Arrays.stream(configURI.getPath().split("/"))
                            .reduce((first, second) -> second)
                            .orElseThrow();
            CLog.i("Determined the config name was %s", name);

            // GCS resolver doesn't respect this, but just in case others do,
            // we'd prefer them here.
            File destDir = FileUtil.createTempDir("tf-configs");

            ResolvedFile resolvedConfigFile = resolveRemoteFile(configURI, destDir.toURI());
            File configFile = resolvedConfigFile.getResolvedFile();
            if (configFile instanceof ExtendedFile) {
                ((ExtendedFile) configFile).waitForDownload();
            }

            CLog.i("Attempting to read from file: %s", configFile.getPath());
            try (BufferedInputStream configInputStream =
                    new BufferedInputStream(new FileInputStream(configFile))) {
                ConfigurationDef configDef = new ConfigurationDef(configName);

                switch (FileUtil.getExtension(configFile.getPath())) {
                    case ".xml":
                    case ".config":
                    case "":
                        // Note: this disabled config loader both prevents templates from being
                        // instantiated and allows me to use the ConfigurationXMLParser without
                        // substantial modification, for now.
                        ConfigLoader exceptionLoader = new ExceptionLoader(false);
                        ConfigurationXmlParser parser =
                                new ConfigurationXmlParser(exceptionLoader, null);

                        parser.parse(
                                configDef,
                                configName,
                                configInputStream,
                                new HashMap<String, String>(),
                                new HashSet<String>());
                        break;
                    case ".tf_yaml":
                        ConfigurationYamlParser yamlParser = new ConfigurationYamlParser();
                        yamlParser.parse(configDef, configName, configInputStream, false);
                        break;
                    default:
                        throw new ConfigurationException(
                                String.format(
                                        "The config format for %s is not supported.", configName),
                                InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                }

                if (resolvedConfigFile.shouldCleanUp()) {
                    FileUtil.deleteFile(configFile);
                }
                FileUtil.recursiveDelete(destDir);

                final ConfigurationXmlParserSettings parserSettings =
                        new ConfigurationXmlParserSettings();
                final ArgsOptionParser cmdArgParser = new ArgsOptionParser(parserSettings);
                if (keyStoreClient != null) {
                    cmdArgParser.setKeyStore(keyStoreClient);
                }
                optionArgsRef.addAll(cmdArgParser.parseBestEffort(listArgs));

                return configDef.createConfiguration(allowedObjects);
            }
        } catch (FileNotFoundException e) {
            throw new ConfigurationException(e.toString(), e);
        } catch (BuildRetrievalError e) {
            throw new ConfigurationException(e.toString(), e);
        } catch (URISyntaxException e) {
            throw new ConfigurationException(
                    String.format("Invalid URI specified: %s", configName), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp dir for config", e);
        }
    }

    @VisibleForTesting
    protected ResolvedFile resolveRemoteFile(URI configURI, URI destDir)
            throws BuildRetrievalError {
        return RemoteFileResolver.resolveRemoteFile(configURI, destDir);
    }

    protected class ExceptionLoader extends ConfigLoader {
        public ExceptionLoader(boolean isGlobal) {
            super(isGlobal);
        }

        @Override
        public ConfigurationDef getConfigurationDef(String name, Map<String, String> templateMap)
                throws ConfigurationException {
            throw new ConfigurationException(
                    "Templates are not allowed in direct configuration contexts");
        }

        @Override
        public boolean isBundledConfig(String name) {
            throw new RuntimeException(
                    new ConfigurationException(
                            "Templates are not allowed in direct configuration contexts"));
        }

        @Override
        protected String findConfigName(String name, String parentName) {
            throw new RuntimeException(
                    new ConfigurationException(
                            "Templates are not allowed in direct configuration contexts"));
        }

        @Override
        public void loadIncludedConfiguration(
                ConfigurationDef def,
                String parentName,
                String name,
                String deviceTagObject,
                Map<String, String> templateMap,
                Set<String> templateSeen)
                throws ConfigurationException {
            throw new ConfigurationException(
                    "Templates are not allowed in direct configuration contexts",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        @Override
        public void loadConfiguration(
                String name,
                ConfigurationDef def,
                String deviceTagObject,
                Map<String, String> templateMap,
                Set<String> templateSeen)
                throws ConfigurationException {
            throw new ConfigurationException(
                    "Templates are not allowed in direct configuration contexts",
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }

        @Override
        protected void trackConfig(String name, ConfigurationDef def) {
            throw new RuntimeException(
                    new ConfigurationException(
                            "Templates are not allowed in direct configuration contexts"));
        }

        @Override
        protected boolean isTrackableConfig(String name) {
            throw new RuntimeException(
                    new ConfigurationException(
                            "Templates are not allowed in direct configuration contexts"));
        }

        // `private String getAbsolutePath(String root, String name)` is private
        // and so cannot be overridden

        // `public boolean isGlobalConfig()` not overridden because it is probably
        // fine to call in this context
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IGlobalConfiguration createGlobalConfigurationFromArgs(String[] arrayArgs,
            List<String> remainingArgs) throws ConfigurationException {
        List<String> listArgs = new ArrayList<String>(arrayArgs.length);
        IGlobalConfiguration config = internalCreateGlobalConfigurationFromArgs(arrayArgs,
                listArgs);
        remainingArgs.addAll(config.setOptionsFromCommandLineArgs(listArgs));

        return config;
    }

    /**
     * Creates a {@link GlobalConfiguration} from the name given in arguments.
     * <p/>
     * Note will not populate configuration with values from options
     *
     * @param arrayArgs the full list of command line arguments, including the config name
     * @param optionArgsRef an empty list, that will be populated with the
     *            remaining option arguments
     * @return a {@link IGlobalConfiguration} created from the args
     * @throws ConfigurationException
     */
    private IGlobalConfiguration internalCreateGlobalConfigurationFromArgs(String[] arrayArgs,
            List<String> optionArgsRef) throws ConfigurationException {
        if (arrayArgs.length == 0) {
            throw new ConfigurationException("Configuration to run was not specified");
        }
        optionArgsRef.addAll(Arrays.asList(arrayArgs));
        // first arg is config name
        final String configName = optionArgsRef.remove(0);
        ConfigurationDef configDef = getConfigurationDef(configName, true, null);
        IGlobalConfiguration config = configDef.createGlobalConfiguration();
        config.setOriginalConfig(configName);
        config.setConfigurationFactory(this);
        return config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void printHelp(PrintStream out) {
        try {
            loadAllConfigs(true);
        } catch (ConfigurationException e) {
            // ignore, should never happen
        }
        // sort the configs by name before displaying
        SortedSet<ConfigurationDef> configDefs = new TreeSet<ConfigurationDef>(
                new ConfigDefComparator());
        configDefs.addAll(mConfigDefMap.values());
        StringBuilder sb = new StringBuilder();
        for (ConfigurationDef def : configDefs) {
            sb.append(String.format("  %s: %s\n", def.getName(), def.getDescription()));
        }
        out.printf(sb.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getConfigList() {
        return getConfigList(null, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getConfigList(String subPath, boolean loadFromEnv) {
        Set<String> configNames = getConfigSetFromClasspath(subPath);
        if (loadFromEnv) {
            // list config on variable path too
            configNames.addAll(getConfigNamesFromTestCases(subPath));
        }
        // sort the configs by name before adding to list
        SortedSet<String> configDefs = new TreeSet<String>();
        configDefs.addAll(configNames);
        List<String> configs = new ArrayList<String>();
        configs.addAll(configDefs);
        return configs;
    }

    /**
     * Private helper to get the full set of configurations.
     */
    private Set<String> getConfigSetFromClasspath(String subPath) {
        ClassPathScanner cpScanner = new ClassPathScanner();
        return cpScanner.getClassPathEntries(new ConfigClasspathFilter(subPath));
    }

    /**
     * Helper to get the test config files from test cases directories from build output.
     *
     * @param subPath where to look for configuration. Can be null.
     */
    @VisibleForTesting
    Set<String> getConfigNamesFromTestCases(String subPath) {
        return ConfigurationUtil.getConfigNamesFromDirs(subPath, getExternalTestCasesDirs());
    }

    @VisibleForTesting
    Map<String, String> getConfigSetFromClasspathFromJar(String subPath) {
        ClassPathScanner cpScanner = new ClassPathScanner();
        return cpScanner.getClassPathEntriesFromJar(new ConfigClasspathFilter(subPath));
    }

    /**
     * Loads all configurations found in classpath and test cases directories.
     *
     * @param discardExceptions true if any ConfigurationException should be ignored.
     * @throws ConfigurationException
     */
    public void loadAllConfigs(boolean discardExceptions) throws ConfigurationException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        boolean failed = false;
        Set<String> configNames = getConfigSetFromClasspath(null);
        // TODO: split the configs into two lists, one from the jar packages and one from test
        // cases directories.
        configNames.addAll(getConfigNamesFromTestCases(null));
        for (String configName : configNames) {
            final ConfigId configId = new ConfigId(configName);
            try {
                ConfigurationDef configDef = attemptLoad(configId, null);
                mConfigDefMap.put(configId, configDef);
            } catch (ConfigurationException e) {
                ps.printf("Failed to load %s: %s", configName, e.getMessage());
                ps.println();
                failed = true;
            }
        }
        if (failed) {
            if (discardExceptions) {
                CLog.e("Failure loading configs");
                CLog.e(baos.toString());
            } else {
                throw new ConfigurationException(baos.toString());
            }
        }
    }

    /**
     * Helper to load a configuration.
     */
    private ConfigurationDef attemptLoad(ConfigId configId, Map<String, String> templateMap)
            throws ConfigurationException {
        ConfigurationDef configDef = null;
        try {
            configDef = getConfigurationDef(configId.name, false, templateMap);
            return configDef;
        } catch (TemplateResolutionError tre) {
            // When a template does not have a default, we try again with known good template
            // to make sure file formatting at the very least is fine.
            Map<String, String> fakeTemplateMap = new HashMap<String, String>();
            if (templateMap != null) {
                fakeTemplateMap.putAll(templateMap);
            }
            fakeTemplateMap.put(tre.getTemplateKey(), DRY_RUN_TEMPLATE_CONFIG);
            // We go recursively in case there are several template to dry run.
            return attemptLoad(configId, fakeTemplateMap);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void printHelpForConfig(String[] args, boolean importantOnly, PrintStream out) {
        try {
            IConfiguration config =
                    internalCreateConfigurationFromArgs(
                            args, new ArrayList<String>(args.length), null, null);
            config.printCommandUsage(importantOnly, out);
        } catch (ConfigurationException e) {
            // config must not be specified. Print generic help
            printHelp(out);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dumpConfig(String configName, PrintStream out) {
        try {
            InputStream configStream = getConfigStream(configName);
            StreamUtil.copyStreams(configStream, out);
        } catch (ConfigurationException e) {
            CLog.e(e);
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /**
     * Return the path prefix of config xml files on classpath
     *
     * <p>Exposed so unit tests can mock.
     *
     * @return {@link String} path with trailing /
     */
    protected String getConfigPrefix() {
        return CONFIG_PREFIX;
    }

    /**
     * Loads an InputStream for given config name
     *
     * @param name the configuration name to load
     * @return a {@link BufferedInputStream} for reading config contents
     * @throws ConfigurationException if config could not be found
     */
    protected BufferedInputStream getConfigStream(String name) throws ConfigurationException {
        InputStream configStream = getBundledConfigStream(name);
        if (configStream == null) {
            // now try to load from file
            try {
                configStream = new FileInputStream(name);
            } catch (FileNotFoundException e) {
                throw new ConfigurationException(String.format("Could not find configuration '%s'",
                        name));
            }
        }
        // buffer input for performance - just in case config file is large
        return new BufferedInputStream(configStream);
    }

    protected InputStream getBundledConfigStream(String name) {
        String extension = FileUtil.getExtension(name);
        if (Strings.isNullOrEmpty(extension)) {
            // If the default name doesn't have an extension, search all possible extensions.
            for (String supportExtension : SUPPORTED_EXTENSIONS) {
                InputStream res =
                        getClass()
                                .getResourceAsStream(
                                        String.format(
                                                "/%s%s%s",
                                                getConfigPrefix(), name, supportExtension));
                if (res != null) {
                    return res;
                }
            }
            return null;
        }
        // Check directly with extension if it has one.
        return getClass().getResourceAsStream(String.format("/%s%s", getConfigPrefix(), name));
    }

    /**
     * Utility method that checks that all configs can be loaded, parsed, and
     * all option values set.
     * Only exposed so that depending project can validate their configs.
     * Should not be exposed in the console.
     *
     * @throws ConfigurationException if one or more configs failed to load
     */
    public void loadAndPrintAllConfigs() throws ConfigurationException {
        loadAllConfigs(false);
        boolean failed = false;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        for (ConfigurationDef def : mConfigDefMap.values()) {
            try {
                def.createConfiguration().printCommandUsage(false,
                        new PrintStream(StreamUtil.nullOutputStream()));
            } catch (ConfigurationException e) {
                if (e.getCause() != null &&
                        e.getCause() instanceof ClassNotFoundException) {
                    ClassNotFoundException cnfe = (ClassNotFoundException) e.getCause();
                    String className = cnfe.getLocalizedMessage();
                    // Some Cts configs are shipped with Trade Federation, we exclude those from
                    // the failure since these packages are not available for loading.
                    if (className != null && className.startsWith("com.android.cts.")) {
                        CLog.w("Could not confirm %s: %s because not part of Trade Federation "
                                + "packages.", def.getName(), e.getMessage());
                        continue;
                    }
                } else if (Pattern.matches(CONFIG_ERROR_PATTERN, e.getMessage())) {
                    // If options are inside configuration object tag we are able to validate them
                    if (!e.getMessage().contains("com.android.") &&
                            !e.getMessage().contains("com.google.android.")) {
                        // We cannot confirm if an option is indeed missing since a template of
                        // option only is possible to avoid repetition in configuration with the
                        // same base.
                        CLog.w("Could not confirm %s: %s", def.getName(), e.getMessage());
                        continue;
                    }
                }
                ps.printf("Failed to print %s: %s", def.getName(), e.getMessage());
                ps.println();
                failed = true;
            }
        }
        if (failed) {
            throw new ConfigurationException(baos.toString());
        }
    }

    /**
     * Exposed for testing. Return a copy of the Map.
     */
    protected Map<ConfigId, ConfigurationDef> getMapConfig() {
        // We return a copy to ensure it is not modified outside
        return new HashMap<ConfigId, ConfigurationDef>(mConfigDefMap);
    }

    /** In some particular case, we need to clear the map. */
    @VisibleForTesting
    public void clearMapConfig() {
        mConfigDefMap.clear();
    }

    /** Reorder the args so that template:map args are all moved to the front. */
    @VisibleForTesting
    protected String[] reorderArgs(String[] args) {
        List<String> nonTemplateArgs = new ArrayList<String>();
        List<String> reorderedArgs = new ArrayList<String>();
        String[] reorderedArgsArray = new String[args.length];
        String arg;

        // First arg is the config.
        if (args.length > 0) {
            reorderedArgs.add(args[0]);
        }

        // Split out the template and non-template args so we can add
        // non-template args at the end while maintaining their order.
        for (int i = 1; i < args.length; i++) {
            arg = args[i];
            if (arg.equals("--template:map")) {
                // We need to account for these two types of template:map args.
                // --template:map tm=tm1
                // --template:map tm tm1
                reorderedArgs.add(arg);
                for (int j = i + 1; j < args.length; j++) {
                    if (args[j].startsWith("-")) {
                        break;
                    } else {
                        reorderedArgs.add(args[j]);
                        i++;
                    }
                }
            } else {
                nonTemplateArgs.add(arg);
            }
        }
        reorderedArgs.addAll(nonTemplateArgs);
        return reorderedArgs.toArray(reorderedArgsArray);
    }
}
