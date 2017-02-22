/*
 * Copyright © 2017 Alexander Rvachev
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
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import net.sf.xfd.CloseableGuard;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.Fd;
import net.sf.xfd.GuardFactory;
import net.sf.xfd.Inotify;
import net.sf.xfd.InotifyFd;
import net.sf.xfd.InotifyImpl;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.util.Locale;
import java.util.Scanner;

import static android.os.Process.myPid;

public final class Rooted extends net.sf.xfd.OS implements Closeable {
    private final OS delegate;

    private final Context context;

    public static Rooted createWithChecks(Context context) throws IOException {
        final Rooted instance = new Rooted(context, getInstance());

        SyscallFactory.assertAccess(context);

        instance.getFactory();

        return instance;
    }

    private Rooted(Context context, OS delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    private volatile FactoryGuard factory;

    private SyscallFactory getFactory() throws IOException {
        FactoryGuard guard;

        final SyscallFactory factory;

        guard = this.factory;

        if (guard == null) {
            synchronized (this) {
                guard = this.factory;

                if (guard == null) {
                    factory = SyscallFactory.create(context);

                    this.factory = new FactoryGuard(this, factory);
                } else {
                    factory = guard.guarded;
                }
            }
        } else {
            factory = guard.guarded;
        }

        return factory;
    }

    @Override
    @CheckResult
    @WorkerThread
    public int creat(@NonNull String path, int mode) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            final ParcelFileDescriptor pfd = factory.creat(path, mode);

            final @Fd int fdInt = pfd.detachFd();

            pfd.close();

            return fdInt;
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("creat() failed, unable to access privileged process", e);
        }
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

            pfd.close();

            return fdInt;
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("open() failed, unable to access privileged process", e);
        }
    }

    @NonNull
    @Override
    @WorkerThread
    public String readlinkat(int fd, String pathname) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            return factory.readlinkat(fd, pathname);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("readlink() failed, unable to access privileged process", e);
        }
    }

    @Override
    @WorkerThread
    public void renameat(@DirFd int fd, String name, @DirFd int fd2, String name2) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.renameat(fd, name, fd2, name2);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("rename() failed, unable to access privileged process", e);
        }
    }

    @Override
    public int inotify_init() throws IOException {
        return delegate.inotify_init();
    }

    @NonNull
    @Override
    public Directory list(@Fd int fd) {
        return delegate.list(fd);
    }

    @NonNull
    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor) {
        return observe(inotifyDescriptor, Looper.myLooper());
    }

    @NonNull
    @Override
    public Inotify observe(@InotifyFd int inotifyDescriptor, Looper looper) {
        return new RootInotify(inotifyDescriptor, looper);
    }

    @Override
    public void fstatat(@DirFd int dir, @NonNull String pathname, @NonNull Stat stat, int flags) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.fstatat(dir, pathname, stat, flags);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("stat() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void fstat(int fd, @NonNull Stat stat) throws IOException {
        delegate.fstat(fd, stat);
    }

    @Override
    public void fsync(int fd) throws IOException {
        delegate.fsync(fd);
    }

    @Override
    public MountInfo getMounts() throws IOException {
        return new MountInfo(OS.getInstance(), open("/proc/self/mountinfo", OS.O_RDONLY, 0));
    }

    @Override
    public void symlinkat(String name, @DirFd int target, String newpath) throws IOException {
        delegate.symlinkat(name, target, newpath);
    }

    @Override
    public void linkat(@DirFd int oldDirFd, String oldName, @DirFd int newDirFd, String newName, @LinkAtFlags int flags) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.linkat(oldDirFd, oldName, newDirFd, newName, flags);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("link() failed, unable to access privileged process", e);
        }
    }

    @Override
    @WorkerThread
    public void unlinkat(@DirFd int target, String pathname, @UnlinkAtFlags int flags) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.unlinkat(target, pathname, flags);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("unlink() failed, unable to access privileged process", e);
        }
    }

    @Override
    @WorkerThread
    public void mknodat(@DirFd int target, String pathname, @FileTypeFlag int mode, int device) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.mknodat(target, pathname, mode, device);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("mknod() failed, unable to access privileged process", e);
        }
    }

    @Override
    @WorkerThread
    public void mkdirat(@DirFd int target, String pathname, int mode) throws IOException {
        try {
            final SyscallFactory factory = getFactory();

            factory.mkdirat(target, pathname, mode);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("mkdir() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void fallocate(@Fd int fd, int mode, long off, long count) throws IOException {
        delegate.fallocate(fd, mode, off, count);
    }

    @Override
    public void readahead(@Fd int fd, long off, int count) throws IOException {
        delegate.readahead(fd, off, count);
    }

    @Override
    public void fadvise(@Fd int fd, long off, long length, int advice) throws IOException {
        delegate.fadvise(fd, off, length, advice);
    }

    @Override
    public boolean isPrivileged() {
        return true;
    }

    @Override
    public boolean faccessat(int fd, String pathname, int mode) throws IOException {
        if (delegate.faccessat(fd, pathname, mode)) {
            return true;
        }

        try {
            final SyscallFactory factory = getFactory();

            return factory.faccessat(fd, pathname, mode);
        } catch (FactoryBrokenException e) {
            factory = null;

            throw new IOException("faccessat() failed, unable to access privileged process", e);
        }
    }

    @Override
    public void dup2(@Fd int source, int dest) throws IOException {
        delegate.dup2(source, dest);
    }

    @Override
    public @Fd int dup(int source) throws IOException {
        return delegate.dup(source);
    }

    @Override
    public void close(@Fd int fd) throws IOException {
        delegate.close(fd);
    }

    @Override
    public void dispose(int fd) {
        delegate.dispose(fd);
    }

    @Override
    public void close() throws IOException {
        synchronized(this) {
            if (factory != null) {
                factory.close();
                factory = null;
            }
        }
    }

    private final class RootInotify extends InotifyImpl {
        RootInotify(@InotifyFd int fd, @Nullable Looper looper) {
            super(fd, looper, Rooted.this, GuardFactory.getInstance(delegate));
        }

        @Override
        @WorkerThread
        protected int addSubscription(@InotifyFd int fd, int watchedFd) throws IOException {
            try {
                final SyscallFactory factory = getFactory();

                return factory.inotify_add_watch(this, fd, watchedFd);
            } catch (FactoryBrokenException e) {
                factory = null;

                throw new IOException("inotify_add_watch() failed, unable to access privileged process", e);
            }
        }
    }

    private static final class FactoryGuard extends CloseableGuard {
        private final SyscallFactory guarded;

        private FactoryGuard(Closeable r, SyscallFactory guarded) {
            super(r);

            this.guarded = guarded;
        }

        @Override
        protected void trigger() {
            close();
        }

        @Override
        public void close() {
            super.close();

            guarded.close();
        }
    }
}
