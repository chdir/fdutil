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

import android.annotation.SuppressLint;
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
import net.sf.xfd.Copy;
import net.sf.xfd.CrappyDirectory;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.Fd;
import net.sf.xfd.FsType;
import net.sf.xfd.InterruptedIOException;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.NativeBits;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.UnreliableIterator;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static net.sf.xfd.NativeBits.O_CREAT;
import static net.sf.xfd.NativeBits.O_NOCTTY;
import static net.sf.xfd.NativeBits.O_NOFOLLOW;
import static net.sf.xfd.NativeBits.O_NONBLOCK;
import static net.sf.xfd.provider.ProviderBase.extractName;
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

    private SerialExecutor getExecutor(long fsId) {
        SerialExecutor exec = execs.get(fsId);
        if (exec == null) {
            if (execs.size() > 3) {
                cleanupExecutors();
            }

            exec = new SerialExecutor(ioExec);
            execs.put(fsId, exec);
        }
        return exec;
    }

    private int nextTask() {
        if (++lastTaskId < 0) {
            lastTaskId = 0;
        }

        return lastTaskId;
    }

    public void copy(OS os, BaseDirLayout layout, FileObject sourceFile, @DirFd int dir, boolean canRemoveOriginal) throws IOException {
        final Context context = this;

        final Stat targetDirStat = new Stat();

        os.fstat(dir, targetDirStat);

        final SerialExecutor exec = getExecutor(targetDirStat.st_dev);

        final MountInfo.Mount m = layout.getFs(targetDirStat.st_dev);

        final boolean canUseExtChars = m != null && isPosix(m.fstype);

        final int taskId = nextTask();

        final NotificationCallback callback = makeCallback(taskId);

        @SuppressLint("StaticFieldLeak")
        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            private CharSequence fileName;

            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                final CancellationHelper ch = params[0];

                boolean copied = false;
                FileObject targetFile = null;
                try (Closeable c = sourceFile) {
                    final CharSequence desc = sourceFile.getDescription(ch);

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

                    callback.onStatusUpdate(msg, fileName.toString());

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

                        callback.onStatusUpdate(result, fileName.toString());

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

    public void rmdir(OS os, CharSequence dirName, @DirFd int dir) throws IOException {
        // XXX suspect conversion
        final String dirNameString = dirName.toString();

        final @DirFd int dirCopy = os.dup(dir);

        final @DirFd int dirFd = os.opendirat(dir, dirName);

        final Stat targetDirStat = new Stat();

        os.fstat(dirFd, targetDirStat);

        final SerialExecutor exec = getExecutor(targetDirStat.st_dev);

        final int taskId = nextTask();

        final NotificationCallback callback = makeCallback(taskId);

        @SuppressLint("StaticFieldLeak")
        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                try (Directory directory = os.list(dirFd)) {
                    callback.onProgressUpdate("Deleting files");

                    removeContents(targetDirStat, new Directory.Entry(), directory, dirFd);

                    os.unlinkat(dirCopy, dirName, OS.AT_REMOVEDIR);

                    return null;
                } catch (ErrnoException errnoe) {
                    return errnoe.code() == ErrnoException.ENOENT ? null : errnoe;
                } catch (CancellationException | InterruptedIOException t) {
                    Thread.interrupted();

                    return t;
                } catch (Throwable t) {
                    t.printStackTrace();

                    return t;
                } finally {
                    os.dispose(dirCopy);
                    os.dispose(dirFd);
                }
            }

            private void removeContents(Stat tempStat,
                                        Directory.Entry tempEntry,
                                        Directory directory,
                                        @DirFd int dirFd) throws IOException
            {
                if (isCancelled()) {
                    throw new CancellationException();
                }

                final UnreliableIterator<Directory.Entry> iterator = directory.iterator();

                while (iterator.moveToNext()) {
                    iterator.get(tempEntry);

                    final CharSequence entryName = tempEntry.name;

                    if (".".contentEquals(entryName) || "..".contentEquals(entryName)) continue;

                    try {
                        if (tempEntry.type == null) {
                            os.fstatat(dirFd, tempEntry.name, tempStat, OS.AT_SYMLINK_NOFOLLOW);

                            tempEntry.type = tempStat.type;
                        }

                        if (tempEntry.type == FsType.DIRECTORY) {
                            @DirFd int innerDirFd = os.openat(dirFd, entryName,
                                    NativeBits.O_DIRECTORY | NativeBits.O_NOFOLLOW, 0);

                            try (Directory newDir = os.list(innerDirFd)) {
                                removeContents(tempStat, tempEntry, newDir, innerDirFd);

                                os.unlinkat(dirFd, entryName, OS.AT_REMOVEDIR);
                            } finally {
                                os.dispose(innerDirFd);
                            }
                        } else {
                            os.unlinkat(dirFd, tempEntry.name, 0);
                        }
                    } catch (ErrnoException errno) {
                        if (errno.code() != ErrnoException.ENOENT) {
                            throw new IOException("Failed to remove " + entryName + ": " + errno.toString(), errno);
                        }
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
                    final String msg = "Deletion complete";

                    callback.onStatusUpdate(msg, dirNameString);

                    toast(msg);
                } else {
                    if (s instanceof InterruptedIOException) {
                        callback.onDismiss();
                    } else {
                        String result = s.getMessage();

                        if (TextUtils.isEmpty(result)) {
                            result = "Deletion failed";
                        }

                        callback.onStatusUpdate(result, dirNameString);

                        toast(result);
                    }
                }
            }
        };

        final CancellationHelper ch = new CancellationHelper(at);

        tasks.put(taskId, ch);

        UpkeepService.start(this);

        callback.onProgressUpdate("Preparing to delete…");

        //noinspection unchecked
        at.executeOnExecutor(exec, ch);
    }

    private static String typeToName(FsType type) {
        switch (type) {
            case LINK:
                return "link";
            case DIRECTORY:
                return "directory";
            case NAMED_PIPE:
                return "pipe";
            case DOMAIN_SOCKET:
                return "socket";
            case FILE:
                return "regular file";
            case CHAR_DEV:
            case BLOCK_DEV:
                return "special device";
            default:
                return "special file";
        }
    }
    private static void onConflict(Stat srcStat, CharSequence source, Stat dstStat, CharSequence target) throws IOException {
        String sourceType = typeToName(srcStat.type);

        String targetType;

        if (dstStat == null) {
            targetType = "non " + sourceType;
        } else {
            targetType = typeToName(dstStat.type);
        }

        throw new IOException(
                "cannot overwrite " + targetType + " " + target + " with " + sourceType + " " + source
        );
    }

    public void copyDir(OS os, BaseDirLayout layout, CharSequence dirPath, CharSequence newName, @DirFd int destDir) throws IOException {
        final Directory.Entry tempEntry = new Directory.Entry();
        final Stat srcDirStat = new Stat();
        final Stat trgDirStat = new Stat();

        CharSequence dirName = extractName(dirPath);

        os.fstat(destDir, trgDirStat);

        final @DirFd int destDirCopy = os.dup(destDir);

        final SerialExecutor exec = getExecutor(trgDirStat.st_dev);

        final int taskId = nextTask();

        final NotificationCallback callback = makeCallback(taskId);

        @SuppressLint("StaticFieldLeak")
        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                try {
                    final @DirFd int srcDirFd = os.opendir(dirPath);

                    try {
                        os.fstat(srcDirFd, srcDirStat);

                        if (srcDirStat.st_dev == trgDirStat.st_dev
                                && srcDirStat.st_ino == trgDirStat.st_ino) {
                            throw new IOException("cannot copy a directory into self");
                        }

                        callback.onProgressUpdate("Copying files");

                        if (!os.mkdirat(destDirCopy, newName, OS.DEF_DIR_MODE)) {
                            os.fstatat(destDirCopy, newName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                            if (trgDirStat.type != FsType.DIRECTORY) {
                                onConflict(trgDirStat, newName, srcDirStat, extractName(dirPath));
                            }
                        }

                        final @DirFd int dstDirFd = os.opendirat(destDirCopy, newName);

                        try (Directory srcDir = os.list(srcDirFd);
                             Copy helper = os.copy()) {

                            copyContents(helper, srcDir, srcDirFd, dstDirFd);
                        }

                        return null;
                    } finally {
                        os.dispose(srcDirFd);
                    }
                } catch (ErrnoException errnoe) {
                    return errnoe;
                } catch (CancellationException | InterruptedIOException t) {
                    Thread.interrupted();

                    return t;
                } catch (Throwable t) {
                    t.printStackTrace();

                    return t;
                } finally {
                    os.dispose(destDirCopy);
                }
            }

            private final int DIR_OPEN_FLAGS =
                            NativeBits.O_DIRECTORY |
                            NativeBits.O_NOCTTY |
                            NativeBits.O_NOFOLLOW;

            private void copyContents(Copy helper,
                                      Directory srcDir,
                                      @DirFd int srcDirFd,
                                      @DirFd int dstDirFd) throws IOException
            {
                if (isCancelled()) {
                    throw new CancellationException();
                }

                final UnreliableIterator<Directory.Entry> iterator = srcDir.iterator();

                boolean didStat;

                while (iterator.moveToNext()) {
                    iterator.get(tempEntry);

                    final CharSequence entryName = tempEntry.name;

                    if (".".contentEquals(entryName) || "..".contentEquals(entryName)) continue;

                    if (tempEntry.type == null) {
                        os.fstatat(srcDirFd, tempEntry.name, srcDirStat, OS.AT_SYMLINK_NOFOLLOW);

                        tempEntry.type = srcDirStat.type;

                        didStat = true;
                    } else {
                        srcDirStat.type = tempEntry.type;
                        srcDirStat.st_ino = tempEntry.ino;

                        didStat = false;
                    }

                    switch (tempEntry.type) {
                        case DIRECTORY:
                            if (!os.mkdirat(destDir, entryName, OS.DEF_DIR_MODE)) {
                                os.fstatat(destDir, entryName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                                if (trgDirStat.type != FsType.DIRECTORY) {
                                    onConflict(trgDirStat, entryName, srcDirStat, entryName);
                                }
                            }

                            @DirFd int foundDirFd =
                                    os.openat(destDir, entryName, DIR_OPEN_FLAGS, 0);

                            try {
                                boolean closedDir = false;

                                @DirFd int createdDirFd = os.openat(destDir, entryName,
                                        NativeBits.O_DIRECTORY | NativeBits.O_NOFOLLOW, 0);

                                Directory existingDir = os.list(foundDirFd);
                                try {
                                    // A simple yet important optimisation: preload
                                    // first N  items of directory, then close both descriptor
                                    // and native window if we exhausted it. This will allow
                                    // for much deeper nested traversal as long as directory
                                    // contents are small enough
                                    CrappyDirectory cache = new CrappyDirectory(existingDir);

                                    UnreliableIterator<?> readahead = cache.iterator();

                                    if (!readahead.moveToPosition(150) && readahead.getPosition() >= 0) {
                                        existingDir.close();

                                        closedDir = true;
                                    }

                                    if (readahead.moveToFirst()) {
                                        copyContents(helper, cache, foundDirFd, createdDirFd);
                                    }
                                } finally {
                                    if (!closedDir) {
                                        existingDir.close();
                                    }

                                    os.dispose(foundDirFd);
                                }
                            } finally {
                                os.dispose(foundDirFd);
                            }

                            break;
                        case FILE:
                            @Fd int origFileFd =
                                    os.openat(srcDirFd, entryName, O_NOFOLLOW, OS.DEF_DIR_MODE);

                            try {
                                if (!didStat) {
                                    os.fstat(origFileFd, srcDirStat);
                                }

                                @Fd int fileFd =
                                        os.openat(dstDirFd, entryName, O_NONBLOCK | O_NOFOLLOW | O_CREAT | O_NOCTTY, srcDirStat.mode);

                                try {
                                    os.fstat(fileFd, trgDirStat);

                                    if (trgDirStat.type != FsType.FILE) {
                                        onConflict(trgDirStat, entryName, srcDirStat, entryName);
                                    }

                                    if (trgDirStat.mode != srcDirStat.mode) {
                                        os.fchmod(fileFd, srcDirStat.mode);
                                    }

                                    long dataSize = srcDirStat.st_size;

                                    if (srcDirStat.st_size == 0) {
                                        return;
                                    }

                                    if (srcDirStat.st_dev == trgDirStat.st_dev &&
                                            srcDirStat.st_ino == trgDirStat.st_ino) {
                                        // the target file is already the same as source
                                        return;
                                    }

                                    try {
                                        dataSize = helper.transfer(origFileFd, srcDirStat, fileFd, srcDirStat, dataSize);
                                    } catch (InterruptedIOException iie) {
                                        dataSize = iie.bytesTransferred;
                                    } finally {
                                        if (dataSize < trgDirStat.st_size) {
                                            os.ftruncate(fileFd, dataSize);
                                        }
                                    }
                                } finally {
                                    os.close(fileFd);
                                }
                            } finally {
                                os.close(origFileFd);
                            }

                            break;

                        case LINK:
                            CharSequence source = os.readlinkat(srcDirFd, entryName);

                            try {
                                os.symlinkat(source, dstDirFd, entryName);
                            } catch (ErrnoException errno) {
                                if (errno.code() != ErrnoException.EEXIST) {
                                    throw errno;
                                }

                                CharSequence dest = os.readlinkat(destDir, entryName);

                                os.fstatat(destDir, entryName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                                if (trgDirStat.type == srcDirStat.type && source.equals(dest)) {
                                    continue;
                                }

                                onConflict(srcDirStat, entryName, trgDirStat, entryName);
                            }

                            break;

                        default:
                            if (!didStat) {
                                os.fstatat(srcDirFd, entryName, srcDirStat, OS.AT_SYMLINK_NOFOLLOW);
                            }

                            try {
                                os.mknodat(dstDirFd, entryName, OS.DEF_FILE_MODE, srcDirStat.st_rdev);
                            } catch (ErrnoException errno) {
                                if (errno.code() != ErrnoException.EEXIST) {
                                    throw errno;
                                }

                                os.fstatat(srcDirFd, entryName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                                if (trgDirStat.type != srcDirStat.type
                                        || trgDirStat.st_rdev != srcDirStat.st_rdev) {

                                    onConflict(srcDirStat, entryName, trgDirStat, entryName);
                                }
                            }
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
                    final String msg = "Copy complete";

                    callback.onStatusUpdate(msg, dirName.toString());

                    toast(msg);
                } else {
                    if (s instanceof InterruptedIOException) {
                        callback.onDismiss();
                    } else {
                        String result = s.getMessage();

                        if (TextUtils.isEmpty(result)) {
                            result = "Copy failed";
                        }

                        callback.onStatusUpdate(result, dirName.toString());

                        toast(result);
                    }
                }
            }
        };

        final CancellationHelper ch = new CancellationHelper(at);

        tasks.put(taskId, ch);

        UpkeepService.start(this);

        callback.onProgressUpdate("Preparing to delete…");

        //noinspection unchecked
        at.executeOnExecutor(exec, ch);
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
