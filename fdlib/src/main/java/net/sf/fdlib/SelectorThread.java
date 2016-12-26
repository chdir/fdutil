package net.sf.fdlib;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SelectorThread extends Thread implements Closeable {
    private final AtomicBoolean done;

    private final Selector selector;

    private final Lock selectorLock = new ReentrantLock();

    private final Condition epollCleanupComplete = selectorLock.newCondition();

    public SelectorThread() throws IOException {
        done = new AtomicBoolean();

        selector = Selector.open();
    }

    @Override
    @SuppressWarnings({"ConstantConditions", "SynchronizationOnLocalVariableOrMethodParameter"})
    public void run() {
        try (Closeable cleanup = this) {
            final Set<SelectionKey> selected = selector.selectedKeys();

            SelectionKey key;

            while (true) {
                final Iterator<SelectionKey> selectedKeys = selected.iterator();

                while (selectedKeys.hasNext()) {
                    if (done.get()) {
                        return;
                    }

                    key = selectedKeys.next();

                    try {
                        if (key.isValid()) {
                            try {
                                ((Runnable) key.attachment()).run();
                            } catch (Exception e) {
                                LogUtil.logCautiously("Received exception from polled resource", e);
                            }
                        }
                    } finally {
                        selectedKeys.remove();
                    }
                }

                while (true) {
                    int selectedCount;

                    selectedCount = selector.select();

                    // make a window for register()
                    selectorLock.lockInterruptibly();
                    try {
                        // let the selection operation perform 1-stage cleanup of unregistered channels
                        selectedCount += selector.selectNow();

                        // epoll_ctl cleanup is complete, let unregister() know
                        epollCleanupComplete.signalAll();
                    } finally {
                        selectorLock.unlock();
                    }

                    if (selectedCount != 0) {
                        break;
                    }
                }
            }
        } catch (ClosedSelectorException | InterruptedException unused) {
            // done processing
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to close selector", e);
        }
    }

    public void unregister(SelectionKey selectionKey) {
        selectorLock.lock();
        try {
            selectionKey.cancel();

            selector.wakeup();

            epollCleanupComplete.awaitUninterruptibly();
        } finally {
            selectorLock.unlock();
        }
    }

    public SelectionKey register(SelectableChannel channel, int ops, Runnable onReady) throws ClosedChannelException {
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
        if (done.compareAndSet(false, true)) {
            selector.close();

            interrupt();
        }
    }
}
