package net.sf.xfd.provider;

import net.sf.xfd.SelectorThread;

import java.io.IOException;

public final class EpollThreadSingleton {
    private static volatile SelectorThread instance;

    public static SelectorThread get() throws IOException {
        if (instance == null) {
            synchronized (EpollThreadSingleton.class) {
                if (instance == null) {
                    instance = new SelectorThread();
                    instance.start();
                }
            }
        }

        return instance;
    }
}