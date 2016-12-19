package net.sf.fdlib;

import android.support.annotation.Keep;
import android.support.annotation.NonNull;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongIndexedContainer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.NoSuchElementException;

import static net.sf.fdlib.DebugAsserts.failIf;

final class DirectoryImpl implements Directory {
    // "Maximum" length of filename
    // As of 2016 ext3/ext4 limit name lengths to 256 bytes
    // VFAT offers 255 2-byte UCS-2 units instead
    // Other filesystems may offer more, but it is still handy to have a reference size
    public static final int FILENAME_MAX = 255;

    // There are at least two files in each directory ('.' and '..'), and most dirs aren't empty
    private static final int DEFAULT_EXPECTED_ELEMENTS = 10;

    // filesystem inode number — uniquely identifies all files in Linux
    // size: 64 bit opaque (can be stored in long)
    private static final int GETDENTS_OFF_INO = 0;

    // opaque cookie for use during seeking
    // size: 64 bit opaque (can be stored in long)
    private static final int GETDENTS_OFF_NEXT = 8;

    // length of the current entry in bytes
    // size: 16 bit unsigned (can be stored in char)
    private static final int GETDENTS_OFF_LENGTH = 16;

    // file type, optional
    // size: 8 bit unsigned (same size as byte, can be stored in short)
    private static final int GETDENTS_OFF_TYPE = 18;

    private static final int GETDENTS_OFF_NAME = 19;

    private final FileNameDecoder nameDecoder = new FileNameDecoder();

    // Cache for opaque directory "offset" cookies; allows listing the dir contents backwards
    final LongIndexedContainer cookieCache = new LongArrayList(DEFAULT_EXPECTED_ELEMENTS);

    // cache references etc.
    private static native void nativeInit();

    // allocate/release the initial direct buffer
    private native ByteBuffer nativeCreate(int fd);
    private static native void nativeRelease(long bufferPointer);

    static {
        nativeInit();
    }

    private DirectoryIterator iterator;

    private int fd;
    private ByteBuffer byteBuffer;
    private byte[] nameBytes;

    @Keep
    @SuppressWarnings({"UnusedDeclaration"})
    private long nativePtr;

    DirectoryImpl(int fd) {
        this.fd = fd;

        nameBytes = new byte[FILENAME_MAX * 2 + 1];

        byteBuffer = nativeCreate(fd)
                .order(ByteOrder.nativeOrder());

        byteBuffer.limit(0);

        cookieCache.add(0L);
    }

    // seek to specified opaque "position"
    // returns new position on success
    private static native long seekTo(int dirFd, long cookie) throws IOException;

    // seek to the start of directory (zeroth item)
    // should never fail because Linux directories have two items at minimal
    private static native void rewind(int dirFd) throws IOException;

    // read next dirent value into the buffer
    // returns count of bytes read (e.g. total size of all dirent structures read) or 0 on reaching end
    private static native int nativeReadNext(int dirFd, long nativeBufferPtr, int capacity) throws IOException;

    // retrieve string bytes from byte buffer
    // returns number of bytes written (terminator byte is not written/counted)
    private native int nativeGetStringBytes(long entryPtr, byte[] reuse, int arrSize);

    FsType getFileType() {
        final byte rawType = byteBuffer.get(byteBuffer.position() + GETDENTS_OFF_TYPE);

        return FsType.forDirentType((short) (rawType & 0xFF));
    }

    String getName() {
        final int strLengthBytes = nativeGetStringBytes(nativePtr + byteBuffer.position(), nameBytes, nameBytes.length);

        return nameDecoder.fromUtf8Bytes(nameBytes, 0, strLengthBytes);
    }

    /**
     * Return the iterator over the directory contents. This method always returns the same
     * instance, which like the whole class, is not safe to use from multiple threads without
     * explicit synchronization.
     */
    @Override
    public DirectoryIterator iterator() {
        if (iterator != null) {
            try {
                iterator.moveToPosition(-1);

                return iterator;
            } catch (IOException e) {
                throw new WrappedIOException(e);
            }
        }

        return iterator = new DirectoryIterator();
    }

    @Override
    public long getOpaqueIndex(int position) {
        return position < cookieCache.size() ? cookieCache.get(position) : -1L;
    }

    @Override
    public void close() {
        nativeRelease(nativePtr);
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Directory clone() {
        try {
            return OS.getInstance().list(fd);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    final class DirectoryIterator implements UnreliableIterator<Entry> {
        // Global position within directory contents.
        // It is not guaranteed to be stable across directory content changes,
        // because filesystems like ext4 order files by filename hash.
        private int position = -1;

        // Whether we have reached the last block during last advancement
        private int lastPosition = -2;

        // allows quickly backtracking within current buffer contents
        private int bufferStart = -1;

        @Override
        public boolean moveToFirst() throws IOException {
            switch (position) {
                case 0:
                    return true;
                case -1:
                    return advanceToFirst();
                default:
                    return advanceBackward(0);
            }
        }

        /**
         * Position the iterator window and/or internal buffer at the beginning of corresponding
         * {@code kernel_dirent} structure
         */
        @Override
        public boolean moveToPosition(int target) throws IOException {
            if (target < -1) {
                throw new IllegalArgumentException("position must be > -1");
            }

            if (target == -1) {
                return resetIterator();
            }

            final int initial = this.position;

            if (target == initial) {
                return true;
            }

            if (target == 0) {
                return moveToFirst();
            }

            if (target < initial) {
                return advanceBackward(target);
            } else {
                if (initial == -1) {
                    if (!advanceToFirst()) {
                        return false;
                    }
                }

                final int reached = advanceForward(target);

                this.position = reached;

                return reached == target;
            }
        }

        // moves to -1
        private boolean resetIterator() throws IOException {
            rewind(fd);

            reset();

            position = -1;

            cookieCache.release();
            cookieCache.add(0L);

            return true;
        }

        // moves from -1 to 0
        private boolean advanceToFirst() throws IOException {
            if (!readNext()) {
                return false;
            }

            position = 0;
            bufferStart = 0;

            return true;
        }

        // Attempt to read the next directory entry into the buffer.
        // Sets the buffer byte position and local index position to 0.
        // If the current entry is the  last one, returns false, otherwise sets buffer limit to
        // the number of bytes read (e.g. size of the new entry) and returns true.
        private boolean readNext() throws IOException {
            int bytesRead = nativeReadNext(fd, nativePtr, byteBuffer.capacity());

            if (bytesRead == 0) {
                return false;
            }

            byteBuffer.position(0);

            byteBuffer.limit(bytesRead);

            lastPosition = -2;

            return true;
        }

        private void reset() {
            byteBuffer.rewind();
            byteBuffer.limit(0);
            lastPosition = -2;
            bufferStart = -1;
        }

        private boolean seek(long cookie) throws IOException {
            // check if this is our placeholder for removed elements
            if (cookie == -1L) {
                return false;
            }

            final long newPosition = seekTo(fd, cookie);

            if (newPosition == cookie) {
                reset();

                return true;
            }

            return false;
        }

        // Position the buffer at specified logical position by seeking the file descriptor,
        // given that it was previously encountered in directory "stream"

        // target < position, position != -1
        private boolean advanceBackward(int target) throws IOException {
            if (bufferStart == -1) {
                // happens in extremely rare case when seeking succeeds, but readNext does not
                // the IOException gets thrown, and then you try to call moveToPosition again...
                throw new IOException("Buffer state inconsistent, reset it first!");
            }

            if (target >= bufferStart) {
                // already contained in buffer, no need for directory file seeking
                byteBuffer.rewind();

                position = bufferStart;

                position = advanceForward(target);

                return position == target;
            }

            final int lastCached = cookieCache.size() - 1;

            failIf(target > lastCached, "cursor state desynchronized");

            final long targetPosCookie = cookieCache.get(target);

            if (seek(targetPosCookie) && readNext()) {
                position = bufferStart = target;

                return true;
            }

            throw new IOException("Directory contents changed");
        }

        // Position a buffer at specified logical position by repeatedly moving forward,
        // given that it is behind that position right now

        // Implementation note: if the current entry is last in directory, the pointer to next
        // entry will contain some garbage (for example, ext4 fills it with same cookie as for
        // current position), furthermore, if the entry is the last in buffer and advancement races
        // with file deletion/creation, the contents may be incorrect anyway (but that's of little
        // concern, because

        // target > position, position != -1
        private int advanceForward(long target) throws IOException {
            int curBufPosBytes = byteBuffer.position();
            int curBufferLimit = byteBuffer.limit();

            int entryLength;
            int currentPosition;
            long nextCookie;

            for (currentPosition = position; currentPosition < target; ++currentPosition) {
                nextCookie = byteBuffer.getLong(curBufPosBytes + GETDENTS_OFF_NEXT);
                entryLength = byteBuffer.getChar(curBufPosBytes + GETDENTS_OFF_LENGTH);
                curBufPosBytes = curBufPosBytes + entryLength;

                final int lastCached = cookieCache.size() - 1;
                final boolean reachedHead = currentPosition == lastCached;

                if (curBufPosBytes == curBufferLimit) {
                    if (nextCookie == 0 || nextCookie == -1) {
                        // invalid cookie, likely end of the stream

                        lastPosition = currentPosition;

                        return currentPosition;
                    }

                    if (reachedHead && nextCookie == cookieCache.get(lastCached)) {
                        // We are at the end of the stream and next cookie is a duplicate (!).
                        // Bail without changing position to avoid adding the duplicate to list.

                        lastPosition = currentPosition;

                        return currentPosition;
                    }

                    if (!readNext()) {
                        lastPosition = currentPosition;

                        return currentPosition;
                    }

                    if (reachedHead) {
                        cookieCache.add(nextCookie);
                    }

                    curBufPosBytes = 0;
                    bufferStart = currentPosition + 1;
                    curBufferLimit = byteBuffer.limit();
                } else {
                    // Check if the next entry have never been encountered before.
                    if (reachedHead) {
                        // it wasn't, cache it
                        cookieCache.add(nextCookie);
                    } else {
                        failIf(currentPosition > lastCached, "overrun detected");

                        // it was, check if cached value matches the one reported by getdents
                        final long cached = cookieCache.get(currentPosition + 1);

                        if (cached != nextCookie) {
                            // does this position still exist in underlying directory?
                            if (seek(cached) && readNext()) {
                                curBufPosBytes = 0;
                                bufferStart = currentPosition + 1;
                                curBufferLimit = byteBuffer.limit();

                                continue;
                            }

                            // toto: recover if seeking does not change position
                            throw new IOException("Directory contents changed");
                        }
                    }

                    byteBuffer.position(curBufPosBytes);
                }
            }

            return currentPosition;
        }

        @Override
        public int getPosition() {
            return position;
        }

        @Override
        public boolean moveToNext() throws IOException {
            return position == -1 ? advanceToFirst() : stepForward();
        }

        private boolean stepForward() throws IOException {
            final int target = position + 1;

            final boolean advanced = advanceForward(target) == target;

            if (advanced) {
                ++this.position;
            }

            return advanced;
        }

        @Override
        public boolean moveToPrevious() throws IOException {
            if (position == -1) {
                return false;
            }

            if (position == 0) {
                return resetIterator();
            }

            return advanceBackward(position - 1);
        }

        @Override
        public void get(@NonNull Entry reuse) {
            if (position == -1) {
                throw new IllegalStateException("Attempting to get element at position -1");
            }

            reuse.ino = byteBuffer.getLong(byteBuffer.position() + GETDENTS_OFF_INO);
            reuse.type = getFileType();
            reuse.name = getName();
        }

        /**
         * @return true if the end of directory stream was reached during last advancement, false otherwise
         */
        public boolean hasReachedEnd() {
            return lastPosition == position;
        }

        /**
         * Whether the iterator can be moved to next entry.
         *
         * Calling this method will not advance the position.
         *
         * This method hides possibility of IO errors due to inherent races. Never use it
         * for directories you don't singularly control.
         *
         * @return always true, the position is -1, otherwise true if can advanced
         *
         * @see {@link #next}
         */
        @Override
        public boolean hasNext() {
            return !hasReachedEnd();
        }

        /**
         * Advance to the next position and retrieve the element.
         *
         * If the position is -1, this method will advance to first element before doing steps above.
         *
         * This method hides possibility of IO errors due to inherent races. Never use it
         * for directories you don't singularly control.
         *
         * If you don't
         *
         * @return element at the *current* position (as specified by {@link #getPosition} at the
         * time of calling this method)
         *
         * @throws WrappedIOException if an IO error occurs
         * @throws NoSuchElementException
         */
        @NonNull
        @Override
        public Entry next() throws WrappedIOException {
            try {
                final int currentPosition = position;

                if (currentPosition == -1) {
                    if (!advanceToFirst()) {
                        throw new WrappedIOException(new IOException("The directory is empty"));
                    }
                } else if (lastPosition == currentPosition) {
                    throw new NoSuchElementException("position = " + position);
                }

                final Entry newEntry = new Entry();

                get(newEntry);

                moveToNext();

                return newEntry;
            } catch (IOException e) {
                throw new WrappedIOException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("getdents does not support removal, use unlink");
        }

        @Override
        public String toString() {
            return "DirectoryIterator[" +
                    "buffer at " + bufferStart + ":" + byteBuffer + "; " +
                    "overall position:" + position +
                    "]";
        }

        int getFd() {
            return fd;
        }

        int bufferPosition() {
            return byteBuffer.position();
        }

        long address() {
            return nativePtr;
        }
    }
}
