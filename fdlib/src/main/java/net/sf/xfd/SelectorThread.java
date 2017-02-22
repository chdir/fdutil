/*
 * Copyright © 2017 Alexander Rvachev
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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A basic implementation of selector-based event loop.
 *
 * <p/>
 *
 * It is inadvisable to excessively interrupt this thread — java epoll-based Selector implementation
 * uses a pipe for interruption, which may filled up if {@code select()} isn't called frequently enough.
 */
public class SelectorThread extends Thread implements Closeable {
    private volatile boolean done;

    private final Selector selector;

    private final Lock selectorLock = new ReentrantLock();

    private final Condition epollCleanupComplete = selectorLock.newCondition();

    public SelectorThread() throws IOException {
        super("Selector thread");

        selector = Selector.open();
    }

    private volatile boolean epollCleanupPending;

    @Override
    public void run() {
        try (SelectorThread selfCleanup = this) {
            final Set<SelectionKey> selected = selector.selectedKeys();

            boolean hasPendingKeys;

            while (true) {
                final Iterator<SelectionKey> selectedKeys = selected.iterator();

                hasPendingKeys = false;

                while (selectedKeys.hasNext()) {
                    if (done) {
                        return;
                    }

                    SelectionKey key = selectedKeys.next();

                    boolean removeKey = true;
                    try {
                        if (key.isValid()) {
                            try {
                                Task attachment = (Task) key.attachment();
                                try {
                                    if (attachment != null) {
                                        removeKey = attachment.run();
                                    }
                                } finally {
                                    attachment = null;
                                }
                            } catch (Exception e) {
                                LogUtil.logCautiously("Received exception from polled resource", e);
                            }
                        }
                    } finally {
                        if (removeKey) {
                            selectedKeys.remove();
                        } else {
                            hasPendingKeys = true;
                        }
                    }
                }

                while (true) {
                    try {
                        int selectedCount = 0;

                        // make a window for register()
                        selectorLock.lock();
                        try {
                            if (done) {
                                return;
                            }

                            // let the selection operation perform 1st-stage cleanup of unregistered channels
                            selectedCount = cleanupNow();
                        } finally {
                            selectorLock.unlock();
                        }

                        if (hasPendingKeys || selectedCount != 0) {
                            break;
                        }

                        if (selector.select() != 0) {
                            break;
                        }
                    } catch (IOException ioe) {
                        // as explained in http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4619326,
                        // sometimes a pipe buffer may overflow and prevent select() from working.
                        // Or the descriptor may be closed. Or something. Either way, we are in no
                        // condition to retry from there.
                        LogUtil.logCautiously("Received exception from epoll", ioe);

                        return;
                    } catch (CancelledKeyException e) {
                        // ignore, that's a normal operation mode for this crappy API
                        LogUtil.logCautiously("Cancelled", e);
                    }
                }
            }
        } catch (ClosedSelectorException unused) {
            // done processing
            LogUtil.logCautiously("Selector closed", unused);
        } catch (IOException ioe) {
            LogUtil.logCautiously("Failed to close selector", ioe);
        }
    }

    private int cleanupNow() throws IOException {
        final int selected = selector.selectNow();

        if (epollCleanupPending) {
            epollCleanupPending = false;
        }

        // epoll_ctl cleanup is complete, let unregister() know
        epollCleanupComplete.signalAll();

        return selected;
    }

    /**
     * You must use this method instead of directly calling {@link SelectionKey#cancel} if you intend
     * to reuse the selector or perform any further manipulations with file descriptor of channel (such
     * as closing it, using as target of {@code dup2} etc.) In nutshell, never use {@code cancel()},
     * always call this method instead.
     *
     * <p/>
     *
     * By the time it returns, this method have ensured, that a file descriptor, associated with specified
     * key, have had been unregistered from {@code epoll} and removed from internal bookkeeping structures
     * of the selector.
     */
    public void unregister(SelectionKey selectionKey) {
        // remove the attachment: this won't actually cancel the key, but at least
        // most problematic memory leaks will be squashed
        selectionKey.attach(null);

        selectorLock.lock();
        try {
            if (!selector.isOpen()) {
                return;
            }

            epollCleanupPending = true;

            selectionKey.cancel();

            selector.wakeup();

            while (epollCleanupPending) {
                epollCleanupComplete.awaitUninterruptibly();
            }
        } finally {
            selectorLock.unlock();
        }
    }

    /**
     * Register a channel with selector. Unlike {@link SelectableChannel#register}, this method
     * does not block.
     *
     * @throws ClosedChannelException if the channel has already been closed by the time of call
     */
    public SelectionKey register(SelectableChannel channel, int ops, Task onReady) throws ClosedChannelException {
        selectorLock.lock();
        try {
            selector.wakeup();

            return channel.register(selector, ops, onReady);
        } finally {
            selectorLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        selectorLock.lock();
        try {
            if (!done) {
                done = true;

                if (!selector.isOpen()) {
                    return;
                }

                epollCleanupPending = true;

                selector.wakeup();

                final Set<SelectionKey> keys = selector.keys();
                if (!keys.isEmpty()) {
                    for (SelectionKey key : keys) {
                        key.cancel();
                    }

                    cleanupNow();
                }

                selector.close();
            }
        } finally {
            selectorLock.unlock();
        }
    }

    public interface Task {
        boolean run();
    }
}
