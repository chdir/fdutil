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

import android.annotation.SuppressLint;
import android.content.Context;

import net.sf.xfd.Rooted;
import net.sf.xfd.OS;

import java.io.IOException;

public final class RootSingleton {
    @SuppressLint("StaticFieldLeak")
    private static volatile Rooted instance;

    public static OS get(Context context) throws IOException {
        if (instance == null) {
            synchronized (RootSingleton.class) {
                if (instance == null) {
                    instance = Rooted.createWithChecks(context);
                }
            }
        }

        return instance;
    }

    public static synchronized void clear() {
        instance = null;
    }
}
