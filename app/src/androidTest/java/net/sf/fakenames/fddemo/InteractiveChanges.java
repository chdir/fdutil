package net.sf.fakenames.fddemo;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

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
public class InteractiveChanges {
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
        dir = InstrumentationRegistry.getTargetContext().getDir("testDir2", Context.MODE_PRIVATE);

        cleanup();

        final Random r = new Random();

        fCount = 75 * 2 + r.nextInt(4) * 75;

        for (int i = 0; i < fCount; ++i) {
            final File randomFile = new File(dir, UUID.randomUUID().toString());

            if (r.nextBoolean()) {
                randomFile.createNewFile();
            } else {
                randomFile.mkdir();
            }
        }
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
    public void allPlusOne() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();
        Collections.addAll(normalIterationResult, dir.list());

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final File testFile = new File(dir, "abracadabra");

        final Directory.Entry file = new Directory.Entry();

        try (Directory d = os.list(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            //noinspection StatementWithEmptyBody
            while (iterator.moveToNext()) {
            }

            if (!testFile.createNewFile()) {
                throw new AssertionError("Failed to create test file!");
            }

            iterator.moveToFirst();

            do {
                iterator.get(file);

                if ("..".equals(file.name) || ".".equals(file.name)) {
                    continue;
                }

                cursorIterationResult.add(file.name);

                Log.d("test", file.toString() + ' ' + d.getOpaqueIndex(iterator.getPosition()));
            } while (iterator.moveToNext());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            testFile.delete();
        }

        assertThat(cursorIterationResult)
                .containsExactlyElementsIn(normalIterationResult)
                .inOrder();
    }

    @Test
    public void allPlusOneAfterReset() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final File testFile = new File(dir, "abracadabra");

        final Directory.Entry file = new Directory.Entry();

        try (Directory d = os.list(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            //noinspection StatementWithEmptyBody
            while (iterator.moveToNext()) {
            }

            if (!testFile.createNewFile()) {
                throw new AssertionError("Failed to create test file!");
            }

            Collections.addAll(normalIterationResult, dir.list());

            iterator.moveToPosition(-1);
            iterator.moveToFirst();

            do {
                iterator.get(file);

                if ("..".equals(file.name) || ".".equals(file.name)) {
                    continue;
                }

                cursorIterationResult.add(file.name);

                Log.d("test", file.toString() + ' ' + d.getOpaqueIndex(iterator.getPosition()));
            } while (iterator.moveToNext());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            testFile.delete();
        }

        assertThat(cursorIterationResult)
                .containsExactlyElementsIn(normalIterationResult)
                .inOrder();
    }

    @Test
    @SuppressWarnings({"ThrowFromFinallyBlock", "ResultOfMethodCallIgnored"})
    public void allMinusOne() throws IOException {
        final ArrayList<String> initialIterationResult = new ArrayList<>();
        final ArrayList<String> postDeletionIterationResult = new ArrayList<>();
        final ArrayList<String> cursorContentsIterationResult = new ArrayList<>();
        final LongHashSet cursorIterationResult = new LongHashSet();

        Collections.addAll(initialIterationResult, dir.list());
        Collections.reverse(initialIterationResult);

        File removed = null;

        final Directory.Entry file = new Directory.Entry();

        int failedBacktracksCounter = 0;

        try (Directory d = os.list(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            final Random r = new Random();

            final int positionToRemove = 19; //2 + r.nextInt(20);

            while (iterator.moveToNext()) {
                if (iterator.getPosition() == positionToRemove) {
                    iterator.get(file);

                    removed = new File(dir, file.name);

                    if (!removed.delete()) {
                        throw new AssertionError("Unable to remove " + file);
                    }
                }
            }

            Collections.addAll(postDeletionIterationResult, dir.list());
            Collections.reverse(postDeletionIterationResult);

            do { // 8ef93436-0363-4785-8b7c-c5b04c5cf52c
                iterator.get(file);

                if (!"..".equals(file.name) && !".".equals(file.name)) {
                    final int currentPosition = iterator.getPosition();

                    final long accountablePosition = d.getOpaqueIndex(currentPosition);

                    if (!cursorIterationResult.add(accountablePosition)) {
                        throw new AssertionError("Duplicate cookie " + accountablePosition +
                                " found at position " + currentPosition);
                    }

                    cursorContentsIterationResult.add(file.name);
                }

                int prev = iterator.getPosition() - 1;

                while (!iterator.moveToPosition(prev)) {
                    ++failedBacktracksCounter;

                    --prev;
                }

                if (iterator.getPosition() == -1) {
                    break;
                }
            } while (true);
        } finally {
            if (removed != null) {
                if (removed.isDirectory()) {
                    removed.mkdir();
                } else {
                    removed.createNewFile();
                }
            }
        }

        assertThat(cursorIterationResult.size()).isAtMost(initialIterationResult.size());

        assertThat(cursorContentsIterationResult).containsAllIn(postDeletionIterationResult);

        assertThat(failedBacktracksCounter).isAtMost(1);
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
