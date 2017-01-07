package net.sf.fdlib;

import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class OS {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";

    protected OS() {
    }

    @IntDef({O_RDONLY, O_WRONLY, O_RDWR})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlag {}

    public static final int O_RDONLY = 0;    // 0b0000000000000000000000;
    public static final int O_WRONLY = 1;    // 0b0000000000000000000001;
    public static final int O_RDWR = 1 << 1;

    @IntDef(AT_REMOVEDIR)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface UnlinkAtFlags {}

    public static final int AT_REMOVEDIR = 0x200;

    @IntDef({S_IFREG, S_IFCHR, S_IFBLK, S_IFIFO, S_IFSOCK})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileTypeFlag {}


    public static final int S_IFREG =  0b1000000000000000;
    public static final int S_IFSOCK = 0b1100000000000000;
    public static final int S_IFBLK =  0b0110000000000000;
    public static final int S_IFCHR =  0b0010000000000000;
    public static final int S_IFIFO =  0b0001000000000000;

    @CheckResult
    @WorkerThread
    public abstract @Fd int open(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendir(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendirat(@DirFd int fd, String name, int flags, int mode) throws IOException;

    @CheckResult
    public abstract @NonNull String readlink(String path) throws IOException;

    @CheckResult
    public abstract @InotifyFd int inotify_init() throws IOException;

    @CheckResult
    public abstract Directory list(@DirFd int fd);

    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor);

    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper);

    @CheckResult
    public abstract Stat fstat(@DirFd int dir);

    public abstract void symlinkat(String name, @DirFd int target, String newpath) throws IOException;

    public abstract void unlinkat(@DirFd int target, String name, @UnlinkAtFlags int flags) throws IOException;

    public abstract void mknodat(@DirFd int target, String name, @FileTypeFlag int mode, int device) throws IOException;

    public abstract void mkdirat(@DirFd int target, String name, int mode) throws IOException;

    public abstract void dup2(@Fd int source, int dest) throws IOException;

    public abstract void close(@Fd int fd) throws IOException;

    public abstract void dispose(int fd);

    private static volatile OS defaultOs;

    public static OS getInstance() throws IOException {
        if (defaultOs == null) {
            synchronized (OS.class) {
                if (defaultOs == null) {
                    defaultOs = Android.getInstance();
                }
            }
        }

        return defaultOs;
    }
}
