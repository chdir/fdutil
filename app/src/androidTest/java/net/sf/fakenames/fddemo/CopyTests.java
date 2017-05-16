package net.sf.fakenames.fddemo;

import android.os.ParcelFileDescriptor;
import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import net.sf.xfd.Copy;
import net.sf.xfd.DebugUtil;
import net.sf.xfd.Fd;
import net.sf.xfd.FsType;
import net.sf.xfd.OS;
import net.sf.xfd.SelectorThread;
import net.sf.xfd.Stat;

import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;
import static net.sf.xfd.DebugUtil.assertContentsEqual;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(ParametrizedRunner.class)
public class CopyTests {
    private static final TestSetup[] setups = new TestSetup[] {
            TestSetup.internal(),
            TestSetup.internal2(),
            TestSetup.external(),
    };

    @Parameterized.Parameters
    public static TestSetup[] parameters() {
        return setups;
    }

    private static final OS os;

    private static final int F1Size = 9;
    private static final int F2Size = 64 * 1024 * 2 + 11;
    private static final int F3Size = 27 * 1024 * 1024 + 1289;

    private static File fewBytes;
    private static File couplePages;
    private static File severalMegabytes;

    private final Random r = new SecureRandom();

    private final TestSetup setup;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CopyTests(TestSetup setup) {
        this.setup = setup;
    }

    @Test
    @SmallTest
    public void copyFewBytes() throws IOException {
        final byte[] sourceBytes = new byte[F1Size];

        r.nextBytes(sourceBytes);

        fewBytes = File.createTempFile("smallest", null, setup.dir);

        try (FileOutputStream fos = new FileOutputStream(fewBytes)) {
            fos.write(sourceBytes);
        }

        try (Copy c = os.copy()) {
            final File testCopy = File.createTempFile("smallest0", null, setup.dir);
            try {
                int s = os.open(fewBytes.getAbsolutePath(), OS.O_RDONLY, 0);
                try {
                    int t = os.open(testCopy.getAbsolutePath(), OS.O_WRONLY, 0);
                    try {
                        final long copySize = c.transfer(s, setup.stat, t, setup.stat, Long.MAX_VALUE);

                        assertThat(copySize).isEqualTo(F1Size);
                    } finally {
                        os.dispose(t);
                    }
                } finally {
                    os.dispose(s);
                }

                assertContentsEqual(fewBytes, testCopy);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                testCopy.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            fewBytes.delete();
        }
    }

    @Test
    @MediumTest
    public void copyCouplePages() throws IOException {
        final byte[] sourceBytes = new byte[F2Size];

        couplePages = File.createTempFile("small", null, setup.dir);

        r.nextBytes(sourceBytes);

        try (FileOutputStream fos = new FileOutputStream(couplePages)) {
            fos.write(sourceBytes);
        }

        try (Copy c = os.copy()) {
            final File testCopy = File.createTempFile("small0", null, setup.dir);
            try {
                int s = os.open(couplePages.getAbsolutePath(), OS.O_RDONLY, 0);
                try {
                    int t = os.open(testCopy.getAbsolutePath(), OS.O_WRONLY, 0);
                    try {
                        final long copySize = c.transfer(s, setup.stat, t, setup.stat, Long.MAX_VALUE);

                        assertThat(copySize).isEqualTo(F2Size);
                    } finally {
                        os.dispose(t);
                    }
                } finally {
                    os.dispose(s);
                }

                assertContentsEqual(couplePages, testCopy);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                testCopy.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            couplePages.delete();
        }
    }

    @Test
    @MediumTest
    public void copyFewMbs() throws IOException {
        final byte[] sourceBytes = new byte[F3Size];

        severalMegabytes = File.createTempFile("severalMb", null, setup.dir);

        r.nextBytes(sourceBytes);

        try (FileOutputStream fos = new FileOutputStream(severalMegabytes)) {
            fos.write(sourceBytes);
        }

        try (Copy c = os.copy()) {
            final File testCopy = File.createTempFile("severalMbs0", null, setup.dir);
            try {
                int s = os.open(severalMegabytes.getAbsolutePath(), OS.O_RDONLY, 0);
                try {
                    int t = os.open(testCopy.getAbsolutePath(), OS.O_WRONLY, 0);
                    try {
                        final long copySize = c.transfer(s, setup.stat, t, setup.stat, Long.MAX_VALUE);

                        assertThat(copySize).isEqualTo(F3Size);
                    } finally {
                        os.dispose(t);
                    }
                } finally {
                    os.dispose(s);
                }

                assertContentsEqual(severalMegabytes, testCopy);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                testCopy.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            severalMegabytes.delete();
        }
    }

    @Test
    @MediumTest
    public void pipeTest() throws IOException, InterruptedException {
        severalMegabytes = File.createTempFile("severalMb", null, setup.dir);

        try (Copy c = os.copy()) {
            final byte[] sourceBytes = new byte[F3Size];

            r.nextBytes(sourceBytes);

            try (FileOutputStream fos = new FileOutputStream(severalMegabytes)) {
                fos.write(sourceBytes);
            }

            final Stat pipeStat = new Stat();
            pipeStat.type = FsType.NAMED_PIPE;

            final File testCopy = File.createTempFile("severalMbs0", null, setup.dir);
            try {
                int s = os.open(severalMegabytes.getAbsolutePath(), OS.O_RDONLY, 0);
                try {
                    Thread thread = null;

                    int t = os.open(testCopy.getAbsolutePath(), OS.O_WRONLY, 0);
                    try {
                        final ParcelFileDescriptor[] holder = ParcelFileDescriptor.createPipe();

                        final ParcelFileDescriptor source = holder[0];
                        final ParcelFileDescriptor sink = holder[1];

                        try (Closeable closeable = source) {
                            @Fd int sinkFd = sink.getFd();
                            @Fd int sourceFd = source.getFd();

                            thread = new Thread("Aux pipe copy") {
                                @Override
                                public void run() {
                                    try (Copy c = os.copy(); Closeable closeable = sink) {
                                        long written = c.transfer(s, setup.stat, sinkFd, pipeStat, Long.MAX_VALUE);

                                        assertThat(written).isEqualTo(F3Size);
                                    } catch (IOException e) {
                                        throw new AssertionError(e);
                                    }
                                }
                            };

                            thread.start();

                            long written = c.transfer(sourceFd, pipeStat, t, setup.stat, Long.MAX_VALUE);

                            thread.join();

                            assertThat(written).isEqualTo(F3Size);
                        }
                    } finally {
                        os.dispose(t);
                    }
                } finally {
                    os.dispose(s);
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                testCopy.delete();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            severalMegabytes.delete();
        }
    }

    @Test
    @LargeTest
    public void pipeInterrupted() throws IOException, InterruptedException {
        severalMegabytes = File.createTempFile("severalMb", null, setup.dir);

        try (SelectorThread st = new SelectorThread(); Copy c = os.copy()) {

            final byte[] sourceBytes = new byte[F3Size];

            r.nextBytes(sourceBytes);

            try (FileOutputStream fos = new FileOutputStream(severalMegabytes)) {
                fos.write(sourceBytes);
            }

            final Stat pipeStat = new Stat();
            pipeStat.type = FsType.NAMED_PIPE;

            for (int i = 0; i < 100; ++i) {
                final File testCopy = File.createTempFile("severalMbs0", null, setup.dir);
                try {
                    int s = os.open(severalMegabytes.getAbsolutePath(), OS.O_RDONLY, 0);
                    try {
                        Thread thread = null;

                        int t = os.open(testCopy.getAbsolutePath(), OS.O_WRONLY, 0);

                        AtomicBoolean terminated = new AtomicBoolean();

                        try {
                            final ParcelFileDescriptor[] holder = ParcelFileDescriptor.createPipe();

                            final ParcelFileDescriptor source = holder[0];
                            final ParcelFileDescriptor sink = holder[1];

                            try (Closeable closeable = source) {
                                @Fd int sinkFd = sink.getFd();
                                @Fd int sourceFd = source.getFd();

                                CountDownLatch countDownLatch = new CountDownLatch(1);
                                AtomicBoolean gotInterrupt = new AtomicBoolean();

                                final Runnable r = new Runnable() {
                                    @Override
                                    public void run() {
                                        try (Copy c = os.copy(); Closeable closeable = sink) {
                                            long lastWrite = -1, totalWritten = 0;

                                            countDownLatch.countDown();

                                            do {
                                                try {
                                                    lastWrite = c.transfer(s, setup.stat, sinkFd, pipeStat, Long.MAX_VALUE);

                                                    totalWritten += lastWrite;
                                                } catch (InterruptedIOException iie) {
                                                    iie.printStackTrace();

                                                    Log.i("!!!", "bytes send so far: " + iie.bytesTransferred);

                                                    totalWritten += iie.bytesTransferred;

                                                    Thread.interrupted();

                                                    gotInterrupt.set(true);
                                                }
                                            } while (lastWrite != 0 && !terminated.get());

                                            assertThat(totalWritten).isEqualTo(F3Size);
                                        } catch (IOException e) {
                                            throw new AssertionError(e);
                                        }
                                    }
                                };

                                thread = new Thread(null, r, "Aux pipe copy");
                                thread.start();

                                countDownLatch.await();

                                Thread.sleep(20); // not necessary, just for flavor

                                thread.interrupt();

                                long lastWrite = -1, totalWritten = 0;

                                do {
                                    try {
                                        lastWrite = c.transfer(sourceFd, pipeStat, t, setup.stat, Long.MAX_VALUE);

                                        totalWritten += lastWrite;
                                    } catch (InterruptedIOException iie) {
                                        totalWritten += iie.bytesTransferred;

                                        Log.i("!!!", "bytes received so far: " + iie.bytesTransferred);
                                    }
                                } while (lastWrite != 0);

                                assertThat(totalWritten).isEqualTo(F3Size);
                                assertThat(gotInterrupt.get()).isTrue();
                            }
                        } finally {
                            if (thread != null) {
                                terminated.set(true);
                                thread.interrupt();
                                thread.join();
                            }

                            os.dispose(t);
                        }
                    } finally {
                        os.dispose(s);
                    }
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    testCopy.delete();
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            severalMegabytes.delete();
        }
    }

    @AfterClass
    public static void cleanup() {
        for (TestSetup setup : setups) {
            setup.cleanup();
        }
    }
}
