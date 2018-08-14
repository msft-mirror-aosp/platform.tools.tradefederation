/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link VersionParser}. */
@RunWith(JUnit4.class)
public class VersionParserTest {

    /**
     * Check that the version seen in Tradefed is valid. This unit tests is expected to fail when
     * running inside an IDE directly (like Eclipse).
     */
    @Test
    public void testVersionIsAvailable() {
        String version = VersionParser.fetchVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        assertNotEquals(VersionParser.DEFAULT_IMPLEMENTATION_VERSION, version);
        // When the implementation-version was not properly replaced in the jar manifest this is
        // what was left.
        assertNotEquals("%BUILD_NUMBER%", version);
    }
}
