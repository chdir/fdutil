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
package net.sf.fakenames.fddemo.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.sf.fakenames.fddemo.BuildConfig;
import net.sf.fakenames.fddemo.MainActivity;
import net.sf.fakenames.fddemo.R;

import java.io.Closeable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class UpkeepService extends Service {
    private static final String ACTION_START_ON_DEMAND = "net.sf.chdir.STARTED";

    private volatile boolean isForeground;

    private Notification notification;

    private static final Intent intent = new Intent(ACTION_START_ON_DEMAND)
            .setComponent(new ComponentName(BuildConfig.APPLICATION_ID, UpkeepService.class.getName()));

    @Override
    public void onCreate() {
        super.onCreate();

        final PendingIntent intent = PendingIntent.getActivity(getBaseContext(), 0,
                new Intent(getBaseContext(), MainActivity.class), 0);

        notification = new Notification.Builder(getBaseContext())
                .setContentText("File Manager is running")
                .setContentTitle("File Manager is running")
                .setContentIntent(intent)
                .setAutoCancel(false)
                .setSmallIcon(R.drawable.ic_provider_icon)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent.getAction();

        if (ACTION_START_ON_DEMAND.equals(action)) {
            startForeground();
        } else {
            stopSelf(startId);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        isForeground = false;

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new DumbBinder();
    }

    public final class DumbBinder extends Binder {
    }

    private void startForeground() {
        if (isForeground) return;

        startForeground(R.id.foreground_nf, notification);

        isForeground = true;
    }

    public static class Binding implements Future<DumbBinder>, ServiceConnection, Closeable {
        private final Context context;
        private final Lock lock;
        private final Condition condition;

        private DumbBinder binder;

        public Binding(Context context) {
            this.context = context;

            this.lock = new ReentrantLock();

            this.condition = lock.newCondition();

            if (!context.bindService(intent, this, BIND_AUTO_CREATE)) {
                throw new IllegalStateException("Unable to bind");
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            lock.lock();
            try {
                return binder != null;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public DumbBinder get() throws InterruptedException {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("wrong thread!");

            lock.lock();
            try {
                while (binder == null) {
                    condition.wait();
                }

                return binder;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public DumbBinder get(long timeout, @NonNull TimeUnit unit) throws InterruptedException, TimeoutException {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("wrong thread!");

            long nanos = unit.toNanos(timeout);
            lock.lock();
            try {
                while (binder == null) {
                    if (nanos <= 0L) throw new TimeoutException();

                    nanos = condition.awaitNanos(nanos);
                }

                return binder;
            } finally {
                lock.unlock();
            }
        }

        @Override
        @MainThread
        public void close() {
            context.unbindService(this);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            lock.lock();
            try {
                binder = (DumbBinder) service;

                condition.notifyAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            throw new IllegalStateException("Service crashed?!");
        }
    }

    @MainThread
    public static Binding bind(Context context) {
        return new Binding(context);
    }

    public static void start(Context context) {
        context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(intent);
    }
}
