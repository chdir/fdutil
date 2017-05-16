package net.sf.fakenames.fddemo;

import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;

import com.carrotsearch.hppc.IntArrayList;

import net.sf.xfd.DebugUtil;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.runners.Parameterized.*;

@RunWith(Parameterized.class)
@UseParametersRunnerFactory(ParametrizedRunner.class)
public class ApplicationTest {
    private static final TestSetup[] setups = new TestSetup[] { TestSetup.internal(), TestSetup.external() };

    @Parameters
    public static TestSetup[] parameters() {
        return setups;
    }

    private final TestSetup setup;

    public ApplicationTest(TestSetup setup) {
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

    private @DirFd int descriptor;

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
    public void testStraightIteration() {
        final ArrayList<String> normalIterationResult = new ArrayList<>();
        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final String[] dirContents = setup.dir.list();
        cursorIterationResult.ensureCapacity(dirContents.length + 2);
        normalIterationResult.ensureCapacity(dirContents.length + 2);

        normalIterationResult.add(".");
        normalIterationResult.add("..");
        Collections.addAll(normalIterationResult, dirContents);

        try (Directory dir = setup.forFd(descriptor)) {
            for (Directory.Entry file : dir) {
                cursorIterationResult.add(file.name.toString());
            }

            assertThat(cursorIterationResult)
                    .containsExactlyElementsIn(normalIterationResult)
                    .inOrder();
        }
    }

    @Test
    @MediumTest
    public void testStraightIteration2() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();

        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final String[] dirContents = setup.dir.list();
        cursorIterationResult.ensureCapacity(dirContents.length + 2);
        normalIterationResult.ensureCapacity(dirContents.length + 2);

        normalIterationResult.add(".");
        normalIterationResult.add("..");
        Collections.addAll(normalIterationResult, dirContents);

        try (Directory dir = setup.forFd(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = dir.iterator();

            Directory.Entry file = new Directory.Entry();

            while (iterator.moveToNext()) {
                iterator.get(file);

                cursorIterationResult.add(file.name.toString());
            }

            assertThat(cursorIterationResult)
                    .containsExactlyElementsIn(normalIterationResult)
                    .inOrder();
        }
    }

    @Test
    @MediumTest
    public void testBackwardIteration() throws IOException {
        final ArrayList<String> normalIterationResult = new ArrayList<>();
        final ArrayList<String> cursorIterationResult = new ArrayList<>();

        final String[] dirContents = setup.dir.list();
        cursorIterationResult.ensureCapacity(dirContents.length + 2);
        normalIterationResult.ensureCapacity(dirContents.length + 2);

        normalIterationResult.add(".");
        normalIterationResult.add("..");
        Collections.addAll(normalIterationResult, dirContents);
        Collections.reverse(normalIterationResult);

        try (Directory dir = setup.forFd(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = dir.iterator();

            if (iterator.moveToPosition(Integer.MAX_VALUE)) {
                throw new AssertionError("Moving to " + Integer.MAX_VALUE + " wasn't supposed to succeed!!");
            }

            Directory.Entry file = new Directory.Entry();

            do {
                if (iterator.getPosition() == -1) {
                    break;
                }

                iterator.get(file);

                cursorIterationResult.add(file.name.toString());
            }
            while (iterator.moveToPrevious());

            assertThat(cursorIterationResult)
                    .containsExactlyElementsIn(normalIterationResult)
                    .inOrder();
        }
    }

    @Test
    @LargeTest
    public void madJumping() throws IOException {
        final String[] dirContents = setup.dir.list();
        final ArrayList<String> normalIterationResult = new ArrayList<>(dirContents.length);
        Collections.addAll(normalIterationResult, dirContents);

        IntArrayList jumps;

        try (Directory dir = setup.forFd(descriptor)) {
            final UnreliableIterator<? super Directory.Entry> iterator = dir.iterator();

            final Random r = new Random();

            int jumpCount = 1000 + r.nextInt(2000);

            jumps = new IntArrayList(jumpCount);

            if (!iterator.moveToPosition(2)) {
                throw new AssertionError("Failed to move to 2 from -1 within " + setup.fCount);
            }

            int pos;

            final Directory.Entry file = new Directory.Entry();

            for (int i = 0; i < jumpCount; ++i) {
                pos = iterator.getPosition();

                int jumpLength = 5 + r.nextInt(20);
                boolean backward = r.nextBoolean();
                int newPos = backward
                        ? Math.max(0, pos - jumpLength)
                        : Math.min(setup.fCount - 1, pos + jumpLength);

                jumps.add(newPos);

                final String expected = normalIterationResult.get(newPos);

                final int cursorTarget = newPos + 2;

                if (!iterator.moveToPosition(cursorTarget)) {
                    throw new AssertionError("Failed to move to " + cursorTarget + " (" + dir.getOpaqueIndex(newPos) + ")" +
                            " from " + pos + " (" + dir.getOpaqueIndex(pos) + ")" +
                            " reached " + iterator.getPosition() + " (" + dir.getOpaqueIndex(iterator.getPosition()) + ")" +
                            " within " + setup.fCount +
                            " cookie list: " + DebugUtil.getCookieList(dir) +
                            " jumps: " + jumps);
                }

                iterator.get(file);

                final CharSequence got = file.name;

                // XXX suspect comparison
                if (!expected.contentEquals(got)) {
                    assertWithMessage("moving from %s to %s yielded unexpected results, jumps: %s", pos, newPos + 2, jumps)
                            .that(expected).isEqualTo(got);
                }
            }
        }
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