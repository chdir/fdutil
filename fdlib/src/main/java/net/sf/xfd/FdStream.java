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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Simple thread-safe Channel with support for interruption.
 *
 * This class was written with intent to avoid clumsiness and unnecessary overhead, associated with
 * traditional Channel design. It does not own the underlying descriptor, does not constantly re-assert
 * it's state and does not need excessive synchronization to guard it against multi-threaded access.
 * Yet it is completely thread-safe.
 *
 * Unlike the {@link InterruptibleChannel}, this class does not integrate with Java Thread interruption
 * framework (such integration is trivial to implement), and does not close the underlying descriptor
 * as means of the interruption. Instead the caller is expected to perform interruption in two steps:
 * 1) Provide the {@link Interruption} object to the Channel. 2) Send Linux signal to the thread,
 * performing the IO on Channel, by calling {@link Interruption#interrupt}.
 */
public final class FdStream implements ByteChannel {
    private final @Fd int descriptor;

    public FdStream(@Fd int descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Does nothing
     */
    @Override
    public void close() {
    }

    /**
     * @return always true (the Channel does not have means to determine, when underlying descriptor is closed)
     */
    @Override
    public boolean isOpen() {
        return true;
    }

    /**
     * Read the bytes from descriptor, roughly following {@link ReadableByteChannel#read} contract.
     *
     * This method is not thread-safe.
     *
     * @param dst Destination byte buffer (must be a direct buffer!)
     * @return The number of bytes read, possibly 0. -1 means end of stream.
     * @throws IOException If any IO error happens
     * @throws InterruptedIOException If the call is interrupted (this does not close the channel!)
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        final int position = dst.position();

        final int read = nativeRead(dst, descriptor, position, dst.remaining());

        if (read > 0) {
            dst.position(position + read);
        }

        return read;
    }

    /**
     * Read the bytes to descriptor, roughly following {@link WritableByteChannel#write} contract.
     *
     * @param src Source byte buffer (must be a direct buffer!)
     * @return The number of bytes written, possibly 0.
     * @throws IOException If any IO error happens
     * @throws InterruptedIOException If the call is interrupted (this does not close the channel!)
     */
    @Override
    public int write(ByteBuffer src) throws IOException {
        final int position = src.position();

        final int written = nativeWrite(src, descriptor, position, src.remaining());

        if (written > 0) {
            src.position(position + written);
        }

        return written;
    }

    private static native int nativeRead(ByteBuffer buffer, @Fd int fd, int to, int bytes) throws IOException;

    private static native int nativeWrite(ByteBuffer buffer, @Fd int fd, int from, int bytes) throws IOException;
}
