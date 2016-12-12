package net.sf.fdlib;

import java.io.IOException;

public class WrappedIOException extends RuntimeException {
    public WrappedIOException(IOException inner) {
        super(inner);
    }

    @Override
    public IOException getCause() {
        return (IOException) super.getCause();
    }
}
