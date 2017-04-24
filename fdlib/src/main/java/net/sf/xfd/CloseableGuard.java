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

import android.support.annotation.CallSuper;
import android.util.Log;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public abstract class CloseableGuard<T> extends PhantomReference<T> implements Guard {
    private static final String TAG = "CloseableGuard";

    static {
        new CleanerThread().start();
    }

    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    static private CloseableGuard first = null;

    private CloseableGuard next, prev;

    private static synchronized void add(CloseableGuard cl) {
        if (first != null) {
            cl.next = first;

            first.prev = cl;
        }

        first = cl;
    }

    private static synchronized boolean remove(CloseableGuard cl) {
        // If already removed, do nothing
        if (cl.next == cl) return false;

        cl.clear();

        // Update list
        if (first == cl) {
            if (cl.next != null)
            first = cl.next;
             else
            first = cl.prev;
        }

        if (cl.next != null) cl.next.prev = cl.prev;

        if (cl.prev != null) cl.prev.next = cl.next;

        // Indicate removal by pointing the cleaner to itself

        cl.next = cl;
        cl.prev = cl;

        return true;
    }

    protected volatile boolean closed;

    protected CloseableGuard(T r) {
        super(r, queue);

        add(this);
    }

    protected abstract void trigger();

    @Override
    @CallSuper
    public void close() {
        remove(this);
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
                    final CloseableGuard<?> ref = (CloseableGuard) CloseableGuard.queue.remove();

                    if (remove(ref)) {
                        ref.trigger();
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "Interrupted from outside, ignoring");
                }
            }
        }
    }
}
