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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KernelModuleUtilsTest {

    /** Test {@link KernelModuleUtils#getDependentModules(String, String)} */
    @Test
    public void testGetDependentModules() {
        String output =
                "Module Size  Used b\n"
                        + "kunit_test             663552  0\n"
                        + "time_test             663558  0\n"
                        + "kunit                  57344  15 kunit_test,time_test\n";
        String[] expected = {"kunit_test", "time_test"};
        String[] actual = KernelModuleUtils.getDependentModules("kunit", output);
        assertArrayEquals(expected, actual);
        assertArrayEquals(
                new String[0], KernelModuleUtils.getDependentModules("kunit", "kunit 123 12"));
    }

    /** Test {@link KernelModuleUtils#getDisplayedModuleName(String)} */
    @Test
    public void testGetDisplayedModuleName() {
        assertEquals("kunit_test", KernelModuleUtils.getDisplayedModuleName("/data/kunit-test.ko"));
        assertEquals("kunit_test", KernelModuleUtils.getDisplayedModuleName("kunit-test.ko"));
    }
}
