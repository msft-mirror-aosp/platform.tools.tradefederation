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

import java.nio.charset.StandardCharsets;

/** Utility class for ResultDB reporter. */
public final class ResultDBUtil {

    /** Converts a byte array to a hexadecimal string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Truncates the string to the given max bytes, avoiding breaking up a multi-byte character.
     *
     * @param input the string to truncate
     * @param maxBytes the maximum number of bytes (in utf-8 encoding) to truncate to
     * @return the truncated string
     */
    public static String truncateString(String input, int maxBytes) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return input;
        }

        int byteCount = 0;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            byte[] codePointBytes =
                    new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);

            if (byteCount + codePointBytes.length <= maxBytes) {
                result.append(Character.toChars(codePoint));
                byteCount += codePointBytes.length;
                i += Character.charCount(codePoint); // Move to the next code point
            } else {
                break;
            }
        }
        return result.toString();
    }
}
