package net.sf.xfd.provider;

import net.sf.fdlib.InotifyWatch;

import java.util.IdentityHashMap;
import java.util.Map;

final class RefCountedWatch implements InotifyWatch {
    private static Map<InotifyWatch, RefCountedWatch> map = new IdentityHashMap<>();

    private int counter = 1;

    private final InotifyWatch delegate;

    private RefCountedWatch(InotifyWatch delegate) {
        this.delegate = delegate;
    }

    public static synchronized RefCountedWatch get(InotifyWatch watch) {
        final RefCountedWatch rcw = map.get(watch);

        if (rcw == null) {
            final RefCountedWatch newRcf = new RefCountedWatch(watch);

            map.put(watch, newRcf);

            return newRcf;
        } else {
            rcw.incCount();

            return rcw;
        }
    }

    @Override
    public synchronized void close() {
        map.remove(delegate);

        delegate.close();
    }

    public synchronized void incCount() {
        ++counter;
    }

    public synchronized void decCount() {
        --counter;

        if (counter == 0) {
            close();
        }
    }
}
