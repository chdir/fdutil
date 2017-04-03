package net.sf.fakenames.fddemo;

import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;

import net.sf.xfd.Copy;
import net.sf.xfd.FsType;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

public class MiscCopyTests {
    private static final OS os;

    private final Stat stat = new Stat(); {
        stat.type = FsType.FILE;
    }

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @MediumTest
    public void copyTiny() throws IOException {
        final Copy c = os.copy();

        final File testSource = new File("/dev/zero");
        final File testTarget = new File("/dev/null");

        int s = os.open(testSource.getAbsolutePath(), OS.O_RDONLY, 0);
        try {
            int t = os.open(testTarget.getAbsolutePath(), OS.O_WRONLY, 0);
            try {
                final Long BIG_ASS = 66L;

                final long copySize = c.transfer(s, stat, t, stat, BIG_ASS);

                assertThat(copySize).isEqualTo(BIG_ASS);
            } finally {
                os.dispose(t);
            }
        } finally {
            os.dispose(s);
        }
    }

    @Test
    @MediumTest
    public void copyHuge() throws IOException {
        final Copy c = os.copy();

        final File testSource = new File("/dev/zero");
        final File testTarget = new File("/dev/null");

        int s = os.open(testSource.getAbsolutePath(), OS.O_RDONLY, 0);
        try {
            int t = os.open(testTarget.getAbsolutePath(), OS.O_WRONLY, 0);
            try {
                final Long BIG_ASS = Integer.MAX_VALUE * 2L;

                final long copySize = c.transfer(s, stat, t, stat, BIG_ASS);

                assertThat(copySize).isEqualTo(BIG_ASS);
            } finally {
                os.dispose(t);
            }
        } finally {
            os.dispose(s);
        }
    }

    @Test
    @LargeTest
    public void copyEpic() throws IOException {
        final Copy c = os.copy();

        final File testSource = new File("/dev/zero");
        final File testTarget = new File("/dev/null");

        int s = os.open(testSource.getAbsolutePath(), OS.O_RDONLY, 0);
        try {
            int t = os.open(testTarget.getAbsolutePath(), OS.O_WRONLY, 0);
            try {
                final Long BIG_ASS = Long.MAX_VALUE / 37901527;

                final long copySize = c.transfer(s, stat, t, stat, BIG_ASS);

                assertThat(copySize).isEqualTo(BIG_ASS);
            } finally {
                os.dispose(t);
            }
        } finally {
            os.dispose(s);
        }
    }
}
