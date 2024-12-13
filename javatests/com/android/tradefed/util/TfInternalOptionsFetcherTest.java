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

import static org.junit.Assert.assertEquals;

import com.android.tradefed.config.Option;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class TfInternalOptionsFetcherTest {
    static class TestObject {
        public static int intVariable;
        public static boolean boolVariable;
        public static String stringVariable;
        public static List<String> listVariable = new ArrayList<>();
        public static Map<String, String> mapStringVariable = new HashMap<>();
        public static Map<Integer, String> mapIntStringVariable = new HashMap<>();

        @Option(name = "option-string")
        public static String optionStringField;

        static {
            TfInternalOptionsFetcher.setResourcePath("/util/TfInternalOptions_test.properties");
            TfInternalOptionsFetcher.fetchOption(TestObject.class);
        }
    }

    @Test
    public void testFetchOptions() {
        assertEquals(5, TestObject.intVariable);
        assertEquals("test", TestObject.stringVariable);
        assertEquals(true, TestObject.boolVariable);
        assertEquals(3, TestObject.listVariable.size());
        assertEquals("string_two", TestObject.listVariable.get(1));
        assertEquals(2, TestObject.mapStringVariable.size());
        assertEquals("mapVal1", TestObject.mapStringVariable.get("mapKey1"));
        assertEquals("mapVal2", TestObject.mapStringVariable.get("mapKey2"));
        assertEquals(2, TestObject.mapIntStringVariable.size());
        assertEquals("mapIntVal1", TestObject.mapIntStringVariable.get(1));
        assertEquals("mapIntVal2", TestObject.mapIntStringVariable.get(2));
        assertEquals("optionString", TestObject.optionStringField);
    }
}
