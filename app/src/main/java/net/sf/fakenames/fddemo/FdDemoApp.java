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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.StrictMode;
import android.preference.PreferenceManager;

import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.xfd.Interruption;
import net.sf.xfd.OS;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class FdDemoApp extends Application implements Thread.UncaughtExceptionHandler {
    static final String FILE_TASKS = "net.sf.chdir.FILES";

    static final String PREFS = "net.sf.chdir.PREFS";

    static {
        System.setProperty(SyscallFactory.DEBUG_MODE, "true");
        System.setProperty(OS.DEBUG_MODE, "true");
        Interruption.unblockSignal();
    }

    private NotificationManager nm;

    private FileTasks fileTasks;

    private LazyPrefs prefs;

    private volatile Thread.UncaughtExceptionHandler defaultHandler;

    @Override
    public void onCreate() {
        super.onCreate();

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(this);

        prefs = new LazyPrefs(this);

        new Thread(prefs).start();

        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        fileTasks = new FileTasks(this, nm);

        registerActivityLifecycleCallbacks(fileTasks);

        new Handler().post(() -> StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build()));
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case FILE_TASKS:
                return fileTasks;
            case PREFS:
                return prefs.get();
            default:
                return super.getSystemService(name);
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            nm.cancelAll();
        } finally {
            defaultHandler.uncaughtException(t, e);
        }
    }

    private static final class LazyPrefs extends FutureTask<SharedPreferences>  {
        private SharedPreferences preferences;

        private LazyPrefs(Context context) {
            super(() -> PreferenceManager.getDefaultSharedPreferences(context));
        }

        public SharedPreferences get() {
            if (preferences == null) {
                synchronized (this) {
                    do {
                        try {
                            preferences = super.get();

                            break;
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e.getCause());
                        } catch (InterruptedException ignored) {
                        }
                    } while (true);
                }
            }

            return preferences;
        }
    }
}
