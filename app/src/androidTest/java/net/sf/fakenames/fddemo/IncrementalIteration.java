package net.sf.fakenames.fddemo;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.carrotsearch.hppc.LongArrayList;

import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.OS;
import net.sf.xfd.UnreliableIterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class IncrementalIteration {
    private static final OS os;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File dir;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @BeforeClass
    public static void init() throws IOException {
        dir = InstrumentationRegistry.getTargetContext().getDir("testDir3", Context.MODE_PRIVATE);

        cleanup();
    }

    private @DirFd
    int descriptor;

    @Before
    public void openDir() {
        String path = dir.getPath();

        try {
            descriptor = os.opendir(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @MediumTest
    public void suddenAddition() throws IOException {
        LongArrayList cookies = new LongArrayList();

        final File testFile = new File(dir, "abracadabra");

        try (Directory d = os.list(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            //noinspection StatementWithEmptyBody
            while (iterator.moveToNext()) {
            }

            if (!testFile.createNewFile()) {
                throw new AssertionError("Failed to create test file!");
            }

            if (iterator.moveToNext()) {
                for (int i = 0; i <= iterator.getPosition(); ++i) {
                    cookies.add(d.getOpaqueIndex(i));
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            testFile.delete();
        }

        assertThat(cookies.toArray()).asList().containsNoDuplicates();
    }

    @Test
    @MediumTest
    public void expectedAddition() throws IOException {
        LongArrayList cookies = new LongArrayList();

        final File testFile = new File(dir, "abracadabra");

        try (Directory d = os.list(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            //noinspection StatementWithEmptyBody
            while (iterator.moveToNext()) {
            }

            if (!testFile.createNewFile()) {
                throw new AssertionError("Failed to create test file!");
            }

            if (!iterator.moveToFirst()) {
                throw new AssertionError("Failed to reset directory iterator");
            }

            if (iterator.moveToNext()) {
                for (int i = 0; i <= iterator.getPosition(); ++i) {
                    cookies.add(d.getOpaqueIndex(i));
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            testFile.delete();
        }

        assertThat(cookies.toArray()).asList().containsNoDuplicates();
    }

    @After
    public void closeDir() {
        os.dispose(descriptor);
    }

    @AfterClass
    public static void cleanup() {
        for (File file : dir.listFiles()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
