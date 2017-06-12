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

import android.support.annotation.Keep;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A wrapper around ByteBuffer, allocated in native memory.
 *
 * Exposes it's pointer for efficient use.
 *
 * The memory, used by this class, is allocated by malloc/memalign, and
 * as such is *not* initialized.
 */
public final class Arena implements Closeable {
    public static final int PAGE_ALIGN = -1;

    private Guard guard;
    private ByteBuffer buffer;
    private long pointer;

    @Keep
    private Arena(long pointer, ByteBuffer buffer, GuardFactory factory) {
        this.pointer = pointer;

        this.guard = factory.forMemory(this, pointer);

        this.buffer = buffer.order(ByteOrder.nativeOrder());
    }

    public long getPtr() {
        return pointer;
    }

    public ByteBuffer getBuf() {
        return buffer;
    }

    @Override
    public void close() {
        guard.close();
    }

    public static native Arena allocate(int size, int alignment, GuardFactory guards) throws OutOfMemoryError;

    static {
        try {
            Android.loadLibraries();
        } catch (IOException ignored) {
        }
    }
}
