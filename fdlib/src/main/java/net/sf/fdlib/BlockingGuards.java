package net.sf.fdlib;

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
