package net.sf.fakenames.fddemo.provider;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
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
import android.support.v4.util.Pools;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import net.sf.fakenames.fddemo.BaseDirLayout;
import net.sf.fakenames.fddemo.R;
import net.sf.fakenames.fddemo.RootSingleton;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

import static android.provider.DocumentsContract.Document.*;
import static android.provider.DocumentsContract.Root.FLAG_LOCAL_ONLY;
import static android.provider.DocumentsContract.Root.FLAG_SUPPORTS_CREATE;
import static android.provider.DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;

@SuppressLint("InlinedApi")
public class FileProvider extends DocumentsProvider {
    public static final String DEFAULT_MIME = "application/octet-stream";

    public static final String AUTHORITY = "net.sf.fddemo.files";

    private static final int FILE_DEFAULT_FLAGS = FLAG_SUPPORTS_RENAME | FLAG_SUPPORTS_WRITE | FLAG_SUPPORTS_DELETE | FLAG_SUPPORTS_REMOVE;

    private static final int DIR_DEFAULT_FLAGS = FILE_DEFAULT_FLAGS | FLAG_DIR_SUPPORTS_CREATE;

    private final MatrixCursor ROOT = new MatrixCursor(new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
    });

    private static final String[] DOCUMENT_PROJECTION  = new String[] {
            COLUMN_DOCUMENT_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_LAST_MODIFIED,
            COLUMN_FLAGS,
            COLUMN_SIZE,
    };

    private volatile OS rooted;

    private volatile MountInfo mountInfo;

    private volatile @InotifyFd int inotifyFd;

    private volatile Inotify inotify;

    private volatile SelectorThread selectorThread;

    private OS getOS() {
        if (rooted == null) {
            synchronized (this) {
                if (rooted == null) {
                    reset();
                }
            }
        }

        return rooted;
    }

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
        try {
            if (inotify != null || rooted != null) {
                throw new AssertionError();
            }

            rooted = RootSingleton.get(getContext());

            inotify = rooted.observe(inotifyFd, Looper.getMainLooper());

            mainThreadHandler.post(() -> {
                try {
                    inotify.setSelector(selectorThread);
                } catch (IOException e) {
                    // too bad...
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            // ok
        }
    }

    @Override
    public boolean onCreate() {
        try {
            initResources();

            selectorThread = new SelectorThread();
            selectorThread.start();
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

    private void initResources() throws IOException {
        final OS os = OS.getInstance();

        @Fd int mountsFd = os.open("/proc/self/mountinfo", OS.O_RDONLY, 0);
        try {
            mountInfo = new MountInfo(mountsFd);
        } finally {
            os.dispose(mountsFd);
        }

        inotifyFd = os.inotify_init();

        assert getContext() != null;

        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        return ROOT;
    }

    @SuppressWarnings("SimplifiableIfStatement")
    private static boolean mimeTypeMatches(String filter, String test) {
        if (test == null) {
            return false;
        } else if (filter == null || "*/*".equals(filter)) {
            return true;
        } else if (filter.equals(test)) {
            return true;
        } else if (filter.endsWith("/*")) {
            return filter.regionMatches(0, test, 0, filter.indexOf('/'));
        } else {
            return false;
        }
    }

    @Override
    public String[] getDocumentStreamTypes(String documentId, String mimeTypeFilter) {
        try {
            final String docType = getDocumentType(documentId);

            if (mimeTypeMatches(mimeTypeFilter, docType)) {
                return new String[] { docType };
            }
        } catch (FileNotFoundException ignored) {
            ignored.printStackTrace();
        }

        return null;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return getDocType(documentId);
    }

    private static String getDocType(String documentId) {
        final int dot = documentId.lastIndexOf('.');

        if (dot != -1) {
            final String extension = MimeTypeMap.getFileExtensionFromUrl(documentId);

            if (!TextUtils.isEmpty(extension)) {
                MimeTypeMap map = MimeTypeMap.getSingleton();

                final String foundMime = map.getMimeTypeFromExtension(extension);

                if (!TextUtils.isEmpty(foundMime)) {
                    return foundMime;
                }
            }
        }

        return DEFAULT_MIME;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = DOCUMENT_PROJECTION;
        }

        try {
            final OS rooted = getOS();

            if (rooted == null) {
                return null;
            }

            @Fd int fd = rooted.open(documentId, OS.O_RDONLY, 0);
            try {
                final Stat stat = new Stat();
                rooted.fstat(fd, stat);

                final MatrixCursor result = new MatrixCursor(projection);

                final Object[] row = new Object[projection.length];

                String canon = canonString(documentId);
                String name = extractName(canon);

                Log.e("Detailing", "Detailing " + documentId);

                for (int i = 0; i < projection.length; ++i) {
                    String column = projection[i];

                    switch (column) {
                        case COLUMN_DOCUMENT_ID:
                            row[i] = canon;
                            break;
                        case COLUMN_DISPLAY_NAME:
                            row[i] = name;

                            break;
                        case COLUMN_MIME_TYPE:
                            if (stat.type == FsType.DIRECTORY) {
                                row[i] = MIME_TYPE_DIR;
                            } else {
                                row[i] = getDocumentType(documentId);
                            }
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
            } finally {
                rooted.dispose(fd);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to stat " + documentId + ": " + e.getMessage());
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        assertAbsolute(documentId);
        assertFilename(displayName);

        final OS os = getOS();

        if (os == null) {
            throw new FileNotFoundException("Failed to rename a document, unable to acquire root access");
        }

        try {
            final String canonPath = canonString(documentId);

            os.renameat(DirFd.NIL, canonPath, DirFd.NIL, canonPath);

            return replacePathPart(canonPath, documentId);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to rename a document. " + e.getMessage());
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        assertAbsolute(parentDocumentId);
        assertFilename(displayName);

        final OS os = getOS();

        if (os == null) {
            throw new FileNotFoundException("Failed to create a document, unable to acquire root access");
        }

        try {
            final String canonParent = canonString(parentDocumentId);

            final @DirFd int parentFd = os.opendir(canonParent, OS.O_RDONLY, 0);

            if (mimeType.equals(MIME_TYPE_DIR)) {
                os.mkdirat(parentFd, displayName, 0);
            } else {
                os.mknodat(parentFd, displayName, OS.S_IFREG, 0);
            }

            return appendPathPart(canonParent, displayName);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create a document. " + e.getMessage());
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        assertAbsolute(documentId);

        final OS os = getOS();

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

    private static boolean isNormalized(String string) {
        return !string.isEmpty() &&
                string.charAt(0) == '/' &&
                !(string.contains("/../") || string.endsWith("/.."));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (!isNormalized(parentDocumentId) || !isNormalized(documentId)) {
            return false;
        }

        final OS os = getOS();

        if (os != null) {
            try {
                final int fdParent = os.open(parentDocumentId, OS.O_RDONLY, 0);
                try {
                    // todo: actually verify that one contains another
                } finally {
                    os.dispose(fdParent);
                }

                final int fdChild = os.open(parentDocumentId, OS.O_RDONLY, 0);
                try {
                    // todo: actually verify that one contains another
                } finally {
                    os.dispose(fdChild);
                }

                return true;
            } catch (IOException e) {
                LogUtil.logCautiously("Error during invocation of isChildDocument", e);
            }
        }

        return false;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final OS os = getOS();
        final Inotify inotify = getInotify();

        if (os == null || inotify == null) {
            return null;
        }

        final Stat stat = new Stat();

        final String canonDocumentId = canonString(parentDocumentId);

        @DirFd int fd;
        try {
            fd = os.opendir(canonDocumentId, OS.O_RDONLY, 0);

            os.fstat(fd, stat);
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to open " + canonDocumentId + ": " + e.getMessage());
        }

        if (projection == null) {
            projection = DOCUMENT_PROJECTION;
        }

        final Directory directory;

        final MountInfo.Mount mount = mountInfo.mountMap.get(stat.st_dev);

        if (mount != null && BaseDirLayout.isRewindSafe(mount.fstype)) {
            directory = os.list(fd);
        } else {
            directory = new CrappyDirectory(os.list(fd));
        }

        final DirectoryCursor cursor = DirectoryCursor.create(os, inotify, fd, directory, projection, canonDocumentId);

        assert getContext() != null;

        final ContentResolver cr = getContext().getContentResolver();

        mainThreadHandler.post(() -> cursor.setNotificationUri(cr, DocumentsContract.buildDocumentUri(AUTHORITY, canonDocumentId)));

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
            final OS rooted = getOS();

            if (rooted == null) {
                return null;
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

    private static void assertAbsolute(String nameStr) throws FileNotFoundException {
        if (nameStr.charAt(0) != '/') {
            throw new FileNotFoundException(nameStr + " is invalid in this context, must be absolute");
        }
    }

    private static void assertFilename(String nameStr) throws FileNotFoundException {
        if (nameStr.indexOf('/') != -1) {
            throw new FileNotFoundException(nameStr + " is not a valid filename, must not contain '/'");
        }
    }

    public static String extractName(String chars) {
        final int lastSlash = chars.lastIndexOf('/');

        switch (lastSlash) {
            case 0:
                return chars;
            case -1:
                // oops
                LogUtil.swallowError(chars + " must have at least one slash!");

                return chars;
            default:
                return chars.substring(lastSlash, chars.length());
        }
    }

    private static String replacePathPart(String parent, String displayName) {
        final String result;

        StringBuilder builder = builderPool.acquire();

        if (builder == null) {
            builder = new StringBuilder(parent.length() + displayName.length() + 1);
        }

        try {
            final int lastSlash = parent.lastIndexOf('/');

            builder.append(parent, 0, lastSlash - 1).append(displayName);

            result = builder.toString();
        } finally {
            builder.setLength(0);

            builderPool.release(builder);
        }

        return result;
    }

    private static String appendPathPart(String parent, String displayName) {
        final String result;

        StringBuilder builder = builderPool.acquire();

        if (builder == null) {
            builder = new StringBuilder(parent.length() + displayName.length() + 1);
        }

        try {
            builder.append(parent).append('/').append(displayName);

            result = builder.toString();
        } finally {
            builder.setLength(0);

            builderPool.release(builder);
        }

        return result;
    }

    private static void stripSlashes(StringBuilder chars) {
        int length = chars.length();

        int prevSlash = -1;

        for (int i = length - 1; i != 0; --i) {
            if ('/' == chars.charAt(i)) {
                if (prevSlash == i + 1) {
                    chars.deleteCharAt(i);

                    i++;
                } else {
                    prevSlash = i;
                }
            }
        }
    }


    public static void removeDotSegments(StringBuilder chars) {
        if (chars.charAt(0) != '/') return;

        /**
         *    /  proc   /  self   /    ..   /   vmstat
         */
        int seg1 = 0, seg2 = 0, seg3 = 0, seg4 = 0;

        int segCnt = 0;

        for (int i = 1; i < chars.length(); ++i) {
            if ('/' == chars.charAt(i)) {
                int segStart = 0;

                switch (segCnt) {
                    case 0: // seg2 == 0
                        seg2 = i;

                        segStart = seg1;

                        ++segCnt;

                        break;
                    case 1: // seg3 = 0
                        seg3 = i;

                        segStart = seg2;

                        ++segCnt;

                        break;
                    case 2: // seg4 = 0
                        seg4 = i;

                        segStart = seg3;

                        ++segCnt;

                        break;
                    case 3: // seg4 > 0, carry
                        seg1 = seg2;
                        seg2 = seg3;
                        seg3 = seg4;

                        seg4 = i;

                        segStart = seg3;
                }

                final int segLength = i - segStart;

                switch (segLength) {
                    default:
                        break;
                    case 2:
                        if (chars.charAt(segStart + 1) == '.') {
                            chars.delete(segStart, i);

                            --segCnt;

                            i = segStart;
                        }
                        break;
                    case 3:
                        if (chars.charAt(segStart + 1) == '.'
                                && chars.charAt(segStart + 2) == '.') {
                            final int prevSegmentStart;

                            if (segCnt == 3) {
                                prevSegmentStart = seg2;
                                segCnt = 1;
                            } else {
                                prevSegmentStart = seg1;
                                segCnt = 0;
                            }

                            chars.delete(prevSegmentStart, i);

                            i = prevSegmentStart;
                        }
                }
            }
        }

        final int resultLength = chars.length();

        if ('/' != chars.charAt(resultLength - 1)) {
            int lastSlash, prevSlash = 0;

            switch (segCnt) {
                case 3:
                    lastSlash = seg4;
                    prevSlash = seg3;
                    break;
                case 2:
                    lastSlash = seg3;
                    prevSlash = seg2;
                    break;
                case 1:
                    lastSlash = seg2;
                    prevSlash = seg1;
                    break;
                default:
                    lastSlash = 0;
            }

            switch (resultLength - lastSlash) {
                case 2:
                    if (chars.charAt(lastSlash + 1) == '.') {
                        chars.delete(lastSlash + 1, resultLength + 1);
                    }
                    break;
                case 3:
                    if (chars.charAt(lastSlash + 1) == '.'
                            && chars.charAt(lastSlash + 2) == '.') {
                        chars.delete(prevSlash + 1, resultLength + 1);
                    }
                    break;
            }
        }
    }

    @Override
    public Uri canonicalize(Uri uri) {
        if (AUTHORITY.equals(uri.getAuthority())) {
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

    private static Pools.Pool<StringBuilder> builderPool = new Pools.SynchronizedPool<>(2);

    private Uri canonTree(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, canonId);
    }

    private Uri canonDocument(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildDocumentUri(AUTHORITY, canonId);
    }

    private static String canonString(String path) {
        StringBuilder builder = builderPool.acquire();

        if (builder == null) {
            builder = new StringBuilder(path.length());
        }

        final String result;
        try {
            builder.append(path);

            stripSlashes(builder);
            removeDotSegments(builder);

            if (path.contentEquals(builder)) {
                result = path;
            } else {
                result = builder.toString();
            }
        } finally {
            builder.setLength(0);

            builderPool.release(builder);
        }

        return result;
    }

    private static String canonString(StringBuilder builder) {
        stripSlashes(builder);
        removeDotSegments(builder);
        return builder.toString();
    }

    private static final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    private static class DirectoryCursor implements CrossProcessCursor, Inotify.InotifyListener {
        private final OS os;
        private final @DirFd int dirFd;
        private final Directory directory;
        private final UnreliableIterator<Directory.Entry> iterator;
        private final String path;
        private final String[] columns;
        private final Inotify inotify;
        private final CursorWindow window;

        private final Stat stat = new Stat();

        private final DataSetObservable dataSetObservable = new DataSetObservable();
        private final ContentObservable contentObservable = new ContentObservable();

        private volatile RefCountedWatch inotifySub;
        private volatile int count = -1;
        private volatile Uri notificationUri;
        private volatile ContentResolver resolver;
        private volatile boolean closed;

        private final StringBuilder nameBuilder = new StringBuilder();

        private int position = -1;

        public static DirectoryCursor create(OS os, Inotify inotify, @DirFd int dirFd, Directory directories, String[] columns, String path) {
            final DirectoryCursor cursor = new DirectoryCursor(os, inotify, dirFd, directories, columns, path);

            // the contract of Cursor requires the window to be filled right from start...
            cursor.fillWindow(0, cursor.window);

            return cursor;
        }

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
                final Directory.Entry reusable = new Directory.Entry();
                final Stat stat = new Stat();

                if (iterator.moveToPosition(position)) {
                    rowloop: do {
                        if (!window.allocRow()) {
                            break;
                        }

                        iterator.get(reusable);

                        nameBuilder.setLength(0);

                        nameBuilder.append(path);

                        if ('/' != nameBuilder.charAt(nameBuilder.length() - 1)) {
                            nameBuilder.append('/');
                        }

                        nameBuilder.append(reusable.name);

                        final String fullChildName = canonString(nameBuilder);

                        Log.e("Listing", "Listing " + fullChildName + " as " + reusable.name);

                        try {
                            int fd = os.openat(dirFd, reusable.name, OS.O_RDONLY, 0);
                            try {
                                os.fstat(fd, stat);
                            } finally {
                                os.dispose(fd);
                            }
                        } catch (IOException ioe) {
                            // opening the child failed, fall back to safe defaults
                            stat.type = reusable.type;
                            stat.st_ino = reusable.ino;
                            stat.st_size = 0;
                            stat.st_dev = Long.MIN_VALUE;
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
                                    final String type = stat.type == FsType.DIRECTORY
                                            ? MIME_TYPE_DIR : getDocType(reusable.name);

                                    success = window.putString(type, position, col);

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

final class RefCountedWatch implements InotifyWatch {
    private static Map<InotifyWatch, RefCountedWatch> map = new IdentityHashMap<>();

    private int counter = 1;

    private final InotifyWatch delegate;

    private RefCountedWatch(InotifyWatch delegate) {
        this.delegate = delegate;
    }

    public static synchronized RefCountedWatch get(InotifyWatch watch) {
        final RefCountedWatch rcw = map.get(watch);

        if (rcw == null) {
            final RefCountedWatch newRcf = new RefCountedWatch(watch);

            map.put(watch, newRcf);

            return newRcf;
        } else {
            rcw.incCount();

            return rcw;
        }
    }

    @Override
    public synchronized void close() {
        map.remove(delegate);

        delegate.close();
    }

    public synchronized void incCount() {
        ++counter;
    }

    public synchronized void decCount() {
        --counter;

        if (counter == 0) {
            close();
        }
    }
}