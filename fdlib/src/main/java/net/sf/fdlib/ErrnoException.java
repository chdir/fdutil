package net.sf.fdlib;

import java.io.IOException;

public class ErrnoException extends IOException {
    private final int errno;

    public ErrnoException(int errno, String explanation) {
        super(explanation);

        this.errno = errno;
    }

    @Override
    public String toString() {
        return super.toString() + " errno " + errno;
    }
}
