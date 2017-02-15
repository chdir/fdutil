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
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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

import net.sf.xfd.DirFd;
import net.sf.xfd.Fd;
import net.sf.xfd.LogUtil;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;

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

import static android.content.ContentResolver.SCHEME_CONTENT;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME;
import static android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE;
import static android.provider.DocumentsContract.Document.COLUMN_SIZE;
import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;
import static android.util.Base64.NO_PADDING;
import static android.util.Base64.NO_WRAP;
import static android.util.Base64.URL_SAFE;
import static android.util.Base64.encodeToString;
import static net.sf.xfd.provider.PermissionDelegate.ACTION_PERMISSION_REQUEST;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_CALLBACK;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_CALLER;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_MODE;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_PATH;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_RESPONSE;
import static net.sf.xfd.provider.PermissionDelegate.EXTRA_UID;
import static net.sf.xfd.provider.PermissionDelegate.META_IS_PERMISSION_DELEGATE;
import static net.sf.xfd.provider.PermissionDelegate.RESPONSE_ALLOW;
import static net.sf.xfd.provider.PermissionDelegate.RESPONSE_DENY;
import static net.sf.xfd.provider.ProviderBase.*;
import static net.sf.xfd.provider.ProviderBase.assertAbsolute;
import static net.sf.xfd.provider.ProviderBase.canonString;
import static net.sf.xfd.provider.ProviderBase.extractName;

@SuppressLint("InlinedApi")
public final class PublicProvider extends ContentProvider {
    public static final String AUTHORITY_SUFFIX = ".public_provider";

    private static final String COOKIE_FILE = "key";
    private static final int COOKIE_SIZE = 20;

    public static final String URI_ARG_TYPE = "t";
    public static final String URI_ARG_EXPIRY = "e";
    public static final String URI_ARG_COOKIE = "c";
    public static final String URI_ARG_MODE = "m";

    private static volatile Intent authActivity;

    private static ComponentName createRelative(String pkg, String cls) {
        final String fullName;
        if (cls.charAt(0) == '.') {
            // Relative to the package. Prepend the package name.
            fullName = pkg + cls;
        } else {
            // Fully qualified package name.
            fullName = cls;
        }
        return new ComponentName(pkg, fullName);
    }

    private static @Nullable Intent authActivityIntent(Context c) {
        if (authActivity == null) {
            synchronized (PublicProvider.class) {
                if (authActivity == null) {
                    final PackageManager pm = c.getPackageManager();

                    final String packageName = c.getPackageName();

                    try {
                        final PackageInfo pi = pm.getPackageInfo(packageName,
                                PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);

                        for (ActivityInfo activity : pi.activities) {
                            final Bundle metadata = activity.metaData;

                            if (metadata != null) {
                                boolean isSuitable = metadata.getBoolean(META_IS_PERMISSION_DELEGATE);

                                if (isSuitable) {
                                    authActivity = new Intent(ACTION_PERMISSION_REQUEST)
                                            .setComponent(createRelative(packageName, activity.name))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }

        return authActivity;
    }

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

    private final String[] COMMON_PROJECTION = new String[] {
            BaseColumns._ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
    };

    private final LruCache<String, ObjectIntMap<String>> accessCache = new LruCache<>(100);

    private ProviderBase base;

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        assert context != null;

        final String packageName = context.getPackageName();

        String authority = packageName + AUTHORITY_SUFFIX;

        try {
            base = new ProviderBase(getContext(), authority);
        } catch (IOException e) {
            e.printStackTrace();

            return false;
        }

        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = uri.getPath();

        if (TextUtils.isEmpty(uri.getPath())) {
            path = "/";
        }

        try {
            assertAbsolute(path);
        } catch (FileNotFoundException e) {
            return null;
        }

        path = canonString(path);

        if (!path.equals(uri.getPath())) {
            uri = uri.buildUpon()
                    .path(path).build();
        }

        if (!checkAccess(uri, "r")) {
            return null;
        }

        if (projection == null) {
            projection = COMMON_PROJECTION;
        }

        final OS os = base.getOS();
        if (os == null) {
            return null;
        }

        int fd;
        try {
            final String name = extractName(path);

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
                            columns.add(name);
                            break;
                        case COLUMN_SIZE:
                            columns.add(stat.st_size);
                            break;
                        case COLUMN_MIME_TYPE:
                            columns.add(base.getTypeFast(path, name, stat));
                            break;
                        default:
                            columns.add(null);
                    }
                }

                cursor.addRow(columns);

                final Context context = getContext();
                assert context != null;

                final String packageName = context.getPackageName();

                cursor.setNotificationUri(context.getContentResolver(),
                        DocumentsContract.buildDocumentUri(packageName + FileProvider.AUTHORITY_SUFFIX, path));

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
    public String getType(@NonNull Uri uri) {
        final String hardCodedType = uri.getQueryParameter(URI_ARG_TYPE);
        if (hardCodedType != null) {
            return hardCodedType.isEmpty() ? null : hardCodedType;
        }

        try {
            assertAbsolute(uri.getPath());

            final String path = uri.getPath();

            final String name = extractName(path);

            return base.getTypeFast(path, name, new Stat());
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        final String hardCodedType = uri.getQueryParameter(URI_ARG_TYPE);
        if (hardCodedType != null) {
            if (hardCodedType.isEmpty()) return null;

            if (mimeTypeMatches(mimeTypeFilter, hardCodedType)) {
                return new String[] { hardCodedType };
            }
        }

        try {
            assertAbsolute(uri.getPath());
        } catch (FileNotFoundException e) {
            return null;
        }

        return base.getStreamTypes(uri.getPath(), mimeTypeFilter);
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
                final Intent intent = authActivityIntent(context);

                if (intent == null) return false;

                context.startActivity(intent
                        .putExtra(EXTRA_MODE, necessaryMode)
                        .putExtra(EXTRA_CALLER, caller)
                        .putExtra(EXTRA_UID, Binder.getCallingUid())
                        .putExtra(EXTRA_CALLBACK, receiver)
                        .putExtra(EXTRA_PATH, uri.getPath()));

                final Bundle result = queue.poll(10, TimeUnit.SECONDS);

                int decision = RESPONSE_DENY;

                if (result != null) {
                    decision = result.getInt(EXTRA_RESPONSE, -1);
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
        String path = uri.getPath();

        assertAbsolute(path);

        final int readableMode = ParcelFileDescriptor.parseMode(requestedMode);

        if (signal != null) {
            final Thread theThread = Thread.currentThread();

            signal.setOnCancelListener(theThread::interrupt);
        }

        path = canonString(path);

        if (!path.equals(uri.getPath())) {
            uri = uri.buildUpon()
                    .path(path).build();
        }

        try {
            if (!checkAccess(uri, requestedMode)) {
                return null;
            }

            final OS rooted = base.getOS();

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

            @Fd int fd = rooted.open(path, openFlags, 0);

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
        try {
            base.assertAbsolute(uri);

            String grantMode = uri.getQueryParameter(URI_ARG_MODE);
            if (TextUtils.isEmpty(grantMode)) {
                grantMode = "r";
            }

            verifyMac(uri, grantMode, grantMode);

            final int flags = ParcelFileDescriptor.parseMode(grantMode);

            final Context context = getContext();
            assert context != null;

            final String packageName = context.getPackageName();

            final Uri canon = DocumentsContract
                    .buildDocumentUri(packageName + FileProvider.AUTHORITY_SUFFIX, canonString(uri.getPath()));

            final int callerUid = Binder.getCallingUid();

            if (callerUid != Process.myUid()) {
                int grantFlags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

                if ((flags & ParcelFileDescriptor.MODE_READ_ONLY) == flags) {
                    grantFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                } else if ((flags & ParcelFileDescriptor.MODE_WRITE_ONLY) == flags)
                    grantFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                else {
                    grantFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    grantFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                }

                final String[] packages;

                final String caller = getCallingPackage();
                if (caller != null) {
                    packages = new String[] { caller };
                } else {
                    final PackageManager pm = context.getPackageManager();
                    packages = pm.getPackagesForUid(callerUid);
                }

                if (packages != null) {
                    for (String pkg : packages) {
                        context.grantUriPermission(pkg, canon, grantFlags);
                    }
                }
            }

            return canon;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        try {
            assertAbsolute(uri.getPath());
        } catch (FileNotFoundException e) {
            return 0;
        }

        if (!checkAccess(uri, "w")) {
            return 0;
        }

        final OS os = base.getOS();

        if (os != null) {
            final boolean isDir = MIME_TYPE_DIR.equals(getType(uri));

            try {
                os.unlinkat(DirFd.NIL, uri.getPath(), isDir ? OS.AT_REMOVEDIR : 0);

                return 1;
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to unlink", e);
            }
        }

        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
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

        final String packageName = context.getPackageName();

        final Uri.Builder b = new Uri.Builder()
                .scheme(SCHEME_CONTENT)
                .authority(packageName + AUTHORITY_SUFFIX);

        if (!"r".equals(mode)) {
            b.appendQueryParameter(URI_ARG_MODE, mode);
        }

        return b.path(path)
                .appendQueryParameter(URI_ARG_EXPIRY, String.valueOf(l))
                .appendQueryParameter(URI_ARG_COOKIE, encodeToString(encoded, URL_SAFE | NO_WRAP | NO_PADDING))
                .build();
    }
}

