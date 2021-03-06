/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fakenames.syscallserver;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import com.carrotsearch.hppc.ObjectArrayList;

import net.sf.xfd.CachingWriter;
import net.sf.xfd.DirFd;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.Fd;
import net.sf.xfd.InotifyFd;
import net.sf.xfd.InotifyImpl;
import net.sf.xfd.LogUtil;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.Utf8;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.lang.Process;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A factory object, that can be used to create {@link ParcelFileDescriptor} instances for files, inaccessible to
 * the application itself. Simply put, semantics of {@link #open(File, int)} are same as of
 * {@link ParcelFileDescriptor#open(File, int)} but with root access.
 * <p>
 * Here is what it does:
 * <ul>
 * <li> requests root access by calling available "su" command;
 * <li> opens a file via the root access;
 * <li> gets it's file descriptor in JVM and returns to you in form of {@link ParcelFileDescriptor}.
 * </ul>
 * <p>
 * Known classes, that can be used with file descriptors are:
 * <ul>
 * <li> {@link java.io.FileInputStream} and {@link java.io.FileOutputStream};
 * <li> {@link java.io.RandomAccessFile};
 * <li> {@link java.nio.channels.FileChannel}
 * <li> {@link java.nio.channels.FileLock};
 * <li> {@link android.os.MemoryFile};
 * </ul>
 * and, probably, many others. The inner workings of {@link android.content.ContentProvider} and entire Android
 * Storage Access Framework are based on them as well.
 * <p>
 * The implementation uses a helper process, run with elevated privileges, that communicates with background thread via
 * a domain socket. There is a single extra thread and single process per factory instance and a best effort is taken to
 * cleanup those when the instance is closed.
 * <p>
 * Note, that most of descriptor properties, including read/write access modes can not be changed after it was created.
 * All descriptor properties are retained when passed between processes, such as via AIDL/Binder or Unix domain
 * sockets, but the integer number, representing the descriptor in each process, may change.
 *
 * @see ParcelFileDescriptor#open(File, int)
 * @see ParcelFileDescriptor#getFileDescriptor()
 */
@SuppressWarnings("WeakerAccess")
public final class SyscallFactory implements Closeable {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";
    public static final String PRIMARY_TIMEOUT = "net.sf.fdshare.TIMEOUT_1";
    public static final String SECONDARY_TIMEOUT = "net.sf.fdshare.TIMEOUT_2";

    private static final String FD_HELPER_TAG = "fdhelper";

    static final String EXEC_PIC = "fdhelper-" + BuildConfig.NATIVE_VER + "_PIC_exec";
    static final String EXEC_NONPIC = "fdhelper-" + BuildConfig.NATIVE_VER + "_exec";

    static final String EXEC_NAME;
    static final boolean DEBUG;
    static final long HELPER_TIMEOUT;
    static final long IO_TIMEOUT;

    static {
        EXEC_NAME = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN ? EXEC_PIC : EXEC_NONPIC;

        DEBUG = "true".equals(System.getProperty(DEBUG_MODE));

        HELPER_TIMEOUT = Long.parseLong(System.getProperty(PRIMARY_TIMEOUT, "20000"));
        IO_TIMEOUT = Long.parseLong(System.getProperty(SECONDARY_TIMEOUT, "2500"));
    }

    /**
     * Create a FileDescriptorFactory, using an internally managed helper
     * process. The helper is run with superuser privileges via the "su"
     * command, available on system's PATH.
     * <p>
     * The device has to be rooted. The "su" command must support
     * {@code su -c "command with arguments"} syntax (most modern ones do).
     * <p>
     * You are highly recommended to cache and reuse the returned instance.
     *
     * @throws IOException if creation of instance fails, such as due to absence of "su" command in {@code PATH} etc.
     */
    public static SyscallFactory create(Context context) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir, System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        final StringBuilder args = new StringBuilder(command)
                .append(' ')
                .append(address);

        return create(address, "su", "-c", args.toString());
    }

    @VisibleForTesting
    static SyscallFactory create(String address, String... cmd) throws IOException {
        // must be created before the process
        final LocalServerSocket socket = new LocalServerSocket(address);
        try {
            final Process shell = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            final SyscallFactory result = new SyscallFactory(shell, socket);

            result.startServer();

            return result;
        } catch (Throwable t) {
            shut(socket);

            throw t;
        }
    }

    private final AtomicBoolean closedStatus = new AtomicBoolean(false);
    private final ArrayBlockingQueue<FdReq> intake = new ArrayBlockingQueue<>(1);
    private final SynchronousQueue<FdResp> responses = new SynchronousQueue<>();

    final CloseableSocket serverSocket;
    final Process clientProcess;

    private volatile Server serverThread;
    private volatile ParcelFileDescriptor terminalFd;

    private SyscallFactory(final Process clientProcess, final LocalServerSocket serverSocket) {
        this.clientProcess = clientProcess;
        this.serverSocket = new CloseableSocket(serverSocket);

        intake.offer(FdReq.PLACEHOLDER);
    }

    private void startServer() throws IOException {
        serverThread = new Server();
        serverThread.start();
    }

    /**
     * Return file descriptor for supplied file, open for specified access with supplied flags
     * and the same creation mode as used by {@link ParcelFileDescriptor#open(File, int)}.
     *
     * <p>
     *
     * <b>Do not call this method from the main thread!</b>
     *
     * @throws IOException recoverable error, such as when file was not found
     * @throws FactoryBrokenException irrecoverable error, that renders this factory instance unusable
     */
    @WorkerThread
    public @NonNull ParcelFileDescriptor open(File file, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        return FdCompat.adopt(openFileDescriptor(file, mode));
    }

    @NonNull FileDescriptor openFileDescriptor(File file, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        return openInternal(null, file.getPath(), mode);
    }

    @WorkerThread
    public @NonNull ParcelFileDescriptor open(CharSequence filepath, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        return FdCompat.adopt(openInternal(null, filepath, mode));
    }

    @WorkerThread
    public @NonNull ParcelFileDescriptor openat(@DirFd int fd, CharSequence filepath, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);

        return FdCompat.adopt(openInternal(pfd, filepath, mode));
    }

    @WorkerThread
    public void mkdirat(@DirFd int fd, CharSequence filepath, int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);

        mkdirInternal(pfd, filepath, mode);
    }

    @WorkerThread
    public boolean faccessat(int fd, CharSequence pathname, int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);

        return faccessInternal(pfd, pathname, mode);
    }

    @WorkerThread
    public void unlinkat(@DirFd int fd, CharSequence filepath, int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);

        unlinkInternal(pfd, filepath, mode);
    }

    @CheckResult
    @WorkerThread
    public int inotify_add_watch(InotifyImpl inotify, @InotifyFd int target, @Fd int pathnameFd) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(target);
        final ParcelFileDescriptor pfd2 = ParcelFileDescriptor.fromFd(pathnameFd);

        return addWatchInternal(inotify, pfd, pfd2);
    }

    @WorkerThread
    public void renameat(@DirFd int from, CharSequence pathname1, @DirFd int to, CharSequence pathname2) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd2 = to < 0 ? null : ParcelFileDescriptor.fromFd(to);

        final ParcelFileDescriptor pfd1;

        if (from == to) {
            pfd1 = pfd2;
        } else if (from < 0) {
            if (to < 0) {
                pfd1 = null;
            } else {
                pfd1 = pfd2;
            }
        } else {
            pfd1 = ParcelFileDescriptor.fromFd(from);
        }

        renameInternal(pfd1, pathname1, pfd2, pathname2);
    }

    @WorkerThread
    public void linkat(@DirFd int from, CharSequence pathname1, @DirFd int to, CharSequence pathname2, int flags) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd2 = to < 0 ? null : ParcelFileDescriptor.fromFd(to);

        final ParcelFileDescriptor pfd1;

        if (from == to) {
            pfd1 = pfd2;
        } else if (from < 0) {
            if (to < 0) {
                pfd1 = null;
            } else {
                pfd1 = pfd2;
            }
        } else {
            pfd1 = ParcelFileDescriptor.fromFd(from);
        }

        linkInternal(pfd1, pathname1, pfd2, pathname2, flags);
    }

    @WorkerThread
    public void mknodat(int fd, CharSequence pathname, int mode, int device) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);

        mknodInternal(pfd, pathname, mode, device);
    }

    @NonNull
    @CheckResult
    @WorkerThread
    public String readlinkat(int target, CharSequence name) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = target < 0 ? null : ParcelFileDescriptor.fromFd(target);

        return readlinkInternal(pfd, name);
    }

    @WorkerThread
    public void fstatat(@DirFd int dir, CharSequence pathname, Stat stat, int flags) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final ParcelFileDescriptor pfd = dir < 0 ? null : ParcelFileDescriptor.fromFd(dir);

        fstatInternal(pfd, pathname, stat, flags);
    }

    @WorkerThread
    public @NonNull ParcelFileDescriptor creat(CharSequence filepath, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        return FdCompat.adopt(creatInternal(filepath, mode));
    }

    private static void throwException(String message) throws IOException {
        final int idx = message.indexOf(' ');
        if (idx < 0) {
            throw new IOException(message);
        }

        final int maybeErrno = parseErrno(message, idx);
        if (maybeErrno < 0) {
            throw new IOException(message);
        }

        throw new ErrnoException(maybeErrno, message.substring(idx + 1));
    }

    // copy-pasted from Integer.parseInt
    private static int parseErrno(String s, int len) {
        final int radix = 10;

        int result = 0;
        boolean negative = false;
        int i = 0;
        int limit = -Integer.MAX_VALUE;
        int multmin;
        int digit;

        if (len > 0) {
            char firstChar = s.charAt(0);
            if (firstChar < '0') { // Possible leading "+" or "-"
                if (firstChar == '-') {
                    negative = true;
                    limit = Integer.MIN_VALUE;
                } else if (firstChar != '+')
                    return -1;

                if (len == 1) // Cannot have lone "+" or "-"
                    return -1;
                i++;
            }
            multmin = limit / radix;
            while (i < len) {
                // Accumulating negatively avoids surprises near MAX_VALUE
                digit = Character.digit(s.charAt(i++),radix);
                if (digit < 0) {
                    return -1;
                }
                if (result < multmin) {
                    return -1;
                }
                result *= radix;
                if (result < limit + digit) {
                    return -1;
                }
                result -= digit;
            }
        } else {
            return -1;
        }
        return negative ? result : -result;
    }

    /**
     * Trigger optional startup bookkeeping.
     *
     * @throws IOException if the initialization fails
     * @throws FactoryBrokenException if irrecoverable error happens
     */
    public void init() throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new InitReq();

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                response.close();

                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    /**
     * Undo changes, performed during optional startup bookkeeping.
     *
     * @throws IOException if the operation fails
     * @throws FactoryBrokenException if irrecoverable error happens
     */
    public void cleanup() throws IOException, FactoryBrokenException {
        if (closedStatus.get()) throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new CleanupReq();

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                response.close();

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private boolean enqueue(FdReq request) throws InterruptedException {
        boolean result = false;

        try {
            result = intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS);
        } finally {
            if (!result) {
                request.close();
            }
        }

        return result;
    }

    private int addWatchInternal(InotifyImpl inotify, ParcelFileDescriptor pfd, ParcelFileDescriptor pathnameFd) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new AddWatch(inotify, pfd, pathnameFd);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    try {
                        return Integer.parseInt(response.message);
                    } catch (NumberFormatException nfe) {
                        throwException(response.message);
                    }
                }

                response.close();

                LogUtil.swallowError(response.message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private boolean faccessInternal(ParcelFileDescriptor fd, CharSequence path, int mode) throws IOException, FactoryBrokenException {
        final FdReq request = serverThread.new FaccessReq(fd, path, mode);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    switch (response.message == null ? "" : response.message) {
                        case "true":
                            return true;
                        case "false":
                            return false;
                        case "":
                            throw new IOException("Unable to access " + path + ": unknown error");
                        default:
                            throwException(response.message);
                    }
                }

                if (!"true".equals(response.message) && !"false".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void unlinkInternal(ParcelFileDescriptor fd, CharSequence path, int mode) throws IOException, FactoryBrokenException {
        final FdReq request = serverThread.new UnlinkReq(fd, path, mode);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void renameInternal(ParcelFileDescriptor pfd1, CharSequence pathname1, ParcelFileDescriptor pfd2, CharSequence pathname2) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new RenameReq(pfd1, pathname1, pfd2, pathname2);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void linkInternal(ParcelFileDescriptor pfd1, CharSequence pathname1, ParcelFileDescriptor pfd2, CharSequence pathname2, int flags) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new LinkReq(pfd1, pathname1, pfd2, pathname2, flags);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void fstatInternal(ParcelFileDescriptor pfd, CharSequence pathname, Stat stat, int flags) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new FstatReq(pfd, pathname, flags);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if (response.message == null) {
                        FstatResp resp = (FstatResp) response;

                        stat.init(resp.st_dev, resp.st_ino, resp.st_size, resp.st_blksize, resp.typeOrdinal);

                        return;
                    } else {
                        throwException(response.message);
                    }
                }

                if (response.message != null) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void mknodInternal(ParcelFileDescriptor pfd, CharSequence pathname, int mode, int device) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new MknodReq(pfd, pathname, mode, device);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void mkdirInternal(ParcelFileDescriptor fd, CharSequence path, int mode) throws IOException, FactoryBrokenException {
        final FdReq request = serverThread.new MkdirReq(fd, path, mode);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    throwException(response.message);
                }

                if (!"READY".equals(response.message)) {
                    LogUtil.swallowError(response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private FileDescriptor openInternal(ParcelFileDescriptor fd, CharSequence path, int mode) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new OpenReq(fd, path, mode);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.fd != null) {
                    if (response.request == request) {
                        return response.fd;
                    }

                    response.close();
                }

                if (response.message != null) {
                    if (response.request == request) {
                        throwException(response.message);
                    } else {
                        LogUtil.swallowError(response.message);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            // open() can be blocking in case if FIFOs
            // this means, that simply abandoning it may cause a call to hang
            // so let's close it ASAP instead
            // TODO: properly cancel this shit
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private FileDescriptor creatInternal(CharSequence path, int mode) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new CreatReq(path, mode);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.fd != null) {
                    if (response.request == request) {
                        return response.fd;
                    }

                    response.close();
                }

                if (response.message != null) {
                    if (response.request == request) {
                        throwException(response.message);
                    } else {
                        LogUtil.swallowError(response.message);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private String readlinkInternal(ParcelFileDescriptor pfd, CharSequence name) throws FactoryBrokenException, IOException {
        final FdReq request = serverThread.new ReadLinkReq(pfd, name);

        FdResp response;
        try {
            if (enqueue(request)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.message != null) {
                    if (response.message.startsWith("/")) {
                        return response.message;
                    }

                    if (response.request == request) {
                        throwException(response.message);
                    } else {
                        LogUtil.swallowError(response.message);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InterruptedIOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    /**
     * Set the flag, indicating the internally used thread and helper process to stop and making further attempts
     * to use this instance fail. This method can be used any number of times, even if the instance is already closed.
     * It does not throw.
     */
    @Override
    public void close() {
        if (closedStatus.compareAndSet(false, true)) {
            shut(clientProcess);
            shut(serverSocket);
            shut(terminalFd);

            if (serverThread != null) {
                while (!intake.offer(FdReq.STOP)) {
                    final FdReq stale = intake.poll();
                    stale.close();
                }
            }
        }
    }

    public boolean isClosed() {
        return closedStatus.get();
    }

    public static void assertAccess(Context context) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir, System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final Process shell = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();

        final StdoutConsumer stdoutConsumer = new StdoutConsumer(shell.getInputStream());
        stdoutConsumer.start();

        try {
            final int exitCode = shell.waitFor();

            stdoutConsumer.quit();

            if (exitCode != 0) {
                throw new IOException("Unable to confirm root: expected UID 0, but was " + exitCode);
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Interrupted before completion");
        }
    }

    private final class Server extends Thread {
        private final ByteBuffer statusMsg = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());

        int lastClientReadCount;

        Server() throws IOException {
            super("fd receiver");
        }

        @Override
        public void run() {
            String message = null;

            try (ReadableByteChannel clientOutput = Channels.newChannel(clientProcess.getInputStream());
                 Closeable c = SyscallFactory.this)
            {
                try {
                    initializeAndHandleRequests(readHelperPid(clientOutput));
                } finally {
                    try {
                        statusMsg.clear();
                        do {
                            lastClientReadCount = clientOutput.read(statusMsg);

                            if (statusMsg.position() == statusMsg.capacity())
                                statusMsg.clear();
                        }
                        while (lastClientReadCount != -1);
                    }
                    catch (IOException ignored) {}
                }
            } catch (Exception e) {
                message = e.getMessage();

                logException("Server thread forced to quit by error", e);
            } finally {
                wakeWaiters(TextUtils.isEmpty(message) ? "The privileged process quit" : message);

                try {
                    setName("BUG: Waiting for su process, which won't quit");

                    final int exitCode = clientProcess.waitFor();

                    logTrace(Log.INFO, "Child exited, exit code: %d", exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // send off any caller threads, the may be still waiting for operation to complete
        private void wakeWaiters(String message) {
            final long leeway = TimeUnit.MILLISECONDS.toNanos(60);
            final long started = System.nanoTime();
            while (true) {
                try {
                    FdReq req = intake.poll(5, TimeUnit.MILLISECONDS);

                    if (req != null) {
                        req.close();
                    }

                    final FdResp error = new FdResp(FdReq.STOP, message);

                    if (!responses.offer(error, 5, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    // ok
                }

                final long newTime = System.nanoTime();
                if (newTime <= started || newTime - started >= leeway) {
                    break;
                }
            }
        }

        private int readHelperPid(ReadableByteChannel clientOutput) throws IOException {
            // the client forks to obtain controlling terminal for itself
            // so we need some way of knowing it's pid
            // note, that certain people are known to write linkers, spouting random bullshit during
            // executable startup, so we must be prepared to filter that out
            final String greeting = readMessage(clientOutput);

            final Matcher m = Pattern.compile("(?:.*)PID:(\\d+)").matcher(greeting);

            if (!m.find())
                throw new IOException("Can't get helper PID" + (greeting.length() == 0 ? "" : " : " + greeting));

            return Integer.valueOf(m.group(1));
        }

        // ensure, that most filename/header packets fit completely in buffer
        private static final int minBufferSize = 255 * 2 + 4;

        private void initializeAndHandleRequests(int helperPid) throws Exception {
            while (!isInterrupted()) {
                try (LocalSocket localSocket = serverSocket.lss.accept())
                {
                    final Credentials credentials = localSocket.getPeerCredentials();

                    if (credentials.getUid() != 0 || credentials.getPid() != helperPid) {
                        continue;
                    }

                    try (ReadableByteChannel rbc = Channels.newChannel(localSocket.getInputStream());
                         WritableByteChannel wbc = Channels.newChannel(localSocket.getOutputStream())) {

                        final String socketMsg = readMessage(rbc);

                        FileDescriptor ptmxFd = getFd(localSocket);

                        if (ptmxFd == null)
                            throw new Exception("Can't get client tty" + (socketMsg.length() == 0 ? "" : " : " + socketMsg));

                        terminalFd = FdCompat.adopt(ptmxFd);

                        ptmxFd = terminalFd.getFileDescriptor();

                        logTrace(Log.DEBUG, "Response to tty request: '" + socketMsg + "', descriptor " + ptmxFd);

                        try (CachingWriter clientTty = new CachingWriter(new FileOutputStream(ptmxFd).getChannel(), minBufferSize)) {
                            // Indicate to the helper that it can close it's copy of it's controlling tty.
                            // When our end is closed the kernel tty driver will send SIGHUP to the helper,
                            // cleanly killing it's root process for us
                            clientTty.append("GO\n").flush();

                            // check if we truly have full access
                            try (FdResp oomFileTestResp = sendFdRequest(new InitReq(), clientTty, rbc, wbc, localSocket)) {
                                logTrace(Log.DEBUG, "Initial response: " + oomFileTestResp);

                                if (oomFileTestResp.fd == null) {
                                    throw new IOException("Failed to obtain test descriptor: " + oomFileTestResp.message);
                                }

                                try (OutputStreamWriter oow = new OutputStreamWriter(new FileOutputStream(oomFileTestResp.fd))) {
                                    oow.append("-1000").flush();

                                    logTrace(Log.DEBUG, "Bootstrap successful!");
                                }
                            } catch (Exception e) {
                                logException("Access test failed", e);
                            }

                            if (intake.take() == FdReq.STOP)
                                return;

                            processRequestsUntilStopped(localSocket, rbc, wbc, clientTty);

                            break;
                        }
                    }
                }
            }
        }

        private void processRequestsUntilStopped(LocalSocket fdrecv,
                                                 ReadableByteChannel statusIn,
                                                 WritableByteChannel statusOut,
                                                 CachingWriter control) throws IOException, InterruptedException {
            FdReq fileOps;

            while ((fileOps = intake.take()) != FdReq.STOP) {
                FdResp response = null;

                try {
                    try {
                        response = sendFdRequest(fileOps, control, statusIn, statusOut, fdrecv);

                        if (!responses.offer(response, IO_TIMEOUT, TimeUnit.MILLISECONDS)) {
                            // the calling thread may have been aborted or something, do the cleanup
                            response.close();
                        }
                    } catch (IOException ioe) {
                        logException("Error during data exchange", ioe);

                        responses.offer(new FdResp(fileOps, ioe.getMessage()), IO_TIMEOUT, TimeUnit.MILLISECONDS);

                        throw ioe;
                    }
                } catch (Throwable t) {
                    if (response != null) {
                        response.close();
                    }

                    throw t;
                } finally {
                    // clear the refs, least the wrath of GC comes upon us

                    //noinspection UnusedAssignment
                    fileOps = null;

                    //noinspection UnusedAssignment
                    response = null;
                }
            }
        }

        private FdResp sendFdRequest(FdReq fileOps, CachingWriter req, ReadableByteChannel rvc, WritableByteChannel wbc, LocalSocket ls) throws IOException {
            try {
                fileOps.writeRequest(req, wbc, ls);
            } finally {
                fileOps.close();
            }

            return fileOps.readResponse(rvc, ls);
        }

        private String readMessage(ReadableByteChannel channel) throws IOException {
            statusMsg.clear();

            return readMessageInner(channel, 0);
        }

        private String readMessageInner(ReadableByteChannel channel, int readSoFar) throws IOException {
            byte[] result = statusMsg.array();
            int offset = statusMsg.arrayOffset();

            int totalReadCount = readSoFar;
            do {
                int lastPos = statusMsg.position();

                if (lastPos == statusMsg.limit()) {
                    final int newSize = result.length + statusMsg.capacity();

                    final byte[] newBuffer = Arrays.copyOfRange(result, offset, newSize);

                    if (result != statusMsg.array()) {
                        System.arraycopy(statusMsg.array(), statusMsg.arrayOffset(), newBuffer, totalReadCount - lastPos, lastPos);
                    }

                    result = newBuffer; offset = 0;

                    statusMsg.clear();
                }

                if (totalReadCount != 0 && statusMsg.get(lastPos - 1) == '\0') {
                    // the terminating character was reached, bail

                    if (result != statusMsg.array() && statusMsg.position() != 0) {
                        // salvage last remaining bytes
                        System.arraycopy(statusMsg.array(), statusMsg.arrayOffset(), result, totalReadCount - lastPos, lastPos);
                    }

                    break;
                }

                lastClientReadCount = channel.read(statusMsg);

                if (lastClientReadCount == -1) {
                    throw new IOException("Disconnected before reading complete message");
                }

                totalReadCount += lastClientReadCount;
            }
            while (true);

            return new String(result, offset, totalReadCount - 1);
        }

        private FileDescriptor[] descriptors = new FileDescriptor[1];

        private void setFd(WritableByteChannel wbc, LocalSocket ls, ParcelFileDescriptor... pfds) throws IOException {
            if (pfds.length == 0) {
                return;
            }

            // see https://code.google.com/p/android/issues/detail?id=231609
            for (final ParcelFileDescriptor p : pfds) {
                if (p == null) return;

                descriptors[0] = p.getFileDescriptor();
                ls.setFileDescriptorsForSend(descriptors);
                statusMsg.limit(1).position(0);
                wbc.write(statusMsg);
            }
        }

        private FileDescriptor getFd(LocalSocket ls) throws IOException {
            final FileDescriptor[] fds = ls.getAncillaryFileDescriptors();

            return fds != null && fds.length == 1 && fds[0] != null ? fds[0] : null;
        }

        final class OpenReq extends FdReq {
            static final int TYPE_OPEN = 1;

            public OpenReq(ParcelFileDescriptor outboundFd, CharSequence fileName, int mode) {
                super(TYPE_OPEN, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                String responseStr = readMessage(rbc);
                final FileDescriptor fd = getFd(ls);

                if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                    responseStr = "Received no file descriptor from helper";
                }

                return new FdResp(this, responseStr, fd);
            }
        }

        final class MkdirReq extends FdReq {
            static final int TYPE_MKDIR = 2;

            public MkdirReq(ParcelFileDescriptor outboundFd, CharSequence fileName, int mode) {
                super(TYPE_MKDIR, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class UnlinkReq extends FdReq {
            static final int TYPE_UNLINK = 3;

            public UnlinkReq(ParcelFileDescriptor outboundFd, CharSequence fileName, int mode) {
                super(TYPE_UNLINK, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class AddWatch extends FdReq {
            static final int TYPE_ADD_WATCH = 4;

            final InotifyImpl inotify;

            public AddWatch(InotifyImpl inotify, ParcelFileDescriptor outboundFd, ParcelFileDescriptor pathnameFd) {
                super(TYPE_ADD_WATCH, "whatever", 0, outboundFd, pathnameFd);

                this.inotify = inotify;
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new InotifyResp(this, readMessage(rbc));
            }
        }

        final class MknodReq extends FdReq {
            static final int TYPE_MKNOD = 5;

            final int device;

            public MknodReq(ParcelFileDescriptor outboundFd, CharSequence fileName, int mode, int device) {
                super(TYPE_MKNOD, fileName, mode, outboundFd);

                this.device = device;
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(device)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class ReadLinkReq extends FdReq {
            static final int TYPE_READLINK = 6;

            public ReadLinkReq(ParcelFileDescriptor outboundFd, CharSequence pathname) {
                super(TYPE_READLINK, pathname, 0, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class RenameReq extends FdReq {
            static final int TYPE_RENAME = 7;

            final CharSequence fileName2;

            public RenameReq(ParcelFileDescriptor fd1, CharSequence fileName1, ParcelFileDescriptor fd2, CharSequence fileName2) {
                super(TYPE_RENAME, fileName1, 0, fd1, fd2);

                this.fileName2 = fileName2;
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ')
                        .append(Utf8.size(fileName2))
                        .append(' ');

                reqWriter.append(fileName).append(fileName2).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class CreatReq extends FdReq {
            static final int TYPE_CREAT = 8;

            public CreatReq(CharSequence fileName1, int mode) {
                super(TYPE_CREAT, fileName1, mode);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                String responseStr = readMessage(rbc);
                final FileDescriptor fd = getFd(ls);

                if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                    responseStr = "Received no file descriptor from helper";
                }

                return new FdResp(this, responseStr, fd);
            }
        }

        final class LinkReq extends FdReq {
            static final int TYPE_LINK = 9;

            final CharSequence fileName2;

            public LinkReq(ParcelFileDescriptor fd1, CharSequence fileName1, ParcelFileDescriptor fd2, CharSequence fileName2, int flags) {
                super(TYPE_LINK, fileName1, flags, fd1, fd2);

                this.fileName2 = fileName2;
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ')
                        .append(Utf8.size(fileName2))
                        .append(' ');

                reqWriter.append(fileName).append(fileName2).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class FaccessReq extends FdReq {
            static final int TYPE_FACCESS = 10;

            public FaccessReq(ParcelFileDescriptor outboundFd, CharSequence fileName, int mode) {
                super(TYPE_FACCESS, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc));
            }
        }

        final class FstatReq extends FdReq {
            static final int TYPE_STAT = 11;

            public FstatReq(ParcelFileDescriptor pfd, CharSequence fileName, int mode) {
                super(TYPE_STAT, fileName, mode, pfd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(Utf8.size(fileName))
                        .append(' ');

                reqWriter.append(fileName).append('\n').flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                statusMsg.clear();

                int hdr = Long.SIZE / Byte.SIZE;

                int lastRead, total = 0;
                do {
                    lastRead = rbc.read(statusMsg);

                    if (lastRead == -1) {
                        throw new IOException("Disconnected before reading complete message");
                    }

                    total += lastRead;
                } while (total < hdr);

                final long errIndicator = statusMsg.getLong(0);

                final String errorMsg;

                if (errIndicator == 1) {
                    byte lastByte = statusMsg.get(statusMsg.position() - 1);

                    if (lastByte == '\0') {
                        errorMsg = new String(statusMsg.array(), statusMsg.arrayOffset() + hdr, statusMsg.position() - hdr - 1);
                    } else {
                        statusMsg.limit(statusMsg.position());
                        statusMsg.position(hdr);
                        statusMsg.compact();

                        errorMsg = readMessageInner(rbc, lastRead - hdr);
                    }

                    statusMsg.clear();
                } else {
                    errorMsg = null;

                    statusMsg.flip();
                    statusMsg.position(hdr);
                }

                return new FstatResp(this, errorMsg, statusMsg);
            }
        }

        final class InitReq extends FdReq {
            static final int TYPE_INIT = 12;

            public InitReq() {
                super(TYPE_INIT, null, 0);
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                String responseStr = readMessage(rbc);
                final FileDescriptor fd = getFd(ls);

                if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                    responseStr = "Received no file descriptor from helper";
                }

                return new FdResp(this, responseStr, fd);
            }
        }

        final class CleanupReq extends FdReq {
            static final int TYPE_CLEANUP = 13;

            public CleanupReq() {
                super(TYPE_CLEANUP, null, 0);
            }

            @Override
            public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(this, readMessage(rbc), null);
            }
        }
    }

    private static void logTrace(int proprity, String message, Object... args) {
        if (DEBUG)
            Log.println(proprity, FD_HELPER_TAG, String.format(message, args));
    }

    private static void logException(String explanation, Exception err) {
        if (DEBUG) {
            Log.e(FD_HELPER_TAG, explanation);

            err.printStackTrace();
        } else {
            Log.d(FD_HELPER_TAG, explanation);
        }
    }

    private static void shut(Closeable closeable) {
        try {
            if (closeable != null)
                closeable.close();
        } catch (IOException e) {
            logException("Failed to close " + closeable, e);
        }
    }

    private static void shut(FileDescriptor fd) {
        try {
            if (fd != null) {
                FdCompat.closeDescriptor(fd);
            }
        } catch (IOException e) {
            logException("Failed to close descriptor", e);
        }
    }

    private static void shut(LocalServerSocket sock) {
        try {
            if (sock != null)
                sock.close();
        } catch (IOException e) {
            logException("Failed to close server socket", e);
        }
    }

    private static void shut(Process proc) {
        try {
            if (proc != null) {
                shut(proc.getInputStream());
                shut(proc.getOutputStream());
                proc.destroy();
            }
        } catch (Exception e) {
            // just as planned
        }
    }

    private static class FdReq implements Closeable {
        static FdReq STOP = new FdReq(0, null, 0);

        static FdReq PLACEHOLDER = new FdReq(0, null, 0);

        final AtomicBoolean done = new AtomicBoolean();

        final int reqType;
        final ParcelFileDescriptor[] outboundFd;
        final CharSequence fileName;
        final int mode;

        public FdReq(int reqType, CharSequence fileName, int mode) {
            this.reqType = reqType;
            this.fileName = fileName;
            this.mode = mode;
            this.outboundFd = null;
        }

        public FdReq(int reqType, CharSequence fileName, int mode, ParcelFileDescriptor outboundFd) {
            this.reqType = reqType;
            this.fileName = fileName;
            this.mode = mode;

            this.outboundFd = outboundFd != null && outboundFd.getFd() >= 0 ?
                    new ParcelFileDescriptor[] { outboundFd } : null;
        }

        public FdReq(int reqType, CharSequence fileName, int mode, ParcelFileDescriptor... outboundFd) {
            this.reqType = reqType;
            this.fileName = fileName;
            this.mode = mode;

            final ObjectArrayList<ParcelFileDescriptor> fds = new ObjectArrayList<>();
            for (ParcelFileDescriptor pfd : outboundFd) {
                if (pfd == null) break;
                fds.add(pfd);
            }

            this.outboundFd = fds.toArray(ParcelFileDescriptor.class);
        }

        public void close() {
            if (done.compareAndSet(false, true)) {
                if (outboundFd != null) {
                    for (ParcelFileDescriptor ofd : outboundFd) {
                        shut(ofd);
                    }
                }
            }
        }

        @Override
        public String toString() {
            return "" + fileName + ',' + mode;
        }

        public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
            reqWriter.append(reqType).append('\n').flush();
        }

        public FdResp readResponse(ReadableByteChannel rbc, LocalSocket ls) throws IOException {
            return null;
        }
    }

    private static class FdResp implements Closeable {
        final AtomicBoolean done = new AtomicBoolean();

        final FdReq request;
        final String message;
        @Nullable final FileDescriptor fd;

        public FdResp(FdReq request, String message) {
            this(request, message, null);
        }

        public FdResp(FdReq request, String message, @Nullable FileDescriptor fd) {
            this.request = request;
            this.message = message;
            this.fd = fd;
        }

        @Override
        public String toString() {
            return "Request: " + request + ". Helper response: '" + message + "', descriptor: " + fd;
        }

        public void close() {
            if (done.compareAndSet(false, true)) {
                shut(fd);
            }
        }
    }

    private static class InotifyResp extends FdResp {
        private final InotifyImpl inotify;

        public InotifyResp(Server.AddWatch request, String message) {
            super(request, message);

            this.inotify = request.inotify;
        }

        @Override
        public void close() {
            if (done.compareAndSet(false, true)) {
                shut(fd);

                try {
                    final int sub = Integer.parseInt(message);

                    inotify.removeSubscriptionInternal(sub);
                } catch (IOException e) {
                    logException("Failed to close watch", e);
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static class FstatResp extends FdResp {
        public final long st_dev;

        public final long st_ino;

        public final long st_size;

        public final int typeOrdinal;

        public final int st_blksize;

        public FstatResp(Server.FstatReq request, String message, ByteBuffer buffer) {
            super(request, message);

            this.st_dev = buffer.getLong();
            this.st_ino = buffer.getLong();
            this.st_size = buffer.getLong();
            this.typeOrdinal = buffer.getInt();
            this.st_blksize = buffer.getInt();
        }
    }

    // workaround for some stupid bug in annotations extractor
    private static class CloseableSocket implements Closeable {
        final LocalServerSocket lss;

        public CloseableSocket(LocalServerSocket lss) {
            this.lss = lss;
        }

        @Override
        public void close() throws IOException {
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    Os.shutdown(lss.getFileDescriptor(), 0);
                } catch (Exception e) {
                    // ignore
                }
            }

            lss.close();
        }
    }
}

final class StdoutConsumer extends Thread {
    private final InputStream stdin;

    private volatile boolean enough;

    StdoutConsumer(InputStream stdin) {
        super("stdout consumer");

        this.stdin = stdin;
    }

    @Override
    public void run() {
        try (InputStream is = stdin) {
            while (!enough) {
                long skipped = is.skip(Long.MAX_VALUE);
            }
        } catch (IOException ignored) {
        }
    }

    void quit() {
        enough = true;

        interrupt();
    }
}