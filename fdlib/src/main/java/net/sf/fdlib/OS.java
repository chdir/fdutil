package net.sf.fdlib;

import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class OS {
    protected OS() {
    }

    @IntDef({O_RDONLY, O_WRONLY, O_RDWR})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlag {}

    public static final int O_RDONLY = 0;    // 0b0000000000000000000000;
    public static final int O_WRONLY = 1;    // 0b0000000000000000000001;
    public static final int O_RDWR = 1 << 1;

    @CheckResult
    public abstract @Fd int open(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    public abstract @DirFd int opendir(String path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    public abstract @InotifyFd int inotify_init() throws IOException;

    @CheckResult
    public abstract Directory list(@Fd int fd);

    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor) throws IOException;

    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper) throws IOException;

    public abstract void dup2(@Fd int source, int dest) throws IOException;

    public abstract void close(@Fd int fd) throws IOException;

    public abstract void dispose(int fd);

    private static OS defaultOs;

    public static OS getInstance() throws IOException {
        if (defaultOs == null) {
            defaultOs = new Android();
        }

        return defaultOs;
    }
}
