package net.sf.fdlib;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Keep;
import android.support.annotation.Nullable;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.ObjectArrayList;
import com.carrotsearch.hppc.ObjectIdentityHashSet;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.procedures.ObjectProcedure;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class InotifyImpl implements Inotify {
    private static final int INOTIFY_OFF_WATCH_DESC = 0;
    private static final int INOTIFY_OFF_MASK = 4;
    private static final int INOTIFY_OFF_RENAME_COOKIE = 8;
    private static final int INOTIFY_OFF_NAME_LEN = 12;
    private static final int INOTIFY_OFF_NAME = 16;

    @Keep
    private static int MASK_IGNORED;

    private static final int NEXT = 0;
    private static final int CANCEL = 1;

    static {
        nativeInit();
    }

    // cache references etc.
    private static native void nativeInit();

    // allocate/release the direct buffer
    private native ByteBuffer nativeCreate();
    private static native void nativeRelease(long bufferPointer);

    private final OS os;

    private volatile boolean done;

    private final IntObjectMap<Watch> subscriptions =  new IntObjectHashMap<>();

    private final @InotifyFd int fd;
    private final ByteBuffer readBuffer;
    private final Looper looper;
    private final Guard guard;

    @Keep
    private long nativePtr;

    // a "channel" created specifically for purpose of using Selector API with inotify descriptors
    private DatagramChannel fake;
    // a bridge to connect the fake "channel" with Android and unix descriptor API
    private ParcelFileDescriptor fakeHolder;

    private SelectionKey selectionKey;

    public InotifyImpl(@InotifyFd int fd, @Nullable Looper looper, OS os, GuardFactory factory) {
        this.fd = fd;

        this.looper = looper != null ? looper : Looper.getMainLooper();

        this.os = os;

        readBuffer = nativeCreate()
                .order(ByteOrder.nativeOrder());

        guard = factory.forMemory(this, nativePtr);
    }

    // bindings for inotify_add_watch/inotify_rm_watch
    protected native int addSubscription(@InotifyFd int fd, int watchedFd) throws IOException;
    protected native void removeSubscription(@InotifyFd int fd, int watchDesc) throws ErrnoException;

    public void removeSubscriptionInternal(int subcription) throws IOException {
        synchronized (this) {
            if (done) {
                return;
            }

            removeSubscription(fd, subcription);
        }
    }

    // a specialized binding for read (2), that can handle EAGAIN/EWOULDBLOCK without throwing
    private static native int read(@InotifyFd int fd, long memAddress, int byteCount) throws ErrnoException;

    private WeakReference<SelectorThread> selector;

    public synchronized void setSelector(SelectorThread selector) throws IOException {
        DebugAsserts.thread(looper, "setSelector");

        primaryLock.lock();
        try {
            if (done) {
                throw new ClosedChannelException();
            }

            if (fake == null) {
                fake = DatagramChannel.open();
                fakeHolder = ParcelFileDescriptor.fromDatagramSocket(fake.socket());
            }

            if (selectionKey != null) {
                final SelectorThread oldSelector = this.selector.get();

                if (oldSelector != null) {
                    oldSelector.unregister(selectionKey);
                }

                selectionKey = null;
            }

            if (selector != null) {
                os.dup2(fd, fakeHolder.getFd());

                // DatagramChannel caches the flag, make sure that it is reset either way
                fake.configureBlocking(true);
                fake.configureBlocking(false);

                selectionKey = selector.register(fake, SelectionKey.OP_READ, this::read);

                this.selector = new WeakReference<>(selector);
            }
        } finally {
            primaryLock.unlock();
        }
    }

    private ObjectIdentityHashSet<Watch> discarded = new ObjectIdentityHashSet<>();
    private ObjectIdentityHashSet<Watch> notified = new ObjectIdentityHashSet<>();

    private final ObjectProcedure<Watch> cleanup = needsCleanup -> {
        needsCleanup.onReset();

        notified.remove(needsCleanup);
    };

    private final ObjectProcedure<Watch> notify = Watch::onChanges;

    private final Lock primaryLock = new ReentrantLock();

    @Override
    public boolean read() {
        if (!primaryLock.tryLock()) {
            return false;
        }

        try {
            if (done) {
                return true;
            }

            int lastRead;

            do {
                lastRead = read(fd, nativePtr, readBuffer.capacity());

                if (lastRead != -1) {
                    readBuffer.limit(lastRead);
                } else {
                    break;
                }

                int currentBufferPosition = 0;

                do {
                    readBuffer.position(currentBufferPosition);

                    final int watchId = readBuffer.getInt(currentBufferPosition + INOTIFY_OFF_WATCH_DESC);

                    if (watchId == -1) {
                        // the event is IN_Q_OVERFLOW, time for everyone to restart their observers
                        sayGoodBye();
                        return true;
                    }

                    Watch sub = subscriptions.get(watchId);
                    if (sub != null) {
                        if ((readBuffer.getInt(currentBufferPosition + INOTIFY_OFF_MASK) & MASK_IGNORED) == MASK_IGNORED) {
                            // the event is IN_IGNORED, basically the same as MASK_OVERFLOW, but only
                            // specific watch descriptor is affected, so we keep going
                            discarded.add(sub);
                        } else {
                            notified.add(sub);
                        }
                    }

                    final int nameLength = readNameLength(currentBufferPosition + INOTIFY_OFF_NAME_LEN);

                    currentBufferPosition = currentBufferPosition + INOTIFY_OFF_NAME + nameLength;
                }
                while (currentBufferPosition != readBuffer.limit());
            }
            while (true);

            discarded.forEach(cleanup);
            notified.forEach(notify);
        } catch (IOException e) {
            final WrappedIOException wrapped = new WrappedIOException(e);

            try {
                close();
            } catch (Throwable closeError) {
                wrapped.addSuppressed(closeError);
            }

            throw wrapped;
        } finally {
            primaryLock.unlock();
            notified.clear();
            discarded.clear();
        }

        return true;
    }

    private void sayGoodBye() throws IOException {
        // clean up observers
        for (ObjectCursor<Watch> observers : subscriptions.values()) {
            observers.value.onReset();
        }

        // flush the rest of inotify queue into the drain
        while (true) {
            if (read(fd, nativePtr, readBuffer.capacity()) == -1) {
                return;
            }
        }
    }

    private int readNameLength(int pos) {
        // ugh, why would they store a name length in uint32 ??
        int nameLength = readBuffer.getInt(pos);

        return (int) (nameLength & 0xFFFFFFFFL);
    }

    @Override
    @SuppressWarnings("SynchronizeOnNonFinalField")
    public synchronized void close() {
        primaryLock.lock();
        try {
            if (!done) {
                done = true;

                guard.close();

                for (ObjectCursor<Watch> observers : subscriptions.values()) {
                    observers.value.onReset();
                }

                if (selectionKey != null && selector != null) {
                    final SelectorThread thread = selector.get();
                    if (thread != null) {
                        thread.unregister(selectionKey);
                    }
                }

                if (fake != null) {
                    try {
                        fake.close();
                    } catch (IOException e) {
                        throw new WrappedIOException(e);
                    }
                }

                // the fd is owned by DatagramSocket and we have already closed it
                // make sure, that ParcelFileDescriptor does not mess anything up
                try {
                    fakeHolder.detachFd();
                    fakeHolder.close();
                } catch (IOException e) {
                    throw new WrappedIOException(e);
                }
            }
        } finally {
            primaryLock.unlock();
        }
    }

    public Watch subscribe(@DirFd int watched, InotifyListener callback) throws IOException {
        DebugAsserts.thread(looper, "subscribe");

        primaryLock.lock();
        try {
            final int watchDescriptor = addSubscription(fd, watched);

            final Watch existing = subscriptions.get(watchDescriptor);

            if (existing != null) {
                final ObjectArrayList<InotifyListener> callbacks = existing.callbacks;

                if (!callbacks.contains(callback)) {
                    callbacks.add(callback);
                }

                return existing;
            }

            final Watch created = new Watch(watchDescriptor, callback);

            subscriptions.put(watchDescriptor, created);

            return created;
        } finally {
            primaryLock.unlock();
        }
    }

    private final class Watch implements InotifyWatch {
        private volatile boolean done;

        private final ObjectArrayList<InotifyListener> callbacks = new ObjectArrayList<>(1);

        private final Handler h;

        private int watchDescriptor;

        Watch(int watchDescriptor, InotifyListener callback) throws IOException {
            this.watchDescriptor = watchDescriptor;

            h = new EventHandler(looper);

            callbacks.add(callback);
        }

        // The synchronization here must be done exactly as it is done below
        // (instead of, say, letting run() do the cleanup), because otherwise a stale watch descriptor
        // may fall victim of reuse (see also https://bugzilla.kernel.org/show_bug.cgi?id=77111)

        @Override
        public void close() {
            primaryLock.lock();
            try {
                if (!done) {
                    done = true;

                    h.removeMessages(CANCEL);

                    dispose();
                }
            } finally {
                primaryLock.unlock();
            }
        }

        void onChanges() {
            Message.obtain(h, NEXT).sendToTarget();
        }

        private void dispose() {
            subscriptions.remove(watchDescriptor);

            try {
                removeSubscription(fd, watchDescriptor);
            } catch (ErrnoException err) {
                // this can mean one of two things: 1) inotify descriptor is closed
                // 2) the watch was already removed implicitly. Either way, the fault is not
                // with us and the cleanup is complete
                LogUtil.logCautiously("Failed to close inotify watch", err);
            }
        }

        void onReset() {
            primaryLock.lock();
            try {
                if (!done) {
                    done = true;

                    dispose();

                    // make sure that it is called at most once
                    Message.obtain(h, CANCEL).sendToTarget();
                }
            } finally {
                primaryLock.unlock();
            }
        }

        private final class EventHandler extends Handler {
            private EventHandler(Looper looper) {
                super(looper);
            }

            @Override
            @SuppressWarnings("SuspiciousSystemArraycopy")
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case NEXT:
                        for (int i = 0; i < callbacks.size(); ++i) {
                            callbacks.get(i).onChanges();
                        }
                        break;
                    case CANCEL:
                        for (int i = 0; i < callbacks.size(); ++i) {
                            callbacks.get(i).onReset();
                        }
                        break;
                }
            }
        }
    }
}
