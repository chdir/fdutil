/*
 * Copyright © 2017 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.xfd.provider;

import net.sf.xfd.InotifyWatch;

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
