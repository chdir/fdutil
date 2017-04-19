package net.sf.fakenames.fddemo.service;

import android.app.Notification;
import android.content.ClipData;
import android.content.Context;
import android.os.RemoteException;

import net.sf.fakenames.fddemo.CancellationHelper;
import net.sf.fakenames.fddemo.FileObject;
import net.sf.fakenames.fddemo.FilenameUtil;
import net.sf.fakenames.fddemo.FsFile;
import net.sf.xfd.DirFd;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.provider.ProviderBase;

import org.parceler.Parcel;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

@Parcel
public final class CopyTask implements FileTask {
    private static final AtomicLong taskIds = new AtomicLong();

    long id;
    ClipData source;
    String target;
    Notification n;

    transient Notification.Builder builder;

    CopyTask() {
    }

    public void copy(OS os, Context context, CancellationHelper ch, NotificationCallback callback) throws IOException, RemoteException {
        Notification n = this.n;

        final String parent = ProviderBase.extractParent(target);

        final Stat targetStat = new Stat();

        @DirFd int parentFd = os.opendir(parent);
        try {
            os.fstatat(parentFd, parent, targetStat, 0);

            try (FileObject sourceFile = FileObject.fromClip(os, context, source))
            {
                assert sourceFile != null;

                final String desc = sourceFile.getDescription(ch);

                final String fileName = FilenameUtil.sanitize(desc);

                final FsFile fsTarget = new FsFile(parentFd, fileName, targetStat);

                try (FileObject targetFile = FileObject.fromTempFile(os, context, fsTarget)) {
                    sourceFile.copyTo(targetFile, ch, callback);
                }
            }
        } finally {
            os.dispose(parentFd);
        }

        callback.notify();
    }

    private void refreshProgress(Context context, NotificationCallback callback, int percent) {
        if (builder == null) {
            //builder = newBuilder(context, id);
        }


        builder.setProgress(0, 0, true);

        n = builder.build();

        //callback.onUpdate();
    }



    public static CopyTask createNew(Context context, ClipData source, String target) {
        final CopyTask t = new CopyTask();

        final long taskId = taskIds.incrementAndGet();

        t.id = taskId;
        t.source = source;
        t.target = target;

        t.n = null;//t.newBuilder(context, taskId).build();

        return t;
    }

    @Override
    public Notification getNotification() {
        return n;
    }

    @Override
    public Void call() throws Exception {
        return null;
    }
}
