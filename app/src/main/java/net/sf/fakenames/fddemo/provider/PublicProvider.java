package net.sf.fakenames.fddemo.provider;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.os.ResultReceiver;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.util.Base64;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;

import net.sf.fakenames.fddemo.PermissionActivity;
import net.sf.fakenames.fddemo.RootSingleton;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Fd;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME;
import static android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE;
import static android.provider.DocumentsContract.Document.COLUMN_SIZE;
import static android.util.Base64.*;
import static net.sf.fakenames.fddemo.PermissionActivity.RESPONSE_ALLOW;
import static net.sf.fakenames.fddemo.PermissionActivity.RESPONSE_DENY;
import static net.sf.fakenames.fddemo.provider.FileProvider.assertAbsolute;

@SuppressLint("InlinedApi")
public final class PublicProvider extends ContentProvider {
    public static final String DEFAULT_MIME = "application/octet-stream";

    public static final String AUTHORITY = "net.sf.fddemo.public";

    private static final String COOKIE_FILE = "key";
    private static final int COOKIE_SIZE = 20;
    private static final String URI_ARG_EXPIRY = "expiry";
    private static final String URI_ARG_COOKIE = "cookie";
    private static final String URI_ARG_MODE = "mode";

    private volatile OS rooted;

    private static volatile Key cookieSalt;

    private static @Nullable Key getSalt(Context c) {
        if (cookieSalt == null) {
            synchronized (PublicProvider.class) {
                if (cookieSalt == null) {
                    try {
                        try (ObjectInputStream oos = new ObjectInputStream(c.openFileInput(COOKIE_FILE))) {
                            cookieSalt = (Key) oos.readObject();
                        } catch (ClassNotFoundException | IOException e) {
                            LogUtil.logCautiously("Unable to read key file, probably corrupted", e);

                            final File corrupted = c.getFileStreamPath(COOKIE_FILE);

                            //noinspection ResultOfMethodCallIgnored
                            corrupted.delete();
                        }

                        if (cookieSalt != null) {
                            return cookieSalt;
                        }

                        final KeyGenerator keygen = KeyGenerator.getInstance("HmacSHA1");
                        keygen.init(COOKIE_SIZE * Byte.SIZE);

                        cookieSalt = keygen.generateKey();

                        try (ObjectOutputStream oos = new ObjectOutputStream(c.openFileOutput(COOKIE_FILE, Context.MODE_PRIVATE))) {
                            oos.writeObject(cookieSalt);
                        } catch (IOException e) {
                            LogUtil.logCautiously("Failed to save key file", e);

                            return null;
                        }
                    } catch (NoSuchAlgorithmException e) {
                        throw new AssertionError("failed to initialize hash functions", e);
                    }
                }
            }
        }

        return cookieSalt;
    }

    private OS getOS() {
        if (rooted == null) {
            synchronized (this) {
                if (rooted == null) {
                    reset();
                }

                if (rooted == null) {
                    try {
                        return OS.getInstance();
                    } catch (IOException e) {
                        e.printStackTrace();

                        return null;
                    }
                }
            }
        }

        return rooted;
    }

    private void reset() {
        try {
            if (rooted != null) {
                throw new AssertionError();
            }

            rooted = RootSingleton.get(getContext());
        } catch (IOException e) {
            e.printStackTrace();
            // ok
        }
    }

    @Override
    public boolean onCreate() {
        try {
            initResources();
        } catch (IOException e) {
            e.printStackTrace();

            return false;
        }

        return true;
    }

    private static final String[] COMMON_PROJECTION = new String[] {
            BaseColumns._ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
    };

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final String path = uri.getPath();
        if (path == null) {
            return null;
        }

        try {
            assertAbsolute(path);
        } catch (FileNotFoundException e) {
            return null;
        }

        if (!checkAccess(uri, "r")) {
            return null;
        }

        if (projection == null) {
            projection = COMMON_PROJECTION;
        }

        final OS os = getOS();
        if (os == null) {
            return null;
        }

        int fd;
        try {
            fd = os.openat(DirFd.NIL, path, OS.O_RDONLY, 0);
            try {
                final Stat stat = new Stat();

                os.fstat(fd, stat);

                final MatrixCursor cursor = new MatrixCursor(projection);

                final ArrayList<Object> columns = new ArrayList<>(projection.length);

                for (String col : projection) {
                    switch (col) {
                        case BaseColumns._ID:
                            columns.add(stat.st_ino);
                            break;
                        case COLUMN_DISPLAY_NAME:
                            columns.add(FileProvider.extractName(path));
                            break;
                        case COLUMN_SIZE:
                            columns.add(stat.st_size);
                            break;
                        case COLUMN_MIME_TYPE:
                            columns.add(FileProvider.getDocType(path, stat));
                            break;
                        default:
                            columns.add(null);
                    }
                }

                cursor.addRow(columns);

                assert getContext() != null;

                cursor.setNotificationUri(getContext().getContentResolver(),
                        DocumentsContract.buildDocumentUri(FileProvider.AUTHORITY, path));

                return cursor;
            } finally {
                os.dispose(fd);
            }
        } catch (IOException e) {
            e.printStackTrace();

            return null;
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        final OS os = getOS();

        if (os == null) {
            return null;
        }

        try {
            final String filepath = uri.getPath();

            assertAbsolute(filepath);

            int fd = os.open(filepath, OS.O_RDONLY, 0);
            try {
                final Stat s = new Stat();

                os.fstat(fd, s);

                return FileProvider.getDocType(filepath, s);
            } finally {
                os.dispose(fd);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void initResources() throws IOException {
        final OS os = OS.getInstance();
    }

    @Nullable
    @Override
    public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        final String docType = getType(uri);

        if (FileProvider.mimeTypeMatches(mimeTypeFilter, docType)) {
            return new String[] { docType };
        }

        return null;
    }

    final boolean checkAccess(Uri uri, String necessaryMode) {
        String grantMode = uri.getQueryParameter(URI_ARG_MODE);
        if (TextUtils.isEmpty(grantMode)) {
            grantMode = "r";
        }

        return checkAccess(uri, grantMode, necessaryMode);
    }

    final boolean checkAccess(Uri uri, String grantMode, String necessaryMode) {
        try {
            verifyMac(uri, grantMode, necessaryMode);

            return true;
        } catch (FileNotFoundException fnfe) {
            final Context context = getContext();

            assert context != null;

            ObjectIntMap<String> decisions = null;

            final String caller = getCallingPackage();

            if (!TextUtils.isEmpty(caller)) {
                decisions = accessCache.get(uri.getPath());
                if (decisions == null) {
                    decisions = new ObjectIntHashMap<>();
                } else {
                    //noinspection SynchronizationOnLocalVariableOrMethodParameter
                    synchronized (decisions) {
                        final int decision = decisions.get(caller);

                        switch (decision) {
                            case RESPONSE_ALLOW:
                                return true;
                        }
                    }
                }
            }

            final ArrayBlockingQueue<Bundle> queue = new ArrayBlockingQueue<>(1);

            final ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    try {
                        queue.offer(resultData, 4, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
            };

            try {
                final Intent intent = new Intent(getContext(), PermissionActivity.class)
                        .putExtra(PermissionActivity.EXTRA_MODE, necessaryMode)
                        .putExtra(PermissionActivity.EXTRA_CALLER, caller)
                        .putExtra(PermissionActivity.EXTRA_UID, Binder.getCallingUid())
                        .putExtra(PermissionActivity.EXTRA_CALLBACK, receiver)
                        .putExtra(PermissionActivity.EXTRA_PATH, uri.getPath())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                context.startActivity(intent);

                final Bundle result = queue.poll(10, TimeUnit.SECONDS);

                int decision = RESPONSE_DENY;

                if (result != null) {
                    decision = result.getInt(PermissionActivity.EXTRA_RESPONSE, -1);
                }

                if (decision == RESPONSE_ALLOW) {
                    if (decisions != null) {
                        //noinspection SynchronizationOnLocalVariableOrMethodParameter
                        synchronized (decisions) {
                            decisions.put(caller, RESPONSE_ALLOW);

                            accessCache.put(uri.getPath(), decisions);
                        }
                    }

                    return true;
                }
            } catch (InterruptedException ignored) {
            }
        }

        return false;
    }

    final void verifyMac(Uri path, String grantMode, String requested) throws FileNotFoundException {
        if (Process.myUid() == Binder.getCallingUid()) {
            return;
        }

        final int requestedMode = ParcelFileDescriptor.parseMode(requested);

        final String cookie = path.getQueryParameter(URI_ARG_COOKIE);
        final String expiry = path.getQueryParameter(URI_ARG_EXPIRY);

        if (TextUtils.isEmpty(cookie) || TextUtils.isEmpty(expiry)) {
            throw new FileNotFoundException("Invalid uri: MAC and expiry date are missing");
        }

        final long l;
        try {
            l = Long.parseLong(expiry);
        } catch (NumberFormatException nfe) {
            throw new FileNotFoundException("Invalid uri: unable to parse expiry date");
        }

        final Key key = getSalt(getContext());
        if (key == null) {
            throw new FileNotFoundException("Unable to verify hash: failed to produce key");
        }

        final int modeInt = ParcelFileDescriptor.parseMode(grantMode);

        if ((requestedMode & modeInt) != requestedMode) {
            throw new FileNotFoundException("Requested mode " + requested + " but limited to " + grantMode);
        }

        final byte[] encoded;
        final Mac hash;
        try {
            hash = Mac.getInstance("HmacSHA1");
            hash.init(key);

            final byte[] modeBits = new byte[] {
                    (byte) (modeInt >> 24), (byte) (modeInt >> 16), (byte) (modeInt >> 8), (byte) modeInt,
            };
            hash.update(modeBits);

            final byte[] expiryDate = new byte[] {
                    (byte) (l >> 56), (byte) (l >> 48), (byte) (l >> 40), (byte) (l >> 32),
                    (byte) (l >> 24), (byte) (l >> 16), (byte) (l >> 8), (byte) l,
            };
            hash.update(expiryDate);

            encoded = hash.doFinal(path.getPath().getBytes());

            final String sample = Base64.encodeToString(encoded, URL_SAFE | NO_WRAP | NO_PADDING);

            if (!cookie.equals(sample)) {
                throw new FileNotFoundException("Expired uri");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new FileNotFoundException("Unable to verify hash: missing HmacSHA1");
        } catch (InvalidKeyException e) {
            throw new FileNotFoundException("Unable to verify hash: corrupted key?!");
        }
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String requestedMode, CancellationSignal signal) throws FileNotFoundException {
        assertAbsolute(uri.getPath());

        final int readableMode = ParcelFileDescriptor.parseMode(requestedMode);

        if (signal != null) {
            final Thread theThread = Thread.currentThread();

            signal.setOnCancelListener(theThread::interrupt);
        }

        try {
            try {
                if (!checkAccess(uri, requestedMode)) {
                    return null;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            final OS rooted = getOS();

            if (rooted == null) {
                throw new FileNotFoundException("Failed to open " + uri.getPath() + ": unable to acquire access");
            }

            final int openFlags;

            if ((readableMode & MODE_READ_ONLY) == readableMode) {
                openFlags = OS.O_RDONLY;
            } else if ((readableMode & MODE_WRITE_ONLY) == readableMode) {
                openFlags = OS.O_WRONLY;
            } else {
                openFlags = OS.O_RDWR;
            }

            final String canonDocumentId = FileProvider.canonString(uri.getPath());

            @Fd int fd = rooted.open(canonDocumentId, openFlags, 0);

            return ParcelFileDescriptor.adoptFd(fd);
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to open " + uri.getPath() + ": " + e.getMessage());
        } finally {
            if (signal != null) {
                signal.setOnCancelListener(null);
            }

            Thread.interrupted();
        }
    }

    @Override
    public Uri canonicalize(@NonNull Uri uri) {
        if (!AUTHORITY.equals(uri.getAuthority())) {
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

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private Uri canonTree(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = FileProvider.canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, canonId);
    }

    private Uri canonDocument(Uri tree) {
        final String documentId = DocumentsContract.getDocumentId(tree);

        final String canonId = FileProvider.canonString(documentId);

        if (documentId.equals(canonId)) {
            return tree;
        }

        return DocumentsContract.buildDocumentUri(AUTHORITY, canonId);
    }

    public static @Nullable Uri publicUri(Context context, String path) {
        return publicUri(context, path, "r");
    }

    public static @Nullable Uri publicUri(Context context, String path, String mode) {
        final int modeInt = ParcelFileDescriptor.parseMode(mode);

        final Key key = getSalt(context);

        if (key == null) {
            return null;
        }

        final Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, 1);
        final long l = c.getTimeInMillis();

        final byte[] encoded;
        try {
            final Mac hash = Mac.getInstance("HmacSHA1");
            hash.init(key);

            final byte[] modeBits = new byte[] {
                    (byte) (modeInt >> 24), (byte) (modeInt >> 16), (byte) (modeInt >> 8), (byte) modeInt,
            };
            hash.update(modeBits);

            final byte[] expiryDate = new byte[] {
                    (byte) (l >> 56), (byte) (l >> 48), (byte) (l >> 40), (byte) (l >> 32),
                    (byte) (l >> 24), (byte) (l >> 16), (byte) (l >> 8), (byte) l,
            };
            hash.update(expiryDate);

            encoded = hash.doFinal(path.getBytes());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("Error while creating a hash: " + e.getMessage(), e);
        }

        final Uri.Builder b = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY);

        if (!"r".equals(mode)) {
            b.appendQueryParameter(URI_ARG_MODE, mode);
        }

        return b.path(path)
                .appendQueryParameter(URI_ARG_EXPIRY, String.valueOf(l))
                .appendQueryParameter(URI_ARG_COOKIE, encodeToString(encoded, URL_SAFE | NO_WRAP | NO_PADDING))
                .build();
    }

    private final LruCache<String, ObjectIntMap<String>> accessCache = new LruCache<>(100);
}

