/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tradefed.util;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.config.OptionSetter.Handler;
import com.android.tradefed.config.OptionSetter.MapHandler;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A utility class that allows classes to load a variables value statically from a res file.
 *
 * <p>The resource file should be in a key=value format, where the key is associated with the
 * variable that needs to be retrieved. A single resource file can contain multiple lines, where
 * each line is associated with one variable.
 *
 * <p>To specify any primitive types, a single key=value pair should be used in a line. e.g.:
 *
 * <ol>
 *   <li>my-integer-key=5
 *   <li>my-string-key=myStringValue
 * </ol>
 *
 * <p>To specify any collections, multiple values can be used, separated by a comma(,). e.g.:
 *
 * <ol>
 *   <li>my-string-list-key=stringOne,stringTwo,stringThree
 *   <li>my-int-list-key=1,2,3,4,5
 * </ol>
 *
 * <p>To specify a map, multiple mapKey\=mapValue pair can be used, separated by a comma(,). e.g.:
 *
 * <ol>
 *   <li>my-map-key=mapKey1\=mapVal1,mapKey2\=mapVal2
 * </ol>
 */
public class TfInternalOptionsFetcher {
    private static String resourcePath = "/util/TfInternalOptions.properties";

    /**
     * Fetches the values for all declared fields of the given {@link Class} from the specified
     * resource file. If a resource file is not set, a default resource file will be used.
     *
     * @param classObj the class {@link Object} whose fields should be populated.
     */
    public static void fetchOption(Class<?> classObj) {
        try (InputStream stream =
                TfInternalOptionsFetcher.class.getResourceAsStream(resourcePath)) {
            // load the properties from the resource file
            Properties properties = new Properties();
            properties.load(stream);
            for (Field field : classObj.getDeclaredFields()) {
                String propertyKey = field.getName();
                String optionVal = (String) properties.get(propertyKey);
                // if property exists, update the field
                if (optionVal != null) {
                    Handler handler = OptionSetter.getHandler(field.getGenericType());
                    if (handler == null) {
                        throw new ConfigurationException(
                                String.format(
                                        "Unable to get handler for option '%s'.", propertyKey),
                                InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                    }
                    boolean isOptionField = false;
                    if (field.getAnnotation(Option.class) != null) {
                        isOptionField = true;
                    }
                    if (Collection.class.isAssignableFrom(field.getType())) {
                        // if field is a collection, allow multiple values to be specified.
                        String[] optionValues = optionVal.split(",");
                        for (String optionValue : optionValues) {
                            // translate the value
                            Object translatedValue = handler.translate(optionValue);
                            OptionSetter.setFieldValue(
                                    propertyKey,
                                    classObj,
                                    field,
                                    null,
                                    translatedValue,
                                    isOptionField);
                        }
                    } else if (Map.class.isAssignableFrom(field.getType())) {
                        // if field is a map, allow multiple key=value pairs for the option value.
                        String[] optionValues = optionVal.split(",");
                        for (String optionValue : optionValues) {
                            String mapKey;
                            String mapVal;
                            // only match = to escape use "\="
                            Pattern p = Pattern.compile("(?<!\\\\)=");
                            String[] parts =
                                    p.split(optionValue, /* allow empty-string values */ -1);
                            // Note that we replace escaped = (\=) to =.
                            if (parts.length == 2) {
                                mapKey = parts[0].replaceAll("\\\\=", "=");
                                mapVal = parts[1].replaceAll("\\\\=", "=");
                            } else {
                                throw new ConfigurationException(
                                        String.format(
                                                "option '%s' has an invalid format for value %s:w",
                                                propertyKey, optionValue),
                                        InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
                            }
                            // translate the key and value
                            Object translatedKey = ((MapHandler) handler).translateKey(mapKey);
                            Object translatedValue = handler.translate(mapVal);
                            OptionSetter.setFieldValue(
                                    propertyKey,
                                    classObj,
                                    field,
                                    translatedKey,
                                    translatedValue,
                                    isOptionField);
                        }
                    } else {
                        OptionSetter.setFieldValue(
                                propertyKey,
                                classObj,
                                field,
                                null,
                                handler.translate(optionVal),
                                isOptionField);
                    }
                }
            }
        } catch (IOException | ConfigurationException e) {
            CLog.w("Unable to fetch option values for class '%s'.", classObj.getName());
            CLog.e(e);
        }
    }

    /** Set the path of the resource file where the value will be retrieved from. */
    public static void setResourcePath(String path) {
        resourcePath = path;
    }
}
