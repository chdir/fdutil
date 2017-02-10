package net.sf.xfd.provider;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.LruCache;
import android.support.v4.util.Pools;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectSet;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Fd;
import net.sf.fdlib.FsType;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;

import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;

public final class ProviderBase extends ContextWrapper {
    public static final String DEFAULT_MIME = "application/octet-stream";

    private static final String ALT_DIR_MIME = "inode/directory";
    private static final String FIFO_MIME = "inode/fifo";
    private static final String CHAR_DEV_MIME = "inode/chardevice";
    private static final String SOCK_MIME = "inode/socket";
    private static final String LINK_MIME = "inode/symlink";
    private static final String BLOCK_MIME = "inode/blockdevice";

    private static final LruCache<String, TimestampedMime> fileTypeCache = new LruCache<>(7);

    private final String authority;

    private volatile MountInfo mounts;
    private volatile OS rooted;
    private volatile Magic magic;

    ProviderBase(Context context, String authority) throws IOException {
        super(context);

        this.authority = authority;

        this.magic = Magic.getInstance(getBaseContext());
    }

    @Nullable
    OS getOS() {
        if (rooted == null) {
            synchronized (this) {
                if (rooted == null) {
                    try {
                        reset();
                    } catch (IOException e) {
                        return null;
                    }
                }
            }
        }

        return rooted;
    }

    private void reset() throws IOException {
        if (rooted != null) {
            throw new AssertionError();
        }

        OS os = null;
        try {
            os = RootSingleton.get(getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace(); // ok
        }

        if (os == null) {
            os = OS.getInstance();
        }

        rooted = os;
        mounts = MountsSingleton.get(os);
    }

    @NonNull
    public String getTypeFastest(int dirFd, String name, Stat stat) {
        if (stat.type != null) {
            switch (stat.type) {
                case DIRECTORY:
                    return MIME_TYPE_DIR;
                case CHAR_DEV:
                    return CHAR_DEV_MIME;
            }
        }

        final OS os = getOS();
        if (os == null) {
            return DEFAULT_MIME;
        }

        // Do we have an option to open the file? Does it even make sense?
        // It is a bad idea to open sockets, special files and, especially, pipes
        // (this can cause block, take some time or have other unwelcome side-effects).
        // Trying to content-sniff ordinary zero-sized files also does not make sense…
        // Unless they are on some special filesystem (such as procfs), that incorrectly
        // reports zero sizes to stat().

        boolean canSniffContent = stat.type == FsType.FILE;

        int fd = Fd.NIL;

        try {
            final boolean isLink = stat.type == FsType.LINK;

            if (isLink) {
                // we must exclude the possibility that this is a symlink to directory

                if (!os.faccessat(dirFd, name, OS.F_OK)) {
                    // the link is broken or target is inaccessible, bail
                    return LINK_MIME;
                }

                try {
                    os.fstatat(dirFd, name, stat, 0);

                    switch (stat.type) {
                        case DIRECTORY:
                            return MIME_TYPE_DIR;
                        case CHAR_DEV:
                            return CHAR_DEV_MIME;
                    }
                } catch (IOException ioe) {
                    LogUtil.logCautiously("Unable to stat target of " + name, ioe);
                }
            } else {
                try {
                    if (canSniffContent) {
                        fd = os.openat(dirFd, name, OS.O_RDONLY, 0);

                        os.fstat(fd, stat);
                    } else {
                        os.fstatat(dirFd, name, stat, 0);
                    }
                } catch (IOException ioe) {
                    LogUtil.logCautiously("Unable to directly stat " + name, ioe);
                }
            }

            final String extension = getExtensionFast(name);

            final MimeTypeMap mimeMap = MimeTypeMap.getSingleton();

            final String foundMime = mimeMap.getMimeTypeFromExtension(extension);
            if (foundMime != null) {
                return foundMime;
            }

            canSniffContent =  canSniffContent && (stat.st_size != 0 || isPossiblySpecial(stat));

            if (isLink) {
                // check if link target has a usable extension
                String resolved = null;

                // Some filesystem (procfs, you!!) do export files as symlinks, but don't allow them
                // to be open via these symlinks. Gotta be careful here.
                if (canSniffContent) {
                    try {
                        fd = os.openat(dirFd, name, OS.O_RDONLY, 0);

                        resolved = os.readlinkat(DirFd.NIL, fdPath(fd));
                    } catch (IOException ioe) {
                        LogUtil.logCautiously("Unable to open target of " + name, ioe);
                    }
                }

                if (resolved == null) {
                    try {
                        resolved = os.readlinkat(dirFd, name);

                        if (resolved.charAt(0) == '/') {
                            resolved = canonString(resolved);
                        }
                    } catch (IOException linkErr) {
                        return LINK_MIME;
                    }
                }

                final String linkTargetExtension = getExtensionFromPath(resolved);

                if (linkTargetExtension != null && !linkTargetExtension.equals(extension)) {
                    final String sortaFastMime = mimeMap.getMimeTypeFromExtension(linkTargetExtension);
                    if (sortaFastMime != null) {
                        return sortaFastMime;
                    }
                }

                // let's try to open by resolved name too, see above
                name = resolved;
            }

            if (canSniffContent) {
                if (fd < 0) {
                    fd = os.openat(dirFd, name, OS.O_RDONLY, 0);
                }

                final String contentInfo = magic.guessMime(fd);

                if (contentInfo != null) {
                    return contentInfo;
                }
            }
        } catch (IOException ioe) {
            LogUtil.logCautiously("Failed to guess type of " + name, ioe);
        } finally {
            if (fd > 0) {
                os.dispose(fd);
            }
        }

        if (stat.type == null) {
            return DEFAULT_MIME;
        }

        switch (stat.type) {
            case LINK:
                return LINK_MIME;
            case DOMAIN_SOCKET:
                return SOCK_MIME;
            case NAMED_PIPE:
                return FIFO_MIME;
            default:
                return DEFAULT_MIME;
        }
    }

    public String getTypeFast(@CanonPath String path, String name, Stat stat) throws FileNotFoundException {
        if ("/".equals(path)) {
            return MIME_TYPE_DIR;
        }

        final OS os = getOS();
        if (os == null) {
            return DEFAULT_MIME;
        }

        try {
            @Fd int fd = Fd.NIL;

            @DirFd int parentFd = os.opendir(extractParent(path), OS.O_RDONLY, 0);
            try {
                int flags = 0;

                if (!os.faccessat(parentFd, name, OS.F_OK)) {
                    flags = OS.AT_SYMLINK_NOFOLLOW;
                }

                os.fstatat(parentFd, name, stat, flags);

                if (stat.type == null) {
                    return DEFAULT_MIME;
                }

                switch (stat.type) {
                    case LINK:
                        return LINK_MIME;
                    case DIRECTORY:
                        return MIME_TYPE_DIR;
                    case CHAR_DEV:
                        return CHAR_DEV_MIME;
                    default:
                }

                final String extension = getExtensionFast(name);

                final MimeTypeMap mimeMap = MimeTypeMap.getSingleton();

                final String foundMime = mimeMap.getMimeTypeFromExtension(extension);
                if (foundMime != null) {
                    return foundMime;
                }

                final boolean canSniffContent = stat.type == FsType.FILE && (stat.st_size != 0 || isPossiblySpecial(stat));

                if (flags == 0) {
                    os.fstatat(parentFd, name, stat, OS.AT_SYMLINK_NOFOLLOW);

                    if (stat.type == FsType.LINK) {
                        String resolved = null;

                        if (canSniffContent) {
                            try {
                                fd = os.openat(parentFd, name, OS.O_RDONLY, 0);

                                resolved = os.readlinkat(DirFd.NIL, "/proc/" + Process.myPid() + "/fd/" + fd);
                            } catch (IOException ioe) {
                                LogUtil.logCautiously("Unable to open target of " + name, ioe);
                            }
                        }

                        if (resolved == null) {
                            resolved = os.readlinkat(parentFd, name);

                            if (resolved.charAt(0) == '/') {
                                resolved = canonString(resolved);
                            }
                        }

                        final String linkTargetExtension = getExtensionFromPath(resolved);

                        if (linkTargetExtension != null && !linkTargetExtension.equals(extension)) {
                            final String sortaFastMime = mimeMap.getMimeTypeFromExtension(linkTargetExtension);
                            if (sortaFastMime != null) {
                                return sortaFastMime;
                            }
                        }
                    }
                }

                if (canSniffContent) {
                    if (fd < 0) {
                        fd = os.openat(parentFd, name, OS.O_RDONLY, 0);
                    }

                    final String contentInfo = magic.guessMime(fd);

                    if (contentInfo != null) {
                        return contentInfo;
                    }
                }

                switch (stat.type) {
                    case LINK:
                        return LINK_MIME;
                    case DOMAIN_SOCKET:
                        return SOCK_MIME;
                    case NAMED_PIPE:
                        return FIFO_MIME;
                    default:
                        return DEFAULT_MIME;
                }
            } finally {
                if (fd > 0) {
                    os.dispose(fd);
                }

                os.dispose(parentFd);
            }
        } catch (IOException e) {
            LogUtil.logCautiously("Encountered IO error during mime sniffing", e);

            throw new FileNotFoundException("Failed to stat " + name);
        }
    }

    private static String getExtensionFast(String name) {
        final int dot = name.lastIndexOf('.');

        if (dot == -1 || dot == name.length() - 1) {
            return null;
        } else {
            return name.substring(dot + 1, name.length());
        }
    }

    public static String getExtensionFromPath(@CanonPath String path) {
        final int dot = path.lastIndexOf('.');

        if (dot == -1 || dot == path.length() - 1 || dot < path.lastIndexOf('/')) {
            return null;
        } else {
            return path.substring(dot + 1, path.length());
        }
    }

    @SuppressWarnings("SimplifiableIfStatement")
    public static boolean mimeTypeMatches(String filter, String test) {
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
    public static String extractParent(String chars) {
        final int lastSlash = chars.lastIndexOf('/');

        switch (lastSlash) {
            case 0:
                return chars;
            case -1:
                // oops
                LogUtil.swallowError(chars + " must have at least one slash!");

                return null;
            default:
                return chars.substring(0, lastSlash + 1);
        }
    }

    public static String extractName(String chars) {
        final int lastSlash = chars.lastIndexOf('/');

        switch (lastSlash) {
            case 0:
            case -1:
                return chars;
            default:
                return chars.substring(lastSlash + 1, chars.length());
        }
    }

    public static boolean isCanon(String s) {
        int l = s.length();

        // check for dots at the end
        if (s.charAt(l - 1) == '.') {
            final int i = s.lastIndexOf('/');

            if (i == l - 2 || (i == l - 3 && s.charAt(l - 2) == '.')) {
                return false;
            }
        }

        // detect slash-dot-slash segments
        int start = 0; int idx;
        do {
            idx = s.indexOf('/', start);

            if (idx == -1) {
                break;
            }

            switch (l - idx) {
                default:
                case 4:
                    // at least three more chars remaining to right
                    if (s.charAt(idx + 1) == '.' && s.charAt(idx + 2) == '.' && s.charAt(idx + 3) == '/') {
                        return false;
                    }
                case 3:
                    // at least two more chars remaining to right
                    if (s.charAt(idx + 1) == '.' && s.charAt(idx + 2) == '/') {
                        return false;
                    }
                case 2:
                    // at least one more char remaining to right
                    if (s.charAt(idx + 1) == '/') {
                        return false;
                    }
                case 1:
            }

            start += 2;
        } while (start < l);

        return true;
    }

    public static void stripSlashes(StringBuilder chars) {
        int length = chars.length();

        int prevSlash = -1;

        for (int i = length - 1; i >= 0; --i) {
            if ('/' == chars.charAt(i)) {
                if (prevSlash == i + 1) {
                    chars.deleteCharAt(i);

                    --prevSlash;
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

    public static String appendPathPart(String parent, String displayName) {
        final String result;

        final StringBuilder builder = acquire(parent.length() + displayName.length() + 1);

        try {
            builder.append(parent).append('/').append(displayName);

            result = builder.toString();
        } finally {
            release(builder);
        }

        return result;
    }

    private static Pools.Pool<StringBuilder> builderPool = new Pools.SynchronizedPool<>(2);

    public static StringBuilder acquire(int length) {
        StringBuilder builder = builderPool.acquire();

        if (builder == null) {
            builder = new StringBuilder(length);
        }

        builder.ensureCapacity(length);

        return builder;
    }

    public static void release(StringBuilder builder) {
        builder.setLength(0);

        builderPool.release(builder);
    }

    static String canonString(StringBuilder builder) {
        stripSlashes(builder);
        removeDotSegments(builder);
        return builder.toString();
    }

    static String canonString(String path) {
        if (isCanon(path)) return path;

        final StringBuilder builder = acquire(path.length());

        final String result;
        try {
            builder.append(path);

            stripSlashes(builder);
            removeDotSegments(builder);

            result = builder.toString();
        } finally {
            release(builder);
        }

        return result;
    }

    @Nullable
    String[] getStreamTypes(@CanonPath String filepath, String mimeTypeFilter) {
        final TimestampedMime guess = guessMimeInternal(filepath);
        if (guess == null) {
            return null;
        }

        final String[] types = guess.mime;

        final ArrayList<String> acceptedTypes = new ArrayList<>();

        for (String type:types) {
            if (mimeTypeMatches(mimeTypeFilter, type))
                acceptedTypes.add(type);
        }

        return acceptedTypes.isEmpty() ? null : acceptedTypes.toArray(new String[acceptedTypes.size()]);
    }

    @Nullable
    private TimestampedMime guessMimeInternal(String filepath) {
        final TimestampedMime cachedResult = fileTypeCache.get(filepath);

        if (cachedResult != null && System.nanoTime() - cachedResult.when > 2_000_000_000) {
            return cachedResult;
        }

        final OS os = getOS();
        if (os == null) {
            return null;
        }

        final String[] guessed = guessTypes(os, filepath);
        if (guessed == null) {
            return null;
        }

        final TimestampedMime mime = new TimestampedMime();

        mime.mime = guessed;
        mime.when = System.nanoTime();

        fileTypeCache.put(filepath, mime);

        return mime;
    }

    private String[] guessTypes(OS os, String filepath) {
        if ("/".equals(filepath)) {
            return new String[] { MIME_TYPE_DIR, ALT_DIR_MIME };
        }

        final ObjectSet<String> mimeCandidates = new ObjectHashSet<>();

        int fd = 0;

        try {
            final Stat s = new Stat();

            @DirFd int parentFd = os.opendir(extractParent(filepath), OS.O_RDONLY, 0);

            final String filename = extractName(filepath);
            try {
                os.fstatat(parentFd, filename, s, 0);
            } catch (IOException ioe) {
                LogUtil.logCautiously("Failed to invoke stat() on " + filename, ioe);
            } finally {
                os.dispose(parentFd);
            }

            FsType origType = null;

            if (s.type != null) {
                origType = s.type;

                switch (s.type) {
                    case DIRECTORY:
                        return new String[] { MIME_TYPE_DIR, ALT_DIR_MIME };
                    case CHAR_DEV:
                        return new String[] { CHAR_DEV_MIME };
                    case NAMED_PIPE:
                        mimeCandidates.add(FIFO_MIME);
                        break;
                    case DOMAIN_SOCKET:
                        mimeCandidates.add(SOCK_MIME);
                        break;
                    case BLOCK_DEV:
                        mimeCandidates.add(BLOCK_MIME);
                    case FILE:
                        try {
                            fd = os.openat(parentFd, filename, OS.O_RDONLY, 0);
                        } catch (IOException ioe) {
                            LogUtil.logCautiously("Failed to invoke open() on " + filename, ioe);
                        }
                    default:
                }
            }

            os.fstatat(parentFd, filename, s, OS.AT_SYMLINK_NOFOLLOW);

            if (s.type == FsType.LINK) {
                try {
                    final String resolved = os.readlinkat(parentFd, filename);

                    if (!filepath.equals(resolved)) {
                        addNameCandidates(resolved, mimeCandidates);
                    }

                    if (origType == FsType.FILE) {
                        fd = os.openat(parentFd, resolved, OS.O_RDONLY, 0);
                    }
                } catch (IOException e) {
                    LogUtil.logCautiously("Error during path resolution", e);
                }
            }

            if (fd > 0) {
                final String contentInfo = magic.guessMime(fd);

                if (!TextUtils.isEmpty(contentInfo) && !DEFAULT_MIME.equals(contentInfo)) {
                    mimeCandidates.add(contentInfo);
                }
            }
        } catch (IOException e) {
            LogUtil.logCautiously("Error during guessing mime type", e);
        } finally {
            if (fd > 0) {
                os.dispose(fd);
            }
        }

        addNameCandidates(filepath, mimeCandidates);

        return mimeCandidates.toArray(String.class);
    }

    public static String fdPath(int fd) {
        final String result;
        final int myPid = Process.myPid();
        final StringBuilder builder = acquire(30);
        try {
            result = builder.append("/proc/").append(myPid).append("/fd/").append(fd).toString();
        } finally {
            release(builder);
        }
        return result;
    }

    private boolean isPossiblySpecial(Stat s) {
        if (s == null) return true;

        final Lock lock = mounts.getLock();
        lock.lock();
        try {
            final MountInfo.Mount mount = mounts.mountMap.get(s.st_dev);

            if (mount == null || mounts.isVolatile(mount)) {
                return true;
            }
        } finally {
            lock.unlock();
        }

        return false;
    }

    private void addNameCandidates(String filepath, ObjectSet<String> mimeCandidates) {
        final String name = extractName(filepath);

        if (TextUtils.isEmpty(name)) {
            return;
        }

        final int dot = name.lastIndexOf('.');

        if (dot == -1 || dot == name.length() - 1) {
            return;
        }

        final String extension = name.substring(dot + 1, name.length());

        mimeCandidates.add("application/x-" + extension);

        final MimeTypeMap map = MimeTypeMap.getSingleton();

        final String foundMime = map.getMimeTypeFromExtension(extension);

        if (!TextUtils.isEmpty(foundMime) && !DEFAULT_MIME.equals(foundMime)) {
            mimeCandidates.add(foundMime);
        }
    }

    void assertAbsolute(Uri uri) throws FileNotFoundException {
        if (uri == null || !authority.equals(uri.getAuthority()) || uri.getPath() == null) {
            throw new FileNotFoundException();
        }

        assertAbsolute(uri.getPath());
    }

    static void assertFilename(String nameStr) throws FileNotFoundException {
        if (nameStr.length() == 0) {
            throw new FileNotFoundException("The name is empty");
        }

        if (nameStr.indexOf('\0') != -1) {
            throw new FileNotFoundException("The file name contains illegal characters");
        }

        if (nameStr.indexOf('/') != -1) {
            throw new FileNotFoundException(nameStr + " is not a valid filename, must not contain '/'");
        }
    }

    static void assertAbsolute(String pathStr) throws FileNotFoundException {
        if (pathStr.length() == 0) {
            throw new FileNotFoundException("The path is empty");
        }

        if (pathStr.indexOf('\0') != -1) {
            throw new FileNotFoundException("The file path contains illegal characters");
        }

        if (pathStr.charAt(0) != '/') {
            throw new FileNotFoundException(pathStr + " is invalid in this context, must be absolute");
        }
    }

    /**
     * @return {@code true} if filesystem is in list of filesystems, known to support telldir, Linux filename conventions etc. {@code false} otherwise
     */
    public static boolean isPosix(String filesystemName) {
        switch (filesystemName) {
            case "ext3":
            case "ext4":
            case "xfs":
            case "f2fS":
            case "procfs":
            case "sysfs":
            case "tmpfs":
            case "devpts":
            case "rootfs":
                return true;
            default:
                return false;
        }
    }

    private void wow() {
        PackageManager pm = getPackageManager();


    }

    private static final class TimestampedMime {
        long size = -1;
        long when;
        String[] mime;
    }
}
