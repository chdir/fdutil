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
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
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
import net.sf.fakenames.fddemo.view.ConfirmationDialog;
import net.sf.xfd.Copy;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.Fd;
import net.sf.xfd.FsType;
import net.sf.xfd.InterruptedIOException;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.NativeBits;
import net.sf.xfd.NativeString;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.UnreliableIterator;
import net.sf.xfd.provider.ProviderBase;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static net.sf.xfd.Directory.READDIR_REUSE_STRINGS;
import static net.sf.xfd.Directory.READDIR_SMALL_BUFFERS;
import static net.sf.xfd.NativeBits.O_CREAT;
import static net.sf.xfd.NativeBits.O_NOCTTY;
import static net.sf.xfd.NativeBits.O_NOFOLLOW;
import static net.sf.xfd.NativeBits.O_NONBLOCK;
import static net.sf.xfd.NativeBits.O_TRUNC;
import static net.sf.xfd.OS.DEF_FILE_MODE;
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

    static boolean isFakeDotDir(CharSequence cs) {
        if (cs.getClass() == NativeString.class) {
            NativeString ns = (NativeString) cs;
            byte[] bytes = ns.getBytes();

            switch (ns.byteLength()) {
                default:
                    return false;
                case 1:
                    return bytes[0] == '.';
                case 2:
                    return bytes[0] == '.' && bytes[1] == '.';
            }
        }

        return ".".contentEquals(cs) || "..".contentEquals(cs);
    }

    public void copy(OS os, BaseDirLayout layout, List<FileObject> sourceFiles, @DirFd int dir, boolean canRemoveOriginal) throws IOException {
        final Context context = this;

        final Directory.Entry tempEntry = new Directory.Entry();
        final Stat srcDirStat = new Stat();
        final Stat trgDirStat = new Stat();

        os.fstat(dir, trgDirStat);

        final SerialExecutor exec = getExecutor(trgDirStat.st_dev);

        final MountInfo.Mount m = layout.getFs(trgDirStat.st_dev);

        final int taskId = nextTask();

        final NotificationCallback callback = makeCallback(taskId);

        UpkeepService.start(this);

        callback.onProgressUpdate("Preparing to copy…");

        final ArrayDeque<FsDir> reusedDirs = new ArrayDeque<>(100);

        final @DirFd int destDirCopy = os.dup(dir);

        @SuppressLint("StaticFieldLeak")
        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            private CharSequence newName;
            private Copy helper;
            private boolean skipPermissionChecks;

            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                // get rid of any pending interruption flags
                // (neither AsyncTask nor underlying FutureTask promise to do so)
                // Even if the user has already interrupted us, we can still find out
                // about that from the isCancelled()
                Thread.interrupted();

                final CancellationHelper ch = params[0];

                boolean copied = false;
                FileObject targetFile = null;

                try {
                    for (FileObject sourceFile : sourceFiles) {
                        try (Closeable c = sourceFile) {
                            newName = sourceFile.getDescription(ch);

                            if (sourceFile.isDirectory(ch)) {
                                if (!os.mkdirat(destDirCopy, newName, OS.DEF_DIR_MODE)) {
                                    os.fstatat(destDirCopy, newName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                                    if (trgDirStat.type != FsType.DIRECTORY) {
                                        onConflict(trgDirStat, newName, srcDirStat, newName);
                                    }
                                }

                                if (!tryFsCopy(sourceFile, ch)) {
                                    throw new IOException("Directory copy failed");
                                }
                            } else {
                                if (os.faccessat(dir, newName, OS.F_OK)) {
                                    throw new IOException("File exists!");
                                }

                                final FsFile tmpFileInfo = new FsFile(destDirCopy, newName, trgDirStat);

                                targetFile = FileObject.fromTempFile(os, context, tmpFileInfo);

                                copied = canRemoveOriginal
                                        ? sourceFile.moveTo(targetFile, ch, callback)
                                        : sourceFile.copyTo(targetFile, ch, callback);

                                if (!copied) {
                                    throw new IOException("Copy failed");
                                }
                            }
                        } finally {
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
                        }
                    }
                }  catch (CancellationException | InterruptedIOException t) {
                    Thread.interrupted();

                    return t;
                } catch (FileNotFoundException e) {
                    nfService.cancel(taskId);

                    return e;
                } catch (Throwable t) {
                    t.printStackTrace();

                    return t;
                } finally {
                    os.dispose(destDirCopy);
                }

                return null;
            }

            private boolean tryFsCopy(FileObject sourceFile, CancellationHelper ch) throws IOException {
                final @DirFd int srcDirFd, dstDirFd;

                try (AssetFileDescriptor srcDirAssetFd = sourceFile.openAsDirectory(ch)) {
                    srcDirFd = srcDirAssetFd.getParcelFileDescriptor().detachFd();

                    try {
                        os.fstat(srcDirFd, srcDirStat);
                    } catch (IOException t) {
                        LogUtil.logCautiously("Bad directory fd", t);

                        os.close(srcDirFd);

                        return false;
                    }
                } catch (Throwable err) {
                    LogUtil.logCautiously("Failed to open directory fd", err);

                    return false;
                }

                try {
                    if (srcDirStat.type != FsType.DIRECTORY) {
                        // wtf?
                        return false;
                    }

                    if (srcDirStat.st_dev == trgDirStat.st_dev
                            && srcDirStat.st_ino == trgDirStat.st_ino) {
                        throw new IOException(context.getString(R.string.err_self_copy));
                    }

                    dstDirFd = os.opendirat(destDirCopy, newName);

                    try {
                        if (canRemoveOriginal && srcDirStat.st_dev == trgDirStat.st_dev) {
                            final CharSequence sourcePath;
                            try {
                                sourcePath = os.readlinkat(DirFd.NIL, ProviderBase.fdPath(srcDirFd));
                            } catch (IOException renameError) {
                                LogUtil.logCautiously("Failed to rename/move directory", renameError);

                                return false;
                            }

                            os.renameat(DirFd.NIL, sourcePath, dstDirFd, newName);

                            return true;
                        }

                        // if either source or target are on permissionless storage,
                        // skip stat() and chmod() calls, because those won't do anything useful
                        skipPermissionChecks = layout.hasPermissionlessFs(srcDirStat.st_dev, trgDirStat.st_dev);

                        tempEntry.name = new NativeString(new byte[256 * 3 / 2]);

                        try (Directory srcDir = os.list(srcDirFd, READDIR_REUSE_STRINGS);
                             Copy helper = os.copy()) {

                            this.helper = helper;

                            copyContents(srcDir, srcDirFd, dstDirFd);
                        } finally {
                            callback.onProgressUpdate("Flushing buffers");

                            for (FsDir cached : reusedDirs) {
                                cached.directory.close();

                                os.close(cached.fd);
                            }
                        }

                        os.fsync(dstDirFd);

                        return true;
                    } finally {
                        os.close(dstDirFd);
                    }
                } finally {
                    os.close(srcDirFd);
                }
            }

            private final int DIR_OPEN_FLAGS =
                    NativeBits.O_DIRECTORY | NativeBits.O_NOCTTY | NativeBits.O_NOFOLLOW;

            private void copyContents(Directory srcDir,
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

                    if (isFakeDotDir(entryName)) continue;

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
                            if (!didStat) {
                                if (skipPermissionChecks) {
                                    srcDirStat.mode = OS.DEF_DIR_MODE;
                                } else {
                                    os.fstat(srcDirFd, srcDirStat);
                                }
                            }

                            if (os.mkdirat(dstDirFd, entryName, srcDirStat.mode)) {
                                trgDirStat.mode = srcDirStat.mode;
                            } else {
                                os.fstatat(dstDirFd, entryName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

                                if (trgDirStat.type != FsType.DIRECTORY) {
                                    onConflict(trgDirStat, entryName, srcDirStat, entryName);
                                }
                            }

                            @DirFd int createdDirFd = os.openat(dstDirFd, entryName,
                                    DIR_OPEN_FLAGS, 0);

                            try {
                                @DirFd int foundDirFd = os.openat(srcDirFd, entryName, DIR_OPEN_FLAGS, 0);

                                if (!skipPermissionChecks && srcDirStat.mode != trgDirStat.mode) {
                                    try {
                                        os.fchmod(foundDirFd, trgDirStat.mode);
                                    } catch (IOException ioerr) {
                                        LogUtil.logCautiously("Failed to chmod directory " + entryName, ioerr);
                                    }
                                }

                                FsDir cached = reusedDirs.poll();

                                Directory directory;
                                if (cached == null) {
                                    directory = os.list(foundDirFd, READDIR_REUSE_STRINGS | READDIR_SMALL_BUFFERS);

                                    cached = new FsDir(directory, foundDirFd);
                                } else {
                                    directory = cached.directory;

                                    os.dup2(foundDirFd, cached.fd);
                                }

                                try {
                                    copyContents(directory, foundDirFd, createdDirFd);
                                } finally {
                                    reusedDirs.push(cached);
                                }
                            } finally {
                                os.close(createdDirFd);
                            }

                            break;
                        case FILE:
                            @Fd int origFileFd =
                                    os.openat(srcDirFd, entryName, O_NOFOLLOW, OS.DEF_DIR_MODE);

                            try {
                                int newFileFlags = O_NONBLOCK | O_NOFOLLOW | O_CREAT | O_NOCTTY;

                                if (skipPermissionChecks) {
                                    // fast path: truncate during creation and use default
                                    // file mode to avoid extra stat() call
                                    newFileFlags |= O_TRUNC;

                                    srcDirStat.mode = DEF_FILE_MODE;
                                } else if (!didStat) {
                                    os.fstat(origFileFd, srcDirStat);
                                }

                                @Fd int fileFd =
                                        os.openat(dstDirFd, entryName, newFileFlags, srcDirStat.mode);

                                try {
                                    if (skipPermissionChecks) {
                                        // fast path: use dumb read/write copy because we don't
                                        // know the size (and dumb filesystems don't support
                                        // more advanced copy modes anyway)
                                        srcDirStat.type = null;

                                        helper.transfer(origFileFd, srcDirStat, fileFd, srcDirStat, Long.MAX_VALUE);

                                        continue;
                                    }

                                    os.fstat(fileFd, trgDirStat);

                                    if (trgDirStat.type != FsType.FILE) {
                                        onConflict(trgDirStat, entryName, srcDirStat, entryName);
                                    }

                                    if (trgDirStat.mode != srcDirStat.mode) {
                                        os.fchmod(fileFd, srcDirStat.mode);
                                    }

                                    long dataSize = srcDirStat.st_size;

                                    if (srcDirStat.st_size == 0) {
                                        continue;
                                    }

                                    if (srcDirStat.st_dev == trgDirStat.st_dev &&
                                            srcDirStat.st_ino == trgDirStat.st_ino) {
                                        // the target file is already the same as source
                                        continue;
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

                                CharSequence dest = os.readlinkat(dstDirFd, entryName);

                                os.fstatat(dstDirFd, entryName, trgDirStat, OS.AT_SYMLINK_NOFOLLOW);

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
                                os.mknodat(dstDirFd, entryName,
                                        srcDirStat.type.getFileType() | DEF_FILE_MODE, srcDirStat.st_rdev);
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

                    if (canRemoveOriginal) {
                        os.unlinkat(srcDirFd, tempEntry.name,
                                tempEntry.type == FsType.DIRECTORY ? OS.AT_REMOVEDIR : 0);
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

                    callback.onStatusUpdate(msg, newName.toString());

                    toast(msg);

                    return;
                }

                if (s instanceof InterruptedIOException) {
                    callback.onDismiss();

                    return;
                }

                //if (s instanceof FileNotFoundException) {
                // purge bogus entry from clipboard
                // TODO set up a filesystem watch when putting stuff in clipboard
                //    cbm.setPrimaryClip(ClipData.newPlainText("", ""));
                //}

                String result = s.getMessage();

                if (TextUtils.isEmpty(result)) {
                    result = "Copy failed";
                }

                callback.onStatusUpdate(result, newName.toString());

                toast(result);
            }
        };

        final CancellationHelper ch = new CancellationHelper(at);

        tasks.put(taskId, ch);

        //noinspection unchecked
        at.executeOnExecutor(exec, ch);
    }

    private static final class FsDir {
        Directory directory;
        int fd;

        private FsDir(Directory directory, int fd) {
            this.directory = directory;
            this.fd = fd;
        }
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
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_provider_icon, "Cancel", cancel).build());

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

    public void rmdir(OS os, Directory.Entry[] dirNames, @DirFd int dir) throws IOException {
        final @DirFd int dirCopy = os.dup(dir);

        final Stat targetDirStat = new Stat();

        os.fstat(dir, targetDirStat);

        final SerialExecutor exec = getExecutor(targetDirStat.st_dev);

        final int taskId = nextTask();

        final NotificationCallback callback = makeCallback(taskId);

        @SuppressLint("StaticFieldLeak")
        final AsyncTask<CancellationHelper, ?, ?> at = new AsyncTask<CancellationHelper, Void, Throwable>() {
            @Override
            protected Throwable doInBackground(CancellationHelper... params) {
                try {
                    Directory.Entry tempEntry = new Directory.Entry();

                    for (Directory.Entry removable : dirNames) {
                        if (isCancelled()) {
                            throw new CancellationException();
                        }

                        try {
                            try {
                                FsType fsType = removable.type;
                                if (fsType == null) {
                                    os.fstatat(dirCopy, removable.name, targetDirStat, 0);
                                    fsType = targetDirStat.type;
                                }

                                os.unlinkat(dirCopy, removable.name, fsType == FsType.DIRECTORY ? OS.AT_REMOVEDIR : 0);
                            } catch (ErrnoException errno) {
                                if (errno.code() != ErrnoException.ENOTEMPTY) {
                                    throw errno;
                                }

                                final @DirFd int dirFd = os.opendirat(dir, removable.name);
                                try (Directory directory = os.list(dirFd, READDIR_SMALL_BUFFERS | READDIR_REUSE_STRINGS)) {
                                    callback.onProgressUpdate("Deleting files");

                                    tempEntry.name = new NativeString(new byte[256 * 3 / 2]);

                                    removeContents(targetDirStat, tempEntry, directory, dirFd);

                                    os.unlinkat(dirCopy, removable.name, OS.AT_REMOVEDIR);
                                } finally {
                                    os.dispose(dirFd);
                                }
                            }
                        } catch (ErrnoException errnoe) {
                            if (errnoe.code() != ErrnoException.ENOENT) {
                                throw new IOException("Failed to remove " + removable.name + ": " + errnoe.toString(), errnoe);
                            }
                        }
                    }
                } catch (CancellationException | InterruptedIOException t) {
                    Thread.interrupted();

                    return t;
                } catch (Throwable t) {
                    t.printStackTrace();

                    return t;
                } finally {
                    os.dispose(dirCopy);
                }

                return null;
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

                    if (isFakeDotDir(entryName)) continue;

                    try {
                        if (tempEntry.type == null) {
                            os.fstatat(dirFd, tempEntry.name, tempStat, OS.AT_SYMLINK_NOFOLLOW);

                            tempEntry.type = tempStat.type;
                        }

                        if (tempEntry.type == FsType.DIRECTORY) {
                            @DirFd int innerDirFd = os.openat(dirFd, entryName,
                                    NativeBits.O_DIRECTORY | NativeBits.O_NOFOLLOW, 0);

                            try (Directory newDir = os.list(innerDirFd, READDIR_SMALL_BUFFERS | READDIR_REUSE_STRINGS)) {
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

                    callback.onStatusUpdate(msg, "Done");

                    toast(msg);
                } else {
                    if (s instanceof InterruptedIOException) {
                        callback.onDismiss();
                    } else {
                        String result = s.getMessage();

                        if (TextUtils.isEmpty(result)) {
                            result = "Deletion failed";
                        }

                        callback.onStatusUpdate(result, "Done");

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
