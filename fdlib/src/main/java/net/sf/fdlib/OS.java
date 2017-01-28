package net.sf.fdlib;

import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class OS {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";

    protected OS() {
    }

    @IntDef(value = {F_OK}, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface AccessFlags {}

    public static final int F_OK = 0;

    @IntDef(value = {O_RDONLY, O_WRONLY, O_RDWR}, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlag {}

    public static final int O_RDONLY = 0;    // 0b0000000000000000000000;
    public static final int O_WRONLY = 1;    // 0b0000000000000000000001;
    public static final int O_RDWR = 1 << 1;

    public static final int DEF_DIR_MODE =  0b111111001; // 0771 aka drwxrwx--x
    public static final int DEF_FILE_MODE = 0b110110110; // 0666 aka  rw-rw-rw-

    public static final int AT_SYMLINK_FOLLOW = 0x400;

    @IntDef(AT_SYMLINK_FOLLOW)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface LinkAtFlags {}

    @IntDef(AT_REMOVEDIR)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface UnlinkAtFlags {}

    public static final int AT_REMOVEDIR = 0x200;

    @IntDef(value = {S_IFREG, S_IFCHR, S_IFBLK, S_IFIFO, S_IFSOCK, DEF_DIR_MODE, DEF_FILE_MODE}, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileTypeFlag {}


    public static final int S_IFREG =  0b1000000000000000;
    public static final int S_IFSOCK = 0b1100000000000000;
    public static final int S_IFBLK =  0b0110000000000000;
    public static final int S_IFCHR =  0b0010000000000000;
    public static final int S_IFIFO =  0b0001000000000000;

    public static final int POSIX_FADV_SEQUENTIAL = 2;

    @IntDef(value = {POSIX_FADV_SEQUENTIAL})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface fadvice {}

    @CheckResult
    @WorkerThread
    public abstract @Fd int creat(@NonNull String path, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @Fd int open(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @Fd int openat(@DirFd int fd, String name, int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendir(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendirat(@DirFd int fd, String name, int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @NonNull String readlinkat(@DirFd int fd, String pathname) throws IOException;

    @CheckResult
    public abstract @InotifyFd int inotify_init() throws IOException;

    @NonNull
    @CheckResult
    public abstract Directory list(@DirFd int fd);

    @NonNull
    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor);

    @NonNull
    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper);

    public abstract void fstat(int dir, @NonNull Stat stat) throws IOException;

    public abstract void fsync(int fd) throws IOException;

    public abstract MountInfo getMounts() throws IOException;

    @WorkerThread
    public abstract void renameat(@DirFd int fd, String name, @DirFd int fd2, String name2) throws IOException;

    public abstract void symlinkat(String name, @DirFd int target, String newpath) throws IOException;

    public abstract void linkat(@DirFd int oldDirFd, String oldName, @DirFd int newDirFd, String newName, @LinkAtFlags int flags) throws IOException;

    @WorkerThread
    public abstract void unlinkat(@DirFd int target, String name, @UnlinkAtFlags int flags) throws IOException;

    @WorkerThread
    public abstract void mknodat(@DirFd int target, String name, @FileTypeFlag int mode, int device) throws IOException;

    @WorkerThread
    public abstract void mkdirat(@DirFd int target, String name, int mode) throws IOException;

    public abstract void fallocate(int fd, int mode, long off, long count) throws IOException;

    public abstract void readahead(int fd, long off, int count) throws IOException;

    public abstract void fadvise(int fd, long off, long length, @fadvice int advice) throws IOException;

    public abstract boolean faccessat(@DirFd int fd, String pathname, @AccessFlags int mode) throws IOException;

    public abstract void dup2(@Fd int source, int dest) throws IOException;

    @CheckResult
    public abstract @Fd int dup(int source) throws IOException;

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
