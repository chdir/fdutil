package net.sf.fdlib;

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

    public abstract void closeDir(@Fd int fd);

    public abstract Directory list(@Fd int fd);

    public abstract @Fd int open(String path, @OpenFlag int flags, int mode) throws IOException;

    public abstract @DirFd int opendir(String path, @OpenFlag int flags, int mode) throws IOException;

    public abstract void close(@Fd int fd) throws IOException;

    public static OS getInstance() throws IOException {
        return new Android();
    }
}
