package net.sf.fdlib;

import android.support.annotation.AnyThread;

import java.io.Closeable;

/**
 * Represents subscription to changes, related to a file object. When inotify file descriptor is
 * closed, all it's watches are removed.
 */
public interface InotifyWatch extends Closeable {
    /**
     * Cancels the watch and release all resources, associated with it.
     *
     * {@linkplain Inotify.InotifyListener#onReset} Registered listeners}
     * <strong>will not</strong> be called if you remove a watch using this method from the
     * associated Looper Thread.
     *
     * <p/>
     *
     * If you have already closed the originating descriptor, do not call this method —
     * doing so may result in undesirable effect due to watch ID reuse.
     *
     * <p/>
     *
     * This method is idempotent, second and following calls have no effect.
     */
    @Override
    @AnyThread
    void close();
}
