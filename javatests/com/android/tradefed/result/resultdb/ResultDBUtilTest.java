/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tradefed.result.resultdb;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ResultDBUtilTest {

    @Test
    public void convertBytesToHex() {
        assertThat(
                        ResultDBUtil.bytesToHex(
                                new byte[] {
                                    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
                                    0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
                                    0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b,
                                    0x1c, 0x1d, 0x1e, 0x1f
                                }))
                .isEqualTo("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    }

    @Test
    public void truncateString() {
        // Test cases for BMP characters.
        assertThat(ResultDBUtil.truncateString("abc", 3)).isEqualTo("abc");
        assertThat(ResultDBUtil.truncateString("abc", 2)).isEqualTo("ab");
        assertThat(ResultDBUtil.truncateString("abc", 1)).isEqualTo("a");
        // Test cases for surrogate pairs. 🌍 is a 4-byte in UTF-8.
        assertThat(ResultDBUtil.truncateString("Hello 🌍!", 8)).isEqualTo("Hello ");
        assertThat(ResultDBUtil.truncateString("Hello 🌍!", 10)).isEqualTo("Hello 🌍");
        assertThat(ResultDBUtil.truncateString("Hello 🌍!", 11)).isEqualTo("Hello 🌍!");
    }
}
