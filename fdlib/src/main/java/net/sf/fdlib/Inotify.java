package net.sf.fdlib;

import android.Manifest;
import android.support.annotation.AnyThread;
import android.support.annotation.CheckResult;
import android.support.annotation.MainThread;
import android.support.annotation.RequiresPermission;
import android.support.annotation.WorkerThread;

import java.io.Closeable;
import java.io.IOException;

/**
 * A wrapper for Linux inotify API. This class takes care of parsing inotify event structures and
 * dispatching callbacks whenever an event occurs. See {@link InotifyListener} for description
 * of supported events.
 *
 * <p/>
 *
 * Thus class can be used in two modes: 1) direct reading from inotify descriptor using thread of
 * your choice and 2) monitoring inotify events from shared {@link SelectorThread}. To implement
 * the first mode simply call {@link #run} in a loop (depending on properties of inotify descriptor,
 * that may block until new events are ready or return immediately if no new events exist).
 * To use this class in second mode start a {@link SelectorThread} and pass it to {@link #setSelector}.
 *
 * <p/>
 *
 * Each instance of this class corresponds to a single file descriptor, returned from
 * {@link OS#inotify_init}, which can be used to monitor multiple filesystem objects. There
 * are limits to maximum number of inotify file descriptors (independent from limit on total count
 * of file descriptors) and total number of filesystem objects, monitored by application process
 * at given time. "Filesystem objects" aren't the same thing as "file descriptors" or "file paths":
 * multiple file paths, referring to the same file (e.g. hardlinks) correspond to the same
 * filesystem object (inode). <strong>The inode is not deleted until all hard links to it are deleted
 * and all open descriptors are closed.</strong> In practice this means, that maintaining a smooth
 * user experience when using inotify may require a number of precautions. Files should be monitored
 * both for deletion and changes of reference count (which happens when a file is "deleted" while
 * other hard links to it exist). More importantly, both files and directories should not be kept
 * perpetually open or else their reference count will never drop below 1.
 *
 * <p/>
 *
 * Only {@link #run} and {@link #close} are thread-safe: {@link #subscribe} and {@link #setSelector}
 * must be called from the same Looper thread as one used to dispatch {@link InotifyListener}.
 */
public interface Inotify extends Closeable, Runnable {
    /**
     * Linux inotify descriptors support {@code epoll} — a mechanism, that allows to efficiently
     * monitor multiple file descriptors for new data without creating dedicated a thread for each.
     * To use it with this class, create and start a SelectorThread. If you pass the
     * Selector to this method, you don't have to call {@link #run} yourself.
     *
     * <p/>
     *
     * Requires Internet permission due to the way Android Selector API works.
     *
     * @param selector a shared Thread, used to poll all inotify descriptors, or {@code null} to stop the polling
     */
    @MainThread
    @RequiresPermission(Manifest.permission.INTERNET)
    void setSelector(SelectorThread selector) throws IOException;

    /**
     * Read next batch of events from inotify descriptor until no more remain.
     * This method must be called in loop to continuously monitor for new inotify events.
     * Alternatively, you can pass a pre-configured {@link SelectorThread} to {@link #setSelector}
     * and let it take care of monitoring.
     */
    @Override
    @WorkerThread
    void run();

    /**
     * Subscribe to changes in filesystem object, associated with specified file descriptor.
     * If the active subscription to that object already exists, the callback will be added to set
     * of callbacks and that subscription will be returned instead.
     *
     * <p/>
     *
     * The strong reference to the callback is stored until destruction of watch, so beware of leaks.
     *
     * @return the subscription for inode, corresponding to specified {@code fd}
     *
     * @throws IOException if process exceeds /proc/sys/fs/inotify/max_user_watches or other IO error happens
     */
    @MainThread
    @CheckResult
    InotifyWatch subscribe(@Fd int fd, InotifyListener callback) throws IOException;

    /**
     * Release resources, associated with this wrapper (including all registered instances of
     * {@link InotifyWatch}). The underlying inotify descriptor is unaffected by this call
     * and must be closed separately.
     *
     * <p/>
     *
     * This method is idempotent, second and following calls have no effect.
     */
    @Override
    @AnyThread
    void close();

    /**
     * A listener for inotify events. Two kinds of events are recognized: "change" and "reset".
     * Multiple events of the same type may be merged together and events prior to reset may be skipped.
     */
    interface InotifyListener {
        /**
         * Notifies of changes, related to renaming, creation and deletion of files. This callback
         * corresponds to IN_CREATE, IN_DELETE, IN_MOVED_FROM and IN_MOVED_TO events of native
         * {@code inotify (7)} API.
         */
        void onChanges();

        /**
         * Notifies of events, that invalidate state of inotify event stream. This callback
         * corresponds to IN_IGNORED and IN_Q_OVERFLOW events of native {@code inotify (7)} API.
         *
         * Examples of events: the containing partition being unmounted, the observed
         * file/directory being deleted etc. In addition this method is called when
         * inotify queue overrun happens (which may imply that some of above-listed events
         * have been silently skipped by kernel).
         *
         * <p/>
         *
         * <strong>
         *     The watch is unregistered prior, during or shortly after calling this method.
         *     You must assert current state of monitored object and optionally re-register the
         *     watch if you intend to continue monitoring it.
         * </strong>
         */
        void onReset();
    }
}
