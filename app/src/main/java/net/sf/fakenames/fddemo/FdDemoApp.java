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
import android.app.NotificationManager;
import android.os.Handler;
import android.os.StrictMode;

import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.xfd.OS;

public final class FdDemoApp extends Application implements Thread.UncaughtExceptionHandler {
    static {
        System.setProperty(SyscallFactory.DEBUG_MODE, "true");
        System.setProperty(OS.DEBUG_MODE, "true");
    }

    private NotificationManager nm;

    private volatile Thread.UncaughtExceptionHandler defaultHandler;

    @Override
    public void onCreate() {
        super.onCreate();

        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(this);

        new Handler().post(() -> StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build()));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            nm.cancelAll();
        } finally {
            defaultHandler.uncaughtException(t, e);
        }
    }
}
