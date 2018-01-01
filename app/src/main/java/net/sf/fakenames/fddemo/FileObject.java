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

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.support.annotation.CallSuper;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;

import net.sf.fakenames.fddemo.service.NotificationCallback;
import net.sf.xfd.Copy;
import net.sf.xfd.DirFd;
import net.sf.xfd.Fd;
import net.sf.xfd.FsType;
import net.sf.xfd.LogUtil;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.provider.ProviderBase;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH;
import static net.sf.xfd.provider.ProviderBase.extractName;

/**
 * Container for information about Uri-addressable object
 */
public abstract class FileObject implements Closeable {
    protected final Context context;
    protected final OS os;
    protected final Stat stat;

    protected CharSequence description;

    protected AtomicBoolean closed = new AtomicBoolean();

    FileObject(OS os, Context context) {
        this(os, context, new Stat());
    }

    FileObject(OS os, Context context, Stat stat) {
        this.os = os;
        this.context = context;
        this.stat = stat;
    }

    @WorkerThread
    public abstract AssetFileDescriptor openForReading(CancellationHelper ch) throws IOException, RemoteException, RuntimeException;

    @WorkerThread
    public abstract AssetFileDescriptor openForWriting(CancellationHelper ch) throws IOException, RemoteException, RuntimeException;

    @WorkerThread
    public abstract long getMaxSize(CancellationHelper ch) throws IOException, RemoteException, RuntimeException;

    @WorkerThread
    public CharSequence getDescription(CancellationHelper ch) throws IOException, RemoteException, RuntimeException {
        return TextUtils.isEmpty(description) ? "unnamed" + new Random().nextLong() : description;
    }

    // Any smaller size is so small that using anything besides read/write is silly: the file is
    // expected to almost certainly fit in CPU cache or get there automagically via builtin
    // memory readahead mechanics.
    private static final int SPLICE_THRESHOLD = 32 * 1024;

    // Files of smaller sizes already fit in filesystem readahead threshold, no separate readahead
    // call needed. See also: https://lwn.net/Articles/372384/
    private static final int READAHEAD_THRESHOLD = 128 * 1024;

    // This is big enough to use justify using mmap/sendfile
    private static final int MMAP_THRESHOLD = 4 * 1024 * 1024;

    // We probably don't want to prefetch bigger pieces in memory at once
    private static final int READAHEAD_LIMIT = 512 * 1024 * 1024;

    volatile boolean copyCancelled;

    public boolean copyTo(FileObject target, CancellationHelper ch, NotificationCallback callback) throws IOException, RemoteException {
        callback.onProgressUpdate("Preparing to copy…");

        final AssetFileDescriptor sourceAssetFd = this.openForReading(ch);
        final Stat s1 = new Stat();

        try (final ParcelFileDescriptor sourceFd = sourceAssetFd.getParcelFileDescriptor()) {
            os.fstat(sourceFd.getFd(), s1);

            if (s1.type == FsType.FILE && s1.st_size == 0) {
                // the file is empty, nothing to do
                return true;
            }

            final long offset = sourceAssetFd.getStartOffset();

            long size = sourceAssetFd.getDeclaredLength();
            if (size == UNKNOWN_LENGTH) {
                if (s1.st_size > 0) {
                    size = s1.st_size;
                } else {
                    size = getMaxSize(ch);
                }
            }

            if (s1.type == FsType.FILE && size > READAHEAD_THRESHOLD) {
                os.readahead(sourceFd.getFd(), offset, READAHEAD_LIMIT);
                os.fadvise(sourceFd.getFd(), offset, 0, OS.POSIX_FADV_SEQUENTIAL);
            }

            try (final AssetFileDescriptor targetAssetFd = target.openForWriting(ch)) {
                final Stat s2 = new Stat();
                final ParcelFileDescriptor targetFd = targetAssetFd.getParcelFileDescriptor();
                os.fstat(targetFd.getFd(), s2);

                try {
                    if (s1.st_dev == s2.st_dev && s1.st_ino == s2.st_ino) {
                        throw new IOException(context.getString(R.string.err_self_copy));
                    }

                    if (s2.type == FsType.FILE && size > 0 && size > s2.st_blksize) {
                        os.fallocate(targetFd.getFd(), 0, 0, size);
                    }

                    long limit = sourceAssetFd.getDeclaredLength();

                    if (offset > 0) {
                        doSkip(sourceFd, s1.type, offset);
                    }

                    boolean copied = doCopy(sourceFd, targetFd, size, limit, callback);

                    callback.onProgressUpdate("Flushing buffers");

                    return copied;
                } catch (Throwable tooBad) {
                    if (tooBad instanceof InterruptedIOException || tooBad instanceof OperationCanceledException) {
                        Thread.interrupted();
                    }

                    callback.onDismiss();

                    try { sourceFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }
                    try { targetFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }

                    throw tooBad;
                }
            }
        }
    }

    private static final long MACRO_BLOCK = 128 * 1024 * 1024;

    private boolean doCopy(ParcelFileDescriptor sourceFd, ParcelFileDescriptor targetFd, double limitHint, long limit, NotificationCallback callback) throws IOException {
        @Fd int s = sourceFd.getFd(); @Fd int t = targetFd.getFd();

        long result;

        try (Copy copy = os.copy()) {
            long max = limit <= 0 ? Long.MAX_VALUE : limit;

            long sent;

            for (result = 0; result < max; ) {
                if (limitHint > 0 && result > 0) {
                    callback.onProgressUpdate((int) Math.round(result / limitHint * 100));
                }

                final long remaining = max - result;
                final long toSend = Math.min(MACRO_BLOCK, remaining);

                sent = copy.transfer(s, null, t, null, toSend);
                result += sent;
                if (sent < toSend) {
                    break;
                }
            }
        }

        sourceFd.checkError();
        targetFd.checkError();

        return limit <= 0 || result == limit;
    }

    private boolean doSkip(ParcelFileDescriptor fd, FsType fileType, long offset) throws IOException {
        try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
            if (fileType == FsType.FILE) {
                // The caller might have already positioned the descriptor at correct position.
                // Or some other position. Either way, doing it like this won't hurt
                try (FileChannel fc = fis.getChannel()) {
                    fc.position(offset);

                    return fc.position() == offset;
                }
            } else {
                // This is dumb, but let's give caller the benefit of doubt —
                // there is no reason why data read from pipe or socket might not have an offset
                long skipped = 0, remaining = offset;

                while ((remaining -= skipped) > 0 && skipped >= 0) {
                    skipped = fis.skip(offset);

                    if (copyCancelled) {
                        return false;
                    }
                }

                return remaining <= 0;
            }
        }
    }

    public boolean moveTo(FileObject fileObject, CancellationHelper ch, NotificationCallback callback) throws IOException, RemoteException {
        if (shortcutMove(fileObject, ch) || copyTo(fileObject, ch, callback)) {
            delete();

            return true;
        }

        return false;
    }

    // try to use the fact that the file may be on the same partition to out advantage
    // to perform rename instead of copy/delete
    protected boolean shortcutMove(FileObject target, CancellationHelper ch) throws IOException, RemoteException {
        final AssetFileDescriptor sourceAssetFd = this.openForReading(ch);

        try (final ParcelFileDescriptor sourceFd = sourceAssetFd.getParcelFileDescriptor()) {
            os.fstat(sourceFd.getFd(), stat);

            final long offset = sourceAssetFd.getStartOffset();

            if (stat.type != FsType.FILE || offset != 0) {
                // can not use rename on non-file descriptors or if the source has
                // non-zero offset
                return false;
            }

            if (stat.st_size == 0) {
                // the file is empty, nothing to do
                return true;
            }

            try (final AssetFileDescriptor targetAssetFd = target.openForWriting(ch)) {
                final ParcelFileDescriptor targetFd = targetAssetFd.getParcelFileDescriptor();
                os.fstat(targetFd.getFd(), target.stat);

                try {
                    if (target.stat.type != FsType.FILE || target.stat.st_dev != stat.st_dev) {
                        return false;
                    }

                    if (stat.st_ino == target.stat.st_ino) {
                        // the move can be completed by unlinking the source inode
                        return true;
                    }

                    CharSequence sourcePath = os.readlinkat(DirFd.NIL, ProviderBase.fdPath(sourceFd.getFd()));
                    CharSequence targetPath = os.readlinkat(DirFd.NIL, ProviderBase.fdPath(targetFd.getFd()));

                    os.renameat(DirFd.NIL, sourcePath, DirFd.NIL, targetPath);

                    return true;
                } catch (Throwable tooBad) {
                    try { sourceFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }
                    try { targetFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }
                }
            }
        } catch (RemoteException | IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    @Override
    @CallSuper
    public void close() {
    }

    protected abstract boolean delete() throws IOException, RemoteException;

    public static FileObject fromTempFile(OS os, Context context, FsFile file) throws IOException {
        final String suffix = ".tmp";

        final String newName = file.name + suffix;

        final @Fd int tmpfile = os.creat("/proc/" + ProviderBase.myPid() + "/fd/" + file.dirFd + '/' + newName, OS.DEF_FILE_MODE);

        return new DescriptorFileObject(os, context, file, newName, tmpfile);
    }

    public static FileObject fromFile(OS os, Context context,
                                      Stat stat, String file, int fd) {
        return new LocalFileObject(os, context, file, extractName(file), stat, fd);
    }

    public static FileObject fromClip(OS os, Context context, ClipData clipData) {
        ClipData.Item clipItem = clipData.getItemAt(0);
        if (clipItem == null || clipItem.getUri() == null) {
            return null;
        }

        final Uri uri = clipItem.getUri();
        if (uri == null) {
            return null;
        }

        final String scheme = uri.getScheme();
        if (TextUtils.isEmpty(scheme)) {
            return null;
        }

        String maybeFilename = null;

        // if someone puts anything besides a filename here, they are idiots
        final ClipDescription clipDescription = clipData.getDescription();
        if (clipDescription != null) {
            final CharSequence label = clipDescription.getLabel();
            if (!TextUtils.isEmpty(label)) {
                maybeFilename = label.toString();
            }
        }

        FileObject result = null;

        switch (scheme) {
            case ContentResolver.SCHEME_CONTENT:
                final String authority = uri.getAuthority();
                if (TextUtils.isEmpty(authority)) {
                    return null;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (DocumentsContract.isTreeUri(uri)) {
                        return null;
                    }
                }

                result = new ContentFileObject(os, context, uri);

                break;
            case ContentResolver.SCHEME_FILE:
                final String path = uri.getPath();
                if (TextUtils.isEmpty(path)) {
                    return null;
                }

                if (!path.startsWith("/")) {
                    return null;
                }

                final String filePath = uri.getPath();
                final String fileName = uri.getLastPathSegment();
                result = new LocalFileObject(os, context, filePath, fileName, new Stat(), Fd.NIL);

                break;
            case ContentResolver.SCHEME_ANDROID_RESOURCE:
                result = new ResourceObject(os, context, uri);

            default:
        }

        if (result != null) {
            result.description = maybeFilename;
        }

        return result;
    }

    private static class ContentFileObject extends FileObject {
        private final ContentResolver resolver;
        private final Uri uri;

        private ContentProviderClient cpc;
        private Cursor info;
        private String description;
        private String mime;
        private long maxSize;
        private int flags;

        public ContentFileObject(OS os, Context c, Uri uri) {
            super(os, c);

            this.uri = uri;
            this.resolver = c.getContentResolver();
        }

        private void connect() {
            if (cpc == null) {
                cpc = resolver.acquireUnstableContentProviderClient(uri.getAuthority());
            }
        }

        private void fetchMetadata(CancellationHelper ch) throws RemoteException {
            if (info == null) {
                connect();

                final String[] projection;

                final boolean isDocument = DocumentsContract.isDocumentUri(context, uri);

                if (isDocument) {
                    projection = new String[]{
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_FLAGS,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                    };
                } else {
                    projection = new String[]{
                            OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE,
                    };
                }

                info = cpc.query(uri, projection, null, null, null, ch.getSignal());

                if (info == null) {
                    return;
                }

                if (!info.moveToFirst()) {
                    info.close();
                    info = null;
                    return;
                }

                boolean isVirtual = false;
                if (isDocument) {
                    int flagsColumn = info.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                    if (flagsColumn != -1 && !info.isNull(flagsColumn)) {
                        flags = info.getInt(flagsColumn);
                    }

                    if ((flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
                        isVirtual = true;
                    }
                }

                if (isVirtual) {
                    String[] streamTypes = cpc.getStreamTypes(uri, "*/*");

                    if (streamTypes != null && streamTypes.length != 0) {
                        mime = streamTypes[0];
                    } else {
                        mime = null;
                    }
                } else {
                    String dbMime = null;
                    int mimeColumn = info.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    if (mimeColumn != -1 && !info.isNull(mimeColumn)) {
                        dbMime = info.getString(mimeColumn);
                    }

                    if (TextUtils.isEmpty(dbMime)) {
                        String[] streamTypes = cpc.getStreamTypes(uri, "*/*");

                        if (streamTypes != null && streamTypes.length != 0) {
                            mime = streamTypes[0];
                        } else {
                            mime = cpc.getType(uri);
                        }
                    } else {
                        mime = dbMime;
                    }
                }

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    throw new UnsupportedOperationException("Copying directories is not supported yet");
                }

                if (TextUtils.isEmpty(description)) {
                    int nameColumn = info.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameColumn != -1 && !info.isNull(nameColumn)) {
                        description = info.getString(nameColumn);
                    }

                    if (TextUtils.isEmpty(description)) {
                        description = uri.getLastPathSegment();
                    }
                }

                int sizeColumn = info.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn != -1 && !info.isNull(sizeColumn)) {
                    maxSize = info.getLong(sizeColumn);
                }
            }
        }

        @Override
        public AssetFileDescriptor openForReading(CancellationHelper ch) throws FileNotFoundException, RemoteException {
            fetchMetadata(ch);

            if (TextUtils.isEmpty(mime)) {
                return cpc.openAssetFile(uri, "r", ch.getSignal());
            } else {
                return cpc.openTypedAssetFileDescriptor(uri, mime, Bundle.EMPTY, ch.getSignal());
            }
        }

        @Override
        public AssetFileDescriptor openForWriting(CancellationHelper ch) throws FileNotFoundException, RemoteException {
            connect();

            return cpc.openAssetFile(uri, "w", ch.getSignal());
        }

        @Override
        public boolean copyTo(FileObject target, CancellationHelper ch, NotificationCallback callback) throws IOException, RemoteException {
            if (target instanceof ContentFileObject) {
                final ContentFileObject t = (ContentFileObject) target;

                if (this.uri.equals(t.uri)) {
                    throw new IOException(context.getString(R.string.err_self_copy));
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (DocumentsContract.isDocumentUri(context, this.uri) && DocumentsContract.isDocumentUri(context, t.uri)) {
                        final String a1 = this.uri.getAuthority();
                        final String a2 = t.uri.getAuthority();
                        if (a1 != null && a1.equals(a2)) {
                            fetchMetadata(ch);

                            if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_COPY) != 0) {
                                try {
                                    final Uri result = DocumentsContract.copyDocument(resolver, uri, t.uri);

                                    return result != null;
                                } catch (Exception e) {
                                    LogUtil.logCautiously("Copying with DocumentContract resulted in error", e);
                                }

                                return false;
                            }
                        }

                        LogUtil.logCautiously("Unable to copy with DocumentContract, attempting stream copy");
                    }
                }
            }

            return super.copyTo(target, ch, callback);
        }

        @Override
        protected boolean delete() throws RuntimeException {
            if (DocumentsContract.isDocumentUri(context, this.uri)) {
                return DocumentsContract.deleteDocument(resolver, uri);
            } else {
                return resolver.delete(uri, null, null) > 0;
            }
        }

        @Override
        public long getMaxSize(CancellationHelper ch) throws RemoteException, RuntimeException {
            fetchMetadata(ch);

            return maxSize;
        }

        @Override
        public CharSequence getDescription(CancellationHelper ch) throws RemoteException, IOException, RuntimeException {
            if (TextUtils.isEmpty(description)) {
                fetchMetadata(ch);
            }

            return TextUtils.isEmpty(description) ? super.getDescription(ch) : description;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (cpc != null) {
                    //noinspection deprecation
                    cpc.release();
                }

                if (info != null) {
                    info.close();
                }

                super.close();
            }
        }
    }

    private static class DescriptorFileObject extends FileObject {
        private final @Fd int fd;
        private final FsFile fileInfo;
        private final String tempName;

        private boolean deleted;

        DescriptorFileObject(OS os, Context context, FsFile fileInfo, String tempName, @Fd int fd) {
            super(os, context, fileInfo.stat);

            this.fd = fd;
            this.fileInfo = fileInfo;
            this.tempName = tempName;
        }

        @Override
        public AssetFileDescriptor openForReading(CancellationHelper unused) throws IOException, RemoteException, RuntimeException {
            return new AssetFileDescriptor(ParcelFileDescriptor.fromFd(fd), 0, -1);
        }

        @Override
        public AssetFileDescriptor openForWriting(CancellationHelper unused) throws IOException, RemoteException, RuntimeException {
            return new AssetFileDescriptor(ParcelFileDescriptor.fromFd(fd), 0, -1);
        }

        @Override
        public CharSequence getDescription(CancellationHelper ch) throws IOException, RemoteException, RuntimeException {
            return fileInfo.name;
        }

        @Override
        public long getMaxSize(CancellationHelper unused) throws IOException, RemoteException, RuntimeException {
            return -1;
        }

        @Override
        protected boolean delete() throws IOException, RemoteException {
            deleted = true;

            if (os.faccessat(DirFd.NIL, "/proc/self/fd/" + fd, OS.F_OK)) {
                os.unlinkat(fileInfo.dirFd, tempName, 0);
            }

            return true;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    super.close();
                } finally {
                    try {
                        if (!deleted) {
                            os.fsync(fd);

                            if (os.faccessat(fileInfo.dirFd, tempName, OS.F_OK)) {
                                os.renameat(fileInfo.dirFd, tempName, fileInfo.dirFd, fileInfo.name);

                                os.fsync(fd);
                            } else {
                                os.linkat(DirFd.NIL, "/proc/" + Process.myPid() + "/fd/" + fd, fileInfo.dirFd, fileInfo.name, OS.AT_SYMLINK_FOLLOW);
                            }
                        }
                    } catch (IOException e) {
                        // ignore
                        LogUtil.logCautiously("Failed to clean up temp file", e);
                    } finally {
                        os.dispose(fd);
                    }
                }
            }
        }
    }

    private static class LocalFileObject extends FileObject {
        private final CharSequence path;
        private final CharSequence name;
        private final OS os;
        private final int dirFd;

        private LocalFileObject(OS os, Context context,
                                String path, String name,
                                Stat stat, int dirFd) {
            super(os, context, stat);

            this.dirFd = dirFd;
            this.os = os;
            this.path = path;
            this.name = name;
        }

        @Override
        protected boolean shortcutMove(FileObject target, CancellationHelper ch) throws IOException {
            @Fd int sourceFd = os.openat(dirFd, path, OS.O_RDONLY, 0);
            try {
                os.fstat(sourceFd, stat);

                if (stat.st_size == 0) {
                    // the file is empty, nothing to do
                    return true;
                }

                try (final AssetFileDescriptor targetAssetFd = target.openForWriting(ch)) {
                    final ParcelFileDescriptor targetFd = targetAssetFd.getParcelFileDescriptor();
                    os.fstat(targetFd.getFd(), target.stat);

                    if (target.stat.type == FsType.FILE && stat.st_dev == target.stat.st_dev) {
                        try {
                            if (stat.st_ino == target.stat.st_ino) {
                                // the move can be completed by unlinking the source inode
                                return true;
                            }

                            final CharSequence targetPath = os.readlinkat(DirFd.NIL,
                                    "/proc/" + Process.myPid() + "/fd/" + targetFd.getFd());

                            os.renameat(dirFd, path, DirFd.NIL, targetPath);

                            return true;
                        } catch (Throwable tooBad) {
                            try {
                                targetFd.closeWithError(tooBad.getMessage());
                            } catch (Exception oops) {
                                oops.printStackTrace();
                            }
                        }
                    }
                }
            } catch (IOException | RemoteException e) {
                e.printStackTrace();
            } finally {
                os.dispose(sourceFd);
            }

            return false;
        }

        @Override
        public AssetFileDescriptor openForReading(CancellationHelper ch) throws IOException {
            @Fd int fd = os.open(path, OS.O_RDONLY, 0);

            return new AssetFileDescriptor(ParcelFileDescriptor.adoptFd(fd), 0, -1);
        }

        @Override
        public AssetFileDescriptor openForWriting(CancellationHelper ch) throws IOException, RemoteException {
            @Fd int fd = os.open(path, OS.O_WRONLY, 0);

            return new AssetFileDescriptor(ParcelFileDescriptor.adoptFd(fd), 0, -1);
        }

        @Override
        public boolean copyTo(FileObject target, CancellationHelper ch, NotificationCallback callback) throws IOException, RemoteException {
            if (target instanceof LocalFileObject) {
                final LocalFileObject t = (LocalFileObject) target;

                if (this.path.equals(t.path)) {
                    throw new IOException(context.getString(R.string.err_self_copy));
                }
            }

            return super.copyTo(target, ch, callback);
        }

        @Override
        public long getMaxSize(CancellationHelper unused) {
            return UNKNOWN_LENGTH;
        }

        @Override
        public CharSequence getDescription(CancellationHelper unused) {
            return name;
        }

        @Override
        protected boolean delete() throws IOException, RemoteException {
            os.unlinkat(DirFd.NIL, path, 0);

            return true;
        }
    }

    private static final class ResourceObject extends FileObject {
        private final ContentResolver resolver;
        private final Uri uri;

        public ResourceObject(OS os, Context context, Uri uri) {
            super(os, context);

            this.uri = uri;
            this.resolver = context.getContentResolver();
        }

        @Override
        public AssetFileDescriptor openForReading(CancellationHelper unused) throws IOException, RemoteException {
            return resolver.openAssetFileDescriptor(uri, "r");
        }

        @Override
        public AssetFileDescriptor openForWriting(CancellationHelper unused) throws IOException, RemoteException {
            return resolver.openAssetFileDescriptor(uri, "w");
        }

        @Override
        protected boolean shortcutMove(FileObject target, CancellationHelper ch) throws IOException, RemoteException {
            return false;
        }

        @Override
        public long getMaxSize(CancellationHelper ch) {
            return UNKNOWN_LENGTH;
        }

        @Override
        protected boolean delete() throws IOException, RemoteException {
            throw new IOException("Can not delete an application resource file");
        }
    }
}
