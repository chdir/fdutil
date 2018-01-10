/*
 * Copyright © 2016 Alexander Rvachev
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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static android.icu.lang.UCharacter.REPLACEMENT_CHAR;

public final class FileNameDecoder {
    // 255 16-bit units is VFAT name length (biggest known so far), but let's make an extra
    private static final int NAME_CHAR_MAX = 255 * 2;

    private final CharBuilder buffer;

    public FileNameDecoder() {
        this(NAME_CHAR_MAX, '\\');
    }

    public FileNameDecoder(int capacity, char escape) {
        this.buffer = new CharBuilder(capacity, escape);
    }

    @NonNull
    public CharSequence decode(byte[] bytes, int offset, int count) {
        switch (count) {
            default:
                break;
            case 2:
                if (bytes[offset] == '.' && bytes[offset + 1] == '.') {
                    return "..";
                }
                break;
            case 1:
                if (bytes[offset] == '.') {
                    return ".";
                }
        }

        boolean cleanDecode = convert(bytes, offset, count);

        String decodingResult = buffer.toString();

        if (cleanDecode) {
            return decodingResult;
        }

        return new NativeString(decodingResult, Arrays.copyOfRange(bytes, offset, offset + count));
    }

    private static final int[] overlong = new int[] {
            0,
            0x80,
            0x800,
            0x10000,
            0x200000,
            0x4000000,
    };

    public String toString(byte[] d, int offset, int byteCount) {
        convert(d, offset, byteCount);

        return buffer.toString();
    }

    String t = "\ud800";

    private boolean convert(byte[] d, int offset, int byteCount) {

        boolean hasFaults = false;
        CharBuilder buffer = this.buffer;
        int idx = offset;
        int last = offset + byteCount;
        int lookahead;

        buffer.elementsCount = 0;

        do {
loop:
            do {
multi_byte:
                do {
                    while (idx < last) {
                        byte b0 = d[idx++];
                        if ((b0 & 0x80) == 0) {
                            // 0xxxxxxx
                            // Range:  U-00000000 - U-0000007F
                            int val = b0 & 0xff;
                            buffer.add((char) val);
                        } else if (((b0 & 0xe0) == 0xc0) || ((b0 & 0xf0) == 0xe0) ||
                                ((b0 & 0xf8) == 0xf0) || ((b0 & 0xfc) == 0xf8) || ((b0 & 0xfe) == 0xfc)) {
                            int utfCount = 1;
                            if ((b0 & 0xf0) == 0xe0) utfCount = 2;
                            else if ((b0 & 0xf8) == 0xf0) utfCount = 3;
                            else if ((b0 & 0xfc) == 0xf8) utfCount = 4;
                            else if ((b0 & 0xfe) == 0xfc) utfCount = 5;
                            // 110xxxxx (10xxxxxx)+
                            // Range:  U-00000080 - U-000007FF (count == 1)
                            // Range:  U-00000800 - U-0000FFFF (count == 2)
                            // Range:  U-00010000 - U-001FFFFF (count == 3)
                            // Range:  U-00200000 - U-03FFFFFF (count == 4)
                            // Range:  U-04000000 - U-7FFFFFFF (count == 5)
                            if (idx + utfCount > last) {
                                break loop;
                            }
                            // Extract usable bits from b0
                            int val = b0 & (0x1f >> (utfCount - 1));
                            for (lookahead = 0; lookahead < utfCount;) {
                                ++lookahead;
                                byte b = d[idx++];
                                if ((b & 0xc0) != 0x80) {
                                    break multi_byte;
                                }
                                // Push new bits in from the right side
                                val <<= 6;
                                val |= b & 0x3f;
                            }

                            // disallows overlong char by checking that val
                            // is greater than or equal to the minimum
                            // value for each count:
                            //
                            // count    min value
                            // -----   ----------
                            //   1           0x80
                            //   2          0x800
                            //   3        0x10000
                            //   4       0x200000
                            //   5      0x4000000
                            if (val < overlong[utfCount]) {
                                break multi_byte;
                            }

                            // Reject chars greater than the Unicode maximum of U+10FFFF.
                            if (val > 0x10FFFF) {
                                break multi_byte;
                            }

                            // reject raw surrogates in byte stream
                            // (we are doing vanilla UTF-8, not WTF-8)
                            if (val >= Character.MIN_SURROGATE && val < (Character.MAX_SURROGATE + 1)) {
                                break multi_byte;
                            }

                            // Encode chars from U+10000 up as surrogate pairs
                            if (val < 0x10000) {
                                buffer.add((char) val);
                            } else {
                                int x = val & 0xffff;
                                int u = (val >> 16) & 0x1f;
                                int w = (u - 1) & 0xffff;
                                int hi = 0xd800 | (w << 6) | (x >> 10);
                                int lo = 0xdc00 | (x & 0x3ff);
                                buffer.add((char) hi, (char) lo);
                            }
                        } else {
                            // Illegal values 0x8*, 0x9*, 0xa*, 0xb*, 0xfd-0xff
                            break loop;
                        }
                    }

                    return !hasFaults;
                }
                while (false);

                // Put input chars back
                idx -= lookahead;
            }
            while (false);

            // escape the offending byte
            buffer.append(d[idx - 1]);

            hasFaults = true;
        }
        while (true);
    }
}
