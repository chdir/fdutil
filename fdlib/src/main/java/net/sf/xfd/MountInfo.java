/*
 * Copyright © 2016 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.xfd;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

public class MountInfo {
    private static final String TAG = "MountInfo";

    private static final ThreadLocal<ByteBuffer> throwawayBuffer = new ThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return ByteBuffer.allocate(1024);
        }
    };

    private final Looper looper = Looper.getMainLooper();
    private final Handler handler = new MountsHandler(looper);
    private final ArrayList<MountChangeListener> listeners = new ArrayList<>();

    private final OS os;
    private final int mountinfo;

    public final LongObjectMap<Mount> mountMap = new LongObjectHashMap<>();
    public final ObjectSet<String> nodev = new ObjectHashSet<>();

    private SocketChannel fake;
    private ParcelFileDescriptor fakeHolder;

    private SelectionKey selectionKey;
    private WeakReference<SelectorThread> selector;

    public MountInfo(OS os, @Fd int mountinfo) throws IOException {
        this.os = os;
        this.mountinfo = mountinfo;

        reparse();
    }

    public Lock getLock() {
        return primaryLock;
    }

    public synchronized void setSelector(SelectorThread selector) throws IOException {
        DebugAsserts.thread(looper, "setSelector");

        primaryLock.lock();
        try {
            if (fake == null) {
                fake = SocketChannel.open();
                fakeHolder = ParcelFileDescriptor.fromSocket(fake.socket());
            }

            if (selectionKey != null) {
                final SelectorThread oldSelector = this.selector.get();

                if (oldSelector != null) {
                    oldSelector.unregister(selectionKey);
                }

                selectionKey = null;
            }

            if (selector != null) {
                os.dup2(mountinfo, fakeHolder.getFd());

                // DatagramChannel caches the flag, make sure that it is reset either way
                fake.configureBlocking(true);
                fake.configureBlocking(false);

                selectionKey = selector.register(fake, SelectionKey.OP_CONNECT, this::reparse);

                this.selector = new WeakReference<>(selector);
            }
        } finally {
            primaryLock.unlock();
        }
    }

    private boolean safeIter = true;

    private void notifyListeners() {
        safeIter = false;
        try {
            for (int i = 0; i < listeners.size(); ++i) {
                listeners.get(i).onMountsChanged();
            }
        } finally {
            safeIter = true;
        }
    }

    public void addMountListener(MountChangeListener listener) {
        DebugAsserts.thread(looper, "addMountListener");

        listeners.add(listener);
    }

    public void removeMountListener(MountChangeListener listener) {
        DebugAsserts.thread(looper, "removeMountListener");

        if (safeIter) {
            listeners.remove(listener);
        } else {
            throw new ConcurrentModificationException("Removing a listener during onMountsChanged() callback is forbidden");
        }
    }

    private static long makedev(long major, long minor) {
        return ((major & 0xfff) << 8) | (minor & 0xff) | ((minor & 0xfff00) << 12);
    }

    private final Lock primaryLock = new ReentrantLock();

    public boolean reparse() {
        if (!primaryLock.tryLock()) {
            return false;
        }

        try {
            LogUtil.logCautiously("reparse() got called");

            mountMap.clear();
            nodev.clear();

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

            parseFilesystems(decoder, true);
            parseMounts(decoder, true);
        } finally {
            primaryLock.unlock();
        }

        handler.sendEmptyMessage(0);

        return true;
    }

    private static final int SANE_SIZE_LIMIT = 200 * 1024 * 1024;

    private int measure(FileChannel fc) throws IOException {
        final ByteBuffer buffer = throwawayBuffer.get();

        int totalRead = 0, lastRead;

        do {
            buffer.clear();

            lastRead = fc.read(buffer);

            totalRead += lastRead;

            if (totalRead > SANE_SIZE_LIMIT) {
                throw new IOException("/proc/ file appears to be too big!!");
            }
        } while (lastRead != -1);

        fc.position(0);

        return totalRead;
    }

    private void parseMounts(CharsetDecoder d, boolean force) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(mountinfo);
             FileChannel fc = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {

            final int totalRead = measure(fc);

            try (Scanner scanner = new Scanner(Channels.newReader(new FileInputStream(pfd.getFileDescriptor()).getChannel(), d, totalRead))) {
                while (scanner.hasNextLine()) {
                    scanner.nextInt();
                    scanner.nextInt();

                    final String[] mm = scanner.next().split(":");

                    final int major = Integer.parseInt(mm[0]);
                    final int minor = Integer.parseInt(mm[1]);

                    final long dev_t = makedev(major, minor);

                    final String source = scanner.next();

                    // ignore bind-mounts for now
                    if ("/".equals(source)) {
                        final String location = scanner.next();

                        // skip optional parts
                        scanner.skip("(.+ -)");

                        final String fsType = scanner.next().intern();

                        final String subject = scanner.next().intern();

                        Mount created = new Mount(dev_t, fsType, location, subject);

                        Mount prev = mountMap.put(dev_t, created);

                        if (prev != null) {
                            created.next = prev;
                        }
                    }

                    scanner.nextLine();
                }

                return;
            } catch (NumberFormatException | NoSuchElementException nse) {
                // oops..
                if (!force) {
                    throw new IOException("Failed to parse mounts list", nse);
                }
            }
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }

        parseMounts(d, false);
    }

    private void parseFilesystems(CharsetDecoder d, boolean force) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File("/proc/filesystems"), MODE_READ_ONLY);
             FileChannel fc = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {

            int totalRead = measure(fc);

            try (Scanner scanner = new Scanner(Channels.newReader(new FileInputStream(pfd.getFileDescriptor()).getChannel(), d, totalRead))) {
                while (scanner.hasNextLine()) {
                    final String firstCol = scanner.next();

                    if ("nodev".equals(firstCol)) {
                        final String secondCol = scanner.next();

                        nodev.add(secondCol.intern());
                    }

                    scanner.nextLine();
                }

                return;
            } catch (NoSuchElementException nse) {
                // oops..
                if (!force) {
                    Log.e(TAG, "Failed to parse fs list", nse);

                    return;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to parse fs list", e);

            return;
        }

        parseFilesystems(d, false);
    }

    public boolean isVolatile(Mount fs) {
        switch (fs.fstype) {
            case "fuse":
                return false;
            default:
                return nodev.contains(fs.fstype);
        }
    }

    public static final class Mount {
        public final long device;
        public final String fstype;
        public final String subject;

        public String rootPath;
        public String description;
        public Mount next;

        public Mount(long device, String fstype, String rootPath, String subject) {
            this.device = device;
            this.fstype = fstype;
            this.rootPath = rootPath;
            this.subject = subject;
        }

        // Note, that we are not even trying to use StorageVolume-based permissions, because
        // those don't currently work for native calls
        public boolean askForPermission(Activity context, int reqId) {
            if (Build.VERSION.SDK_INT >= 24) {
                final StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                final StorageVolume sv = sm.getStorageVolume(new File(rootPath));
                if (sv == null || !Environment.MEDIA_MOUNTED.equals(sv.getState())) {
                    return false;
                }
            }

            if (Build.VERSION.SDK_INT >= 23) {
                if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    context.requestPermissions(new String[] { WRITE_EXTERNAL_STORAGE }, reqId);
                    return true;
                }
            }

            return false;
        }

        @Override
        public String toString() {
            return fstype + ' ' + rootPath + ' ' + subject;
        }
    }

    public interface MountChangeListener {
        void onMountsChanged();
    }

    private final class MountsHandler extends Handler {
        public MountsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            notifyListeners();
        }
    }
}
