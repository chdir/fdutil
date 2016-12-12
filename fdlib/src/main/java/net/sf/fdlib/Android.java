package net.sf.fdlib;

import android.os.Build;

import net.sf.fakenames.fdlib.BuildConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Android extends OS {
    Android() throws IOException {
        try {
            System.loadLibrary("coreio-" + BuildConfig.NATIVE_VER);
        } catch (UnsatisfiedLinkError loadLibraryFailed) {
            throw new IOException("Failed to load native library", loadLibraryFailed);
        }
    }

    @Override
    public @Fd int open(String path, int flags, int mode) throws IOException {
        return nativeOpen(toNative(path), flags, mode);
    }

    @Override
    public @DirFd int opendir(String path, int flags, int mode) throws IOException {
        return nativeOpenDir(toNative(path), flags, mode);
    }

    @Override
    public Directory list(@Fd int fd) {
        return new DirectoryImpl(fd);
    }

    public void close(@Fd int fd) throws IOException {
        nativeClose(fd);
    }

    @Override
    public void closeDir(@DirFd int fd) {
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

    private static native @Fd int nativeOpen(Object path, int flags, int mode) throws IOException;

    private static native @DirFd int nativeOpenDir(Object path, int flags, int mode) throws IOException;

    private static native void nativeClose(int fd) throws IOException;
}
