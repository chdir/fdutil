package net.sf.fdlib;

import android.util.Log;

import java.io.Closeable;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public abstract class CloseableGuard extends PhantomReference<Closeable> implements Guard {
    private static final String TAG = "CloseableGuard";

    static {
        new CleanerThread().start();
    }

    private static final ReferenceQueue<Closeable> queue = new ReferenceQueue<>();

    volatile boolean closed;

    protected CloseableGuard(Closeable r) {
        super(r, queue);
    }

    protected abstract void trigger();

    @Override
    public void close() {
        closed = true;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private static final class CleanerThread extends Thread {
        private CleanerThread() {
            super("Ref queue reaper");
        }

        @Override
        public void run() {
            for (;;) {
                try {
                    final CloseableGuard ref = (CloseableGuard) CloseableGuard.queue.remove();

                    if (!ref.closed) {
                        ref.trigger();
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "Interrupted from outside, ignoring");
                }
            }
        }
    }
}
