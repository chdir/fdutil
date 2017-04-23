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

import android.content.ContentProvider;
import android.net.Uri;
import android.os.CancellationSignal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

/**
 * A wrapper, that integrates arbitrary thread interruption strategies with Java Thread interruption
 * ({@link Thread#interrupt} etc.)
 *
 * Java interruption framework is rather rudimentary — each blocking API method sets up a single
 * thread-local callback before invoking a blocking native call. If the thread gets interrupted
 * during the call, the callback takes care of cancelling the blocking OS operation. When the call
 * ends, the callback is set back to {@code null}. Callback nesting is not supported.
 *
 * This class is probably not what you need unless you are writing a JNI wrapper around existing
 * native API and badly want to integrate it with Thread interruption. For simple, low-overhead
 * way to wake up a thread see {@link Interruption}. If you want to cancel an operation, implemented
 * in library or Android platform, that has it's own cancellation mechanism other than thread interruption
 * (example: {@link ContentProvider#openFile(Uri, String, CancellationSignal)}), using this class is
 * probably not the best idea: the API in question may internally use interruptible platform classes
 * ({@link FileChannel} etc.), which might interfere with you logic. Since the API in question *does not*
 * support Thread interruption in the first place, and the nesting of callbacks is not supported,
 * the result of such interference might be rather unexpected.
 *
 * Waking up a thread, blocking inside InterruptibleStage, does not reset Thread interruption flag.
 * You might have to do so yourself by calling {@link Thread#interrupted}.
 *
 * @see AbstractInterruptibleChannel#begin()
 */
public class InterruptibleStage {
    private static final SelectorProvider placeholder = SelectorProvider.provider();

    private final Sel hack = new Sel();

    // guarded by open/close
    private Runnable callback;

    public void begin(Runnable callback) {
        this.callback = callback;

        hack.customBegin();
    }

    public void end() {
        callback = null;

        hack.customEnd();
    }

    private final class Sel extends AbstractSelector {
        Sel() {
            super(placeholder);
        }

        void customBegin() {
            begin();
        }

        void customEnd() {
            end();
        }

        @Override
        protected void implCloseSelector() throws IOException {
        }

        @Override
        protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<SelectionKey> keys() {
            return Collections.emptySet();
        }

        @Override
        public Set<SelectionKey> selectedKeys() {
            return Collections.emptySet();
        }

        @Override
        public int selectNow() throws IOException {
            return 0;
        }

        @Override
        public int select(long timeout) throws IOException {
            return 0;
        }

        @Override
        public int select() throws IOException {
            return 0;
        }

        @Override
        public Selector wakeup() {
            final Runnable cb = callback;
            if (cb != null) {
                cb.run();
            }

            return this;
        }
    }
}
