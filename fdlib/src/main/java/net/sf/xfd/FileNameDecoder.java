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

import com.carrotsearch.hppc.CharArrayList;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

final class FileNameDecoder {
    // 255 16-bit units is VFAT name length (biggest known so far), but let's make an extra
    private static final int NAME_CHAR_MAX = 255 * 2;

    private final char[] smallTempArray = new char[NAME_CHAR_MAX];

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

    private final CharBuffer charBuffer = CharBuffer.wrap(smallTempArray);

    private final CharBuilder builder = new CharBuilder();

    private CharSequence escapeEverything(ByteBuffer b) {
        builder.elementsCount = 0;

        b.rewind();

        do builder.append(b.get()); while (b.hasRemaining());

        final byte[] bytes = new byte[b.limit()];

        b.rewind();

        b.get(bytes);

        return new NativeString(bytes, builder.toString());
    }

    private void salvageBytes(ByteBuffer b, CoderResult r) {
        final int e;

        if (hasAdvanceBug) {
            int pos = b.position();

            b.reset();

            e = Math.min((pos - b.position()) + r.length(), b.remaining());
        } else {
            e = Math.min(r.length(), b.remaining());

            builder.append(charBuffer);
        }

        for (int i = 0; i < e; b.hasRemaining(), ++i) {
            builder.append(b.get());
        }
    }

    private CharSequence salvageValidBytes(ByteBuffer buffer, CoderResult r) {
        if (r.length() <= 0) {
            return escapeEverything(buffer);
        }

        builder.elementsCount = 0;

        salvageBytes(buffer, r);

        while (buffer.hasRemaining()) {
            charBuffer.mark();

            buffer.mark();

            r = decoder.decode(buffer, charBuffer, true);

            if (r.isOverflow()) {
                // should not normally happen
                return escapeEverything(buffer);
            }

            if (r.isError()) {
                if (r.length() <= 0) {
                    return escapeEverything(buffer);
                }

                salvageBytes(buffer, r);
            } else {
                builder.append(charBuffer);
            }
        }

        charBuffer.mark();

        if (decoder.flush(charBuffer) != CoderResult.UNDERFLOW) {
            // Sadly, we can not salvage anything at this point. Thanks you, buggy decoder!
            return escapeEverything(buffer);
        }

        builder.append(charBuffer);

        final byte[] bytes = new byte[buffer.limit()];

        buffer.rewind();

        buffer.get(bytes);

        return new NativeString(bytes, builder.toString());
    }

    @SuppressWarnings("ConstantConditions")
    CharSequence fromUtf8Bytes(ByteBuffer buffer) {
        charBuffer.clear();

        decoder.reset();

        charBuffer.mark();

        CoderResult r;
        do {
            buffer.mark();

            r = decoder.decode(buffer, charBuffer, true);

            if (r.isOverflow()) {
                // should not normally happen
                return new NativeString(buffer);
            }

            if (r.isError()) {
                return salvageValidBytes(buffer, r);
            }
        } while (buffer.hasRemaining());

        r = decoder.flush(charBuffer);

        if (r != CoderResult.UNDERFLOW) {
            // Sadly, we can not salvage anything at this point. Thanks you, buggy decoder!
            return escapeEverything(buffer);
        }

        charBuffer.flip();

        switch (charBuffer.length()) {
            default:
                break;
            case 1:
                if (smallTempArray[0] == '.') return ".";
                break;
            case 2:
                if (smallTempArray[0] == '.' && smallTempArray[1] == '.') return "..";
                break;
        }

        return charBuffer.toString();
    }

    private static final class CharBuilder extends CharArrayList {
        private static final char[] digits = {
                '0' , '1' , '2' , '3' , '4' , '5' ,
                '6' , '7' , '8' , '9' , 'A' , 'B' ,
                'C' , 'D' , 'E' , 'F'
        };

        // append hex-escaped byte to builder (see Integer#toHexString)
        void append(byte i) {
            ensureBufferSpace(4);

            add('\\');

            add('x');

            add(digits[i & 15]);

            i >>>= 4;

            insert(elementsCount - 1, digits[i & 15]);
        }

        // append contents of CharBuffer to builder
        void append(CharBuffer chars) {
            int pos = chars.position();

            chars.reset();

            final int l = pos - chars.position();

            ensureBufferSpace(l);

            chars.get(buffer, elementsCount, l);

            elementsCount += l;
        }

        @Override
        public String toString() {
            return new String(toArray());
        }
    }

    private static final byte[] buggedChar = new byte[] { (byte) 0x80 };

    private static boolean hasAdvanceBug() {
        final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();

        final ByteBuffer buffer = ByteBuffer.wrap(buggedChar);

        final CharBuffer chars = CharBuffer.allocate(10);

        final CoderResult result = dec.decode(buffer, chars, true);

        if (!result.isError() || result.length() <= 0) {
            // wow
            return true;
        }

        if (buffer.position() != 0) {
            return true;
        }

        return false;
    }

    private static final boolean hasAdvanceBug = hasAdvanceBug();
}
