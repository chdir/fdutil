package net.sf.xfd.provider;

import net.sf.xfd.MountInfo;
import net.sf.xfd.OS;

import java.io.IOException;

public final class MountsSingleton {
    private static volatile MountInfo instance;

    public static MountInfo get(OS os) throws IOException {
        if (instance == null) {
            synchronized (MountsSingleton.class) {
                if (instance == null) {
                    instance = os.getMounts();
                    instance.setSelector(EpollThreadSingleton.get());
                }
            }
        }

        return instance;
    }
}
