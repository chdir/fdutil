package net.sf.fakenames.fddemo;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.google.common.truth.Truth;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.Random;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */

@RunWith(AndroidJUnit4.class)
public class ApplicationTest {
    private static final OS os;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File dir;
    private static int fCount;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @BeforeClass
    public static void init() throws IOException {
        dir = InstrumentationRegistry.getTargetContext().getDir("testDir", Context.MODE_PRIVATE);

        final Random r = new Random();

        fCount = 75 * 2 + r.nextInt(30) * 75;

        for (int i = 0; i < fCount; ++i) {
            final File randomFile = new File(dir, UUID.randomUUID().toString());

            if (r.nextBoolean()) {
                randomFile.createNewFile();
            } else {
                randomFile.mkdir();
            }
        }
    }

    private @DirFd int descriptor;

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
    public void testStraightIteration() {
        final ArrayList<String> normalIterationResult = new ArrayList<>();

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        Collections.addAll(normalIterationResult, dir.list());

        Directory dir = os.list(descriptor);

        for (Directory.Entry file : dir) {
            if ("..".equals(file.name) || ".".equals(file.name)) {
                continue;
            }

            cursorIterationResult.add(file.name);
        }

        assertThat(cursorIterationResult)
                .containsExactlyElementsIn(normalIterationResult)
                .inOrder();
    }

    @Test
    public void testBackwardIteration() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        Collections.addAll(normalIterationResult, dir.list());

        Collections.reverse(normalIterationResult);

        Directory dir = os.list(descriptor);

        final UnreliableIterator<? super Directory.Entry> iterator = dir.iterator();

        iterator.moveToPosition(Integer.MAX_VALUE);

        Directory.Entry file = new Directory.Entry();

        do {
            if (iterator.getPosition() == -1) {
                break;
            }

            iterator.get(file);

            if ("..".equals(file.name) || ".".equals(file.name)) {
                continue;
            }

            cursorIterationResult.add(file.name);
        }
        while (iterator.moveToPrevious());

        assertThat(cursorIterationResult)
                .containsExactlyElementsIn(normalIterationResult)
                .inOrder();
    }

    @Test
    public void madJumping() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();
        Collections.addAll(normalIterationResult, dir.list());

        Directory dir = os.list(descriptor);

        final UnreliableIterator<? super Directory.Entry> iterator = dir.iterator();

        final Random r = new Random();

        int jumpCount = 100 + r.nextInt(500);

        if (!iterator.moveToPosition(2)) {
            throw new AssertionError("Failed to move to 2 within " + fCount);
        }

        int pos;

        final Directory.Entry file = new Directory.Entry();

        for (int i = 0; i < jumpCount; ++i) {
            pos = iterator.getPosition();

            int jumpLength = 5 + r.nextInt(20);
            boolean backward = r.nextBoolean();
            int newPos = backward
                    ? Math.max(0, pos - jumpLength)
                    : Math.min(fCount - 1, pos + jumpLength);

            final String expected = normalIterationResult.get(newPos);

            if (!iterator.moveToPosition(newPos + 2)) {
                throw new AssertionError("Failed to move to " + newPos + " within " + fCount);
            }

            iterator.get(file);

            final String got = file.name;

            if (!expected.equals(got)) {
                final ArrayList<String> linearView = new ArrayList<>();
                try (Directory copy = dir.clone()) {
                    for (Directory.Entry e : copy) {
                        linearView.add(e.name);
                    }
                }

                final String allItems = Arrays.toString(linearView.toArray(new String[linearView.size()]));

                assertWithMessage("moving from %s to %s yielded unexpected results, complete list: %s", pos, newPos + 2, allItems)
                        .that(expected).isEqualTo(got);
            }
        }
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