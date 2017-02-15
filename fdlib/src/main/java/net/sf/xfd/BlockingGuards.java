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

import java.io.Closeable;

final class BlockingGuards extends GuardFactory {
    final OS os;

    BlockingGuards(OS os) {
        this.os = os;
    }

    @Override
    public Guard forMemory(Closeable scope, long memoryPointer) {
        return new MemoryGuard(scope, memoryPointer);
    }

    @Override
    public Guard forDescriptor(Closeable scope, int fileDescriptor) {
        return new FdGuard(scope, fileDescriptor);
    }

    private final class FdGuard extends CloseableGuard {
        private final @Fd int fd;

        FdGuard(Closeable r, int fd) {
            super(r);

            this.fd = fd;
        }

        @Override
        protected void trigger() {
            close();
        }

        @Override
        public void close() {
            super.close();

            os.dispose(fd);
        }
    }

    private static final class MemoryGuard extends CloseableGuard {
        private final long pointer;

        MemoryGuard(Closeable r, long pointer) {
            super(r);

            this.pointer = pointer;
        }

        @Override
        protected void trigger() {
            close();
        }

        @Override
        public void close() {
            super.close();

            free(pointer);
        }
    }

    private static native void free(long pointer);
}
