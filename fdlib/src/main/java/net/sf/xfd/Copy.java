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

import android.support.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * A wrapper around some intermediate state, used for copying data between file descriptors (mainly
 * an immovable, page-aligned memory buffer). It attempts to use appropriate system calls depending
 * on type of file descriptor, and might be more efficient then naive copy-loops.
 *
 * <p/>
 *
 * This class does not use implicit thread-local buffers and can be reused between multiple
 * copy operations.
 *
 * <p/>
 *
 * This class is not thread-safe.
 */
public interface Copy extends Closeable {
    /**
     * Copy up to {@code count} bytes from the current file offset of {@code source} to the
     * current file offset of {@code target} (both offsets are adjusted appropriately). The method
     * attempts to fully transfer exact amount of data, but may copy less if enf of stream on
     * source descriptor is reached.
     *
     * <p/>
     *
     * Both descriptors must be in blocking mode. Attempting to use this method with non-blocking
     * descriptors might fail with exception (due to {@code EAGAIN}) or result in surprising
     * behavior (see description of {@code SPLICE_F_NONBLOCK} for details).
     *
     * <p/>
     *
     * The exact approach to copying depends on types of the descriptors as specified by
     * {@code sourceStat} and {@code targetStat}, — currently supported are {@code splice}
     * and {@code sendfile} with fallback to read/write loop. Some filesystems and file descriptors
     * are incompatible with efficient copy modes (for example, FAT/FUSE might not work with
     * {@code splice}, certain Linux versions do not support using sendfile with files open with
     * O_APPEND flag etc.) In such cases this method will silently fall back to read/write loop.
     *
     * @param source source file descriptor (must be blocking)
     * @param sourceStat (optional) structure with information about source file descriptor
     * @param target target file descriptor (must be blocking)
     * @param targetStat (optional) structure with information about target file descriptor
     * @param count total count of bytes to write (if zero or negative, {@link Long#MAX_VALUE} will be used)
     *
     * @return the number of bytes actually copied to {@code target} before reaching EOF
     *
     * @throws InterruptedIOException if thee calling thread is interrupted before completion
     * @throws ErrnoException if an OS error happens
     * @throws IOException if another IO error occurs
     */
    long transfer(@Fd int source, @Nullable Stat sourceStat, @Fd int target, @Nullable Stat targetStat, long count) throws IOException;

    /**
     * Release resources, associated with this wrapper.
     *
     * Do not call this method when a copy is in progress! To interrupt the ongoing copy,
     * {@linkplain Thread#interrupt interrupt} the Thread, that performs it.
     *
     * <p/>
     *
     * This method is idempotent, second and following calls have no effect.
     */
    @Override
    void close();
}
