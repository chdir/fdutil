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
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Process;
import android.os.StatFs;
import android.os.StrictMode;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.Keep;

import com.carrotsearch.hppc.ObjectIdentityHashSet;
import com.carrotsearch.hppc.cursors.ObjectCursor;

import net.sf.fakenames.fddemo.util.Utils;
import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.xfd.Interruption;
import net.sf.xfd.OS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FdDemoApp extends Application implements Thread.UncaughtExceptionHandler {
    public static final String EXTRA_HASH = "net.sf.xfd.E.hash";
    public static final String EXTRA_TRACE = "net.sf.xfd.E.trace";

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

    private FailSafe failsafe;

    @Override
    public void onCreate() {
        super.onCreate();

        failsafe = FailSafe.init(this);

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
            failsafe.handleException(t, e);
        }
    }

    public void resetErrorHandler() {
        if (failsafe != null) {
            Thread.setDefaultUncaughtExceptionHandler(failsafe.defaultHandler);
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

    private static final class FailSafe implements ActivityLifecycleCallbacks {
        private static final String HPROF_DIR_NAME = "heap_dumps";

        private static final String HPROF_NAME = DateFormat.getInstance().format(new Date());

        private final AtomicBoolean dead = new AtomicBoolean();

        private final TraceWalker traceWalker;

        private final Context ctx;
        private final String primaryHprofFile;
        private final String reservedHprofFile;
        private final Thread.UncaughtExceptionHandler defaultHandler;
        private final Intent intent;

        @Keep
        private byte[] ballast = new byte[1024 * 1024 * 2];

        public FailSafe(Application app) throws NoSuchAlgorithmException {
            this.traceWalker = new TraceWalker(32 * 1024);

            ctx = app.getBaseContext();

            String primaryHprofFile = null;
            final File primaryStorage = app.getExternalFilesDir(null);
            if (canWrite(primaryStorage)) {
                final File primary = new File(primaryStorage, HPROF_DIR_NAME);
                if (cleanupDir(primary, false)) {
                    primaryHprofFile = primary.getAbsolutePath() + '/' + HPROF_NAME;
                }
            }

            String reservedHprofFile = null;
            final File reservedStorage = getInternalFallbackStorage(app);
            final File reserved = new File(reservedStorage, HPROF_DIR_NAME);
            if (cleanupDir(reserved, true)) {
                reservedHprofFile = reserved.getAbsolutePath() + '/' + HPROF_NAME;
            }

            this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            this.primaryHprofFile = primaryHprofFile;
            this.reservedHprofFile = reservedHprofFile;

            final byte[] dummy = new byte[0];

            this.intent = new Intent()
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_HASH, dummy)
                    .putExtra(EXTRA_HASH, dummy)
                    .setClass(app, ErrorReportActivity.class);
        }

        private File getInternalFallbackStorage(Context ctx) {
            return Build.VERSION.SDK_INT >= 21 ? ctx.getNoBackupFilesDir() : ctx.getFilesDir();
        }

        private static boolean cleanupDir(File file, boolean internal) {
            if (!ensureDir(file)) {
                return false;
            }

            final File[] existingDumps = file.listFiles();

            if (existingDumps == null) {
                return true;
            }

            final int maxPendingDumps = internal ? 1 : 2;

            if (existingDumps.length > maxPendingDumps) {
                for (File f : existingDumps) {
                    if (!safeUnlink(f)) return false;
                }
            }

            return true;
        }

        private static boolean ensureDir(File path) {
            return path.isDirectory() || safeUnlink(path);
        }

        private static boolean safeUnlink(File file) {
            return file.delete() || !file.exists();
        }

        private static boolean canWrite(File file) {
            return file != null && file.canWrite();
        }

        static FailSafe init(Application app) {
            final FailSafe instance;
            try {
                instance = new FailSafe(app);

                app.registerActivityLifecycleCallbacks(instance);

                return instance;
            } catch (Throwable oops) {
                Utils.printTraceCautiously(oops);

                return null;
            }
        }

        private ObjectIdentityHashSet<Activity> running = new ObjectIdentityHashSet<>(4);

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            synchronized (this) {
                running.add(activity);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            synchronized (this) {
                running.remove(activity);
            }
        }

        public void onActivityStarted(Activity activity) {}
        public void onActivityResumed(Activity activity) {}
        public void onActivityPaused(Activity activity) {}
        public void onActivityStopped(Activity activity) {}
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        public void handleException(Thread t, Throwable e) {
            if (dead.compareAndSet(false, true)) {
                try {
                    if (e instanceof OutOfMemoryError) {
                        ballast = null;

                        safeHprofDump();
                    }

                    Utils.printTraceCautiously(e);

                    try {
                        final byte[] digest = traceWalker.hash(e);

                        final byte[] bytes = traceWalker.getPayload();

                        intent.putExtra(EXTRA_HASH, digest);
                        intent.putExtra(EXTRA_TRACE, bytes);

                        // Start the error report (in the worst case, it will just overlap our process).
                        // Doing it this early should guard against any possible deadlocks during the
                        // following step.
                        startCrashDialog();

                        ActivityManager am = (ActivityManager) ctx.getSystemService(ACTIVITY_SERVICE);

                        synchronized (this) {
                            for (ObjectCursor<Activity> activity : running) {
                                killDead(activity.value);
                            }
                        }

                        if (Build.VERSION.SDK_INT >= 21) {
                            List<ActivityManager.AppTask> tasks = am.getAppTasks();

                            for (ActivityManager.AppTask task : tasks) {
                                task.finishAndRemoveTask();
                            }
                        }

                        Process.killProcess(Process.myPid());
                    } catch (Throwable inner) {
                        Utils.printTraceCautiously(inner);
                    }
                } finally {
                    defaultHandler.uncaughtException(t, e);
                }
            }
        }

        private void startCrashDialog() {
            try {
                final AlarmManager mgr = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);

                final long bugReportTime = SystemClock.elapsedRealtime() + 1000;

                final PendingIntent crashPi = PendingIntent.getActivity(ctx, R.id.req_err_activity, intent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);

                mgr.setExact(AlarmManager.ELAPSED_REALTIME, bugReportTime, crashPi);
            }
            catch (Throwable endure) {}
        }

        private static void killDead(Activity activity) {
            try {
                try {
                    // cancel result delivery to prevent an exception from
                    // being thrown by finishAffinity etc.
                    activity.setResult(Activity.RESULT_CANCELED);
                }
                catch (RuntimeException endure) {}

                boolean justCallFinish = false;

                if (Build.VERSION.SDK_INT < 21) {
                    // try to use finishAffinity (even if this is not precisely what we want)
                    try {
                        activity.finishAffinity();
                    } catch (RuntimeException e) {
                        // let's hope for the best...
                        justCallFinish = true;
                    }
                }

                if (justCallFinish) {
                    activity.finish();
                }
            } catch (Throwable endure) {}
        }

        private void safeHprofDump() {
            try {
                String dumpLocation = null;

                StatFs fsStat;

                final long memTotal = Runtime.getRuntime().maxMemory();

                if (primaryHprofFile != null) {
                    try {
                        fsStat = new StatFs(primaryHprofFile);

                        if (fsStat.getAvailableBytes() > memTotal) {
                            dumpLocation = primaryHprofFile;
                        }
                    }
                    catch (Throwable endure) {}
                }

                if (reservedHprofFile != null && dumpLocation == null) {
                    fsStat = new StatFs(reservedHprofFile);

                    if (fsStat.getAvailableBytes() > memTotal) {
                        dumpLocation = reservedHprofFile;
                    }
                }

                if (dumpLocation != null) {
                    Debug.dumpHprofData(dumpLocation);
                }
            }
            catch (Throwable endure) {}
        }
    }

    private static final class TraceWalker {
        private static final int MAX_SIZE = 64 * 1024;

        private static final String CAUSE_CAPTION = "Caused by: ";

        private static final String SUPPRESSED_CAPTION = "Suppressed: ";

        private static final char SEP = ':';

        private static final char MAJOR_SEP1 = '-';

        private static final char MAJOR_SEP2 = '#';

        private final ObjectIdentityHashSet<Throwable> errors = new ObjectIdentityHashSet<>(60);

        private final MessageDigest sha = MessageDigest.getInstance("SHA-1");

        private final ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);

        private final CharBuffer asChars = buffer.asCharBuffer();

        private final byte[] resultStorage = new byte[20];

        private final ByteArrayOutputStream baos;

        private final PrintWriter pris;

        private TraceWalker(int preAllocSize) throws NoSuchAlgorithmException {
            if (preAllocSize > MAX_SIZE) {
                throw new IllegalArgumentException("Maximum allowed trace size is 64kb");
            }

            baos = new ByteArrayOutputStream(preAllocSize);

            pris = new PrintWriter(baos);
        }

        private byte[] hash(Throwable t) throws DigestException {
            emitTrace(t);

            if (buffer.position() != 0) {
                buffer.flip();
                sha.update(buffer);
            }

            pris.flush();
            pris.close();

            sha.digest(resultStorage, 0, resultStorage.length);

            return resultStorage;
        }

        private byte[] getPayload() {
            return baos.toByteArray();
        }

        private void print(Throwable t, String caption, int depth) {
            for (int i = 0; i < depth; ++i) {
                pris.print('\t');
            }

            if (!caption.isEmpty()) {
                pris.print(caption);
            }

            final Class<?> cl = t.getClass();

            final String s = cl.getName();

            final String message = t.getMessage();

            if (message != null) {
                pris.print(s);
                pris.print(": ");
                pris.println(message);
            } else {
                pris.println(s);
            }
        }

        private void print(StackTraceElement ste, int depth) {
            for (int i = 0; i < depth; ++i) {
                pris.print('\t');
            }

            pris.println("\tat " + ste);
        }

        private void emitTrace(Throwable t) {
            emitTrace(t, "", 0);
        }

        private void emitTrace(Throwable t, String caption, int depth) {
            if (!errors.add(t)) {
                return;
            }

            print(t, caption, depth);

            final Class<?> cl = t.getClass();

            hashString(cl.getName());

            for (StackTraceElement tracePart : t.getStackTrace()) {
                print(tracePart, depth);

                hashString(tracePart.getClassName());
                hashString(tracePart.getMethodName());
                hashInt(tracePart.getLineNumber());
            }

            emitSeparator(MAJOR_SEP1);

            for (Throwable suppressed : t.getSuppressed()) {
                emitTrace(suppressed, SUPPRESSED_CAPTION, depth + 1);
            }

            emitSeparator(MAJOR_SEP2);

            final Throwable cause = t.getCause();

            if (cause != null) {
                emitTrace(cause, CAUSE_CAPTION, depth);
            }
        }

        private void emitSeparator(char separator) {
            maybeFlush(Character.BYTES);

            buffer.putChar(separator);
        }

        private void hashInt(int lineNumber) {
            maybeFlush(Integer.BYTES);

            buffer.putInt(lineNumber);
        }

        private void maybeFlush(int spaceNeeded) {
            if (buffer.remaining() < spaceNeeded) {
                buffer.flip();
                sha.update(buffer);
                buffer.clear();
            }
        }

        private void hashString(String str) {
            maybeFlush(Character.BYTES);

            int start = 0, end = Math.min(buffer.remaining() / 2, str.length());

            while (start != end) {
                int bufPos = buffer.position();

                asChars.position(bufPos / 2);
                asChars.put(str, start, end);

                buffer.position(bufPos + (end - start) * 2);

                if (!buffer.hasRemaining()) {
                    buffer.flip();
                    sha.update(buffer);
                    buffer.clear();
                }

                start = end;

                end = Math.min(start + asChars.remaining(), str.length());
            }

            maybeFlush(Character.BYTES);

            emitSeparator(SEP);
        }
    }
}
