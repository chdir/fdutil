package net.sf.fdlib;

import android.os.Looper;

final class DebugAsserts {
    static void failIf(boolean fail, String s) {
        if (fail) {
            throw new AssertionError(s);
        }
    }

    static void thread(Looper expected, String s) {
        if (Looper.myLooper() != expected) {
            throw new AssertionError(s + " must be executed on " + expected.getThread() +
                    ", but was called on " + Thread.currentThread());
        }
    }
}
