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
