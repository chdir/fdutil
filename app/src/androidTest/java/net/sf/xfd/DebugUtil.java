package net.sf.xfd;

import com.carrotsearch.hppc.LongContainer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class DebugUtil {
    public static String getCookieList(Directory directory) {
        LongContainer lc = ((DirectoryImpl) directory).cookieCache;
        return "<"  + lc.size() + "> " + lc.toString();
    }

    public static void assertContentsEqual(File source, File target) throws IOException {
        try (RandomAccessFile r1 = new RandomAccessFile(source, "r");
             RandomAccessFile r2 = new RandomAccessFile(source, "r")) {
            final long r1L = r1.length();
            final long r2L = r1.length();
            if (r1L != r2L) {
                throw new AssertionError("Size of test files differ: " + r1L + " vs " + r2L);
            }

            final ByteBuffer b1 = r1.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, r1L);
            final ByteBuffer b2 = r2.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, r1L);

            if (!b1.equals(b2)) {
                throw new AssertionError("Contents of sample buffers differ");
            }
        }
    }

    public static CharSequence wrapRawForm(String utf8Suffix) {
        return new NativeString(utf8Suffix.getBytes(), "whoops");
    }

    public static CharSequence buildCharSequence(byte[] randPrefix, byte[] name, String utf8Suffix) {
        for (int i = 0; i < randPrefix.length; ++i) {
            // erase null bytes and slashes
            if (randPrefix[i] == 0 || randPrefix[i] == 47) {
                randPrefix[i] = 1;
            }
        }

        final byte[] last = utf8Suffix.getBytes();

        final byte[] resulting = new byte[randPrefix.length + name.length + last.length];

        System.arraycopy(randPrefix, 0, resulting, 0, randPrefix.length);
        System.arraycopy(name, 0, resulting, randPrefix.length, name.length);
        System.arraycopy(last, 0, resulting, randPrefix.length + name.length, last.length);

        return new NativeString(resulting, "whoops");
    }

    public static byte[] getBytes(CharSequence sequence) {
        return sequence.getClass() == NativeString.class ? ((NativeString) sequence).toBytes() : sequence.toString().getBytes();
    }
}
