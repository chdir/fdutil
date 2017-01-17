package net.sf.fakenames.syscallserver;

import android.content.Context;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.Fd;
import net.sf.fdlib.GuardFactory;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.InotifyImpl;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Pattern;

import static android.os.Process.myPid;

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

    private Rooted(Context context, OS delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    private volatile SyscallFactory factoryInstance;

    private SyscallFactory getFactory() throws IOException {
        if (factoryInstance == null) {
            synchronized (this) {
                if (factoryInstance == null) {
                    factoryInstance = SyscallFactory.create(context, getContext());
                }
            }
        }

        return factoryInstance;
    }

    private String getContext() {
        String contextFileName = String.format(Locale.ENGLISH, "/proc/%d/attr/current", myPid());

        try (Scanner s = new Scanner(new FileInputStream(contextFileName))) {
            s.useDelimiter("(:|\\Z)");

            // skip user and object type
            if (s.hasNext()) s.next(); else return null;
            if (s.hasNext()) s.next(); else return null;

            if (s.hasNext()) {
                return s.next();
            }
        } catch (FileNotFoundException ok) {
            // either there is no SELinux, or we are simply powerless to do anything
        }

        return null;
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

    @Override
    @WorkerThread
    public int openat(@DirFd int fd, String pathname, int flags, int mode) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            final ParcelFileDescriptor pfd = factory.openat(fd, pathname, flags);

            final @Fd int fdInt = pfd.detachFd();

            return fdInt;
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("open() failed, unable to access privileged process", e);
        }
    }

    @NonNull
    @Override
    public String readlinkat(int fd, String pathname) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            return factory.readlinkat(fd, pathname);
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("readlink() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void renameat(@DirFd int fd, String name, @DirFd int fd2, String name2) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.renameat(fd, name, fd2, name2);
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("rename() failed, unable to access privileged process", e);
        }
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
        return observe(inotifyDescriptor, Looper.myLooper());
    }

    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper) {
        return new RootInotify(inotifyDescriptor, looper);
    }

    @Override
    public void fstat(int fd, Stat stat) throws IOException {
        delegate.fstat(fd, stat);
    }

    @Override
    public void symlinkat(String name, @DirFd int target, String newpath) throws IOException {
        delegate.symlinkat(name, target, newpath);
    }

    @Override
    public void unlinkat(@DirFd int target, String pathname, @UnlinkAtFlags int flags) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.unlinkat(target, pathname, flags);
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("unlink() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void mknodat(@DirFd int target, String pathname, @FileTypeFlag int mode, int device) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.mknodat(target, pathname, mode, device);
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("mknod() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void mkdirat(@DirFd int target, String pathname, int mode) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.mkdirat(target, pathname, mode);
        } catch (FactoryBrokenException e) {
            factoryInstance = null;

            throw new IOException("mkdir() failed, unable to access privileged process", e);
        }
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

    private final class RootInotify extends InotifyImpl {
        RootInotify(@InotifyFd int fd, @Nullable Looper looper) {
            super(fd, looper, Rooted.this, GuardFactory.getInstance(delegate));
        }

        @Override
        protected int addSubscription(@InotifyFd int fd, int watchedFd) throws IOException {
            try {
                final SyscallFactory factory = getFactory();

                return factory.inotify_add_watch(fd, watchedFd);
            } catch (FactoryBrokenException e) {
                factoryInstance = null;

                throw new IOException("inotify_add_watch() failed, unable to access privileged process", e);
            }
        }
    }
}
