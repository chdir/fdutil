package net.sf.fdlib;

import java.io.IOException;

public final class ErrnoException extends IOException {
    public static final int EOPNOTSUPP = 95;
    public static final int ENOTDIR = 20;
    public static final int ENOENT = 2;

    private final int errno;

    public ErrnoException(int errno, String explanation) {
        super(explanation);

        this.errno = errno;
    }

    public int code() {
        return errno;
    }

    @Override
    public String toString() {
        return super.toString() + " (errno " + errno + ')';
    }
}
