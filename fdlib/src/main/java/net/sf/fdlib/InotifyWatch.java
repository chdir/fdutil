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
     * <p/>
     *
     * This method is idempotent, second and following calls have no effect.
     */
    @Override
    @AnyThread
    void close();
}
