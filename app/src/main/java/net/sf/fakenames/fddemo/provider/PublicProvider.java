package net.sf.fakenames.fddemo.provider;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pools;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import net.sf.fakenames.fddemo.RootSingleton;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Fd;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Calendar;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import static android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME;
import static android.provider.DocumentsContract.Document.COLUMN_SIZE;
import static android.util.Base64.*;

@SuppressLint("InlinedApi")
public final class PublicProvider extends ContentProvider {
    public static final String DEFAULT_MIME = "application/octet-stream";

    public static final String AUTHORITY = "net.sf.fddemo.public";

    private static final String COOKIE_FILE = "key";
    private static final int COOKIE_SIZE = 20;
    private static final String URI_ARG_EXPIRY = "expiry";
    private static final String URI_ARG_COOKIE = "cookie";

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
    };

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final String path = uri.getPath();
        if (path == null) {
            return null;
        }

        try {
            FileProvider.assertAbsolute(path);

            verifyMac(uri);
        } catch (FileNotFoundException e) {
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
        return getDocType(uri.getPath());
    }

    private void initResources() throws IOException {
        final OS os = OS.getInstance();
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

    @Nullable
    @Override
    public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        try {
            final String docType = getType(uri);

            if (mimeTypeMatches(mimeTypeFilter, docType)) {
                return new String[] { docType };
            }
        } catch (Exception ignored) {
            ignored.printStackTrace();
        }

        return null;
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

    final void verifyMac(Uri path) throws FileNotFoundException {
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

        final byte[] encoded;
        final Mac hash;
        try {
            hash = Mac.getInstance("HmacSHA1");

            hash.init(key);
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
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode, CancellationSignal signal) throws FileNotFoundException {
        assertAbsolute(uri.getPath());

        verifyMac(uri);

        final int readableMode = ParcelFileDescriptor.parseMode(mode);

        if (signal != null) {
            final Thread theThread = Thread.currentThread();

            signal.setOnCancelListener(theThread::interrupt);
        }

        try {
            final OS rooted = getOS();

            if (rooted == null) {
                throw new FileNotFoundException("Failed to open " + uri.getPath() + ": unable to acquire access");
            }

            final int openFlags;

            if ((readableMode & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
                openFlags = OS.O_RDWR;
            } else if ((readableMode & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
                openFlags = OS.O_WRONLY;
            } else {
                openFlags = OS.O_RDONLY;
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

    @Override
    public Uri canonicalize(@NonNull Uri uri) {
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
            final byte[] expiryDate = new byte[] {
                    (byte) (l >> 56), (byte) (l >> 48), (byte) (l >> 40), (byte) (l >> 32),
                    (byte) (l >> 24), (byte) (l >> 16), (byte) (l >> 8), (byte) l,
            };
            hash.update(expiryDate);
            encoded = hash.doFinal(path.getBytes());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("Error while creating a hash: " + e.getMessage(), e);
        }

        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendQueryParameter(URI_ARG_EXPIRY, String.valueOf(l))
                .appendQueryParameter(URI_ARG_COOKIE, encodeToString(encoded, URL_SAFE | NO_WRAP | NO_PADDING))
                .path(path)
                .build();
    }
}

