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

import android.content.ContentProvider;
import android.content.Context;
import android.database.CrossProcessCursorWrapper;
import android.database.CursorWindow;
import android.database.CursorWrapper;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.provider.DocumentsContract;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Fd;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.OS;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.Process;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.os.Process.myPid;

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
    public static SyscallFactory create(Context context, String seLinuxContext) throws IOException {
        final String command = new File(context.getApplicationInfo().nativeLibraryDir, System.mapLibraryName(EXEC_NAME)).getAbsolutePath();

        final String address = UUID.randomUUID().toString();

        final StringBuilder args = new StringBuilder(command)
                .append(' ')
                .append(address);

        if (!TextUtils.isEmpty(seLinuxContext)) {
            args.append(' ').append(seLinuxContext);
        }

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
    private volatile FileDescriptor terminalFd;

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
        return FdCompat.adopt(openFileDescriptor(file, mode));
    }

    @WorkerThread
    public void mkdirat(@DirFd int fd, String filepath, int mode) throws IOException, FactoryBrokenException {
        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);
        try {
            mkdirInternal(pfd, filepath, mode);
        } finally {
            if (pfd != null) { try { pfd.close(); } catch (IOException ignore) {} }
        }
    }

    @WorkerThread
    public void unlinkat(@DirFd int fd, String filepath, int mode) throws IOException, FactoryBrokenException {
        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);
        try {
            unlinkInternal(pfd, filepath, mode);
        } finally {
            if (pfd != null) { try { pfd.close(); } catch (IOException ignore) {} }
        }
    }

    @CheckResult
    @WorkerThread
    public int inotify_add_watch(@InotifyFd int target, @Fd int pathnameFd) throws IOException, FactoryBrokenException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(target);
             ParcelFileDescriptor pfd2 = ParcelFileDescriptor.fromFd(pathnameFd)) {
            return addWatchInternal(pfd, pfd2);
        }
    }

    @WorkerThread
    public void renameat(@DirFd int from, String pathname1, @DirFd int to, String pathname2) throws IOException, FactoryBrokenException {
        try (ParcelFileDescriptor pfd1 = ParcelFileDescriptor.fromFd(from)) {
            ParcelFileDescriptor pfd2;

            if (from == to) {
                pfd2 = pfd1;
            } else {
                pfd2 = ParcelFileDescriptor.fromFd(to);
            }

            try {
                renameInternal(pfd1, pathname1, pfd2, pathname2);
            } finally {
                if (pfd2 != pfd1) shut(pfd2);
            }
        }
    }

    @WorkerThread
    public void mknodat(int fd, String pathname, int mode, int device) throws IOException, FactoryBrokenException {
        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);
        try {
            mknodInternal(pfd, pathname, mode, device);
        } finally {
            if (pfd != null) { try { pfd.close(); } catch (IOException ignore) {} }
        }
    }

    @CheckResult
    @WorkerThread
    public String readlinkat(int target, String name) throws IOException, FactoryBrokenException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(target)) {
            return readlinkInternal(pfd, name);
        }
    }

    @WorkerThread
    public @NonNull ParcelFileDescriptor open(String filepath, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        return FdCompat.adopt(openInternal(null, filepath, mode));
    }

    @WorkerThread
    public @NonNull ParcelFileDescriptor openat(@DirFd int fd, String filepath, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        final ParcelFileDescriptor pfd = fd < 0 ? null : ParcelFileDescriptor.fromFd(fd);
        try {
            return FdCompat.adopt(openInternal(pfd, filepath, mode));
        } finally {
            if (pfd != null) { try { pfd.close(); } catch (IOException ignore) {} }
        }
    }

    @NonNull FileDescriptor openFileDescriptor(File file, @OS.OpenFlag int mode) throws IOException, FactoryBrokenException {
        return openInternal(null, file.getPath(), mode);
    }

    private int addWatchInternal(ParcelFileDescriptor pfd, ParcelFileDescriptor pathnameFd) throws FactoryBrokenException, IOException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new AddWatch(pfd, pathnameFd);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    try {
                        return Integer.parseInt(response.message);
                    } catch (NumberFormatException nfe) {
                        LogUtil.swallowError(response.message);

                        throw new IOException("Failed to add inotify watch: " + response.message);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        } catch (IOException e) {
            e.printStackTrace();
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void unlinkInternal(ParcelFileDescriptor fd, String path, int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new UnlinkReq(fd, path, mode);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    LogUtil.swallowError(response.message);

                    throw new IOException("Failed to delete: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void renameInternal(ParcelFileDescriptor pfd1, String pathname1, ParcelFileDescriptor pfd2, String pathname2) throws FactoryBrokenException, IOException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new RenameReq(pfd1, pathname1, pfd2, pathname2);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    LogUtil.swallowError(response.message);

                    throw new IOException("Failed to rename: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void mknodInternal(ParcelFileDescriptor pfd, String pathname, int mode, int device) throws FactoryBrokenException, IOException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new MknodReq(pfd, pathname, mode, device);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    LogUtil.swallowError(response.message);

                    throw new IOException("Failed to create a node: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private void mkdirInternal(ParcelFileDescriptor fd, String path, int mode) throws IOException, FactoryBrokenException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new MkdirReq(fd, path, mode);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.request == request) {
                    if ("READY".equals(response.message)) {
                        return;
                    }

                    LogUtil.swallowError(response.message);

                    throw new IOException("Failed to create directory: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private FileDescriptor openInternal(ParcelFileDescriptor fd, String path, int mode) throws FactoryBrokenException, IOException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new OpenReq(fd, path, mode);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.fd != null) {
                    if (response.request == request) {
                        return response.fd;
                    }

                    FdCompat.closeDescriptor(response.fd);
                }

                if (response.message != null) {
                    LogUtil.swallowError(response.message);

                    if (response.request == request) {
                        throw new IOException("Failed to open file: " + response.message);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
        }

        close();

        throw new FactoryBrokenException("Failed to retrieve response from helper");
    }

    private String readlinkInternal(ParcelFileDescriptor pfd, String name) throws FactoryBrokenException, IOException {
        if (closedStatus.get())
            throw new FactoryBrokenException("Already closed");

        final FdReq request = serverThread.new ReadLinkReq(pfd, name);

        FdResp response;
        try {
            if (intake.offer(request, HELPER_TIMEOUT, TimeUnit.MILLISECONDS)
                    && (response = responses.poll(IO_TIMEOUT, TimeUnit.MILLISECONDS)) != null) {
                if (response.message != null) {
                    if (response.message.startsWith("/")) {
                        return response.message;
                    }

                    LogUtil.swallowError(response.message);

                    throw new IOException("Failed to resolve link path: " + response.message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted before completion");
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
                serverThread.interrupt();

                do {
                    intake.clear();
                }
                while (!intake.offer(FdReq.STOP));
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
            throw new IOException("Interrupted before completion", e);
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

                    final FdResp error = new FdResp(FdReq.STOP, message, null);

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

            if (!m.matches())
                throw new IOException("Can't get helper PID" + (greeting.length() == 0 ? "" : " : " + greeting));

            return Integer.valueOf(m.group(1));
        }

        // ensure, that a filename/header packets fit completely in buffer
        private static final int minBufferSize = 255 * 2 + 4;

        private void initializeAndHandleRequests(int helperPid) throws Exception {
            while (!isInterrupted()) {
                try (LocalSocket localSocket = serverSocket.lss.accept())
                {
                    final int socketPid = localSocket.getPeerCredentials().getPid();
                    if (socketPid != helperPid) {
                        continue;
                    }

                    try (ReadableByteChannel rbc = Channels.newChannel(localSocket.getInputStream());
                         WritableByteChannel wbc = Channels.newChannel(localSocket.getOutputStream())) {

                        final String socketMsg = readMessage(rbc);
                        final FileDescriptor ptmxFd = getFd(localSocket);

                        if (ptmxFd == null)
                            throw new Exception("Can't get client tty" + (socketMsg.length() == 0 ? "" : " : " + socketMsg));

                        terminalFd = ptmxFd;

                        logTrace(Log.DEBUG, "Response to tty request: '" + socketMsg + "', descriptor " + ptmxFd);

                        try (CachingWriter clientTty = new CachingWriter(Channels.newWriter(new FileOutputStream(ptmxFd).getChannel(), StandardCharsets.UTF_8.newEncoder(), minBufferSize))) {
                            // Indicate to the helper that it can close it's copy of it's controlling tty.
                            // When our end is closed the kernel tty driver will send SIGHUP to the helper,
                            // cleanly killing it's root process for us
                            clientTty.append("GO\n").flush();

                            // as little exercise in preparation to real deal, try to protect our helper from OOM killer
                            final String oomFile = "/proc/" + helperPid + "/oom_score_adj";

                            final FdResp oomFileTestResp = sendFdRequest(new OpenReq(null, oomFile, OS.O_RDWR), clientTty, rbc, wbc, localSocket);

                            logTrace(Log.DEBUG, "Response to " + oomFile + " request: " + oomFileTestResp);

                            if (oomFileTestResp.fd != null) {
                                try (OutputStreamWriter oow = new OutputStreamWriter(new FileOutputStream(oomFileTestResp.fd))) {
                                    oow.append("-1000");

                                    logTrace(Log.DEBUG, "Successfully adjusted helper's OOM score to -1000");
                                } catch (IOException ok) {
                                    logException("Write to " + oomFile + " failed", ok);
                                }
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
                            if (response.fd != null) {
                                FdCompat.closeDescriptor(response.fd);
                            }
                        }
                    } catch (IOException ioe) {
                        logException("Error during data exchange", ioe);

                        responses.offer(new FdResp(fileOps, ioe.getMessage(), null), IO_TIMEOUT, TimeUnit.MILLISECONDS);

                        throw ioe;
                    }
                } catch (Throwable t) {
                    if (response != null && response.fd != null)
                        FdCompat.closeDescriptor(response.fd);

                    throw t;
                }
            }
        }

        private FdResp sendFdRequest(FdReq fileOps, CachingWriter req, ReadableByteChannel rvc, WritableByteChannel wbc, LocalSocket ls) throws IOException {
            fileOps.writeRequest(req, wbc, ls);

            return fileOps.readResponse(fileOps, rvc, ls);
        }

        private String readMessage(ReadableByteChannel channel) throws IOException {
            statusMsg.clear();

            byte[] result = statusMsg.array();

            int totalReadCount = 0;
            do {
                if (statusMsg.position() == statusMsg.limit()) {
                    final int newSize = result.length + statusMsg.capacity();

                    final byte[] newBuffer = Arrays.copyOf(result, newSize);

                    System.arraycopy(statusMsg.array(), 0, newBuffer, statusMsg.arrayOffset(), statusMsg.capacity());

                    result = newBuffer;
                }

                if (totalReadCount != 0 && statusMsg.get(statusMsg.position() - 1) == '\0') {
                    // the terminating character was reached, bail
                    break;
                }

                if (statusMsg.position() == statusMsg.limit()) {
                    statusMsg.clear();
                }

                lastClientReadCount = channel.read(statusMsg);

                if (lastClientReadCount == -1) {
                    throw new IOException("Disconnected before reading complete message");
                }

                totalReadCount += lastClientReadCount;
            }
            while (true);

            return new String(result, 0, totalReadCount - 1);
        }

        private FileDescriptor[] descriptors = new FileDescriptor[1];

        private void setFd(WritableByteChannel wbc, LocalSocket ls, ParcelFileDescriptor... pfds) throws IOException {
            if (pfds.length == 0) {
                return;
            }

            // see https://code.google.com/p/android/issues/detail?id=231609
            for (final ParcelFileDescriptor p : pfds) {
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

            public OpenReq(ParcelFileDescriptor outboundFd, String fileName, int mode) {
                super(TYPE_OPEN, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(fileName.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                String responseStr = readMessage(rbc);
                final FileDescriptor fd = getFd(ls);

                if (fd == null && "READY".equals(responseStr)) { // unlikely, but..
                    responseStr = "Received no file descriptor from helper";
                }

                return new FdResp(fileOps, responseStr, fd);
            }
        }

        final class MkdirReq extends FdReq {
            static final int TYPE_MKDIR = 2;

            public MkdirReq(ParcelFileDescriptor outboundFd, String fileName, int mode) {
                super(TYPE_MKDIR, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(fileName.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
            }
        }

        final class UnlinkReq extends FdReq {
            static final int TYPE_UNLINK = 3;

            public UnlinkReq(ParcelFileDescriptor outboundFd, String fileName, int mode) {
                super(TYPE_UNLINK, fileName, mode, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(mode)
                        .append(' ')
                        .append(fileName.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
            }
        }

        final class AddWatch extends FdReq {
            static final int TYPE_ADD_WATCH = 4;

            public AddWatch(ParcelFileDescriptor outboundFd, ParcelFileDescriptor pathnameFd) {
                super(TYPE_ADD_WATCH, "whatever", 0, outboundFd, pathnameFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
            }
        }

        final class MknodReq extends FdReq {
            static final int TYPE_MKNOD = 5;

            final int device;

            public MknodReq(ParcelFileDescriptor outboundFd, String fileName, int mode, int device) {
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
                        .append(fileName.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
            }
        }

        final class ReadLinkReq extends FdReq {
            static final int TYPE_READLINK = 6;

            public ReadLinkReq(ParcelFileDescriptor outboundFd, String pathname) {
                super(TYPE_READLINK, pathname, 0, outboundFd);
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(fileName.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
            }
        }

        final class RenameReq extends FdReq {
            static final int TYPE_UNLINK = 7;

            final String fileName2;

            public RenameReq(ParcelFileDescriptor fd1, String fileName1, ParcelFileDescriptor fd2, String fileName2) {
                super(TYPE_UNLINK, fileName1, 0, fd1, fd2);

                this.fileName2 = fileName2;
            }

            @Override
            public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
                super.writeRequest(reqWriter, wbc, ls);

                reqWriter.append(outboundFd == null ? 0 : outboundFd.length)
                        .append(' ')
                        .append(fileName.getBytes().length)
                        .append(' ')
                        .append(fileName2.getBytes().length)
                        .append(' ');

                reqWriter.append(fileName).append(fileName2).append("\n").flush();

                if (outboundFd != null && outboundFd.length != 0) {
                    setFd(wbc, ls, outboundFd);
                }
            }

            @Override
            public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
                return new FdResp(fileOps, readMessage(rbc), null);
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
            // just as planned
        }
    }

    private static void shut(FileDescriptor fd) {
        try {
            if (fd != null) {
                FdCompat.closeDescriptor(fd);
            }
        } catch (IOException e) {
            // just as planned
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

    private static class FdReq {
        static FdReq STOP = new FdReq(0, null, 0);

        static FdReq PLACEHOLDER = new FdReq(0, null, 0);

        final int reqType;
        final ParcelFileDescriptor[] outboundFd;
        final String fileName;
        final int mode;

        public FdReq(int reqType, String fileName, int mode) {
            this.reqType = reqType;
            this.fileName = fileName;
            this.mode = mode;
            this.outboundFd = null;
        }

        public FdReq(int reqType, String fileName, int mode, ParcelFileDescriptor outboundFd) {
            this.reqType = reqType;
            this.fileName = fileName;
            this.mode = mode;

            this.outboundFd = outboundFd != null && outboundFd.getFd() >= 0 ?
                    new ParcelFileDescriptor[] { outboundFd } : null;
        }

        public FdReq(int reqType, String fileName, int mode, ParcelFileDescriptor... outboundFd) {
            this.reqType = reqType;
            this.outboundFd = outboundFd;
            this.fileName = fileName;
            this.mode = mode;
        }

        public void close() {
            if (outboundFd != null) {
                for (ParcelFileDescriptor fd : outboundFd) {
                    try { fd.close(); } catch (IOException ignored) {}
                }
            }
        }

        @Override
        public String toString() {
            return fileName + ',' + mode;
        }

        public void writeRequest(CachingWriter reqWriter, WritableByteChannel wbc, LocalSocket ls) throws IOException {
            reqWriter.append(reqType).append('\n').flush();
        }

        public FdResp readResponse(FdReq fileOps, ReadableByteChannel rbc, LocalSocket ls) throws IOException {
            return null;
        }
    }

    private static final class FdResp {
        final FdReq request;
        final String message;
        @Nullable final FileDescriptor fd;

        public FdResp(FdReq request, String message, @Nullable FileDescriptor fd) {
            this.request = request;
            this.message = message;
            this.fd = fd;
        }

        @Override
        public String toString() {
            return "Request: " + request + ". Helper response: '" + message + "', descriptor: " + fd;
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

final class CachingWriter extends Writer {
    private final Writer writer;

    private char[] writeBuffer;

    private final StringBuilder builder = new StringBuilder();

    CachingWriter(Writer writer) {
        this.writer = writer;
    }

    @Override
    public void write(@NonNull String str, int off, int len) throws IOException {
        append(str, off, len);
    }

    @Override
    public void write(int c) throws IOException {
        builder.append((char) c);
    }

    @Override
    public void write(@NonNull String str) throws IOException {
        append(str);
    }

    @Override
    public CachingWriter append(CharSequence csq, int start, int end) throws IOException {
        builder.append(csq, start, end);
        return this;
    }

    @Override
    public CachingWriter append(CharSequence csq) throws IOException {
        builder.append(csq);
        return this;
    }

    @Override
    public CachingWriter append(char character) {
        builder.append(character);
        return this;
    }

    public CachingWriter append(int integer) {
        builder.append(integer);
        return this;
    }

    public CachingWriter append(String string) {
        builder.append(string);
        return this;
    }

    @Override
    public void write(@NonNull char[] cbuf, int off, int len) throws IOException {
        flushBuffer();
        writer.write(cbuf, off, len);
    }

    private void flushBuffer() throws IOException {
        final int bufLength = builder.length();

        char[] buffer;

        if (bufLength > 9000) {
            // don't permanently hold onto very large buffers
            buffer = new char[bufLength];
        } else {
            if (writeBuffer == null || writeBuffer.length < bufLength) {
                writeBuffer = new char[bufLength];
            }

            buffer = writeBuffer;
        }

        builder.getChars(0, bufLength, writeBuffer, 0);

        builder.setLength(0);

        writer.write(buffer, 0, bufLength);
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}