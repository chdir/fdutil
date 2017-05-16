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

import java.io.IOException;
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
        return nativeCreat(toNative(path), mode);
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @Fd int open(@NonNull CharSequence path, int flags, int mode) throws IOException {
        return openat(DirFd.AT_FDCWD, path, flags, mode);
    }

    private static final int BLOCKING_FLAGS = O_NONBLOCK | O_DIRECTORY;

    @Override
    @SuppressWarnings("WrongConstant")
    public @Fd int openat(@DirFd int fd, @NonNull CharSequence pathname, int flags, int mode) throws IOException {
        if ((flags & BLOCKING_FLAGS) != 0) {
            return nativeOpenAt(fd, toNative(pathname), flags, mode);
        } else {
            return blockingOpen(fd, pathname, flags, mode);
        }
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @DirFd int opendir(@NonNull CharSequence path) throws IOException {
        return opendirat(DirFd.AT_FDCWD, path);
    }

    @Override
    @SuppressWarnings("WrongConstant")
    public @DirFd int opendirat(@DirFd int fd, @NonNull CharSequence name) throws IOException {
        return nativeOpenAt(fd, toNative(name), O_NOCTTY | O_DIRECTORY, 0);
    }

    @NonNull
    @Override
    public CharSequence readlinkat(@DirFd int fd, @NonNull CharSequence pathname) throws IOException {
        return fromNative(nativeReadlink(fd, toNative(pathname)));
    }

    @Override
    public void symlinkat(@NonNull CharSequence name, @DirFd int target, @NonNull CharSequence newpath) throws IOException {
        nativeSymlinkAt(toNative(name), target, toNative(newpath));
    }

    @Override
    public void linkat(@DirFd int oldDirFd, @NonNull CharSequence oldName, @DirFd int newDirFd, @NonNull CharSequence newName, @LinkAtFlags int flags) throws IOException {
        nativeLinkAt(oldDirFd, toNative(oldName), newDirFd, toNative(newName), flags);
    }

    @Override
    public void unlinkat(@DirFd int target, @NonNull CharSequence name, @UnlinkAtFlags int flags) throws IOException {
        nativeUnlinkAt(target, toNative(name), flags);
    }

    @Override
    public void mknodat(@DirFd int target, @NonNull CharSequence name, @FileTypeFlag int mode, int device) throws IOException {
        nativeMknodAt(target, toNative(name), mode, device);
    }

    @Override
    public void mkdirat(@DirFd int target, @NonNull CharSequence name, int mode) throws IOException {
        nativeMkdirAt(target, toNative(name), mode);
    }

    @Override
    public boolean faccessat(int fd, @NonNull CharSequence pathname, int mode) throws IOException {
        return nativeFaccessAt(fd, toNative(pathname), mode);
    }

    @Override
    public void fstatat(int dir, @NonNull CharSequence pathname, @NonNull Stat stat, int flags) throws IOException {
        nativeFstatAt(dir, toNative(pathname), stat, flags);
    }

    @Override
    public void renameat(@DirFd int fd, CharSequence name, @DirFd int fd2, CharSequence name2) throws IOException {
        nativeRenameAt(fd, toNative(name), fd2, toNative(name2));
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
        return new DirectoryImpl(fd, GuardFactory.getInstance(this));
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
        return new CopyImpl(this);
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
        return new InotifyImpl(fd, looper, this, GuardFactory.getInstance(this));
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
        InterruptibleStageImpl stage = InterruptibleStageImpl.get();
        try {
            nativeFsync(stage.i.nativePtr, fd);
        } finally {
            stage.end();
        }
    }

    @Override
    public void setrlimit(int type, @NonNull Limit stat) throws IOException {
        nativeSetrlimit(stat.current, stat.max, type);
    }

    @Override
    public native void getrlimit(int type, @NonNull Limit stat) throws IOException;

    @Override
    public native void fallocate(@Fd int fd, int mode, long off, long length) throws IOException;

    @Override
    public native void readahead(@Fd int fd, long off, int byteCount) throws IOException;

    @Override
    public native void fadvise(@Fd int fd, long off, long length, int advice) throws IOException;

    @Override
    public native void dup2(int source, int dest) throws IOException;

    @Override
    public native int dup(int source) throws IOException;

    @Override
    public native int inotify_init() throws IOException;

    @Override
    public native void fstat(int dir, @NonNull Stat stat) throws ErrnoException;

    @Override
    public native void close(int fd) throws ErrnoException;

    private static int blockingOpen(@DirFd int fd, CharSequence pathname, int flags, int mode) throws IOException {
        final InterruptibleStageImpl stage = InterruptibleStageImpl.get();

        stage.begin();
        try {
            return nativeOpenAt2(stage.i.nativePtr, fd, toNative(pathname), flags, mode);
        } finally {
            stage.end();
        }
    }

    private static Object toNative(CharSequence string) {
        if (string.getClass() == NativeString.class) {
            return ((NativeString) string).bytes;
        }

        final String chars = string.toString();

        return Build.VERSION.SDK_INT >= 23 ? chars : chars.getBytes(StandardCharsets.UTF_8);
    }

    private static CharSequence fromNative(Object string) {
        return string.getClass() == String.class ? (String) string : new NativeString((byte[]) string);
    }

    private static native void nativeFstatAt(@DirFd int dir, Object o, Stat stat, int flags);

    private static native void nativeLinkAt(@DirFd int oldDirFd, Object o, @DirFd int newDirFd, Object o1, @LinkAtFlags int flags);

    private static native boolean nativeFaccessAt(int fd, Object pathname, int mode) throws ErrnoException;

    private static native void nativeRenameAt(@DirFd int fd, Object o, @DirFd int fd2, Object o1) throws ErrnoException;

    private static native void nativeMkdirAt(@DirFd int target, Object name, int mode) throws ErrnoException;

    private static native void nativeMknodAt(@DirFd int target, Object name, int mode, int device) throws ErrnoException;

    private static native void nativeUnlinkAt(@DirFd int target, Object name, int flags) throws ErrnoException;

    private static native void nativeSymlinkAt(Object name, @DirFd int target, Object newpath) throws ErrnoException;

    private static native @Fd int nativeCreat(Object pathname, int mode) throws ErrnoException;

    private static native int nativeOpenAt(@DirFd int fd, Object pathname, int flags, int mode) throws ErrnoException;

    private static native int nativeOpenAt2(long token, @DirFd int fd, Object pathname, int flags, int mode) throws IOException;

    private static native Object nativeReadlink(@DirFd int fd, Object pathname) throws IOException;

    private static native void nativeSetrlimit(long cur, long max, int type) throws ErrnoException;

    private static native void nativeFsync(long nativePtr, int fd) throws ErrnoException;
}
