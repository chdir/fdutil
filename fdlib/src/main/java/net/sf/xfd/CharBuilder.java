package net.sf.xfd;

import com.carrotsearch.hppc.CharArrayList;

import java.nio.CharBuffer;

public final class CharBuilder extends CharArrayList {
    private static final char[] digits = {
            '0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'A', 'B',
            'C', 'D', 'E', 'F'
    };

    private final char escape;

    public CharBuilder(int capacity, char escape) {
        super(capacity);

        this.escape = escape;
    }

    // append hex-escaped bytes to builder (see Integer#toHexString)
    void append(byte[] b, int offset, int count) {
        for (int i = 0; i < count; ++i) {
            append(b[offset + i]);
        }
    }

    // append hex-escaped byte to builder (see Integer#toHexString)
    void append(byte i) {
        ensureBufferSpace(4);

        add(escape, 'x');

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
        return new String(buffer, 0, elementsCount);
    }
}
