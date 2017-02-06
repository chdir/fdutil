/**
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
package net.sf.fakenames.fddemo;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;

import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.fdlib.OS;

public final class FdDemoApp extends Application {
    static {
        System.setProperty(SyscallFactory.DEBUG_MODE, "true");
        System.setProperty(OS.DEBUG_MODE, "true");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        new Handler().post(() -> StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build()));
    }
}
