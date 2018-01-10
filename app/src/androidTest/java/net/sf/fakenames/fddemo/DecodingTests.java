package net.sf.fakenames.fddemo;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.carrotsearch.hppc.ByteArrayList;

import net.sf.xfd.DebugUtil;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.FileNameDecoder;
import net.sf.xfd.OS;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class DecodingTests {
    private static final OS os;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte[][] sillyNames = new byte[][] {
            // Non-minimal sequences
            emit(0xF0, 0x80, 0x80, 0x80),
            emit(0xE0, 0x80, 0x80),
            emit(0xC0, 0x80),
            // UTF-16 Surrogates
            emit(0xED, 0xA0, 0x80),
            emit(0xED, 0xAF, 0xBF),
            emit(0xED, 0xB0, 0x80),
            emit(0xED, 0xBF, 0xBF),
            // Bad bytes
            emit(0xC0, 0xC1, 0xF5, 0xFF),
    };

    private static final String[] SMP_SYMBOLS = {
            "\uD83D\uDE00", "\uD83D\uDE3B", "\uD83D\uDE2C",
    };

    private static final Set<CharSequence> SMP_SET = new HashSet<>();

    private static final Set<String> SMP_STR_SET = new HashSet<>();

    private static byte[] emit(int... a) {
        final byte[] bytes = new byte[a.length];

        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] = (byte) a[i];
        }

        return bytes;
    }

    private final StringDecomposer builder = new StringDecomposer();

    private byte[] unescape(String string) {
        builder.elementsCount = 0;

        boolean gotAnother;

        int next = 0;
        do {
            int newPos = string.indexOf("\\x", next);

            gotAnother = newPos != -1;

            if (newPos != next) {
                final String pure = string.substring(next, gotAnother ? newPos : string.length());

                builder.append(pure);
            }

            if (gotAnother) {
                final String escape = string.substring(newPos + 2, newPos + 4);

                int decoded = Integer.parseInt(escape, 16);

                builder.add((byte) decoded);

                next = newPos + 4;
            }
        }
        while (gotAnother);

        return builder.toArray();
    }

    private static String dir;

    @BeforeClass
    public static void init() throws IOException {
        dir = InstrumentationRegistry.getTargetContext().getDir("testDir16", Context.MODE_PRIVATE).getAbsolutePath();

        cleanup();

        final Random r = new Random();

        final byte[] randBytes = new byte[15];

        @DirFd int dirFd = os.opendir(dir);
        try {
            for (byte[] sillyName : sillyNames) {
                r.nextBytes(randBytes);

                os.mknodat(dirFd, DebugUtil.buildCharSequence(randBytes, sillyName, UUID.randomUUID().toString()), OS.DEF_FILE_MODE, 0);
            }

            for (String SMP_SYM : SMP_SYMBOLS) {
                final CharSequence chars = DebugUtil.wrapRawForm(SMP_SYM);

                SMP_SET.add(chars);
                SMP_STR_SET.add(SMP_SYM);

                os.mknodat(dirFd, chars, OS.DEF_FILE_MODE, 0);
            }
        } finally {
            os.dispose(dirFd);
        }
    }

    @Test
    public void dataPreservation() throws IOException {
        @DirFd int dirFd = os.opendir(dir);
        try {
            try (Directory dir = os.list(dirFd)) {
                for (Directory.Entry e : dir) {
                    if ("..".contentEquals(e.name) || ".".contentEquals(e.name)) continue;

                    final String escaped = e.name.toString();

                    final byte[] unescaped = unescape(escaped);

                    Log.e("!!!", escaped);

                    try {
                        //assertThat(unescaped.length).isEqualTo(DebugUtil.getBytes(e.name).length);
                        assertThat(unescaped).isEqualTo(DebugUtil.getBytes(e.name));
                    } catch (Throwable t) {
                        Log.e("!!!", "Failed at " + Arrays.toString(unescaped));

                        throw t;
                    }
                }
            }
        } finally {
            os.dispose(dirFd);
        }
    }

    @Test
    public void smpHandling() throws IOException {
        int found = 0;

        @DirFd int dirFd = os.opendir(dir);
        try {
            try (Directory dir = os.list(dirFd)) {
                for (Directory.Entry e : dir) {
                    if (SMP_SET.contains(e.name) || SMP_STR_SET.contains(e.name.toString())) {
                        ++found;
                    }
                }
            }
        } finally {
            os.dispose(dirFd);
        }

        assertThat(found).isEqualTo(SMP_SET.size());
    }

    @AfterClass
    public static void cleanup() throws IOException {
        @DirFd int dirFd = os.opendir(dir);
        try {
            try (Directory dir = os.list(dirFd)) {
                for (Directory.Entry e : dir) {
                    if (!"..".equals(e.name) && !".".equals(e.name)) {
                        os.unlinkat(dirFd, e.name, 0);
                    }
                }
            }
        } finally {
            os.dispose(dirFd);
        }
    }

    private static final class StringDecomposer extends ByteArrayList {
        void append(String str) {
            add(str.getBytes());
        }
    }
}
