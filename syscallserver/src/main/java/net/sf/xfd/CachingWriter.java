package net.sf.xfd;

import com.carrotsearch.hppc.CharArrayList;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

public final class CachingWriter extends CharArrayList implements Closeable, Flushable {
    private static final CharBuffer EMPTY_BUFFER = CharBuffer.wrap(CharArrayList.EMPTY_ARRAY);

    private static final int UPPER_BOUND = 9000;

    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();

    private final int charBufferBound;
    private final ByteBuffer output;
    private final WritableByteChannel channel;

    private CharBuffer chars = EMPTY_BUFFER;

    public CachingWriter(WritableByteChannel channel, int byteBufferSize) {
        this(channel, byteBufferSize, UPPER_BOUND);
    }

    public CachingWriter(WritableByteChannel channel, int byteBufferSize, int charBufferBound) {
        super(0);

        this.channel = channel;

        this.charBufferBound = charBufferBound;

        this.output = ByteBuffer.allocateDirect(byteBufferSize);
    }

    @Override
    public void add(char e1) {
        int limit = chars.limit();

        super.add(e1);

        chars.limit(limit + 1);
    }

    public CachingWriter append(char ch) throws IOException {
        if (chars.limit() != 0) {
            add(ch);
        } else {
            ensureByteBufferSpace(1);
            output.put((byte) ch);
        }

        return this;
    }

    public CachingWriter append(long i) throws IOException {
        if (i > Integer.MAX_VALUE) {
            bail(i);
        }

        return append((int) i);
    }

    private void bail(long i) throws IOException {
        throw new IOException("The String is too long: can handle up to " + Integer.MAX_VALUE + ", received " + i);
    }

    public CachingWriter append(int i) throws IOException {
        flushToBuffer();

        int appendedLength = (i < 0) ? stringSize(-i) + 1 : stringSize(i);

        ensureByteBufferSpace(appendedLength);

        final int lastPos = output.position();

        getChars(i, lastPos + appendedLength, output);

        output.position(lastPos + appendedLength);

        return this;
    }

    private void ensureByteBufferSpace(int appendedLength) throws IOException {
        if (output.position() + appendedLength > output.limit()) {
            doFlushToChannel();
        }
    }

    public CachingWriter append(CharSequence csq) throws IOException {
        if (csq.getClass() == NativeString.class) {
            flushToBuffer();

            append(((NativeString) csq).getBytes());
        } else {
            append(csq.toString());
        }

        return this;
    }

    private void append(byte[] bytes) throws IOException {
        int toPut = bytes.length;

        do {
            int canPut = Math.min(output.remaining(), toPut);

            output.put(bytes, bytes.length - toPut, canPut);

            toPut -= canPut;

            if (output.remaining() == 0) {
                doFlushToChannel();
            }
        }
        while (toPut > 0);
    }

    @Override
    protected void ensureBufferSpace(int expectedAdditions) {
        super.ensureBufferSpace(expectedAdditions);

        if (buffer != chars.array()) {
            chars = CharBuffer.wrap(buffer);
            chars.limit(elementsCount);
        }
    }

    private void append(String string) throws IOException {
        int toPut = string.length();

        int i = 0;
        do {
            ++i;
            final int appendCount;

            int newLength = toPut + elementsCount;

            if (newLength < charBufferBound) {
                appendCount = toPut;

                ensureCapacity(newLength);
            } else {
                appendCount = charBufferBound - elementsCount;

                ensureCapacity(charBufferBound);
            }

            final int limit = chars.limit();

            final int srcBegin = string.length() - toPut;
            final int srcEnd = srcBegin + appendCount;

            string.getChars(srcBegin, srcEnd, this.buffer, elementsCount);

            elementsCount += appendCount;
            chars.limit(limit + appendCount);

            toPut -= appendCount;

            if (elementsCount == charBufferBound) {
                doFlushToBuffer();
            }
        }
        while (toPut > 0);
    }

    private void flushToBuffer() throws IOException {
        if (!chars.hasRemaining()) return;

        doFlushToBuffer();
    }

    private void doFlushToBuffer() throws IOException {
        try {
            CoderResult result;
            do {
                result = encoder.encode(chars, output, true);

                if (result.isError()) {
                    throw new IllegalArgumentException("Illegal character combination, aborting");
                } else if (result.isOverflow()) {
                    flushToChannel();
                }
            }
            while (chars.hasRemaining());

            do {
                result = encoder.flush(output);

                if (result.isError()) {
                    throw new IllegalArgumentException("Illegal character combination, aborting");
                } else if (result.isOverflow()) {
                    flushToChannel();
                } else {
                    break;
                }
            }
            while (true);
        } finally {
            encoder.reset();
            chars.rewind();
            chars.limit(0);
            elementsCount = 0;
        }
    }

    private void flushToChannel() throws IOException {
        if (output.position() == 0) return;

        doFlushToChannel();
    }

    private void doFlushToChannel() throws IOException {
        output.flip();
        try {
            do {
                channel.write(output);
            }
            while (output.hasRemaining());
        } finally {
            output.clear();
        }
    }

    public void flush() throws IOException {
        flushToBuffer();
        flushToChannel();
    }

    /**
     * All possible chars for representing a number as a String
     */
    final static byte[] digits = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'
    };

    final static byte [] DigitTens = {
            '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
            '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
            '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
            '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
            '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
            '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
            '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
            '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
    } ;

    final static byte [] DigitOnes = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    } ;

    /**
     * Places characters representing the integer i into the
     * character array buf. The characters are placed into
     * the buffer backwards starting with the least significant
     * digit at the specified index (exclusive), and working
     * backwards from there.
     *
     * Will fail if i == Integer.MIN_VALUE
     */
    private static void getChars(int i, int index, ByteBuffer buf) {
        int q, r;
        int charPos = index;
        byte sign = 0;

        if (i < 0) {
            sign = '-';
            i = -i;
        }

        // Generate two digits per iteration
        while (i >= 65536) {
            q = i / 100;
            // really: r = i - (q * 100);
            r = i - ((q << 6) + (q << 5) + (q << 2));
            i = q;
            buf.put(--charPos, DigitOnes[r]);
            buf.put(--charPos, DigitTens[r]);
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        for (;;) {
            q = (i * 52429) >>> (16+3);
            r = i - ((q << 3) + (q << 1));  // r = i-(q*10) ...
            buf.put(--charPos, digits [r]);
            i = q;
            if (i == 0) break;
        }
        if (sign != 0) {
            buf.put(--charPos, sign);
        }
    }

    private final static int [] sizeTable = { 9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999, Integer.MAX_VALUE };

    private static int stringSize(int x) {
        for (int i=0; ; i++)
            if (x <= sizeTable[i])
                return i+1;
    }

    @Override
    public void close() throws IOException {
        flush();

        channel.close();
    }
}
