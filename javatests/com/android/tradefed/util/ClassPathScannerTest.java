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
package com.android.tradefed.util;

import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.android.tradefed.util.ClassPathScanner.ClassNameFilter;
import com.android.tradefed.util.ClassPathScanner.IClassPathFilter;

import java.util.Set;

/** Unit tests for {@link ClassPathScanner} */
@RunWith(JUnit4.class)
public class ClassPathScannerTest {

    /**
     * Simple test to ensure this class can be found via {@link
     * ClassPathScanner#getClassPathEntries(IClassPathFilter)}
     */
    @Test
    public void testGetClassPathEntries() {
        ClassPathScanner cpScanner = new ClassPathScanner();
        Set<String> classEntries = cpScanner.getClassPathEntries(new ClassNameFilter());
        assertTrue(classEntries.contains(this.getClass().getName()));
    }
}
