package net.sf.fakenames.syscallserver;

import android.content.Context;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.Fd;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.IOException;

public final class Rooted extends net.sf.fdlib.OS {
    private final OS delegate;

    private final Context context;

    public static OS createWithChecks(Context context) throws IOException {
        final Rooted instance = new Rooted(context, getInstance());

        SyscallFactory.assertAccess(context);

        instance.getFactory();

        return instance;
    }

    public static OS newInstance(Context context) throws IOException {
        return new Rooted(context, getInstance());
    }

    private Rooted(Context context, net.sf.fdlib.OS delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    private volatile SyscallFactory factoryInstance;

    private SyscallFactory getFactory() throws IOException {
        if (factoryInstance == null) {
            synchronized (this) {
                factoryInstance = SyscallFactory.create(context);
            }
        }

        return factoryInstance;
    }

    @Override
    @WorkerThread
    public int open(String path, @OpenFlag int flags, int mode) throws IOException {
        return openat(DirFd.AT_FDCWD, path, flags, mode);
    }

    @Override
    @WorkerThread
    public int opendir(String path, @OpenFlag int flags, int mode) throws IOException {
        return opendirat(DirFd.AT_FDCWD, path, flags, mode);
    }

    @Override
    @WorkerThread
    @SuppressWarnings("WrongConstant")
    public int opendirat(@DirFd int fd, String pathname, int flags, int mode) throws IOException {
        return openat(fd, pathname, flags, mode);
    }

    private int openat(@DirFd int fd, String pathname, int flags, int mode) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            final ParcelFileDescriptor pfd = factory.openat(fd, pathname, flags);

            final @Fd int fdInt = pfd.detachFd();

            return fdInt;
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("open() failed: unable to access privileged process", e);
        }
    }

    @NonNull
    @Override
    public String readlink(String path) throws IOException {
        return delegate.readlink(path);
    }

    @Override
    public int inotify_init() throws IOException {
        return delegate.inotify_init();
    }

    @Override
    public Directory list(@Fd int fd) {
        return delegate.list(fd);
    }

    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor) {
        return delegate.observe(inotifyDescriptor);
    }

    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper) {
        return delegate.observe(inotifyDescriptor, looper);
    }

    @Override
    public Stat fstat(@DirFd int fd) {
        return delegate.fstat(fd);
    }

    @Override
    public void symlinkat(String name, @DirFd int target, String newpath) throws IOException {
        delegate.symlinkat(name, target, newpath);
    }

    @Override
    public void unlinkat(@DirFd int target, String name, @UnlinkAtFlags int flags) throws IOException {
        delegate.unlinkat(target, name, flags);
    }

    @Override
    public void mknodat(@DirFd int target, String name, @FileTypeFlag int mode, int device) throws IOException {
        delegate.mknodat(target, name, mode, device);
    }

    @Override
    public void mkdirat(@DirFd int target, String name, int mode) throws IOException {
        delegate.mkdirat(target, name, mode);
    }

    @Override
    public void dup2(@Fd int source, int dest) throws IOException {
        delegate.dup2(source, dest);
    }

    @Override
    public void close(@Fd int fd) throws IOException {
        delegate.close(fd);
    }

    @Override
    public void dispose(int fd) {
        delegate.dispose(fd);
    }
}
