package net.sf.fdlib;

import android.os.Build;
import android.os.Looper;
import android.support.annotation.NonNull;

import net.sf.fakenames.fdlib.BuildConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Android extends OS {
    Android() {}

    private static volatile Android instance;

    public static Android getInstance() throws IOException {
        if (instance == null) {
            try {
                System.loadLibrary("coreio-" + BuildConfig.NATIVE_VER);

                instance = new Android();
            } catch (UnsatisfiedLinkError loadLibraryFailed) {
                throw new IOException("Failed to load native library", loadLibraryFailed);
            }
        }

        return instance;
    }

    @Override
    public @Fd int open(String path, int flags, int mode) throws IOException {
        return nativeOpenAt(DirFd.AT_FDCWD, toNative(path), flags, mode);
    }

    @Override
    public @Fd int openat(@DirFd int fd, String pathname, int flags, int mode) throws IOException {
        return nativeOpenAt(DirFd.AT_FDCWD, toNative(pathname), flags, mode);
    }

    @Override
    public @DirFd int opendir(String path, int flags, int mode) throws IOException {
        return nativeOpenDirAt(DirFd.AT_FDCWD, toNative(path), flags, mode);
    }

    @Override
    public @DirFd int opendirat(@DirFd int fd, String name, int flags, int mode) throws IOException {
        return nativeOpenDirAt(fd, toNative(name), flags, mode);
    }

    @NonNull
    @Override
    public String readlinkat(@DirFd int fd, String pathname) throws IOException {
        return fromNative(nativeReadlink(fd, toNative(pathname)));
    }

    @Override
    public void symlinkat(String name, @DirFd int target, String newpath) throws IOException {
        nativeSymlinkAt(toNative(name), target, toNative(newpath));
    }

    @Override
    public void unlinkat(@DirFd int target, String name, @UnlinkAtFlags int flags) throws IOException {
        nativeUnlinkAt(target, toNative(name), flags);
    }

    @Override
    public void mknodat(@DirFd int target, String name, @FileTypeFlag int mode, int device) throws IOException {
        nativeMknodAt(target, toNative(name), mode, device);
    }

    @Override
    public void mkdirat(@DirFd int target, String name, int mode) throws IOException {
        nativeMkdirAt(target, toNative(name), mode);
    }

    @Override
    public native void dup2(int source, int dest) throws IOException;

    @Override
    public native int inotify_init() throws IOException;

    @NonNull
    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor) {
        return observe(inotifyDescriptor, Looper.myLooper());
    }

    @NonNull
    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper) {
        return new InotifyImpl(inotifyDescriptor, looper, this, GuardFactory.getInstance(this));
    }

    @Override
    public native void fstat(int dir, Stat stat) throws ErrnoException;

    @Override
    public void renameat(@DirFd int fd, String name, @DirFd int fd2, String name2) throws IOException {
        nativeRenameAt(fd, toNative(name), fd2, toNative(name2));
    }

    @NonNull
    @Override
    public Directory list(@Fd int fd) {
        return new DirectoryImpl(fd, GuardFactory.getInstance(this));
    }

    public void close(@Fd int fd) throws IOException {
        nativeClose(fd);
    }

    @Override
    public void dispose(@Fd int fd) {
        try {
            nativeClose(fd);
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }
    }

    private static Object toNative(String string) {
        return Build.VERSION.SDK_INT >= 23 ? string : string.getBytes(StandardCharsets.UTF_8);
    }

    private static String fromNative(Object string) {
        return Build.VERSION.SDK_INT >= 23 ? (String) string : new String((byte[]) string, StandardCharsets.UTF_8);
    }

    private static native void nativeRenameAt(@DirFd int fd, Object o, @DirFd int fd2, Object o1) throws ErrnoException;

    private static native void nativeMkdirAt(@DirFd int target, Object name, int mode) throws ErrnoException;

    private static native void nativeMknodAt(@DirFd int target, Object name, int mode, int device) throws ErrnoException;

    private static native void nativeUnlinkAt(@DirFd int target, Object name, int flags) throws ErrnoException;

    private static native void nativeSymlinkAt(Object name, @DirFd int target, Object newpath) throws ErrnoException;

    private static native @Fd int nativeOpenAt(@DirFd int fd, Object pathname, int flags, int mode) throws ErrnoException;

    private static native @DirFd int nativeOpenDirAt(@DirFd int fd, Object pathname, int flags, int mode) throws ErrnoException;

    private static native Object nativeReadlink(@DirFd int fd, Object pathname) throws IOException;

    private static native void nativeClose(int fd) throws ErrnoException;
}
