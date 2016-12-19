package net.sf.fakenames.fddemo;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongHashSet;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.OS;
import net.sf.fdlib.UnreliableIterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

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
            descriptor = os.opendir(path, OS.O_RDONLY, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
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
        os.closeDir(descriptor);
    }

    @AfterClass
    public static void cleanup() {
        for (File file : dir.listFiles()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
