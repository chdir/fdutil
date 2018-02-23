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
package net.sf.xfd;

import android.os.Looper;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class OS {
    public static final String DEBUG_MODE = "net.sf.fdshare.DEBUG";

    protected OS() {
    }

    @IntDef(value = {F_OK, R_OK, W_OK, X_OK}, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface AccessFlags {}

    public static final int R_OK  = 1 << 2;  /* Read */
    public static final int W_OK  = 1 << 1;  /* Write */
    public static final int X_OK = 1;        /* Execute */
    public static final int F_OK = 0;        /* File exists */

    @IntDef(value = {O_RDONLY, O_WRONLY, O_RDWR})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpenFlag {}

    public static final int O_RDONLY = 0;    // 0b0000000000000000000000;
    public static final int O_WRONLY = 1;    // 0b0000000000000000000001;
    public static final int O_RDWR = 1 << 1;

    public static final int DEF_DIR_MODE =  0b111111001; // 0771 aka drwxrwx--x
    public static final int DEF_FILE_MODE = 0b110110110; // 0666 aka  rw-rw-rw-

    public static final int AT_SYMLINK_NOFOLLOW = 0x100;

    @IntDef(AT_SYMLINK_NOFOLLOW)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatAtFlags {}

    public static final int AT_SYMLINK_FOLLOW = 0x400;

    @IntDef(AT_SYMLINK_FOLLOW)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface LinkAtFlags {}

    @IntDef(AT_REMOVEDIR)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface UnlinkAtFlags {}

    public static final int AT_REMOVEDIR = 0x200;

    @IntDef(value = {S_IFREG, S_IFCHR, S_IFBLK, S_IFIFO, S_IFSOCK, DEF_DIR_MODE, DEF_FILE_MODE}, flag = true)
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileTypeFlag {}

    public static final int S_IFMT =   0b1111000000000000;

    public static final int S_IFREG =  0b1000000000000000;
    public static final int S_IFSOCK = 0b1100000000000000;
    public static final int S_IFBLK =  0b0110000000000000;
    public static final int S_IFCHR =  0b0010000000000000;
    public static final int S_IFIFO =  0b0001000000000000;

    public static final int POSIX_FADV_SEQUENTIAL = 2;

    @IntDef(value = {POSIX_FADV_SEQUENTIAL})
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    public @interface fadvice {}

    @CheckResult
    @WorkerThread
    public abstract @Fd int creat(@NonNull CharSequence path, int mode) throws IOException;

    /**
     * Open the specified file. This method delegates to Linux {@code open} system call and has similar
     * behavior (see {@code man 2 open}) except the differences, described here.
     *
     * This method might take additional steps to support Java Thread interruption, but only if the
     * {@code flags} argument does not contain {@link NativeBits#O_NONBLOCK} or
     * {@link NativeBits#O_DIRECTORY}. If the calling thread gets interrupted before or during the
     * such invocation, the {@link InterruptedIOException} is thrown (without clearing the
     * thread interruption status)
     *
     * @param path absolute path to the file
     * @param flags one of {@link OpenFlag} constants bitwise-ored with other flags, accepted by {@code open} call
     * @param mode filesystem mode of created file (ignored, if the call does not create a file)
     *
     * @return new descriptor, referring to specified file
     *
     * @throws InterruptedIOException if the thread gets interrupted during a blocked open call
     * @throws IOException if another IO error occurs
     */
    @CheckResult
    @WorkerThread
    public abstract @Fd int open(@NonNull CharSequence path, @OpenFlag int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @Fd int openat(@DirFd int fd, @NonNull CharSequence name, int flags, int mode) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendir(@NonNull CharSequence path) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @DirFd int opendirat(@DirFd int fd, @NonNull CharSequence name) throws IOException;

    @CheckResult
    public abstract @InotifyFd int inotify_init() throws IOException;

    @NonNull
    @CheckResult
    public abstract Copy copy();

    @NonNull
    @CheckResult
    public abstract Directory list(@DirFd int fd);

    @NonNull
    @CheckResult
    public abstract Directory list(@DirFd int fd, int flags);

    @NonNull
    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor);

    @NonNull
    @CheckResult
    public abstract Inotify observe(@InotifyFd int inotifyDescriptor, @Nullable Looper looper);

    @NonNull
    @CheckResult
    public abstract MountInfo getMounts() throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @NonNull CharSequence readlinkat(@DirFd int fd, @NonNull CharSequence pathname) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract @NonNull CharSequence canonicalize(@DirFd int fd, @NonNull CharSequence pathname) throws IOException;

    @WorkerThread
    public abstract void fstatat(@DirFd int dir, @NonNull CharSequence pathname, @NonNull Stat stat, @StatAtFlags int flags) throws IOException;

    @WorkerThread
    public abstract void renameat(@DirFd int fd, @Nullable CharSequence name, @DirFd int fd2, @Nullable CharSequence name2) throws IOException;

    @WorkerThread
    public abstract void linkat(@DirFd int oldDirFd, @NonNull CharSequence oldName, @DirFd int newDirFd, @NonNull CharSequence newName, @LinkAtFlags int flags) throws IOException;

    @WorkerThread
    public abstract void unlinkat(@DirFd int target, @NonNull CharSequence name, @UnlinkAtFlags int flags) throws IOException;

    @WorkerThread
    public abstract void symlinkat(@NonNull CharSequence name, @DirFd int target, @NonNull CharSequence newpath) throws IOException;

    @WorkerThread
    public abstract void mknodat(@DirFd int target, @NonNull CharSequence name, @FileTypeFlag int mode, int device) throws IOException;

    @CheckResult
    @WorkerThread
    public abstract boolean mkdirat(@DirFd int target, @NonNull CharSequence name, int mode) throws IOException;

    @CheckResult
    public abstract int dup(int source) throws IOException;

    public abstract void ftruncate(@Fd int fd, long length) throws IOException;

    public abstract void fsync(int fd) throws IOException;

    public abstract void fstat(int dir, @NonNull Stat stat) throws IOException;

    public abstract void fchmod(@Fd int fd, short mode) throws IOException;

    public abstract void getrlimit(int type, @NonNull Limit stat) throws IOException;

    public abstract void setrlimit(int type, @NonNull Limit stat) throws IOException;

    public abstract void fallocate(int fd, int mode, long off, long count) throws IOException;

    public abstract void readahead(int fd, long off, int count) throws IOException;

    public abstract void fadvise(int fd, long off, long length, @fadvice int advice) throws IOException;

    public abstract boolean faccessat(@DirFd int fd, @NonNull CharSequence pathname, @AccessFlags int mode) throws IOException;

    public abstract void dup2(@Fd int source, int dest) throws IOException;

    public abstract void close(@Fd int fd) throws IOException;

    public abstract void dispose(int fd);

    public boolean isPrivileged() {
        return false;
    }

    private static volatile OS defaultOs;

    /**
     * @return the singleton instance
     *
     * @throws IOException if loading the native components fails (usually because of corrupt/missing native libraries)
     */
    public static OS getInstance() throws IOException {
        if (defaultOs == null) {
            synchronized (OS.class) {
                if (defaultOs == null) {
                    defaultOs = Android.getInstance();
                }
            }
        }

        return defaultOs;
    }
}
