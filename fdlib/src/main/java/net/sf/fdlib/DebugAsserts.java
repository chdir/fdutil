package net.sf.fdlib;

final class DebugAsserts {
    static void failIf(boolean fail, String s) {
        if (fail) {
            throw new AssertionError(s);
        }
    }
}
