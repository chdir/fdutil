package net.sf.fakenames.fddemo;

import android.support.test.filters.MediumTest;
import android.util.Log;

import com.carrotsearch.hppc.LongHashSet;

import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.OS;
import net.sf.xfd.UnreliableIterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ParametrizedRunner.class)
public class InteractiveChanges {
    private static final TestSetup[] setups = new TestSetup[] { TestSetup.internal(), TestSetup.external() };

    @Parameterized.Parameters
    public static TestSetup[] parameters() {
        return setups;
    }

    private final TestSetup setup;

    public InteractiveChanges(TestSetup setup) {
        this.setup = setup;
    }

    private static final OS os;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private @DirFd
    int descriptor;

    @Before
    public void openDir() {
        String path = setup.dir.getPath();

        try {
            descriptor = os.opendir(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @MediumTest
    public void allPlusOne() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();
        Collections.addAll(normalIterationResult, setup.dir.list());

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final File testFile = new File(setup.dir, "abracadabra");

        final Directory.Entry file = new Directory.Entry();

        try (Directory d = setup.forFd(descriptor)) {
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

                if ("..".contentEquals(file.name) || ".".contentEquals(file.name)) {
                    continue;
                }

                cursorIterationResult.add(file.name.toString());

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
    @MediumTest
    public void allPlusOneAfterReset() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final File testFile = new File(setup.dir, "abracadabra");

        final Directory.Entry file = new Directory.Entry();

        try (Directory d = setup.forFd(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            //noinspection StatementWithEmptyBody
            while (iterator.moveToNext()) {
            }

            if (!testFile.createNewFile()) {
                throw new AssertionError("Failed to create test file!");
            }

            Collections.addAll(normalIterationResult, setup.dir.list());

            iterator.moveToPosition(-1);
            iterator.moveToFirst();

            do {
                iterator.get(file);

                if ("..".contentEquals(file.name) || ".".contentEquals(file.name)) {
                    continue;
                }

                cursorIterationResult.add(file.name.toString());
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
    @MediumTest
    @SuppressWarnings({"ThrowFromFinallyBlock", "ResultOfMethodCallIgnored"})
    public void allMinusOne() throws IOException {
        final ArrayList<String> initialIterationResult = new ArrayList<>();
        final ArrayList<String> postDeletionIterationResult = new ArrayList<>();
        final ArrayList<String> cursorContentsIterationResult = new ArrayList<>();
        final LongHashSet cursorIterationResult = new LongHashSet();

        Collections.addAll(initialIterationResult, setup.dir.list());
        Collections.reverse(initialIterationResult);

        File removed = null;

        final Directory.Entry file = new Directory.Entry();

        int failedBacktracksCounter = 0;

        try (Directory d = setup.forFd(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = d.iterator();

            final Random r = new Random();

            final int positionToRemove = 19; //2 + r.nextInt(20);

            while (iterator.moveToNext()) {
                if (iterator.getPosition() == positionToRemove) {
                    iterator.get(file);

                    removed = new File(setup.dir.toString(), file.name.toString());

                    if (!removed.delete()) {
                        throw new AssertionError("Unable to remove " + file);
                    }
                }
            }

            Collections.addAll(postDeletionIterationResult, setup.dir.list());
            Collections.reverse(postDeletionIterationResult);

            do { // 8ef93436-0363-4785-8b7c-c5b04c5cf52c
                iterator.get(file);

                if (!"..".contentEquals(file.name) && !".".contentEquals(file.name)) {
                    final int currentPosition = iterator.getPosition();

                    final long accountablePosition = d.getOpaqueIndex(currentPosition);

                    if (!cursorIterationResult.add(accountablePosition)) {
                        throw new AssertionError("Duplicate cookie " + accountablePosition +
                                " found at position " + currentPosition);
                    }

                    cursorContentsIterationResult.add(file.name.toString());
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
        for (TestSetup setup : setups) {
            setup.cleanup();
        }
    }
}
