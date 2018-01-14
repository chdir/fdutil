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

import android.os.Build;
import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.sf.fakenames.fdlib.BuildConfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static net.sf.xfd.NativeBits.*;

final class Android extends OS {
    Android() {}

    private static volatile Android instance;

    public static Android getInstance() throws IOException {
        if (instance == null) {
            loadLibraries();

            instance = new Android();
        }

        return instance;
    }

    private static volatile boolean loaded;

    static void loadLibraries() throws IOException {
        if (!loaded) {
            synchronized (Android.class) {
                if (!loaded) {
                    try {
                        System.loadLibrary("coreio-" + BuildConfig.NATIVE_VER);

                        loaded = true;
                    } catch (UnsatisfiedLinkError loadLibraryFailed) {
                        throw new IOException("Failed to load native library", loadLibraryFailed);
                    }
                }
            }
        }
    }

    @Override
    @CheckResult
    public int creat(@NonNull CharSequence path, int mode) throws IOException {
        return blockingOpen(path, DirFd.NIL, O_CREAT | O_RDWR | O_TRUNC, mode);
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @Fd int open(@NonNull CharSequence path, int flags, int mode) throws IOException {
        return openat(DirFd.AT_FDCWD, path, flags, mode);
    }

    @Override
    public @Fd int openat(@DirFd int fd, @NonNull CharSequence pathname, int flags, int mode) throws IOException {
        return blockingOpen(pathname, fd, flags, mode);
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @DirFd int opendir(@NonNull CharSequence path) throws IOException {
        return opendirat(DirFd.AT_FDCWD, path);
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @DirFd int opendirat(@DirFd int fd, @NonNull CharSequence name) throws IOException {
        return blockingOpen(name, fd, O_NOCTTY | O_DIRECTORY, 0);
    }

    @NonNull
    @Override
    public CharSequence readlinkat(@DirFd int fd, @NonNull CharSequence pathname) throws IOException {
        Object p = prepare(pathname);

        return new NativeString(nativeReadlink(toNative(p), length(p), fd));
    }

    @Override
    public void linkat(@DirFd int oldDirFd, @NonNull CharSequence oldName, @DirFd int newDirFd, @NonNull CharSequence newName, @LinkAtFlags int flags) throws IOException {
        Object o1 = prepare(oldName);
        Object o2 = prepare(newName);

        nativeLinkAt(toNative(o1), toNative(o2), length(o1), length(o2), oldDirFd, newDirFd, flags);
    }

    @Override
    public void unlinkat(@DirFd int target, @NonNull CharSequence name, @UnlinkAtFlags int flags) throws IOException {
        Object o = prepare(name);

        nativeUnlinkAt(toNative(o), length(o), target, flags);
    }

    @Override
    public void symlinkat(@NonNull CharSequence name, @DirFd int target, @NonNull CharSequence newpath) throws IOException {
        Object n1 = prepare(name);
        Object n2 = prepare(newpath);

        nativeSymlinkAt(toNative(n1), toNative(n2), length(n1), length(n2), target);
    }

    @Override
    public void mknodat(@DirFd int target, @NonNull CharSequence name, @FileTypeFlag int mode, int device) throws IOException {
        Object o = prepare(name);

        nativeMknodAt(toNative(o), length(o), target, mode, device);
    }

    @Override
    public boolean mkdirat(@DirFd int target, @NonNull CharSequence name, int mode) throws IOException {
        Object n = prepare(name);

        return nativeMkdirAt(toNative(n), length(n), target, mode);
    }

    @Override
    public boolean faccessat(int fd, @NonNull CharSequence pathname, int mode) throws IOException {
        Object p = prepare(pathname);

        return nativeFaccessAt(toNative(p), length(p), fd, mode);
    }

    @Override
    public void fstatat(int dir, @NonNull CharSequence pathname, @NonNull Stat stat, int flags) throws IOException {
        Object p = prepare(pathname);

        nativeFstatAt(toNative(p), stat, length(p), dir, flags);
    }

    @Override
    public void renameat(@DirFd int fd, CharSequence name, @DirFd int fd2, CharSequence name2) throws IOException {
        Object n1 = prepare(name);
        Object n2 = prepare(name2);

        nativeRenameAt(toNative(n1), toNative(n2), length(n1), length(n2), fd, fd2);
    }

    @Override
    public void dispose(int fd) {
        try {
            close(fd);
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }
    }

    /**
     * Create a wrapper around directory descriptor for convenient access.
     *
     * This method should never throw.
     *
     * Created wrapper won't own the descriptor, you have to close it separately.
     *
     * This method assumes, that received descriptor is positioned at zero offset (as all freshly
     * open descriptors are). If that isn't the case, you should rewind the underlying descriptor
     * to achieve reasonable behavior (you can always do so by calling {@code moveToPosition(-1)}).
     *
     * @param fd the directory descriptor
     *
     * @return a new instance of wrapper class
     */
    @NonNull
    @Override
    public Directory list(@DirFd int fd) {
        return list(fd, 0);
    }

    /**
     * Create a wrapper around directory descriptor for convenient access.
     *
     * This method should never throw.
     *
     * Created wrapper won't own the descriptor, you have to close it separately.
     *
     * This method assumes, that received descriptor is positioned at zero offset (as all freshly
     * open descriptors are). If that isn't the case, you should rewind the underlying descriptor
     * to achieve reasonable behavior (you can always do so by calling {@code moveToPosition(-1)}).
     *
     * @param fd the directory descriptor
     * @param flags see {@link Directory} for supported flags
     *
     * @return a new instance of wrapper class
     */
    @NonNull
    @Override
    public Directory list(@DirFd int fd, int flags) {
        int size, alignment;

        if ((flags & Directory.READDIR_SMALL_BUFFERS) == 0) {
            // If https://serverfault.com/a/9548 is to be trusted, the biggest filename length
            // in Linux as of 2026 is 1020 bytes (VFAT UCS-2 filenames)
            int MIN_BUF_SIZE = 1024 * 4;

            // Let's go with Binder's favorite size and use 1Mb as upper bound of buffer size
            int MAX_BUF_SIZE = 1024 * 1024;

            size = MIN_BUF_SIZE;
            alignment = Arena.PAGE_ALIGN;

            final Stat dirStat = new Stat();

            try {
                fstat(fd, dirStat);

                if (dirStat.type != null && dirStat.type != FsType.DIRECTORY) {
                    throw new IllegalArgumentException("Expected directory, but got " + dirStat.type);
                }

                size = dirStat.st_blksize <= 0
                        ? MIN_BUF_SIZE
                        : (dirStat.st_blksize > MAX_BUF_SIZE ? MAX_BUF_SIZE : dirStat.st_blksize);
            } catch (ErrnoException ignored) {
            }
        } else {
            size = 1024 * 2;
            alignment = 8;
        }

        final Arena buf = Arena.allocate(size, alignment, GuardFactory.getInstance(this));

        return new DirectoryImpl(fd, buf, flags);
    }

    /**
     * Create a wrapper around native memory allocation. The created object can be used
     * to efficiently copy data between file descriptors.
     *
     * This method should never throw.
     *
     * @return a new instance of wrapper class
     */
    @NonNull
    @Override
    public Copy copy() {
        int CHUNK_SIZE = 64 * 1024;

        final Arena buf = Arena.allocate(CHUNK_SIZE, Arena.PAGE_ALIGN, GuardFactory.getInstance(this));

        return new CopyImpl(buf);
    }

    /**
     * Create a wrapper around directory descriptor for convenient access.
     *
     * This method should never throw.
     *
     * Created wrapper won't own the descriptor, you have to close it separately (but there may
     * be additional nuances if {@link Inotify#setSelector} is called).
     *
     * @param fd inotify descriptor, such as created by {@link #inotify_init}
     *
     * @return a new instance of wrapper class
     */
    @NonNull
    @Override
    public Inotify observe(@InotifyFd int fd) {
        return observe(fd, Looper.myLooper());
    }

    /**
     * Create a wrapper around directory descriptor for convenient access.
     *
     * This method should never throw.
     *
     * Created wrapper won't own the descriptor, you have to close it separately (but there may
     * be additional nuances if {@link Inotify#setSelector} is called).
     *
     * @param fd inotify descriptor, such as created by {@link #inotify_init}
     * @param looper the Looper of Thread, used for dispatching notification callbacks (if {@code null}, the main thread will be used)
     */
    @NonNull
    @Override
    public Inotify observe(@InotifyFd int fd, @Nullable Looper looper) {
        return new InotifyImpl(fd, looper, createBuffer(), this);
    }

    private Arena createBuffer() {
        // If https://serverfault.com/a/9548 is to be trusted, the biggest filename length
        // in Linux as of 2026 is 510 bytes (VFAT UCS-2 filenames)
        // Let's go with Binder's favorite size and use 1Mb as upper bound of buffer size
        int MAX_BUF_SIZE = 1024 * 1024;

        return Arena.allocate(MAX_BUF_SIZE, Arena.PAGE_ALIGN, GuardFactory.getInstance(this));
    }

    /**
     * Create a wrapper around system mountpoint list.
     *
     * This method returns a new instance each time it is called.
     */
    @NonNull
    @Override
    public MountInfo getMounts() throws IOException {
        return new MountInfo(this, open("/proc/self/mountinfo", OS.O_RDONLY, 0));
    }

    @Override
    public void fsync(int fd) throws IOException {
        Interruption stage = Interruption.begin();
        try {
            nativeFsync(stage.toNative(), fd);

            i10nCheck(stage, "fsync", 0);
        } finally {
            Interruption.end();
        }
    }

    @Override
    public void setrlimit(int type, @NonNull Limit stat) throws IOException {
        nativeSetrlimit(stat.current, stat.max, type);
    }

    @Override
    public native void getrlimit(int type, @NonNull Limit stat) throws IOException;

    @Override
    public native void fchmod(@Fd int fd, short mode) throws IOException;

    @Override
    public native void fallocate(@Fd int fd, int mode, long off, long length) throws IOException;

    @Override
    public native void readahead(@Fd int fd, long off, int byteCount) throws IOException;

    @Override
    public native void fadvise(@Fd int fd, long off, long length, int advice) throws IOException;

    @Override
    public void dup2(int source, int dest) throws IOException {
        dup2n(source, dest);
    }

    @Override
    public native int dup(int source) throws IOException;

    @Override
    public native void ftruncate(int fd, long newsize) throws IOException;

    @Override
    public native int inotify_init() throws IOException;

    @Override
    public native void fstat(int dir, @NonNull Stat stat) throws ErrnoException;

    @Override
    public native void close(int fd) throws ErrnoException;

    private int blockingOpen(CharSequence pathname, @DirFd int fd, int flags, int mode) throws IOException {
        final Interruption stage = Interruption.begin();
        try {
            Object p = prepare(pathname);

            int r = nativeOpenAt(stage.toNative(), toNative(p), length(p), fd, flags, mode);

            if (stage.isInterrupted()) {
                if (r >= 0) {
                    close(r);
                }

                throw new InterruptedIOException("open");
            }

            return r;
        } finally {
            Interruption.end();
        }
    }

    private static Object prepare(CharSequence string) {
        if (string.getClass() == NativeString.class) {
            return string;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            return string.toString();
        }

        return string.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Object toNative(Object string) {
        if (string.getClass() == NativeString.class) {
            return ((NativeString) string).getBytes();
        }

        return string;
    }

    private static int length(Object string) {
        if (string.getClass() == NativeString.class) {
            return ((NativeString) string).byteLength();
        }

        if (Build.VERSION.SDK_INT < 23) {
            return ((byte[]) string).length;
        }

        return - ((String) string).length();
    }

    static void i10nCheck(Interruption i10n, String caller, long transferred) throws java.io.InterruptedIOException {
        if (!i10n.isInterrupted()) return;

        Thread.interrupted();

        throw new InterruptedIOException(transferred, caller);
    }

    private static native void dup2n(int source, int dest) throws IOException;

    private static native void nativeFstatAt(Object pathname, Stat stat, int length, @DirFd int dir, int flags);

    private static native void nativeLinkAt(Object o1, Object o2, int l1, int l2, @DirFd int oldDirFd, @DirFd int newDirFd, @LinkAtFlags int flags);

    private static native boolean nativeFaccessAt(Object pathname, int length, int fd, int mode) throws ErrnoException;

    private static native void nativeRenameAt(Object o, Object o1, int l1, int l2, @DirFd int fd, @DirFd int fd2) throws ErrnoException;

    private static native boolean nativeMkdirAt(Object name, int length, @DirFd int target, int mode) throws ErrnoException;

    private static native void nativeMknodAt(Object name, int length, @DirFd int target, int mode, int device) throws ErrnoException;

    private static native void nativeUnlinkAt(Object name, int length, @DirFd int target, int flags) throws ErrnoException;

    private static native void nativeSymlinkAt(Object name, Object newpath, int l1, int l2, @DirFd int target) throws ErrnoException;

    private static native int nativeOpenAt(long token, Object pathname, int length, @DirFd int fd, int flags, int mode) throws IOException;

    private static native byte[] nativeReadlink(Object pathname, int length, @DirFd int fd) throws IOException;

    private static native void nativeSetrlimit(long cur, long max, int type) throws ErrnoException;

    private static native void nativeFsync(long nativePtr, int fd) throws ErrnoException;

    // used by DirectoryImpl

    // seek to specified position (or opaque "position" in case of directories)
    // returns new position on success
    static native long seekTo(long cookie, int dirFd) throws ErrnoException;

    // seek to the start of directory (zeroth item)
    // should never fail because Linux directories have two items at minimal
    static native void rewind(int dirFd) throws ErrnoException;

    // read next dirent value into the buffer
    // returns count of bytes read (e.g. total size of all dirent structures read) or 0 on reaching end
    static native int nativeReadNext(long nativeBufferPtr, int dirFd, int capacity) throws ErrnoException;

    // retrieve string bytes from byte buffer
    // returns number of bytes written (terminator byte is not written/counted)
    static native int nativeGetStringBytes(long entryPtr, byte[] reuse, int arrSize);

    // used by CopyImpl

    static native long doSendfile(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;

    static native long doSplice(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;

    static native long doDumbCopy(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;

    // Used by BlockingGuards

    static native void free(long pointer);
}
