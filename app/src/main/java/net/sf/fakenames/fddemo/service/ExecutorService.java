package net.sf.fakenames.fddemo.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.sf.fakenames.fddemo.R;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ExecutorService extends Service {
    private static final String ACTION_START_ON_DEMAND = "net.sf.fddemo.STARTED";

    private Executor toughPool;
    private boolean isForeground;
    private int bindingCount;

    private final AtomicInteger runningInstance = new AtomicInteger(-1);

    private Notification notification;
    private Intent intent;

    @Override
    public void onCreate() {
        super.onCreate();

        notification = new Notification.Builder(getBaseContext())
                .setContentText("File Manager is running")
                .setContentTitle("File Manager is running")
                .setSmallIcon(R.drawable.ic_provider_icon)
                .build();

        intent = new Intent(ACTION_START_ON_DEMAND, null, getBaseContext(), ExecutorService.class);

        final ThreadPoolExecutor exec = new ThreadPoolExecutor(1, 4, 6, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        exec.setThreadFactory(new SimpleFactory());
        exec.allowCoreThreadTimeOut(true);
        toughPool = exec;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent.getAction();

        if (ACTION_START_ON_DEMAND.equals(action)) {
            checkForeground();
            return START_REDELIVER_INTENT;
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void checkStarted() {

        if (false) {
            startService(intent);
        }
    }

    private void checkForeground() {
        if (bindingCount == 0) {
            startForeground();
        } else {
            stopForeground();
        }
    }

    @Override
    public void onDestroy() {
        isForeground = false;

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForeground() {
        if (isForeground) return;

        startForeground(R.id.foreground_nf, notification);

        isForeground = true;
    }

    private void stopForeground() {
        if (!isForeground) return;

        stopForeground(true);

        isForeground = false;
    }

    public static void bind(Context context, ServiceConnection connection) {
        context.bindService(new Intent(context, ExecutorService.class), connection, BIND_AUTO_CREATE);
    }

    public static void unbind(Context context, ServiceConnection connection) {
        context.unbindService(connection);
    }

    private static final class SimpleFactory implements ThreadFactory {
        @Override
        public Thread newThread(@NonNull Runnable r) {
            final Thread t = new Thread("IO thread");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }
    }
}
