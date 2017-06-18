package net.sf.fakenames.fddemo;

import android.os.MemoryFile;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import net.sf.xfd.CachingWriter;
import net.sf.xfd.DebugUtil;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class WriterTest {
    private static final int ITER_MAX = 500;

    private static final int LONG_ENOUGH = 1024 * 1024;

    private final Random random = new Random();

    @Test
    public void testShortOut() throws IOException {
        final MemoryFile memoryFile = new MemoryFile("test-file", LONG_ENOUGH);

        try (Closeable c = memoryFile::close) {
            try (WritableByteChannel channel = Channels.newChannel(memoryFile.getOutputStream())) {
                try (CachingWriter writer = new CachingWriter(channel, 8, 10)) {
                    writer.append(20);
                    writer.append(' ');
                    writer.append("TestTest");
                    writer.flush();

                    final byte[] getBack = new byte[LONG_ENOUGH];

                    memoryFile.readBytes(getBack, 0, 0, LONG_ENOUGH);

                    final String intStr = new String(getBack, 0, 2);
                    assertThat(20).isEqualTo(Integer.parseInt(intStr));

                    final String remainderStr = new String(getBack, 2, 9);
                    assertThat(remainderStr).isEqualTo(" TestTest");
                }
            }
        }
    }

    private String makeRandString(int length) {
        final char[] storage = new char[length];

        for (int i = 0; i < storage.length; ++i) {
            storage[i] = (char) (byte) random.nextInt(128);
        }

        return new String(storage);
    }

    @Test
    public void testLooongString() throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final MemoryFile memoryFile = new MemoryFile("test-file", LONG_ENOUGH);

        try (Closeable c = memoryFile::close) {
            try (WritableByteChannel channel = Channels.newChannel(memoryFile.getOutputStream())) {
                try (CachingWriter writer = new CachingWriter(channel, 8, 10)) {
                    final String str = makeRandString(LONG_ENOUGH);

                    writer.append(str);
                    writer.flush();

                    final byte[] getBack = new byte[LONG_ENOUGH];

                    memoryFile.readBytes(getBack, 0, 0, LONG_ENOUGH);
                    DebugUtil.assertContentsEqual(str.getBytes(), getBack);
                }
            }
        }
    }

    @Test
    public void testLooongBytes() throws IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final MemoryFile memoryFile = new MemoryFile("test-file", LONG_ENOUGH);

        try (Closeable c = memoryFile::close) {
            try (WritableByteChannel channel = Channels.newChannel(memoryFile.getOutputStream())) {
                try (CachingWriter writer = new CachingWriter(channel, 8, 10)) {
                    final byte[] pureEvil = new byte[LONG_ENOUGH];

                    random.nextBytes(pureEvil);

                    writer.append(DebugUtil.wrapRawForm(pureEvil));
                    writer.flush();

                    final byte[] getBack = new byte[LONG_ENOUGH];

                    memoryFile.readBytes(getBack, 0, 0, LONG_ENOUGH);

                    DebugUtil.assertContentsEqual(pureEvil, getBack);
                }
            }
        }
    }

    @Test
    @LargeTest
    public void randomOut() throws NoSuchAlgorithmException, IOException {
        final MessageDigest shaFirst = MessageDigest.getInstance("SHA-512");
        final MessageDigest shaSecond = MessageDigest.getInstance("SHA-512");

        int iterationsCount = random.nextInt(ITER_MAX) + 250;

        try (CachingWriter writer = new CachingWriter(Channels.newChannel(new DigestOutputStream(new FileOutputStream("/dev/null"), shaFirst)), 9000)) {
            for (int i = 0; i < iterationsCount; ++i) {
                switch (random.nextInt(3)) {
                    case 0:
                        final String str = makeRandString(random.nextInt(LONG_ENOUGH) + 1);

                        writer.append(str);

                        shaSecond.update(str.getBytes());

                        break;
                    case 1:
                        final int randInt = random.nextInt(Integer.MAX_VALUE);

                        writer.append(randInt);

                        shaSecond.update(String.valueOf(randInt).getBytes());

                        break;
                    case 2:
                        final byte[] rawBytes = new byte[random.nextInt(LONG_ENOUGH)];

                        random.nextBytes(rawBytes);

                        writer.append(DebugUtil.wrapRawForm(rawBytes));

                        shaSecond.update(rawBytes);

                        break;
                }
            }
        }

        DebugUtil.assertContentsEqual(shaFirst.digest(), shaSecond.digest());
    }
}
