package net.sf.fakenames.fddemo;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.ObbScanner;
import android.os.Environment;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import net.sf.xfd.MountInfo;
import net.sf.xfd.OS;
import net.sf.xfd.SelectorThread;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class MountsTests {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private static final OS os;

    private static File obbFile;

    static {
        try {
            os = OS.getInstance();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void extractObb() throws IOException {
        final Context testedCtx = InstrumentationRegistry.getTargetContext();
        final Context myCtx = InstrumentationRegistry.getContext();

        final AssetManager am = myCtx.getAssets();

        final String name = "empty.obb";

        final File obbDir = testedCtx.getObbDir();

        obbFile = new File(obbDir, name);

        try (InputStream is = am.open(name);
             OutputStream os = new FileOutputStream(obbFile))
        {
            byte[] buf = new byte[1024 * 4];

            int read;
            while ((read = is.read(buf)) != -1) {
                os.write(buf, 0, read);
            }
        }
    }

    @Test
    public void basicList() throws IOException {
        MountInfo mi = os.getMounts();

        assertThat(mi.mountMap.size()).isAtLeast(1);
    }

    @Test
    public void reload() throws IOException {
        MountInfo mi = os.getMounts();

        mi.reparse();

        assertThat(mi.mountMap.size()).isAtLeast(1);
    }

    @Test
    public void basicSelectorUse() throws IOException {
        MountInfo mi = os.getMounts();

        try (SelectorThread thread = new SelectorThread()) {
            thread.start();

            instrumentation.runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    try {
                        mi.setSelector(thread);

                        mi.setSelector(null);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });
        }
    }

    volatile boolean gotIt;

    @Test
    public void mountSomeObb() throws IOException {
        gotIt = false;

        MountInfo mi = os.getMounts();

        final Lock lock = mi.getLock();

        final Condition ready = lock.newCondition();

        int oldSize = mi.mountMap.size();

        Context myCtx = instrumentation.getContext();

        StorageManager sm = (StorageManager) myCtx.getSystemService(Context.STORAGE_SERVICE);

        final OnObbStateChangeListener l = new OnObbStateChangeListener() {
            @Override
            public void onObbStateChange(String path, int state) {
                if (state == MOUNTED) {
                    lock.lock();
                    try {
                        mi.reparse();

                        gotIt = true;

                        ready.signalAll();
                    } finally {
                        lock.unlock();
                    }
                } else if (state != UNMOUNTED) {
                    throw new AssertionError("Unexpected state: " + state);
                }
            }
        };

        int newSize = oldSize;

        final String obbPath = obbFile.getAbsolutePath();

        lock.lock();
        try {
            sm.mountObb(obbPath, null, l);
            try {
                while (!gotIt) {
                    ready.awaitUninterruptibly();
                }

                newSize = mi.mountMap.size();
            } finally {
                sm.unmountObb(obbPath, true, l);
            }
        } finally {
            lock.unlock();
        }

        assertThat(newSize).isGreaterThan(oldSize);
    }

    @Test
    public void mountSomeObbWithSelector() throws IOException {
        gotIt = false;

        MountInfo mi = os.getMounts();

        final Lock lock = mi.getLock();

        final Condition ready = lock.newCondition();

        int oldSize = mi.mountMap.size();

        Context myCtx = instrumentation.getContext();

        StorageManager sm = (StorageManager) myCtx.getSystemService(Context.STORAGE_SERVICE);

        final OnObbStateChangeListener l = new OnObbStateChangeListener() {
            @Override
            public void onObbStateChange(String path, int state) {
                if (state != MOUNTED && state != UNMOUNTED) {
                    throw new AssertionError("Unexpected state: " + state);
                }
            }
        };

        try (SelectorThread thread = new SelectorThread()) {
            thread.start();

            instrumentation.runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    try {
                        mi.addMountListener(new MountInfo.MountChangeListener() {
                            @Override
                            public void onMountsChanged() {
                                lock.lock();
                                try {
                                    gotIt = true;

                                    ready.signalAll();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        });

                        mi.setSelector(thread);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });

            int newSize = oldSize;

            final String obbPath = obbFile.getAbsolutePath();

            lock.lock();
            try {
                sm.mountObb(obbPath, null, l);
                try {
                    while (!gotIt) {
                        ready.awaitUninterruptibly();
                    }

                    newSize = mi.mountMap.size();
                } finally {
                    sm.unmountObb(obbPath, true, l);
                }
            } finally {
                lock.unlock();
            }

            instrumentation.runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    try {
                        mi.setSelector(null);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });

            assertThat(newSize).isGreaterThan(oldSize);
        }
    }

    @AfterClass
    public static void deleteObb() {
        //noinspection ResultOfMethodCallIgnored
        obbFile.delete();
    }
}
