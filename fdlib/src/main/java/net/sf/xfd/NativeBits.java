package net.sf.xfd;

import java.io.IOException;

public final class NativeBits {
    private NativeBits() {}

    public static final int O_CREAT = 0b1000000;

    public static final int O_DIRECTORY = 0b10000000000000000;

    public static final int O_NONBLOCK = nativeInit();

    public static final int O_NOCTTY = nativeInit();

    private static int nativeInit() { return 0; }

    private static native void fixConstants();

    static {
        try {
            Android.loadLibraries();

            fixConstants();
        } catch (IOException ignored) {
        }
    }
}
