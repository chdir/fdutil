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
package net.sf.fakenames.fddemo;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.widget.Toast;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.ObjectIdentityHashSet;
import com.carrotsearch.hppc.ObjectSet;
import com.carrotsearch.hppc.cursors.LongObjectCursor;

import net.sf.fakenames.fddemo.service.NotificationCallback;
import net.sf.fakenames.fddemo.service.UpkeepService;
import net.sf.xfd.DirFd;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static net.sf.xfd.provider.ProviderBase.isPosix;

public final class FileTasks extends ContextWrapper implements Application.ActivityLifecycleCallbacks {
    private final IntObjectMap<CancellationHelper> tasks = new IntObjectHashMap<>();
    private final LongObjectMap<SerialExecutor> execs = new LongObjectHashMap<>();
    private final ObjectSet<Activity> started = new ObjectIdentityHashSet<>(1);

    private final ExecutorService ioExec;

    private final NotificationManager nfService;

    private int lastTaskId;

    @SuppressWarnings("WrongConstant")
    public static FileTasks getInstance(Context context) {
        final Context c = context.getApplicationContext();

        return (FileTasks) c.getSystemService(FdDemoApp.FILE_TASKS);
    }

    public FileTasks(Context base, NotificationManager nfService) {
        super(base);

        this.nfService = nfService;

        final ThreadFactory priorityFactory = r -> new Thread(r, "Odd jobs thread");
        ioExec = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 20L, TimeUnit.SECONDS, new SynchronousQueue<>(), priorityFactory);
    }

    public void copy(OS os, BaseDirLayout layout, FileObject sourceFile, @DirFd int dir, boolean canRemoveOriginal) throws IOException {
        final Context context = this;

        final Stat targetDirStat = new Stat();

        os.fstat(dir, targetDirStat);

        SerialExecutor exec = execs.get(targetDirStat.st_dev);
        if (exec == null) {
            if (execs.size() > 3) {
                cleanupExecutors();
            }

            exec = new SerialExecutor(ioExec);
            execs.put(targetDirStat.st_dev, exec);
        }

        final MountInfo.Mount m = layout.getFs(targetDirStat.st_dev);

        final boolean canUseExtChars = m != null && isPosix(m.fstype);

        if (++lastTaskId < 0) {
            lastTaskId = 0;
        }

        final int taskId = lastTaskId;

        final NotificationCallback callback = makeCallback(taskId);

        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            private String fileName;

            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                final CancellationHelper ch = params[0];

                boolean copied = false;
                FileObject targetFile = null;
                try (Closeable c = sourceFile) {
                    final String desc = sourceFile.getDescription(ch);

                    fileName = canUseExtChars
                            ? FilenameUtil.sanitize(desc)
                            : FilenameUtil.sanitizeCompat(desc);

                    if (os.faccessat(dir, fileName, OS.F_OK)) {
                        throw new IOException("File exists!");
                    }

                    final FsFile tmpFileInfo = new FsFile(dir, fileName, targetDirStat);

                    targetFile = FileObject.fromTempFile(os, context, tmpFileInfo);

                    copied = canRemoveOriginal
                            ? sourceFile.moveTo(targetFile, ch, callback)
                            : sourceFile.copyTo(targetFile, ch, callback);

                    return copied ? null : new IOException("Copy failed");
                } catch (InterruptedIOException t) {
                    Thread.interrupted();

                    return t;
                } catch (FileNotFoundException e) {
                    nfService.cancel(taskId);

                    return e;
                } catch (Throwable t) {
                    t.printStackTrace();

                    return t;
                } finally {
                    try {
                        if (targetFile != null) {
                            if (!copied) {
                                try {
                                    targetFile.delete();
                                } catch (RemoteException | IOException ioe) {
                                    LogUtil.logCautiously("Failed to remove target file", ioe);
                                }
                            }

                            targetFile.close();
                        }
                    } finally {
                        os.dispose(dir);
                    }
                }
            }

            @Override
            protected void onCancelled() {
                removeTask(taskId);

                callback.onDismiss();
            }

            @Override
            protected void onPostExecute(Throwable s) {
                removeTask(taskId);

                if (s == null) {
                    final String msg = canRemoveOriginal ? "Move complete" : "Copy complete";

                    callback.onStatusUpdate(msg, fileName);

                    toast(msg);
                } else {
                    if (s instanceof InterruptedIOException) {
                        callback.onDismiss();
                    } else {
                        //if (s instanceof FileNotFoundException) {
                            // purge bogus entry from clipboard
                            // TODO set up a filesystem watch when putting stuff in clipboard
                        //    cbm.setPrimaryClip(ClipData.newPlainText("", ""));
                        //}

                        String result = s.getMessage();

                        if (TextUtils.isEmpty(result)) {
                            result = "Copy failed";
                        }

                        callback.onStatusUpdate(result, fileName);

                        toast(result);
                    }
                }
            }
        };

        final CancellationHelper ch = new CancellationHelper(at);

        tasks.put(taskId, ch);

        UpkeepService.start(this);

        callback.onProgressUpdate("Preparing to copy…");

        //noinspection unchecked
        at.executeOnExecutor(exec, ch);
    }

    private Toast toast;

    private void toast(String message) {
        if (!hasFgActivity) return;

        if (toast != null) {
            toast.cancel();
        }

        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT);

        toast.show();
    }

    private void removeTask(int taskId) {
        if (tasks.remove(taskId) != null && tasks.isEmpty()) {
            UpkeepService.stop(this);
        }
    }

    private void cleanupExecutors() {
        for (LongObjectCursor<SerialExecutor> c : execs) {
            if (c.value.isVacant()) {
                execs.remove(c.key);
            }
        }
    }

    Notification.Builder emptyBuilder;

    NotificationCallback makeCallback(int taskId) {
        final FileTasks act = this;

        final long when = System.currentTimeMillis();

        return new NotificationCallback() {
            @Override
            public void onStatusUpdate(String message, String subtext) {
                if (hasFgActivity) {
                    nfService.cancel(taskId);
                } else {
                    act.onStatusUpdate(when, taskId, message, subtext);
                }
            }

            @Override
            public void onProgressUpdate(String message) {
                act.onProgressUpdate(when, taskId, message);
            }

            @Override
            public void onProgressUpdate(int precentage) {
                act.onProgressUpdate(when, taskId, precentage);
            }

            @Override
            public void onDismiss() {
                nfService.cancel(taskId);
            }
        };
    }

    public void onStatusUpdate(long when, int taskId, String message, String subtext) {
        if (emptyBuilder == null) {
            emptyBuilder = newStatelessBuilder(this, taskId);
        }

        emptyBuilder.setWhen(when);
        emptyBuilder.setShowWhen(true);
        emptyBuilder.setContentText(message);
        emptyBuilder.setTicker(message);
        emptyBuilder.setSubText(subtext);

        nfService.notify(taskId, emptyBuilder.build());
    }

    public void onProgressUpdate(long when, int taskId, String message) {
        final Notification.Builder progressBuilder = newProgressBuilder(this, taskId);

        progressBuilder.setWhen(when);
        progressBuilder.setProgress(100, 100, true);
        progressBuilder.setContentText(message);
        progressBuilder.setTicker(message);

        nfService.notify(taskId, progressBuilder.build());
    }

    public void onProgressUpdate(long when, int taskId, int percentage) {
        final Notification.Builder progressBuilder = newProgressBuilder(this, taskId);

        progressBuilder.setWhen(when);
        progressBuilder.setProgress(100, percentage, false);
        progressBuilder.setContentText("Performing copy…");

        nfService.notify(taskId, progressBuilder.build());
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder newProgressBuilder(Context context, int taskId) {
        final Uri uniqueId = Uri.fromParts("id", String.valueOf(taskId), null);
        final Intent targetIntent = new Intent(Intent.ACTION_VIEW, uniqueId, context, MainActivity.class);
        final PendingIntent content = PendingIntent.getActivity(context, R.id.req_main, targetIntent, 0);

        final Notification.Builder builder = new Notification.Builder(context)
                .setContentTitle("Copying files")
                .setContentText("Preparing to copy…")
                .setContentIntent(content)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setSmallIcon(R.drawable.ic_provider_icon);

        if (Build.VERSION.SDK_INT >= 20) {
            final Intent cancelIntent = new Intent(MainActivity.ACTION_CANCEL, uniqueId, context, MainActivity.class);
            final PendingIntent cancel = PendingIntent.getActivity(context, R.id.req_task, cancelIntent, 0);
            builder.addAction(new Notification.Action.Builder(-1, "Cancel", cancel).build());

            builder.setLocalOnly(true);

            if (Build.VERSION.SDK_INT >= 21) {
                builder.setCategory(Notification.CATEGORY_PROGRESS);
            }
        }

        return builder;
    }

    private Notification.Builder newStatelessBuilder(Context context, int taskId) {
        final Uri uniqueId = Uri.fromParts("id", String.valueOf(taskId), null);
        final Intent targetIntent = new Intent(Intent.ACTION_VIEW, uniqueId, context, MainActivity.class);
        final PendingIntent content = PendingIntent.getActivity(context, R.id.req_main, targetIntent, 0);

        final Notification.Builder builder = new Notification.Builder(context)
                .setContentTitle("Copying files")
                .setContentText("Preparing to copy…")
                .setContentIntent(content)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setSmallIcon(R.drawable.ic_provider_icon);

        if (Build.VERSION.SDK_INT >= 20) {
            builder.setLocalOnly(false);
        }

        return builder;
    }

    void handleCancellationIntent(Intent data) {
        if (data == null) return;

        final Uri uri = data.getData();

        if (uri == null) return;

        final String ssp = uri.getSchemeSpecificPart();

        if (TextUtils.isEmpty(ssp)) return;

        int taskId = Integer.parseInt(ssp);

        CancellationHelper ch = tasks.remove(taskId);

        if (ch != null) {
            if (tasks.isEmpty()) {
                UpkeepService.stop(this);
            }

            // we have to do that here to account for tasks, that never get to run
            // (and thus don't have their onCancelled() called)
            nfService.cancel(taskId);

            ch.cancel();
        }
    }

    volatile boolean hasFgActivity;

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        hasFgActivity = true;

        started.add(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {
        started.removeAll(activity);

        hasFgActivity = !started.isEmpty();
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        started.removeAll(activity);

        hasFgActivity = !started.isEmpty();
    }

    private static final class SerialExecutor implements Executor {
        private final Executor delegate;

        private final ArrayDeque<Runnable> mTasks = new ArrayDeque<>();

        private Runnable active;

        private SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        synchronized boolean isVacant() {
            return active == null;
        }

        @MainThread
        public void execute(@NonNull final Runnable d) {
            final Runnable r = () -> {
                try {
                    d.run();
                } finally {
                    scheduleNext();
                }
            };

            synchronized (this) {
                if (active == null) {
                    this.active = r;

                    delegate.execute(r);
                } else {
                    mTasks.offer(r);
                }
            }
        }

        synchronized void scheduleNext() {
            final Runnable active = mTasks.poll();

            this.active = active;

            if (active != null) {
                delegate.execute(active);
            }
        }
    }
}
