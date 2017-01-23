package net.sf.fakenames.fddemo;

import android.content.ClipData;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.support.annotation.CallSuper;
import android.text.TextUtils;

import net.sf.fakenames.fddemo.provider.FileProvider;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Fd;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Random;

/**
 * Container for information about Uri-addressable object
 */
public abstract class FileObject implements Closeable {
    protected final CancellationSignal cancellationSignal = new CancellationSignal();

    protected final Uri uri;
    protected final Context context;
    protected final OS os;

    FileObject(OS os, Context context, Uri uri) {
        this.os = os;
        this.context = context;
        this.uri = uri;
    }

    public abstract AssetFileDescriptor openForReading() throws IOException, RemoteException;

    public abstract AssetFileDescriptor openForWriting() throws IOException, RemoteException;

    public abstract long getMaxSize() throws IOException, RemoteException;

    public String getDescription() throws IOException, RemoteException {
        return "unnamed" + new Random().nextLong();
    }

    public boolean copyTo(FileObject target) throws IOException, RemoteException {
        if (this.uri.equals(target.uri)) {
            throw new IOException(context.getString(R.string.err_self_copy));
        }

        final AssetFileDescriptor sourceAssetFd = this.openForReading();
        final AssetFileDescriptor targetAssetFd = target.openForWriting();

        final Stat s1 = new Stat(), s2 = new Stat();

        final ParcelFileDescriptor sourceFd = sourceAssetFd.getParcelFileDescriptor();
        final ParcelFileDescriptor targetFd = targetAssetFd.getParcelFileDescriptor();

        os.fstat(sourceFd.getFd(), s1);
        os.fstat(targetFd.getFd(), s2);

        if (s1.st_ino == s2.st_ino) {
            throw new IOException(context.getString(R.string.err_self_copy));
        }

        if (s1.st_size == 0) {
            return true;
        }

        long offset = sourceAssetFd.getStartOffset();
        long limit = sourceAssetFd.getDeclaredLength();
        long remaining = limit < 0 ? limit : Long.MAX_VALUE;

        cancellationSignal.setOnCancelListener(() -> {
            Thread.currentThread().interrupt();
        });

        try (FileChannel fc1 = new FileInputStream(sourceFd.getFileDescriptor()).getChannel();
             FileChannel fc2 = new FileOutputStream(targetFd.getFileDescriptor()).getChannel()) {
            long sent = -1;
            while (sent != 0 && (limit < 0 || (remaining -= sent) > 0)) {
                if (cancellationSignal.isCanceled()) {
                    return false;
                }

                sent = fc2.transferFrom(fc1, offset, 1024 * 1024 * 4);

                offset += sent;
            }

            sourceFd.checkError();
            targetFd.checkError();
        } catch (Throwable tooBad) {
            try { sourceFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }
            try { targetFd.closeWithError(tooBad.getMessage()); } catch (Exception oops) { oops.printStackTrace(); }

            throw tooBad;
        } finally {
            cancellationSignal.setOnCancelListener(null);
        }

        return limit < 0 || remaining == 0;
    }

    public boolean moveTo(FileObject fileObject) throws IOException, RemoteException {
        if (copyTo(fileObject)) {
            delete();

            return true;
        }

        return false;
    }

    @Override
    @CallSuper
    public void close() throws IOException {
        cancellationSignal.cancel();
    }

    protected abstract boolean delete() throws IOException, RemoteException;

    public static FileObject fromFile(OS os, Context context, File file) {
        return new LocalFileObject(os, context, Uri.fromFile(file));
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

                return new ContentFileObject(os, context, uri);

            case ContentResolver.SCHEME_FILE:
                final String path = uri.getPath();
                if (TextUtils.isEmpty(path)) {
                    return null;
                }

                if (!path.startsWith("/")) {
                    return null;
                }

                return new LocalFileObject(os, context, uri);

            case ContentResolver.SCHEME_ANDROID_RESOURCE:
                return new ResourceObject(os, context, uri);

            default:
        }

        return null;
    }

    private static class ContentFileObject extends FileObject {
        private final ContentResolver resolver;

        private ContentProviderClient cpc;
        private Cursor info;
        private String description;
        private String mime;
        private long maxSize;
        private int flags;

        public ContentFileObject(OS os, Context c, Uri uri) {
            super(os, c, uri);

            this.resolver = c.getContentResolver();
        }

        private void connect() {
            if (cpc == null) {
                cpc = resolver.acquireUnstableContentProviderClient(uri.getAuthority());
            }
        }

        private void fetchMetadata() throws RemoteException {
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

                info = cpc.query(uri, projection, null, null, null, cancellationSignal);
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

                int nameColumn = info.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn != -1 && !info.isNull(nameColumn)) {
                    description = info.getString(nameColumn);
                }

                if (TextUtils.isEmpty(description)) {
                    description = uri.getLastPathSegment();
                }

                int sizeColumn = info.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn != -1 && !info.isNull(sizeColumn)) {
                    maxSize = info.getLong(sizeColumn);
                }
            }
        }

        @Override
        public AssetFileDescriptor openForReading() throws FileNotFoundException, RemoteException {
            fetchMetadata();

            if (TextUtils.isEmpty(mime)) {
                return cpc.openAssetFile(uri, "r", cancellationSignal);
            } else {
                return cpc.openTypedAssetFileDescriptor(uri, mime, Bundle.EMPTY, cancellationSignal);
            }
        }

        @Override
        public AssetFileDescriptor openForWriting() throws FileNotFoundException, RemoteException {
            connect();

            return cpc.openAssetFile(uri, "w", cancellationSignal);
        }

        @Override
        public boolean copyTo(FileObject target) throws IOException, RemoteException {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (DocumentsContract.isDocumentUri(context, this.uri) && DocumentsContract.isDocumentUri(context, target.uri)) {
                    if (uri.getAuthority().equals(target.uri.getAuthority())) {
                        if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_COPY) != 0) {
                            final Uri result = DocumentsContract.copyDocument(resolver, uri, target.uri);
                            if (result != null) {
                                return true;
                            }
                        }
                    }
                }
            }

            return super.copyTo(target);
        }

        @Override
        public boolean moveTo(FileObject target) throws IOException, RemoteException {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (DocumentsContract.isDocumentUri(context, this.uri) && DocumentsContract.isDocumentUri(context, target.uri)) {
                    if (uri.getAuthority().equals(FileProvider.AUTHORITY)) {
                        if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_COPY) != 0) {
                            final Uri result = DocumentsContract.renameDocument(resolver, uri, uri.getPath());
                            if (result != null) {
                                return true;
                            }
                        }
                    }
                }
            }

            return super.moveTo(target);
        }

        @Override
        protected boolean delete() {
            if (DocumentsContract.isDocumentUri(context, this.uri)) {
                return DocumentsContract.deleteDocument(resolver, uri);
            } else {
                return resolver.delete(uri, null, null) > 0;
            }
        }

        @Override
        public long getMaxSize() throws RemoteException {
            fetchMetadata();

            return maxSize;
        }

        @Override
        public String getDescription() throws RemoteException, IOException {
            fetchMetadata();

            return TextUtils.isEmpty(description) ? super.getDescription() : description;
        }

        @Override
        public void close() throws IOException {
            super.close();

            if (cpc != null) {
                //noinspection deprecation
                cpc.release();
            }

            if (info != null) {
                info.close();
            }
        }
    }

    private static class LocalFileObject extends FileObject {
        private final String path;
        private final String name;
        private final OS os;

        public LocalFileObject(OS os, Context context, Uri uri) {
            super(os, context, uri);

            this.os = os;
            this.path = uri.getPath();
            this.name = uri.getLastPathSegment();

        }

        @Override
        public AssetFileDescriptor openForReading() throws IOException {
            @Fd int fd = os.open(path, OS.O_RDONLY, 0);

            return new AssetFileDescriptor(ParcelFileDescriptor.adoptFd(fd), 0, -1);
        }

        @Override
        public AssetFileDescriptor openForWriting() throws IOException, RemoteException {
            @Fd int fd = os.open(path, OS.O_WRONLY, 0);

            return new AssetFileDescriptor(ParcelFileDescriptor.adoptFd(fd), 0, -1);
        }

        @Override
        public long getMaxSize() {
            return -1;
        }

        @Override
        public String getDescription() {
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

        public ResourceObject(OS os, Context context, Uri uri) {
            super(os, context, uri);

            this.resolver = context.getContentResolver();
        }

        @Override
        public AssetFileDescriptor openForReading() throws IOException, RemoteException {
            return resolver.openAssetFileDescriptor(uri, "r");
        }

        @Override
        public AssetFileDescriptor openForWriting() throws IOException, RemoteException {
            return resolver.openAssetFileDescriptor(uri, "w");
        }

        @Override
        public long getMaxSize() {
            return -1;
        }

        @Override
        protected boolean delete() throws IOException, RemoteException {
            throw new IOException("Can not delete an application resource");
        }
    }
}
