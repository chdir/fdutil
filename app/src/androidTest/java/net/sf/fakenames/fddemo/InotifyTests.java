package net.sf.fakenames.fddemo;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.OS;
import net.sf.fdlib.SelectorThread;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.Scanner;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class InotifyTests {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

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
        dir = InstrumentationRegistry.getTargetContext().getDir("inotifyTestDir", Context.MODE_PRIVATE);

        cleanup();
    }

    private @DirFd int dirDescriptor;
    private @InotifyFd int inotifyDescriptor;

    @Before
    public void openDir() {
        String path = dir.getPath();

        try {
            dirDescriptor = os.opendir(path, OS.O_RDONLY, 0);
            inotifyDescriptor = os.inotify_init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testPlain() throws IOException {
        final File dir = new File("/proc/self/fd/" + dirDescriptor).getCanonicalFile();
        final File aFile = new File(dir, "lalalalala");

        final class Listener implements Inotify.InotifyListener {
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
            }
        };

        final Listener listener = new Listener();

        try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
            instrumentation.runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    try {
                        //noinspection CheckResult
                        inotify.subscribe(dirDescriptor, listener);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });

            if (!aFile.createNewFile()) {
                throw new AssertionError("Failed to create " + aFile);
            }

            sync(dirDescriptor);

            inotify.run();

            instrumentation.waitForIdleSync();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            aFile.delete();
        }

        assertThat(listener.changesDetected).isEqualTo(1);
    }

    @Test
    public void unregisterOnDelete() throws IOException {
        final File dir = new File("/proc/self/fd/" + dirDescriptor).getCanonicalFile();

        final File aFile = new File(dir, UUID.randomUUID().toString());
        if (!aFile.mkdir()) {
            throw new AssertionError("Failed to create test dir");
        }

        final class Listener implements Inotify.InotifyListener {
            private int resetsDetected = 0;
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
                ++resetsDetected;
            }
        }
        ;

        final Listener listener = new Listener();

        final int innerDirFd = os.open(aFile.getAbsolutePath(), OS.O_RDONLY, 0);

        try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
            try {
                instrumentation.runOnMainSync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            //noinspection CheckResult
                            inotify.subscribe(innerDirFd, listener);
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    }
                });

                final File tmp = new File(aFile, "ttt");
                if (!tmp.createNewFile()) {
                    throw new AssertionError("Failed to create temporary test file");
                }
                if (!tmp.delete()) {
                    throw new AssertionError("Failed to delete " + tmp);
                }

                if (!aFile.delete()) {
                    throw new AssertionError("Failed to delete " + aFile);
                }
            } finally {
                os.dispose(innerDirFd);

                //noinspection ResultOfMethodCallIgnored
                aFile.delete();
            }

            sync(dirDescriptor);

            inotify.run();

            instrumentation.waitForIdleSync();

            assertThat(listener.changesDetected).isEqualTo(0);
            assertThat(listener.resetsDetected).isEqualTo(1);
        }

        assertThat(listener.changesDetected).isEqualTo(0);
        assertThat(listener.resetsDetected).isEqualTo(1);
    }

    @Test
    public void renameTillItDrops() throws IOException {
        int needEventsToOverflow;

        try (Scanner s = new Scanner(new FileInputStream("/proc/sys/fs/inotify/max_queued_events"))) {
            needEventsToOverflow = s.nextInt() + 1;
        }

        final File dir = new File("/proc/self/fd/" + dirDescriptor).getCanonicalFile();

        File aFile = new File(dir, "testName");
        //noinspection ResultOfMethodCallIgnored
        aFile.createNewFile();

        final class Listener implements Inotify.InotifyListener {
            private int resetsDetected = 0;
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
                ++resetsDetected;
            }
        };

        final Listener listener = new Listener();

        try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
            instrumentation.runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    try {
                        //noinspection CheckResult
                        inotify.subscribe(dirDescriptor, listener);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });

            for (int i = 0; i < needEventsToOverflow; ++i) {
                final File newName = new File(dir, UUID.randomUUID().toString());

                if (!aFile.renameTo(newName)) {
                    throw new AssertionError("Failed to perform a rename on step " + i);
                }

                aFile = newName;
            }

            sync(dirDescriptor);

            inotify.run();

            instrumentation.waitForIdleSync();

            assertThat(listener.changesDetected).isEqualTo(0);
            assertThat(listener.resetsDetected).isEqualTo(1);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            aFile.delete();
        }

        assertThat(listener.changesDetected).isEqualTo(0);
        assertThat(listener.resetsDetected).isEqualTo(1);
    }

    @Test
    public void selectorUsage() throws IOException {
        final class Listener implements Inotify.InotifyListener {
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
            }
        };

        final Listener listener = new Listener();

        try (SelectorThread thread = new SelectorThread()) {
            thread.start();

            final File aFile = new File(dir, "testtesttest");

            try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
                instrumentation.runOnMainSync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            inotify.setSelector(thread);

                            //noinspection CheckResult
                            inotify.subscribe(dirDescriptor, listener);
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    }
                });

                if (!aFile.createNewFile()) {
                    throw new AssertionError("Failed to create " + aFile);
                }

                sync(dirDescriptor);

                instrumentation.waitForIdleSync();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                aFile.delete();
            }
        }

        assertThat(listener.changesDetected).isEqualTo(1);
    }

    @Test
    public void heavySelectorLoad() throws IOException {
        int queueMax;

        try (Scanner s = new Scanner(new FileInputStream("/proc/sys/fs/inotify/max_queued_events"))) {
            queueMax = s.nextInt();
        }

        int tolerableCount = Math.min(queueMax - 1, 99999);

        final File dir = new File("/proc/self/fd/" + dirDescriptor).getCanonicalFile();

        File aFile = new File(dir, "testName");
        //noinspection ResultOfMethodCallIgnored
        aFile.createNewFile();

        final class Listener implements Inotify.InotifyListener {
            private int resetsDetected = 0;
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
                ++resetsDetected;
            }
        };

        final Listener listener = new Listener();

        try (SelectorThread thread = new SelectorThread()) {
            thread.start();
            try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
                instrumentation.runOnMainSync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            inotify.setSelector(thread);

                            //noinspection CheckResult
                            inotify.subscribe(dirDescriptor, listener);
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    }
                });

                for (int i = 0; i < tolerableCount; ++i) {
                    if (!new File(dir, UUID.randomUUID().toString()).createNewFile()) {
                        throw new AssertionError("Failed to create test file");
                    }
                }

                sync(dirDescriptor);

                instrumentation.waitForIdleSync();

                assertThat(listener.changesDetected).isAtLeast(1);
                assertThat(listener.resetsDetected).isEqualTo(0);
            } finally {
                for (File file : dir.listFiles()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }

        assertThat(listener.changesDetected).isAtLeast(1);
        assertThat(listener.resetsDetected).isEqualTo(1);
    }

    @Test
    public void headCount() throws IOException, InterruptedException {
        int queueMax;

        try (Scanner s = new Scanner(new FileInputStream("/proc/sys/fs/inotify/max_queued_events"))) {
            queueMax = s.nextInt();
        }

        int eventsTotal = Math.min(queueMax - 1, 999);

        final File dir = new File("/proc/self/fd/" + dirDescriptor).getCanonicalFile();

        File aFile = new File(dir, "testName");
        //noinspection ResultOfMethodCallIgnored
        aFile.createNewFile();

        final class Listener implements Inotify.InotifyListener {
            private int resetsDetected = 0;
            private int changesDetected = 0;

            @Override
            public void onChanges() {
                ++changesDetected;
            }

            @Override
            public void onReset() {
                ++resetsDetected;
            }
        };

        final Listener listener = new Listener();

        // create and initialize the main thread to ensure maximum priority
        final SelectorThread[] t = new SelectorThread[1];

        instrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    final int uiPriority = Thread.currentThread().getPriority();

                    final SelectorThread s = new SelectorThread();

                    s.setPriority(uiPriority);
                    s.start();

                    t[0] = s;
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        });

        try (SelectorThread thread = t[0]) {
            try (Inotify inotify = os.observe(inotifyDescriptor, Looper.getMainLooper())) {
                instrumentation.runOnMainSync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            inotify.setSelector(thread);

                            //noinspection CheckResult
                            inotify.subscribe(dirDescriptor, listener);
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    }
                });

                for (int i = 0; i < eventsTotal; ++i) {
                    if (!new File(dir, UUID.randomUUID().toString()).createNewFile()) {
                        throw new AssertionError("Failed to create test file");
                    }

                    // give the kernel & SelectorThread some time to process each entry
                    Thread.sleep(6);
                }

                sync(dirDescriptor);

                instrumentation.waitForIdleSync();

                assertThat(listener.changesDetected).isEqualTo(eventsTotal);
                assertThat(listener.resetsDetected).isEqualTo(0);
            } finally {
                for (File file : dir.listFiles()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }

        assertThat(listener.changesDetected).isEqualTo(eventsTotal);
        assertThat(listener.resetsDetected).isEqualTo(1);
    }

    private static void sync(int fd) throws SyncFailedException {
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
        pfd.getFileDescriptor().sync();
        pfd.detachFd();
    }

    @AfterClass
    public static void cleanup() {
        for (File file : dir.listFiles()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
