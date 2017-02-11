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
package net.sf.xfd.provider;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.CharArrayBuffer;
import android.database.ContentObservable;
import android.database.ContentObserver;
import android.database.CrossProcessCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import net.sf.fdlib.CrappyDirectory;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.Fd;
import net.sf.fdlib.FsType;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.InotifyWatch;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;
import net.sf.fdlib.SelectorThread;
import net.sf.fdlib.Stat;
import net.sf.fdlib.UnreliableIterator;
import net.sf.fdlib.WrappedIOException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME;
import static android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID;
import static android.provider.DocumentsContract.Document.COLUMN_FLAGS;
import static android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED;
import static android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE;
import static android.provider.DocumentsContract.Document.COLUMN_SIZE;
import static android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_COPY;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
import static android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;
import static android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY;
import static android.provider.DocumentsContract.Root.FLAG_SUPPORTS_CREATE;
import static android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;
import static net.sf.xfd.provider.ProviderBase.*;
import static net.sf.xfd.provider.ProviderBase.acquire;
import static net.sf.xfd.provider.ProviderBase.assertAbsolute;
import static net.sf.xfd.provider.ProviderBase.assertFilename;
import static net.sf.xfd.provider.ProviderBase.canonString;
import static net.sf.xfd.provider.ProviderBase.extractName;
import static net.sf.xfd.provider.ProviderBase.release;

@SuppressLint("InlinedApi")
public final class FileProvider extends DocumentsProvider {
    public static final String AUTHORITY_SUFFIX = ".file_provider";

    private static final int FILE_DEFAULT_FLAGS = FLAG_SUPPORTS_RENAME | FLAG_SUPPORTS_WRITE |
            FLAG_SUPPORTS_DELETE | FLAG_SUPPORTS_REMOVE | FLAG_SUPPORTS_COPY | FLAG_SUPPORTS_MOVE;

    private static final int DIR_DEFAULT_FLAGS = FILE_DEFAULT_FLAGS | FLAG_DIR_SUPPORTS_CREATE;

    private final MatrixCursor ROOT = new MatrixCursor(new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
    }, 1);

    private final String[] DOCUMENT_PROJECTION  = new String[] {
            COLUMN_DOCUMENT_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_LAST_MODIFIED,
            COLUMN_FLAGS,
            COLUMN_SIZE,
    };

    private volatile MountInfo mountInfo;

    private volatile Inotify inotify;

    private volatile SelectorThread selectorThread;

    private Inotify getInotify() {
        if (inotify == null) {
            synchronized (this) {
                if (inotify == null) {
                    reset();
                }
            }
        }

        return inotify;
    }

    private void reset() {
        final OS os = base.getOS();

        if (os != null) {
            try {
                mountInfo = MountsSingleton.get(os);
            } catch (IOException e) {
                throw new WrappedIOException(e);
            }

            inotify = os.observe(inotifyFd, Looper.getMainLooper());

            mainThreadHandler.post(() -> {
                try {
                    inotify.setSelector(selectorThread);
                } catch (IOException e) {
                    // too bad...
                    e.printStackTrace();
                }
            });
        }
    }

    private String authority;

    private ProviderBase base;

    private @InotifyFd int inotifyFd;

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        assert context != null;

        final String packageName = context.getPackageName();

        this.authority = packageName + AUTHORITY_SUFFIX;

        try {
            base = new ProviderBase(getContext(), authority);

            final OS os = OS.getInstance();

            inotifyFd = os.inotify_init();

            selectorThread = EpollThreadSingleton.get();
        } catch (IOException e) {
            e.printStackTrace();

            return false;
        }

        ROOT.addRow(new Object[]{
                "/",
                "/",
                FLAG_LOCAL_ONLY | FLAG_SUPPORTS_CREATE | FLAG_SUPPORTS_IS_CHILD,
                R.drawable.ic_provider_icon,
                "Local Filesystem",
        });

        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        return ROOT;
    }

    @Nullable
    @Override
    public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        try {
            base.assertAbsolute(uri);
        } catch (FileNotFoundException e) {
            return null;
        }

        return base.getStreamTypes(canonString(uri.getPath()), mimeTypeFilter);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final OS os = base.getOS();
        if (os == null) {
            throw new FileNotFoundException("Failed to stat a document, unable to acquire root access");
        }

        try {
            documentId = canonString(documentId);

            final String name = extractName(documentId);

            return base.getTypeFast(documentId, name, new Stat());
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        assertAbsolute(documentId);

        final OS rooted = base.getOS();
        if (rooted == null) {
            return null;
        }

        Log.e("Detailing", "Detailing " + documentId);

        if (projection == null) {
            projection = DOCUMENT_PROJECTION;
        }

        final MatrixCursor result = new MatrixCursor(projection, 1);

        final Object[] row = new Object[projection.length];

        documentId = canonString(documentId);

        final Stat stat = new Stat();
        final String name = extractName(documentId);
        final String mime = base.getTypeFast(documentId, name, stat);

        for (int i = 0; i < projection.length; ++i) {
            String column = projection[i];

            switch (column) {
                case COLUMN_DOCUMENT_ID:
                    row[i] = documentId;
                    break;
                case COLUMN_DISPLAY_NAME:
                    row[i] = name;
                    break;
                case COLUMN_MIME_TYPE:
                    row[i] = mime;
                    break;
                case COLUMN_FLAGS:
                    if (stat.type == FsType.DIRECTORY) {
                        row[i] = DIR_DEFAULT_FLAGS;
                    } else {
                        row[i] = FILE_DEFAULT_FLAGS;
                    }
                    break;
                case COLUMN_SIZE:
                    row[i] = stat.st_size;
                    break;
            }
        }

        result.addRow(row);

        return result;
    }

    @Nullable
    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        assertAbsolute(documentId);
        assertFilename(displayName);

        final OS os = base.getOS();
        if (os == null) {
            throw new FileNotFoundException("Failed to rename a document, unable to acquire root access");
        }

        final String canonPath = canonString(documentId);

        if (canonPath.length() < 2) {
            throw new FileNotFoundException("Attempting to rename '/'");
        }

        try {
            final int slashAt = canonPath.lastIndexOf('/');

            final String filename = canonPath.substring(slashAt + 1, canonPath.length());
            if (filename.equals(displayName)) {
                return null;
            }

            final String parent = canonPath.substring(0, slashAt + 1);

            final @DirFd int parentFd = os.opendir(parent, OS.O_RDONLY, 0);
            try {
                os.renameat(parentFd, filename, parentFd, displayName);

                return appendPathPart(parent, displayName);
            } finally {
                os.dispose(parentFd);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to rename a document. " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        assertAbsolute(parentDocumentId);
        assertFilename(displayName);

        final OS rooted = base.getOS();
        if (rooted == null) {
            throw new FileNotFoundException("Failed to create a document, unable to acquire root access");
        }

        parentDocumentId = canonString(parentDocumentId);

        try {

            final @DirFd int parentFd = rooted.opendir(parentDocumentId, OS.O_RDONLY, 0);
            try {
                if (mimeType.equals(MIME_TYPE_DIR)) {
                    rooted.mkdirat(parentFd, displayName, 0);
                } else {
                    rooted.mknodat(parentFd, displayName, OS.S_IFREG, 0);
                }
            } finally {
                rooted.dispose(parentFd);
            }

            return appendPathPart(parentDocumentId, displayName);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create a document. " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        assertAbsolute(sourceDocumentId);
        assertAbsolute(sourceParentDocumentId);
        assertAbsolute(targetParentDocumentId);

        final OS rooted = base.getOS();
        if (rooted == null) {
            throw new FileNotFoundException("Unable to copy " + sourceDocumentId + ": can not acquire superuser access");
        }

        sourceParentDocumentId = canonString(sourceParentDocumentId);
        targetParentDocumentId = canonString(targetParentDocumentId);
        sourceDocumentId = canonString(sourceDocumentId);

        final String fileName = extractName(sourceDocumentId);

        try {
            @DirFd int fd1 = rooted.opendir(sourceParentDocumentId, OS.O_RDONLY, 0);
            try {
                @DirFd int fd2 = rooted.opendir(targetParentDocumentId, OS.O_RDONLY, 0);
                try {
                    final long fs1, fs2;

                    final Stat stat = new Stat();

                    rooted.fstat(fd1, stat);
                    fs1 = stat.st_dev;

                    rooted.fstat(fd2, stat);
                    fs2 = stat.st_dev;

                    final String resultingDocument = appendPathPart(targetParentDocumentId, fileName);

                    if (fs1 == fs2) {
                        rooted.renameat(fd1, fileName, fd2, fileName);
                    } else {
                        copyImpl(rooted, sourceDocumentId, targetParentDocumentId);

                        try {
                            rooted.unlinkat(fd1, fileName, 0);
                        } catch (IOException e) {
                            LogUtil.logCautiously("Failed to remove original", e);
                        }
                    }

                    return resultingDocument;
                } finally {
                    rooted.dispose(fd2);
                }
            } finally {
                rooted.dispose(fd1);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to move: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        assertAbsolute(sourceDocumentId);
        assertAbsolute(targetParentDocumentId);

        final OS rooted = base.getOS();
        if (rooted == null) {
            throw new FileNotFoundException("Unable to copy " + sourceDocumentId + ": can not acquire superuser access");
        }

        sourceDocumentId = canonString(sourceDocumentId);
        targetParentDocumentId = canonString(targetParentDocumentId);

        return copyImpl(rooted, sourceDocumentId, targetParentDocumentId);
    }

    private String copyImpl(OS rooted, String sourceDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        try {
            @Fd int fd = rooted.open(sourceDocumentId, OS.O_RDONLY, 0);
            try {
                final Stat stat = new Stat();

                rooted.fstat(fd, stat);

                if (stat.type == FsType.DIRECTORY) {
                    throw new FileNotFoundException("Copying directories is not supported yet");
                }

                final String sourceName = extractName(sourceDocumentId);
                final String targetPath = appendPathPart(targetParentDocumentId, sourceName);

                rooted.mknodat(DirFd.NIL, targetPath, OS.S_IFREG, 0);

                if (stat.st_size == 0) {
                    return targetPath;
                }

                @Fd int targetFd = rooted.open(targetPath, OS.O_RDONLY, 0);
                try {
                    long offset = 0;
                    long limit = stat.st_size;
                    long remaining = limit < 0 ? limit : Long.MAX_VALUE;

                    try (ParcelFileDescriptor pfd1 = ParcelFileDescriptor.adoptFd(fd);
                         ParcelFileDescriptor pfd2 = ParcelFileDescriptor.adoptFd(targetFd)) {
                        try (FileChannel fc1 = new FileInputStream(pfd1.getFileDescriptor()).getChannel();
                             FileChannel fc2 = new FileOutputStream(pfd2.getFileDescriptor()).getChannel()) {
                            long sent = -1;
                            while (sent != 0 && (limit < 0 || (remaining -= sent) > 0)) {
                                sent = fc2.transferFrom(fc1, offset, 1024 * 1024 * 4);

                                offset += sent;
                            }
                        } finally {
                            pfd1.detachFd();
                            pfd2.detachFd();
                        }
                    }

                    return targetPath;
                } finally {
                    rooted.dispose(targetFd);
                }
            } finally {
                rooted.dispose(fd);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to copy " + sourceDocumentId + ": " + e.getMessage());
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        assertAbsolute(documentId);

        final OS os = base.getOS();
        if (os != null) {
            try {
                final String canonDocument = canonString(documentId);

                final int fd = os.open(canonDocument, OS.O_RDONLY, 0);

                final Stat stat = new Stat();

                try {
                    os.fstat(fd, stat);
                } finally {
                    os.dispose(fd);
                }

                os.unlinkat(DirFd.NIL, canonDocument, stat.type == FsType.DIRECTORY ? OS.AT_REMOVEDIR : 0);
            } catch (IOException e) {
                throw new FileNotFoundException("Unable to delete " + documentId + ": " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            assertAbsolute(documentId);
        } catch (FileNotFoundException e) {
            return false;
        }

        StringBuilder reuse = null;

        try {
            if (!isCanon(documentId)) {
                reuse = acquire(documentId.length());
                reuse.append(documentId);
                documentId = canonString(reuse);
            }

            if (!isCanon(parentDocumentId)) {
                if (reuse == null) reuse = acquire(parentDocumentId.length());
                else reuse.setLength(0);
                reuse.append(parentDocumentId);
                parentDocumentId = canonString(reuse);
            }

            return documentId.startsWith(parentDocumentId);
        } finally {
            if (reuse != null) {
                release(reuse);
            }
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final OS os = base.getOS();
        final Inotify inotify = getInotify();

        if (os == null || inotify == null) {
            return null;
        }

        final Stat stat = new Stat();

        parentDocumentId = canonString(parentDocumentId);

        @DirFd int fd;
        try {
            fd = os.opendir(parentDocumentId, OS.O_RDONLY, 0);

            try {
                os.fstat(fd, stat);
            } catch (Throwable t) {
                os.dispose(fd);

                throw new FileNotFoundException("Unable to stat " + parentDocumentId + ": " + t.getMessage());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to open " + parentDocumentId + ": " + e.getMessage());
        }

        if (projection == null) {
            projection = DOCUMENT_PROJECTION;
        }

        final Directory directory;
        final MountInfo.Mount mount;
        final Lock mountsLock = mountInfo.getLock();
        mountsLock.lock();
        try {
            mount = mountInfo.mountMap.get(stat.st_dev);
        } finally {
            mountsLock.unlock();
        }

        if (mount != null && isPosix(mount.fstype)) {
            directory = os.list(fd);
        } else {
            directory = new CrappyDirectory(os.list(fd));
        }

        final DirectoryCursor cursor = createDirCursor(os, inotify, fd, directory, projection, parentDocumentId);

        assert getContext() != null;

        final ContentResolver cr = getContext().getContentResolver();

        final String canonId = parentDocumentId;

        mainThreadHandler.post(() -> cursor.setNotificationUri(cr, DocumentsContract.buildDocumentUri(authority, canonId)));

        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        assertAbsolute(documentId);

        final int readableMode = ParcelFileDescriptor.parseMode(mode);

        if (signal != null) {
            final Thread theThread = Thread.currentThread();

            signal.setOnCancelListener(theThread::interrupt);
        }

        try {
            final OS rooted = base.getOS();
            if (rooted == null) {
                throw new FileNotFoundException("Failed to open " + documentId + ": unable to acquire access");
            }

            final int openFlags;

            if ((readableMode & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
                openFlags = OS.O_RDWR;
            } else if ((readableMode & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
                openFlags = OS.O_WRONLY;
            } else {
                openFlags = OS.O_RDONLY;
            }

            final String canonDocumentId = canonString(documentId);

            @Fd int fd = rooted.open(canonDocumentId, openFlags, 0);

            return ParcelFileDescriptor.adoptFd(fd);
        } catch (IOException e) {
          throw new FileNotFoundException("Unable to open " + documentId + ": " + e.getMessage());
        } finally {
            if (signal != null) {
                signal.setOnCancelListener(null);
            }

            Thread.interrupted();
        }
    }

    private static String replacePathPart(String parent, String displayName) {
        final String result;

        final StringBuilder builder = acquire(parent.length() + displayName.length() + 1);

        try {
            final int lastSlash = parent.lastIndexOf('/');

            builder.append(parent, 0, lastSlash - 1).append(displayName);

            result = builder.toString();
        } finally {
            release(builder);
        }

        return result;
    }

    @Override
    public Uri canonicalize(Uri uri) {
        if (!authority.equals(uri.getAuthority())) {
            return null;
        }

        if (DocumentsContract.isTreeUri(uri)) {
            return canonTree(uri);
        }

        if (DocumentsContract.isDocumentUri(getContext(), uri)) {
            return canonDocument(uri);
        }

        return super.canonicalize(uri);
    }

    private Uri canonTree(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildTreeDocumentUri(authority, canonId);
    }

    private Uri canonDocument(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildDocumentUri(authority, canonId);
    }

    private static final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public DirectoryCursor createDirCursor(OS os, Inotify inotify, @DirFd int dirFd, Directory directories, String[] columns, String path) {
        final DirectoryCursor cursor = new DirectoryCursor(os, inotify, dirFd, directories, columns, path);

        // the contract of Cursor requires the window to be filled right from start...
        cursor.fillWindow(0, cursor.window);

        return cursor;
    }

    private final class DirectoryCursor implements CrossProcessCursor, Inotify.InotifyListener {
        private final OS os;
        private final @DirFd int dirFd;
        private final Directory directory;
        private final UnreliableIterator<Directory.Entry> iterator;
        private final String path;
        private final String[] columns;
        private final Inotify inotify;
        private final CursorWindow window;

        private final DataSetObservable dataSetObservable = new DataSetObservable();
        private final ContentObservable contentObservable = new ContentObservable();

        private final Directory.Entry reusable = new Directory.Entry();
        private final Stat stat = new Stat();

        private volatile RefCountedWatch inotifySub;
        private volatile int count = -1;
        private volatile Uri notificationUri;
        private volatile ContentResolver resolver;
        private volatile boolean closed;

        private final StringBuilder nameBuilder;

        private int position = -1;

        private DirectoryCursor(OS os, Inotify inotify, @DirFd int dirFd, Directory directory, String[] columns, String path) {
            this.os = os;
            this.inotify = inotify;
            this.dirFd = dirFd;
            this.columns = columns;
            this.path = path;

            this.directory = directory;

            this.iterator = directory.iterator();

            final int slash = path.lastIndexOf('/');
            final String name = slash == -1 ? path : path.substring(slash);

            this.window = new CursorWindow(name);

            this.nameBuilder = acquire(path.length() + 255);
        }

        @Override
        public synchronized void setNotificationUri(ContentResolver resolver, Uri notificationUri) {
            if (closed) return;

            this.resolver = resolver;

            this.notificationUri = notificationUri;

            if (notificationUri != null && inotifySub == null) {
                try {
                    InotifyWatch newWatch = inotify.subscribe(dirFd, this);

                    inotifySub = RefCountedWatch.get(newWatch);
                } catch (IOException e) {
                    // ok
                    e.printStackTrace();
                }
            }
        }

        @Override
        public Uri getNotificationUri() {
            return notificationUri;
        }

        @Override
        public boolean getWantsAllOnMoveCalls() {
            return false;
        }

        @Override
        public void setExtras(Bundle extras) {
        }

        @Override
        public Bundle getExtras() {
            return Bundle.EMPTY;
        }

        @Override
        public Bundle respond(Bundle extras) {
            return Bundle.EMPTY;
        }

        @Override
        public int getType(int columnIndex) {
            switch (columns[columnIndex]) {
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                case DocumentsContract.Document.COLUMN_FLAGS:
                case DocumentsContract.Document.COLUMN_SIZE:
                    return FIELD_TYPE_INTEGER;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    return FIELD_TYPE_STRING;
                default:
                    return FIELD_TYPE_NULL;
            }
        }

        @Override
        public boolean isNull(int columnIndex) {
            return false;
        }

        @Override
        public boolean onMove(int oldPosition, int newPosition) {
            final int currentPos = iterator.getPosition();

            try {
                final boolean moved = iterator.moveToPosition(newPosition);

                final int newPos = iterator.getPosition();

                if (newPos > currentPos) {
                    if (newPosition == newPos - 1) {
                        return true;
                    }
                }

                return moved;
            } catch (IOException e) {
                e.printStackTrace();

                return false;
            }
        }

        @Override
        public CursorWindow getWindow() {
            return window;
        }

        @Override
        public void fillWindow(int position, CursorWindow window) {
            if (position < 0 || (count != -1 && position >= count)) {
                return;
            }

            final int numColumns = columns.length;
            window.clear();
            window.setStartPosition(position);
            window.setNumColumns(numColumns);
            try {
                final boolean needName = hasColumn(COLUMN_DISPLAY_NAME) || hasColumn(COLUMN_DOCUMENT_ID);
                final boolean needMime = hasColumn(COLUMN_MIME_TYPE) || hasColumn(COLUMN_FLAGS);
                final boolean needSize = hasColumn(COLUMN_SIZE);

                if (iterator.moveToPosition(position)) {
                    rowloop: do {
                        if (!window.allocRow()) {
                            break;
                        }

                        iterator.get(reusable);

                        String fullChildName = null;
                        if (needName) {
                            nameBuilder.setLength(0);

                            nameBuilder.append(path);

                            if ('/' != nameBuilder.charAt(nameBuilder.length() - 1)) {
                                nameBuilder.append('/');
                            }

                            nameBuilder.append(reusable.name);

                            fullChildName = canonString(nameBuilder);

                            //Log.e("Listing", "Listing " + fullChildName + " as " + reusable.name);
                        }

                        String mime = null;
                        if (needMime) {
                            stat.type = reusable.type;
                            stat.st_size = 0;
                            stat.st_ino = reusable.ino;

                            mime = base.getTypeFastest(dirFd, reusable.name, stat);
                        } else if (needSize) {
                            os.fstatat(dirFd, reusable.name, stat, 0);
                        }

                        for (int col = 0; col < numColumns; ++col) {
                            final String column = columns[col];
                            final boolean success;

                            switch (column) {
                                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                                    success = window.putString(fullChildName, position, col);
                                    break;

                                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                                    success = window.putString(reusable.name, position, col);
                                    break;

                                case DocumentsContract.Document.COLUMN_FLAGS:
                                    final int flags = stat.type == FsType.DIRECTORY
                                            ? DIR_DEFAULT_FLAGS : FILE_DEFAULT_FLAGS;

                                    success = window.putLong(flags, position, col);

                                    break;

                                case DocumentsContract.Document.COLUMN_SIZE:
                                    success = window.putLong(stat.st_size, position, col);
                                    break;

                                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                                    success = window.putString(mime, position, col);
                                    break;

                                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                                default:
                                    success = window.putNull(position, col);
                                    break;
                            }
                            if (!success) {
                                window.freeLastRow();
                                break rowloop;
                            }
                        }
                        position += 1;
                    } while (iterator.moveToNext());
                }
            } catch (IOException e) {
                e.printStackTrace();

                Log.e("error", "error during reading directory contents: " + e.getMessage());
            }
        }

        private boolean hasColumn(String col) {
            for (String column : columns) {
                if (col.equals(column)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public int getCount() {
            if (count == -1) {
                synchronized (this) {
                    if (count == -1) {
                        try {
                            iterator.moveToPosition(Integer.MAX_VALUE);

                            count = iterator.getPosition() + 1;
                        } catch (IOException e) {
                            // :(
                            e.printStackTrace();
                        }
                    }
                }
            }

            return count;
        }

        @Override
        public int getPosition() {
            return position;
        }

        @Override
        public boolean move(int offset) {
            return moveToPosition(position + offset);
        }

        @Override
        public boolean moveToPosition(int position) {
            if (position > this.position && isAfterLast()) {
                return false;
            }

            if (position < 0) {
                this.position = -1;
                return false;
            }

            if (position == this.position) {
                return true;
            }

            boolean result = onMove(this.position, position);
            if (!result) {
                this.position = -1;
            } else {
                this.position = position;
            }

            return result;
        }

        @Override
        public boolean moveToFirst() {
            try {
                final boolean moved = iterator.moveToPosition(0);

                if (moved) {
                    position = 0;
                }

                return moved;
            } catch (IOException e) {
                e.printStackTrace();

                return false;
            }
        }

        @Override
        public boolean moveToLast() {
            try {
                iterator.moveToPosition(Integer.MAX_VALUE);

                position = iterator.getPosition();

                return position != -1;
            } catch (IOException e) {
                e.printStackTrace();

                return false;
            }
        }

        @Override
        public boolean moveToNext() {
            return moveToPosition(position + 1);
        }

        @Override
        public boolean moveToPrevious() {
            return moveToPosition(position - 1);
        }

        @Override
        public boolean isFirst() {
            return position == 0;
        }

        @Override
        public boolean isLast() {
            try {
                return position >= 0 && !iterator.moveToPosition(position + 1);
            } catch (IOException e) {
                e.printStackTrace();

                return true;
            }
        }

        @Override
        public boolean isBeforeFirst() {
            return position == -1;
        }

        @Override
        public boolean isAfterLast() {
            return position > iterator.getPosition();
        }

        @Override
        public int getColumnIndex(String columnName) {
            // Hack according to bug 903852
            final int periodIndex = columnName.lastIndexOf('.');
            if (periodIndex != -1) {
                Exception e = new Exception();
                Log.e("error", "requesting column name with table name -- " + columnName, e);
                columnName = columnName.substring(periodIndex + 1);
            }

            String columnNames[] = getColumnNames();
            int length = columnNames.length;
            for (int i = 0; i < length; i++) {
                if (columnNames[i].equalsIgnoreCase(columnName)) {
                    return i;
                }
            }

            return -1;
        }

        @Override
        public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException {
            final int index = getColumnIndex(columnName);
            if (index < 0) {
                throw new IllegalArgumentException("column '" + columnName + "' does not exist");
            }
            return index;
        }

        @Override
        public String getColumnName(int columnIndex) {
            return columns[columnIndex];
        }

        @Override
        public String[] getColumnNames() {
            return columns;
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public byte[] getBlob(int columnIndex) {
            return window.getBlob(position, columnIndex);
        }

        @Override
        public String getString(int columnIndex) {
            return window.getString(position, columnIndex);
        }

        @Override
        public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
            window.copyStringToBuffer(position, columnIndex, buffer);
        }

        @Override
        public short getShort(int columnIndex) {
            return window.getShort(position, columnIndex);
        }

        @Override
        public int getInt(int columnIndex) {
            return window.getInt(position, columnIndex);
        }

        @Override
        public long getLong(int columnIndex) {
            return window.getLong(position, columnIndex);
        }

        @Override
        public float getFloat(int columnIndex) {
            return window.getFloat(position, columnIndex);
        }

        @Override
        public double getDouble(int columnIndex) {
            return window.getDouble(position, columnIndex);
        }

        @Override
        @Deprecated
        public boolean requery() {
            Log.e("error", "requery() called — this method has no effect on this Cursor");

            return false;
        }

        @Override
        @Deprecated
        public void deactivate() {
            Log.e("error", "deactivate() called — this method has no effect on this Cursor");
        }

        @Override
        public synchronized void close() {
            if (closed) return;

            release(nameBuilder);

            closed = true;

            dataSetObservable.unregisterAll();

            window.close();

            if (inotifySub != null) {
                inotifySub.decCount();
            }

            directory.close();

            os.dispose(dirFd);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void registerContentObserver(ContentObserver observer) {
            contentObservable.registerObserver(observer);
        }

        @Override
        public void unregisterContentObserver(ContentObserver observer) {
            if (!closed) {
                contentObservable.unregisterObserver(observer);
            }
        }

        @Override
        public void registerDataSetObserver(DataSetObserver observer) {
            dataSetObservable.registerObserver(observer);
        }

        @Override
        public void unregisterDataSetObserver(DataSetObserver observer) {
            dataSetObservable.unregisterObserver(observer);
        }

        @Override
        public synchronized void onChanges() {
            contentObservable.dispatchChange(false, notificationUri);

            resolver.notifyChange(notificationUri, null, false);

            dataSetObservable.notifyChanged();
        }

        @Override
        public synchronized void onReset() {
            onChanges();

            try {
                os.fstat(dirFd, stat);
            } catch (IOException e) {
                e.printStackTrace();

                // WTF???
                return;
            }

            if (inotifySub != null) {
                inotifySub.close();
            }

            try {
                InotifyWatch newWatch = inotify.subscribe(dirFd, this);

                inotifySub = RefCountedWatch.get(newWatch);
            } catch (IOException e) {
                e.printStackTrace();

                // ok...
            }
        }
    }

}

