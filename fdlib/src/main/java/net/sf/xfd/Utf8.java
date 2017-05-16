/*
 * Copyright (C) 2017 Square, Inc.
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
package net.sf.xfd;

public final class Utf8 {
    private Utf8() {
    }

    public static int charLength(byte[] bytes, int offset, int length) {
        int charCount = 0, expectedLen;

        for (int i = offset; i < length; i++) {
            charCount++;
            // Lead byte analysis
            if      ((bytes[i] & 0b10000000) == 0b00000000) continue;
            else if ((bytes[i] & 0b11100000) == 0b11000000) expectedLen = 2;
            else if ((bytes[i] & 0b11110000) == 0b11100000) expectedLen = 3;
            else if ((bytes[i] & 0b11111000) == 0b11110000) expectedLen = 4;
            else if ((bytes[i] & 0b11111100) == 0b11111000) expectedLen = 5;
            else if ((bytes[i] & 0b11111110) == 0b11111100) expectedLen = 6;
            else    return -1;

            // Count trailing bytes
            while (--expectedLen > 0) {
                if (++i >= length) {
                    return -1;
                }
                if ((bytes[i] & 0b11000000) != 0b10000000) {
                    return -1;
                }
            }
        }
        return charCount;
    }

    public static long size(String string) {
        return size(string, 0, string.length());
    }

    /**
     * Returns the number of bytes used to encode the slice of {@code string} as UTF-8
     */
    public static long size(String string, int beginIndex, int endIndex) {
        if (string == null) throw new IllegalArgumentException("string == null");
        if (beginIndex < 0) throw new IllegalArgumentException("beginIndex < 0: " + beginIndex);
        if (endIndex < beginIndex) {
            throw new IllegalArgumentException("endIndex < beginIndex: " + endIndex + " < " + beginIndex);
        }
        if (endIndex > string.length()) {
            throw new IllegalArgumentException(
                    "endIndex > string.length: " + endIndex + " > " + string.length());
        }

        long result = 0;
        for (int i = beginIndex; i < endIndex;) {
            int c = string.charAt(i);

            if (c < 0x80) {
                // A 7-bit character with 1 byte.
                result++;
                i++;

            } else if (c < 0x800) {
                // An 11-bit character with 2 bytes.
                result += 2;
                i++;

            } else if (c < 0xd800 || c > 0xdfff) {
                // A 16-bit character with 3 bytes.
                result += 3;
                i++;

            } else {
                int low = i + 1 < endIndex ? string.charAt(i + 1) : 0;
                if (c > 0xdbff || low < 0xdc00 || low > 0xdfff) {
                    // A malformed surrogate, which yields '?'.
                    result++;
                    i++;

                } else {
                    // A 21-bit character with 4 bytes.
                    result += 4;
                    i += 2;
                }
            }
        }

        return result;
    }
}
