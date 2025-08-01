/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.MultiMap;

import org.kxml2.io.KXmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** Utility functions to handle configuration files. */
public class ConfigurationUtil {

    // Element names used for emitting the configuration XML.
    public static final String CONFIGURATION_NAME = "configuration";
    public static final String OPTION_NAME = "option";
    public static final String CLASS_NAME = "class";
    public static final String NAME_NAME = "name";
    public static final String KEY_NAME = "key";
    public static final String VALUE_NAME = "value";

    /**
     * Create a serializer to be used to create a new configuration file.
     *
     * @param outputXml the XML file to write to
     * @return a {@link KXmlSerializer}
     */
    static KXmlSerializer createSerializer(File outputXml) throws IOException {
        PrintWriter output = new PrintWriter(outputXml);
        KXmlSerializer serializer = new KXmlSerializer();
        serializer.setOutput(output);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startDocument("UTF-8", null);
        return serializer;
    }

    /**
     * Add a class to the configuration XML dump.
     *
     * @param serializer a {@link KXmlSerializer} to create the XML dump
     * @param classTypeName a {@link String} of the class type's name
     * @param obj {@link Object} to be added to the XML dump
     * @param excludeClassFilter list of object configuration type or fully qualified class names to
     *     be excluded from the dump. for example: {@link Configuration#TARGET_PREPARER_TYPE_NAME}.
     *     com.android.tradefed.testtype.StubTest
     * @param printDeprecatedOptions whether or not to print deprecated options
     * @param printUnchangedOptions whether or not to print options that haven't been changed
     */
    static void dumpClassToXml(
            KXmlSerializer serializer,
            String classTypeName,
            Object obj,
            List<String> excludeClassFilter,
            boolean printDeprecatedOptions,
            boolean printUnchangedOptions)
            throws IOException {
        dumpClassToXml(
                serializer,
                classTypeName,
                obj,
                false,
                excludeClassFilter,
                new NoOpConfigOptionValueTransformer(),
                printDeprecatedOptions,
                printUnchangedOptions);
    }

    /**
     * Add a class to the configuration XML dump.
     *
     * @param serializer a {@link KXmlSerializer} to create the XML dump
     * @param classTypeName a {@link String} of the class type's name
     * @param obj {@link Object} to be added to the XML dump
     * @param isGenericObject Whether or not the object is specified as <object> in the xml
     * @param excludeClassFilter list of object configuration type or fully qualified class names to
     *     be excluded from the dump. for example: {@link Configuration#TARGET_PREPARER_TYPE_NAME}.
     *     com.android.tradefed.testtype.StubTest
     * @param printDeprecatedOptions whether or not to print deprecated options
     * @param printUnchangedOptions whether or not to print options that haven't been changed
     */
    static void dumpClassToXml(
            KXmlSerializer serializer,
            String classTypeName,
            Object obj,
            boolean isGenericObject,
            List<String> excludeClassFilter,
            IConfigOptionValueTransformer transformer,
            boolean printDeprecatedOptions,
            boolean printUnchangedOptions)
            throws IOException {
        if (excludeClassFilter.contains(classTypeName)) {
            return;
        }
        if (excludeClassFilter.contains(obj.getClass().getName())) {
            return;
        }
        if (isGenericObject) {
            serializer.startTag(null, "object");
            serializer.attribute(null, "type", classTypeName);
            serializer.attribute(null, CLASS_NAME, obj.getClass().getName());
            dumpOptionsToXml(
                    serializer, obj, transformer, printDeprecatedOptions, printUnchangedOptions);
            serializer.endTag(null, "object");
        } else {
            serializer.startTag(null, classTypeName);
            serializer.attribute(null, CLASS_NAME, obj.getClass().getName());
            dumpOptionsToXml(
                    serializer, obj, transformer, printDeprecatedOptions, printUnchangedOptions);
            serializer.endTag(null, classTypeName);
        }
        serializer.flush();
    }

    /**
     * Add all the options of class to the command XML dump.
     *
     * @param serializer a {@link KXmlSerializer} to create the XML dump
     * @param obj {@link Object} to be added to the XML dump
     * @param printDeprecatedOptions whether or not to skip the deprecated options
     * @param printUnchangedOptions whether or not to print options that haven't been changed
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dumpOptionsToXml(
            KXmlSerializer serializer,
            Object obj,
            IConfigOptionValueTransformer transformer,
            boolean printDeprecatedOptions,
            boolean printUnchangedOptions)
            throws IOException {
        Object comparisonBaseObj = null;
        if (!printUnchangedOptions) {
            try {
                comparisonBaseObj = obj.getClass().getDeclaredConstructor().newInstance();
            } catch (InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        List<Field> fields = OptionSetter.getOptionFieldsForClass(obj.getClass());
        // Sort fields to always print in same order
        Collections.sort(
                fields,
                new Comparator<Field>() {
                    @Override
                    public int compare(Field arg0, Field arg1) {
                        return arg0.getName().compareTo(arg1.getName());
                    }
                });
        for (Field field : fields) {
            Option option = field.getAnnotation(Option.class);
            Deprecated deprecatedAnnotation = field.getAnnotation(Deprecated.class);
            // If enabled, skip @Deprecated options
            if (!printDeprecatedOptions && deprecatedAnnotation != null) {
                continue;
            }
            Object fieldVal = OptionSetter.getFieldValue(field, obj);
            if (fieldVal == null) {
                continue;
            }
            if (comparisonBaseObj != null) {
                Object compField = OptionSetter.getFieldValue(field, comparisonBaseObj);
                if (fieldVal.equals(compField)) {
                    continue;
                }
            }

            if (fieldVal instanceof Collection) {
                for (Object entry : (Collection) fieldVal) {
                    entry = transformer.transform(obj, option, entry);
                    dumpOptionToXml(serializer, option.name(), null, entry.toString());
                }
            } else if (fieldVal instanceof Map) {
                Map map = (Map) fieldVal;
                for (Object entryObj : map.entrySet()) {
                    Map.Entry entry = (Entry) entryObj;
                    Object value = entry.getValue();
                    value = transformer.transform(obj, option, value);
                    dumpOptionToXml(
                            serializer, option.name(), entry.getKey().toString(), value.toString());
                }
            } else if (fieldVal instanceof MultiMap) {
                MultiMap multimap = (MultiMap) fieldVal;
                for (Object keyObj : multimap.keySet()) {
                    for (Object valueObj : multimap.get(keyObj)) {
                        valueObj = transformer.transform(obj, option, valueObj);
                        dumpOptionToXml(
                                serializer, option.name(), keyObj.toString(), valueObj.toString());
                    }
                }
            } else {
                fieldVal = transformer.transform(obj, option, fieldVal);
                dumpOptionToXml(serializer, option.name(), null, fieldVal.toString());
            }
            serializer.flush();
        }
    }

    /**
     * Add a single option to the command XML dump.
     *
     * @param serializer a {@link KXmlSerializer} to create the XML dump
     * @param name a {@link String} of the option's name
     * @param key a {@link String} of the option's key, used as name if param name is null
     * @param value a {@link String} of the option's value
     */
    private static void dumpOptionToXml(
            KXmlSerializer serializer, String name, String key, String value) throws IOException {
        serializer.startTag(null, OPTION_NAME);
        serializer.attribute(null, NAME_NAME, name);
        if (key != null) {
            serializer.attribute(null, KEY_NAME, key);
        }
        serializer.attribute(null, VALUE_NAME, value);
        serializer.endTag(null, OPTION_NAME);
    }

    /**
     * Helper to get the test config files from given directories.
     *
     * @param subPath where to look for configuration. Can be null.
     * @param dirs a list of {@link File} of extra directories to search for test configs
     */
    public static Set<String> getConfigNamesFromDirs(String subPath, List<File> dirs) {
        Set<File> res = getConfigNamesFileFromDirs(subPath, dirs);
        if (res.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> files = new HashSet<>();
        res.forEach(file -> files.add(file.getAbsolutePath()));
        return files;
    }

    /**
     * Helper to get the test config files from given directories.
     *
     * @param subPath The location where to look for configuration. Can be null.
     * @param dirs A list of {@link File} of extra directories to search for test configs
     * @return the set of {@link File} that were found.
     */
    public static Set<File> getConfigNamesFileFromDirs(String subPath, List<File> dirs) {
        List<String> patterns = new ArrayList<>();
        patterns.add(".*\\.config$");
        patterns.add(".*\\.xml$");
        return getConfigNamesFileFromDirs(subPath, dirs, patterns);
    }

    /**
     * Search a particular pattern of in the given directories.
     *
     * @param subPath The location where to look for configuration. Can be null.
     * @param dirs A list of {@link File} of extra directories to search for test configs
     * @param configNamePatterns the list of patterns for files to be found.
     * @return the set of {@link File} that were found.
     */
    public static Set<File> getConfigNamesFileFromDirs(
            String subPath, List<File> dirs, List<String> configNamePatterns) {
        return getConfigNamesFileFromDirs(subPath, dirs, configNamePatterns, false);
    }

    /**
     * Search a particular pattern of in the given directories.
     *
     * @param subPath The location where to look for configuration. Can be null.
     * @param dirs A list of {@link File} of extra directories to search for test configs
     * @param configNamePatterns the list of patterns for files to be found.
     * @param includeDuplicateFileNames whether to include config files with same name but different
     *     content.
     * @return the set of {@link File} that were found.
     */
    public static Set<File> getConfigNamesFileFromDirs(
            String subPath,
            List<File> dirs,
            List<String> configNamePatterns,
            boolean includeDuplicateFileNames) {
        Set<File> configNames = new LinkedHashSet<>();
        for (File dir : dirs) {
            if (subPath != null) {
                dir = new File(dir, subPath);
            }
            if (!dir.isDirectory()) {
                CLog.d("%s doesn't exist or is not a directory.", dir.getAbsolutePath());
                continue;
            }
            try {
                for (String configNamePattern : configNamePatterns) {
                    configNames.addAll(FileUtil.findFilesObject(dir, configNamePattern));
                }
            } catch (IOException e) {
                CLog.w("Failed to get test config files from directory %s", dir.getAbsolutePath());
            }
        }
        return dedupFiles(configNames, includeDuplicateFileNames);
    }

    /**
     * From a same tests dir we only expect a single instance of each names, so we dedup the files
     * if that happens.
     */
    private static Set<File> dedupFiles(Set<File> origSet, boolean includeDuplicateFileNames) {
        Map<String, List<File>> newMap = new LinkedHashMap<>();
        for (File f : origSet) {
            try {
                if (!FileUtil.readStringFromFile(f).contains("<configuration")) {
                    CLog.e("%s doesn't look like a test configuration.", f);
                    continue;
                }
            } catch (IOException e) {
                CLog.e(e);
                continue;
            }
            // Always keep the first found
            if (!newMap.keySet().contains(f.getName())) {
                List<File> newList = new LinkedList<>();
                newList.add(f);
                newMap.put(f.getName(), newList);
            } else if (includeDuplicateFileNames) {
                // Two files with same name may have different contents. Make sure they are
                // identical. if not, add them to the list.
                boolean isSameContent = false;
                for (File uniqueFiles : newMap.get(f.getName())) {
                    try {
                        isSameContent = FileUtil.compareFileContents(uniqueFiles, f);
                        if (isSameContent) {
                            break;
                        }
                    } catch (IOException e) {
                        CLog.e(e);
                    }
                }
                if (!isSameContent) {
                    newMap.get(f.getName()).add(f);
                    CLog.d(
                            "Config %s already exists, but content is different. Not skipping.",
                            f.getName());
                }
            }
        }
        Set<File> uniqueFiles = new LinkedHashSet<>();
        for (List<File> files : newMap.values()) {
            uniqueFiles.addAll(files);
        }
        return uniqueFiles;
    }
}
