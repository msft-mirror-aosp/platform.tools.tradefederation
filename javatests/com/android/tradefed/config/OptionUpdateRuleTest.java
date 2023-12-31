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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link OptionUpdateRule} */
@RunWith(JUnit4.class)
public class OptionUpdateRuleTest {
    private static final String OPTION_NAME = "option-name";
    private static final Object CURRENT = "5 current value";
    private static final Object UPDATE = "5 update value";
    private static final Object SMALL_UPDATE = "0 update value";
    private static final Object BIG_UPDATE = "9 update value";

    @Test
    public void testFirst_simple() throws Exception {
        assertTrue(OptionUpdateRule.FIRST.shouldUpdate(OPTION_NAME, null, UPDATE));
        assertFalse(OptionUpdateRule.FIRST.shouldUpdate(OPTION_NAME, CURRENT, UPDATE));
    }

    @Test
    public void testLast_simple() throws Exception {
        assertTrue(OptionUpdateRule.LAST.shouldUpdate(OPTION_NAME, null, UPDATE));
        assertTrue(OptionUpdateRule.LAST.shouldUpdate(OPTION_NAME, CURRENT, UPDATE));
    }

    @Test
    public void testGreatest_simple() throws Exception {
        assertTrue(OptionUpdateRule.GREATEST.shouldUpdate(OPTION_NAME, null, SMALL_UPDATE));
        assertFalse(OptionUpdateRule.GREATEST.shouldUpdate(OPTION_NAME, CURRENT, SMALL_UPDATE));
        assertTrue(OptionUpdateRule.GREATEST.shouldUpdate(OPTION_NAME, CURRENT, BIG_UPDATE));
    }

    @Test
    public void testLeast_simple() throws Exception {
        assertTrue(OptionUpdateRule.LEAST.shouldUpdate(OPTION_NAME, null, BIG_UPDATE));
        assertTrue(OptionUpdateRule.LEAST.shouldUpdate(OPTION_NAME, CURRENT, SMALL_UPDATE));
        assertFalse(OptionUpdateRule.LEAST.shouldUpdate(OPTION_NAME, CURRENT, BIG_UPDATE));
    }

    @Test
    public void testImmutable_simple() throws Exception {
        assertTrue(OptionUpdateRule.IMMUTABLE.shouldUpdate(OPTION_NAME, null, UPDATE));
        try {
            OptionUpdateRule.IMMUTABLE.shouldUpdate(OPTION_NAME, CURRENT, UPDATE);
            fail("ConfigurationException not thrown when updating an IMMUTABLE option");
        } catch (ConfigurationException e) {
            // expected
        }
    }

    @Test
    public void testInvalidComparison() throws Exception {
        try {
            // Strings aren't comparable with integers
            OptionUpdateRule.GREATEST.shouldUpdate(OPTION_NAME, 13, UPDATE);
            fail("ConfigurationException not thrown for invalid comparison.");
        } catch (ConfigurationException e) {
            // Expected.  Moreover, the exception should be actionable, so make sure we mention the
            // specific mismatching types.
            final String msg = e.getMessage();
            assertTrue(msg.contains("Integer"));
            assertTrue(msg.contains("String"));
        }
    }

    @Test
    public void testNotComparable() throws Exception {
        try {
            OptionUpdateRule.LEAST.shouldUpdate(OPTION_NAME, new Exception("hi"), UPDATE);
        } catch (ConfigurationException e) {
            // expected
        }
    }
}

