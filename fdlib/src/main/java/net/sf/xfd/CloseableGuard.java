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

import java.io.Closeable;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public abstract class CloseableGuard<T> extends PhantomReference<T> implements Guard {
    private static final String TAG = "CloseableGuard";

    static {
        new CleanerThread().start();
    }

    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    protected volatile boolean closed;

    protected CloseableGuard(T r) {
        super(r, queue);
    }

    protected abstract void trigger();

    @Override
    @CallSuper
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
                    final CloseableGuard<?> ref = (CloseableGuard) CloseableGuard.queue.remove();

                    if (!ref.closed) {
                        ref.trigger();
                    }

                    ref.clear();
                } catch (InterruptedException e) {
                    Log.i(TAG, "Interrupted from outside, ignoring");
                }
            }
        }
    }
}
