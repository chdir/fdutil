#include "common.h"

#include "linux_syscall_support.h"
#include "moar_syscalls.h"

#include <sys/inotify.h>
#include <sys/stat.h>
#include <limits>
#include <stdio.h>
#include <stdlib.h>

inline static jint coreio_openat(JNIEnv *env, jint fd, jworkaroundstr name, jint flags, jint mode) {
    jboolean isArray = env -> IsInstanceOf(name, byteArrayClass);

    const char *utf8Path = getUtf8(env, isArray, name);

    if (utf8Path == NULL) {
        env->ThrowNew(oomError, "file name buffer");
        return -1;
    }

    int newFd = TEMP_FAILURE_RETRY(sys_openat(fd, utf8Path, flags, mode));

    if (newFd < 0) {
        handleError(env);
    }

    freeUtf8(env, isArray, name, utf8Path);

    return newFd;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass versionClass = env->FindClass("android/os/Build$VERSION");

    if (versionClass == NULL)
        return -1;

    jfieldID sdkIntFieldID = env->GetStaticFieldID(versionClass, "SDK_INT", "I");

    if (sdkIntFieldID == NULL) {
        return -1;
    }

    API_VERSION = env->GetStaticIntField(versionClass, sdkIntFieldID);

    if (env->ExceptionCheck() == JNI_TRUE) {
        return -1;
    }

    byteArrayClass = saveClassRef("[B", env);
    if (byteArrayClass == NULL) {
        return -1;
    }

    ioException = saveClassRef("java/io/IOException", env);
    if (ioException == NULL) {
        return -1;
    }

    iIoException = saveClassRef("net/sf/xfd/InterruptedIOException", env);
    if (iIoException == NULL) {
        return -1;
    }

    iieConstructor = env->GetMethodID(iIoException, "<init>", "(JLjava/lang/String;)V");
    if (iieConstructor == NULL) {
        return -1;
    }

    oomError = saveClassRef("java/lang/OutOfMemoryError", env);
    if (oomError == NULL) {
        return -1;
    }

    illegalStateException = saveClassRef("java/lang/IllegalStateException", env);
    if (illegalStateException == NULL) {
        return -1;
    }

    statContainer = saveClassRef("net/sf/xfd/Stat", env);
    if (statContainer == NULL) {
        return -1;
    }

    statContainerInit = env->GetMethodID(statContainer, "init", "(JJJII)V");
    if (statContainerInit == NULL) {
        return -1;
    }

    limitContainer = saveClassRef("net/sf/xfd/Limit", env);
    if (limitContainer == NULL) {
        return -1;
    }

    limitContainerInit = env->GetMethodID(limitContainer, "init", "(JJ)V");
    if (limitContainerInit == NULL) {
        return -1;
    }

    limitContainerCur = env -> GetFieldID(limitContainer, "current", "J");
    if (limitContainerCur == NULL) {
        return -1;
    }

    limitContainerMax = env -> GetFieldID(limitContainer, "max", "J");
    if (limitContainerMax == NULL) {
        return -1;
    }

    errnoException = saveClassRef("net/sf/xfd/ErrnoException", env);
    if (errnoException == NULL) {
        return -1;
    }

    errnoExceptionConstructor = env->GetMethodID(errnoException, "<init>", "(ILjava/lang/String;)V");
    if (errnoExceptionConstructor == NULL) {
        return -1;
    }

    long systemPageSize = sysconf(_SC_PAGE_SIZE);

    if (systemPageSize > 0) {
        pageSize = (size_t) systemPageSize;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_net_sf_xfd_NativeBits_fixConstants(JNIEnv *env, jclass type) {
    jfieldID nonblock = env -> GetStaticFieldID(type, "O_NONBLOCK", "I");
    env -> SetStaticIntField(type, nonblock, O_NONBLOCK);

    jfieldID noctty = env -> GetStaticFieldID(type, "O_NOCTTY", "I");
    env -> SetStaticIntField(type, noctty, O_NOCTTY);

    jfieldID nofollow = env -> GetStaticFieldID(type, "O_NOFOLLOW", "I");
    env -> SetStaticIntField(type, nofollow, O_NOFOLLOW);

    jfieldID rlimit_nofile = env -> GetStaticFieldID(type, "RLIMIT_NOFILE", "I");
    env -> SetStaticIntField(type, rlimit_nofile, RLIMIT_NOFILE);
}

JNIEXPORT jint JNICALL PKG_SYM(nativeOpenAt)(JNIEnv *env, jclass type, jint fd, jobject path, jint flags, jint mode) {
    return coreio_openat(env, fd, path, flags | O_LARGEFILE, mode);
}

JNIEXPORT jint JNICALL PKG_SYM(nativeOpenAt2)(JNIEnv *env, jclass type, jlong token, jint fd, jworkaroundstr path, jint flags, jint mode) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(token);

    jboolean isArray = env -> IsInstanceOf(path, byteArrayClass);

    const char *utf8Path = getUtf8(env, isArray, path);

    if (utf8Path == NULL) {
        env->ThrowNew(oomError, "file name buffer");
        return -1;
    }

    if (handler -> interrupted.load(memory_order_relaxed)) {
        env -> ThrowNew(iIoException, "open");

        handler -> clear_flag();

        return -1;
    }

attempt_open:
    int newFd = sys_openat(fd, utf8Path, flags, mode);

    bool eintr;

    if (newFd < 0) {
        eintr = errno == EINTR;

        if (!eintr) {
            handleError(env);
            goto cleanup;
        }
    } else {
        eintr = false;
    }

    if (handler -> interrupted.load(memory_order_relaxed)) {
        if (newFd >= 0) {
            close(newFd);
        }

        env -> ThrowNew(iIoException, "open");

        handler -> clear_flag();
    } else if (eintr) {
        goto attempt_open;
    }

cleanup:
    freeUtf8(env, isArray, path, utf8Path);

    return newFd;
}

JNIEXPORT void JNICALL PKG_SYM(close)(JNIEnv *env, jclass type, jint fd) {
    if (close(fd) == -1) {
        handleError(env);
    }
}

JNIEXPORT void JNICALL PKG_SYM(dup2)(JNIEnv *env, jobject instance, jint source, jint dest) {
    if (TEMP_FAILURE_RETRY(dup2(source, dest)) == dest) {
        return;
    }

    handleError(env);
}

JNIEXPORT void JNICALL PKG_SYM(getrlimit)(JNIEnv *env, jobject instance, int type, jobject limitStruct) {
    rlimit l;

    if (getrlimit(type, &l)) {
        handleError(env);

        return;
    }

    env -> CallNonvirtualVoidMethod(limitStruct, limitContainer, limitContainerInit, (jlong) l.rlim_cur, (jlong) l.rlim_max);
}

JNIEXPORT void JNICALL PKG_SYM(nativeSetrlimit)(JNIEnv *env, jclass clazz, jlong cur, jlong max, jint type) {
    kernel_rlimit l;

    l.rlim_cur = (unsigned long) cur;
    l.rlim_max = (unsigned long) max;

    if (TEMP_FAILURE_RETRY(sys_setrlimit(type, &l))) {
        handleError(env);
        return;
    }
}

JNIEXPORT jint JNICALL PKG_SYM(inotify_1init)(JNIEnv *env, jobject instance) {
    int fd = inotify_init();

    if (fd == -1) {
        handleError(env);
        return -1;
    }

    if (fcntl(fd, F_SETFL, O_NONBLOCK) == -1) {
        handleError(env);
        return -1;
    }

    return fd;
}

JNIEXPORT void JNICALL Java_net_sf_xfd_BlockingGuards_free(JNIEnv *env, jclass cl, jlong pointer) {
    free(reinterpret_cast<void*>(pointer));
}

#define CHUNK_SIZE (64 * 1024)

static_assert (CHUNK_SIZE < SIZE_MAX, "size_t has unexpected size");
static_assert (CHUNK_SIZE < SSIZE_MAX, "ssize_t has unexpected size");

JNIEXPORT jlong JNICALL Java_net_sf_xfd_CopyImpl_nativeInit(JNIEnv *env, jclass type) {
    if (pageSize > 1024 * 1024 * 2) {
        pageSize = 1024 * 4;
    }

    void *bufferAddress = memalign(pageSize, CHUNK_SIZE);

    if (bufferAddress == NULL) {
        env -> ThrowNew(oomError, "copy buffer");
    }

    return reinterpret_cast<jlong>(bufferAddress);
}

static jlong dumbCopy(InterruptHandler* handler, char* buf, int64_t* sizeRef, jint fd1, jint fd2) {
    int64_t size = *sizeRef;

    int64_t totalWritten = 0;

    ssize_t remaining;
    do {
        int64_t to_read = size - totalWritten;

        if (to_read > CHUNK_SIZE) {
            to_read = CHUNK_SIZE;
        }

        remaining = read(fd1, buf, (size_t) to_read);

        switch (remaining) {
            default:
                break;
            case 0:
                return totalWritten;
            case -1:
                switch (errno) {
                    case EINTR:
                        remaining = 0;
                        break;
                    default:
                        return -2;
                }
        }

        if (handler -> interrupted.load(memory_order_relaxed)) {
            goto bail;
        }

        ssize_t written = remaining;

        while (remaining > 0) {
            ssize_t lastWritten = write(fd2, buf + written - remaining, (size_t) remaining);

            if (lastWritten == -1) {
                switch (errno) {
                    case EINTR:
                        lastWritten = 0;
                        break;
                    default:
                        return -2;
                }
            }

            remaining -= lastWritten;

            if (handler -> interrupted.load(memory_order_relaxed)) {
                totalWritten += (written - remaining);

                goto bail;
            }
        }

        totalWritten += written;
    }
    while (totalWritten < size);

    return totalWritten;

bail:
    if (remaining > 0) {
        // attempt to put already read bytes back
        if (sys_lseek(fd1, -remaining, SEEK_CUR) == -1) {
            errno = EINTR;
            return -2;
        }
    }

    *sizeRef -= totalWritten;

    return -1;
}

JNIEXPORT jlong JNICALL Java_net_sf_xfd_CopyImpl_doSendfile(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong total, jint fd1, jint fd2) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(ptr);

    int64_t totalBytes,remaining;

    totalBytes = remaining = total;

    if (handler -> interrupted.load(memory_order_relaxed)) {
        goto interrupted;
    }

    // attempt to do sendfile first
    do {
        size_t to_send = remaining > SSIZE_MAX ? SSIZE_MAX : (size_t) remaining;

        ssize_t sent = sys_sendfile(fd2, fd1, NULL, to_send);

        switch (sent) {
            default:
                break;
            case 0:
                goto enough;
            case -1:
                sent = 0;

                switch (errno) {
                    case EOPNOTSUPP:
                    case EINVAL:
                        goto enough;
                    case EINTR:
                        break;
                    default:
                        handleError(env);
                        return -1;
                }
        }

        remaining -= sent;

        if (handler -> interrupted.load(memory_order_relaxed)) {
            goto interrupted;
        }
    }
    while (remaining > 0);

    return totalBytes - remaining;

enough:
{
    // make sure to write out any remaining data
    char *b = reinterpret_cast<char *>(buffer);

    jlong res = dumbCopy(handler, b, &remaining, fd1, fd2);

    switch (res) {
        default:
            break;
        case -1:
            goto interrupted;
        case -2:
            handleError(env);
            return -1;
    }

    return res + (totalBytes - remaining);
}

interrupted:
    throwInterrupted(env, totalBytes - remaining, "sendfile");

    handler -> clear_flag();

    return -1;
}

JNIEXPORT jlong JNICALL Java_net_sf_xfd_CopyImpl_doSplice(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong total, jint fd1, jint fd2) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(ptr);

    int64_t totalBytes,remaining;

    totalBytes = remaining = total;

    if (handler -> interrupted.load(memory_order_relaxed)) {
        goto interrupted;
    }

    do {
        size_t to_splice = remaining > CHUNK_SIZE ? CHUNK_SIZE : (size_t) remaining;

        int spliced = sys_splice(fd1, NULL, fd2, NULL, to_splice, 0);

        switch (spliced) {
            default:
                break;
            case 0:
                return totalBytes - remaining;
            case -1:
                switch (errno) {
                    case EOPNOTSUPP:
                    case EINVAL:
                        goto enough;
                    case EINTR:
                        spliced = 0;
                        break;
                    default:
                        handleError(env);
                        return -1;
                }
        }

        remaining -= spliced;

        if (handler -> interrupted.load(memory_order_relaxed)) {
            goto interrupted;
        }
    }
    while (remaining > 0);

    return totalBytes - remaining;

enough:
{
    // make sure to write out any remaining data
    char *b = reinterpret_cast<char *>(buffer);

    int64_t res = dumbCopy(handler, b, &remaining, fd1, fd2);

    switch (res) {
        default:
            break;
        case -1:
            goto interrupted;
        case -2:
            handleError(env);
            return -1;
    }

    return res + (totalBytes - remaining);
}

interrupted:
    throwInterrupted(env, totalBytes - remaining, "splice");

    handler -> clear_flag();

    return -1;
}

JNIEXPORT jlong JNICALL Java_net_sf_xfd_CopyImpl_doDumbCopy(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong size, jint fd1, jint fd2) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(ptr);

    jlong initial = size;

    if (!handler -> interrupted.load(memory_order_relaxed)) {
        char* b = reinterpret_cast<char*>(buffer);

        int64_t res = dumbCopy(handler, b, &size, fd1, fd2);

        switch (res) {
            default:
                return res;
            case -2:
                handleError(env);
                return -1;
            case -1:
                break;
        }
    }

    throwInterrupted(env, initial - size, "copy");

    handler -> clear_flag();

    return -1;
}

const size_t RLINK_INITIAL_BUFFER_SIZE = 1000;

const size_t RLINK_MAX_BUFFER_SIZE = 16 * 1024 * 1024;

static char *resolve_with_buffer_size(int base, const char *linkpath, size_t bufferSize, size_t *finalStringSize);

static char* resolve_with_buffer(int base, const char *linkpath, void* buffer, size_t bufferSize, size_t *finalStringSize) {
    LOG("calling readlink() for %s with buffer size %u", linkpath, bufferSize);

    int resolvedNameLength = TEMP_FAILURE_RETRY(sys_readlinkat(base, linkpath, static_cast<char*>(buffer), bufferSize));
    int err = errno;

    if (resolvedNameLength != -1) {
        if (resolvedNameLength < bufferSize) {
            *finalStringSize = (size_t) resolvedNameLength;
            return static_cast<char*>(buffer);
        }

        if ((bufferSize = bufferSize * 2) < RLINK_MAX_BUFFER_SIZE) {
            LOG("The length is %d, trying to realloc", resolvedNameLength);

            void* newBuffer = realloc(buffer, bufferSize);
            if (newBuffer != NULL) {
                buffer = newBuffer;

                return resolve_with_buffer(base, linkpath, buffer, bufferSize, finalStringSize);
            } else {
                err = errno;
            }
        } else {
            err = ENAMETOOLONG;
        }
    }

    free(buffer);
    errno = err;
    return NULL;
}

static char *resolve_with_buffer_size(int base, const char *linkpath, size_t bufferSize, size_t *finalStringSize) {
    void* buffer = realloc(NULL, bufferSize);

    if (buffer == NULL) {
        return NULL;
    }

    return resolve_with_buffer(base, linkpath, buffer, bufferSize, finalStringSize);
}

static const char* resolve_contcat(int base, const char* linkpath, size_t *stringSize, size_t nameSize) {
    kernel_stat64 dirStat;

    char fdBuf[25];
    sprintf(fdBuf, "/proc/self/fd/%d", base);

    if (TEMP_FAILURE_RETRY(sys_lstat64(fdBuf, &dirStat))) {
        return NULL;
    }

    size_t initialBufferSize;

    if (dirStat.st_size > 0) {
        initialBufferSize = (size_t) dirStat.st_size + 1 + nameSize;
    } else {
        initialBufferSize = RLINK_INITIAL_BUFFER_SIZE;
    }

    char *resolved = resolve_with_buffer_size(-1, fdBuf, initialBufferSize, stringSize);
    if (resolved == NULL) {
        return NULL;
    }

    size_t totalSize = *stringSize + nameSize + 1;

    if (totalSize >= initialBufferSize) {
        void* newBuf = realloc(resolved, totalSize + 1);
        int err = errno;

        if (newBuf == NULL) {
            free(resolved);
            errno = err;

            return NULL;
        }

        resolved = static_cast<char*>(newBuf);
    }

    resolved[*stringSize] = '/';
    resolved[*stringSize + 1] = '\0';

    *stringSize = totalSize;

    strncat(resolved, linkpath, nameSize);

    return resolved;
}

static const char *resolve_link(int base, const char* linkpath, size_t *stringSize) {
    kernel_stat64 dirStat;

    if (TEMP_FAILURE_RETRY(sys_fstatat64_fixed(base, linkpath, &dirStat, AT_SYMLINK_NOFOLLOW)) < 0) {
        return NULL;
    }

    if (!S_ISLNK(dirStat.st_mode)) {
        size_t nameSize = strlen(linkpath);

        if (base < 0) {
            *stringSize = nameSize;

            return linkpath;
        }

        return resolve_contcat(base, linkpath, stringSize, nameSize);
    }

    size_t initialBufferSize;

    if (dirStat.st_size > 0) {
        initialBufferSize = (size_t) dirStat.st_size + 1;
    } else {
        initialBufferSize = RLINK_INITIAL_BUFFER_SIZE;
    }

    const char* resolved = resolve_with_buffer_size(base, linkpath, initialBufferSize, stringSize);

    if (resolved == NULL) {
        return NULL;
    }

    if (resolved[0] == '/') {
        return resolved;
    } else {
        const char* concatenated = resolve_contcat(base, resolved, stringSize, *stringSize);
        int err = errno;

        free((void *) resolved);

        if (concatenated == NULL) {
            errno = err;
            return NULL;
        }

        return concatenated;
    }
}

JNIEXPORT jworkaroundstr JNICALL PKG_SYM(nativeReadlink)(JNIEnv *env, jclass type, jint fd, jworkaroundstr pathname) {
    jboolean isArray = env -> IsInstanceOf(pathname, byteArrayClass);

    const char* utfName = getUtf8(env, isArray, pathname);

    size_t stringSize;

    const char* resolved = resolve_link(fd, utfName, &stringSize);

    if (resolved == utfName) {
        freeUtf8(env, isArray, pathname, utfName);
        return pathname;
    }

    if (resolved == NULL) {
        handleError(env);
        return NULL;
    }

    jworkaroundstr result = toString(env, const_cast<char*>(resolved), RLINK_MAX_BUFFER_SIZE, stringSize);

    free((void *) resolved);

    return result;
}

JNIEXPORT void JNICALL PKG_SYM(nativeSymlinkAt)(JNIEnv *env, jobject instance, jworkaroundstr name_, jint target, jworkaroundstr newpath_) {
    jboolean isArray1 = env -> IsInstanceOf(name_, byteArrayClass);

    const char *name = getUtf8(env, isArray1, name_);
    if (name == NULL) {
        return;
    }

    jboolean isArray2 = env -> IsInstanceOf(name_, byteArrayClass);

    const char *newpath = getUtf8(env, isArray2, newpath_);
    if (newpath == NULL) {
        return;
    }

    int rc = TEMP_FAILURE_RETRY(sys_symlinkat(name, target, newpath));
    int err = errno;

    freeUtf8(env, isArray1, name_, name);
    freeUtf8(env, isArray2, newpath_, newpath);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL PKG_SYM(nativeUnlinkAt)(JNIEnv *env, jobject instance, jint target, jworkaroundstr name, jint flags) {
    jboolean isArray = env -> IsInstanceOf(name, byteArrayClass);

    const char* name_ = getUtf8(env, isArray, name);
    if (name_ == NULL) {
        return;
    }

    int rc = TEMP_FAILURE_RETRY(sys_unlinkat(target, name_, flags));
    int err = errno;

    freeUtf8(env, isArray, name, name_);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL PKG_SYM(nativeMknodAt)(JNIEnv *env, jclass type, jint target, jworkaroundstr name, jint mode, jint device) {
    jboolean isArray = env -> IsInstanceOf(name, byteArrayClass);

    const char* name_ = getUtf8(env, isArray, name);

    if (name_ == NULL) {
        return;
    }

    int rc = TEMP_FAILURE_RETRY(sys_mknodat(target, name_, static_cast<mode_t>(mode), static_cast<dev_t>(device)));
    int err = errno;

    freeUtf8(env, isArray, name, name_);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL PKG_SYM(nativeMkdirAt)(JNIEnv *env, jclass type, jint target, jworkaroundstr name, jint mode) {
    jboolean isArray = env -> IsInstanceOf(name, byteArrayClass);

    const char* name_ = getUtf8(env, isArray, name);

    int rc = TEMP_FAILURE_RETRY(sys_mkdirat(target, name_, static_cast<mode_t>(mode)));
    int err = errno;

    freeUtf8(env, isArray, name, name_);

    if (rc) {
        handleError(env, err);
    }
}


JNIEXPORT void JNICALL PKG_SYM(fstat)(JNIEnv *env, jobject self, jint fd, jobject statStruct) {
    kernel_stat64 dirStat;

    if (TEMP_FAILURE_RETRY(sys_fstat64(fd, &dirStat)) != 0) {
        handleError(env);
        return;
    }

    jint fileTypeOrdinal = 0;

    if (S_ISBLK(dirStat.st_mode)) {
        fileTypeOrdinal = 0;
    } else if (S_ISCHR(dirStat.st_mode)) {
        fileTypeOrdinal = 1;
    } else if (S_ISFIFO(dirStat.st_mode)) {
        fileTypeOrdinal = 2;
    } else if (S_ISSOCK(dirStat.st_mode)) {
        fileTypeOrdinal = 3;
    } else if (S_ISLNK(dirStat.st_mode)) {
        fileTypeOrdinal = 4;
    } else if (S_ISREG(dirStat.st_mode)) {
        fileTypeOrdinal = 5;
    } else if (S_ISDIR(dirStat.st_mode)) {
        fileTypeOrdinal = 6;
    } else {
        fileTypeOrdinal = 7;
    }

    env -> CallNonvirtualVoidMethod(statStruct, statContainer, statContainerInit,
                            dirStat.st_dev, dirStat.st_ino, dirStat.st_size, dirStat.st_blksize, fileTypeOrdinal);
}

JNIEXPORT void JNICALL PKG_SYM(nativeRenameAt)(JNIEnv *env, jclass type, jint fd, jworkaroundstr o, jint fd2, jworkaroundstr o1) {
    jboolean isArray1 = env -> IsInstanceOf(o, byteArrayClass);

    const char* name_ = getUtf8(env, isArray1, o);
    if (name_ == NULL) {
        return;
    }

    jboolean isArray2 = env -> IsInstanceOf(o1, byteArrayClass);

    const char* name1_ = getUtf8(env, isArray2, o1);

    if (name1_ != NULL) {
        if (sys_renameat(fd, name_, fd2, name1_) == -1) {
            handleError(env);
        }

        freeUtf8(env, isArray2, o1, name1_);
    }

    freeUtf8(env, isArray1, o, name_);
}

JNIEXPORT void JNICALL PKG_SYM(readahead)(JNIEnv *env, jclass type, jint fd, jlong off, jint len) {
    if (TEMP_FAILURE_RETRY(sys_readahead(fd, off, len))) {
        if (errno == EOPNOTSUPP || errno == ENOTSUP) {
            LOG("readahead not supported by target filesystem");
            return;
        }

        handleError(env);
    }
}

JNIEXPORT void JNICALL PKG_SYM(fallocate)(JNIEnv *env, jclass type, jint fd, jint mode, jlong off, jlong len) {
    if (TEMP_FAILURE_RETRY(sys_fallocate(fd, mode, off, len))) {
        if (errno == EOPNOTSUPP || errno == ENOTSUP) {
            LOG("fallocate not supported by target filesystem");
            return;
        }

        handleError(env);
    }
}

JNIEXPORT void JNICALL PKG_SYM(fadvise)(JNIEnv *env, jclass type, jint fd, jlong off, jlong len, jint advice) {
    if (TEMP_FAILURE_RETRY(sys_fadvise(fd, off, len, advice))) {
        if (errno == EOPNOTSUPP || errno == ENOTSUP) {
            LOG("fadvise not supported by target filesystem");
            return;
        }

        handleError(env);
    }
}

JNIEXPORT jint JNICALL PKG_SYM(dup)(JNIEnv *env, jobject instance, jint source) {
    int result = dup(source);

    if (result == -1) {
        handleError(env);
    }

    return result;
}

JNIEXPORT jboolean JNICALL PKG_SYM(nativeFaccessAt)(JNIEnv *env, jclass type, jint fd, jworkaroundstr pathname_, jint mode) {
    jboolean isArray = env -> IsInstanceOf(pathname_, byteArrayClass);

    const char *utf8Path = getUtf8(env, isArray, pathname_);

    int ret = TEMP_FAILURE_RETRY(sys_faccessat(fd, utf8Path, mode));
    if (ret) {
        switch (errno) {
            case EACCES:
            case ELOOP:
            case ENOENT:
            case ENOTDIR:
                break;
            default:
                handleError(env);
        }
    }

    freeUtf8(env, isArray, pathname_, utf8Path);

    return static_cast<jboolean>(ret ? JNI_FALSE : JNI_TRUE);
}

JNIEXPORT jint JNICALL PKG_SYM(nativeCreat)(JNIEnv *env, jclass type, jobject pathname, jint mode) {
    return coreio_openat(env, -1, pathname, O_CREAT | O_RDWR | O_TRUNC, mode);
}

JNIEXPORT void JNICALL PKG_SYM(nativeFsync)(JNIEnv *env, jclass type, jlong nativePtr, jint fd) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(nativePtr);

    if (handler -> interrupted.load(memory_order_relaxed)) {
        env -> ThrowNew(iIoException, "fsync");

        handler -> clear_flag();

        return;
    }

    bool eintr = false;

    do {
        if (fsync(fd)) {
            eintr = errno == EINTR;

            if (!eintr) {
                handleError(env);
                return;
            }
        }

        if (handler -> interrupted.load(memory_order_relaxed)) {
            env -> ThrowNew(iIoException, "fsync");

            handler -> clear_flag();

            return;
        }
    } while (eintr);
}

JNIEXPORT void JNICALL PKG_SYM(nativeLinkAt)(JNIEnv *env, jclass type, jint oldDirFd, jworkaroundstr o, jint newDirFd, jworkaroundstr o1, jint flags) {
    jboolean isArray1 = env -> IsInstanceOf(o, byteArrayClass);

    const char* name_ = getUtf8(env, isArray1, o);
    if (name_ == NULL) {
        return;
    }

    jboolean isArray2 = env -> IsInstanceOf(o1, byteArrayClass);

    const char* name1_ = getUtf8(env, isArray2, o1);

    if (name1_ != NULL) {
        if (sys_linkat(oldDirFd, name_, newDirFd, name1_, flags)) {
            handleError(env);
        }

        freeUtf8(env, isArray2, o1, name1_);
    }

    freeUtf8(env, isArray1, o, name_);
}

JNIEXPORT void JNICALL PKG_SYM(nativeFstatAt)(JNIEnv *env, jclass type, jint dir, jworkaroundstr pathname, jobject statStruct, jint flags) {
    jboolean isArray = env -> IsInstanceOf(pathname, byteArrayClass);

    const char *utf8Path = getUtf8(env, isArray, pathname);
    if (utf8Path == NULL) {
        return;
    }

    kernel_stat64 fdStat;

    jint fileTypeOrdinal = 0;

    if (TEMP_FAILURE_RETRY(sys_fstatat64_fixed(dir, utf8Path, &fdStat, flags))) {
        handleError(env);
        goto cleanup;
    }

    if (S_ISBLK(fdStat.st_mode)) {
        fileTypeOrdinal = 0;
    } else if (S_ISCHR(fdStat.st_mode)) {
        fileTypeOrdinal = 1;
    } else if (S_ISFIFO(fdStat.st_mode)) {
        fileTypeOrdinal = 2;
    } else if (S_ISSOCK(fdStat.st_mode)) {
        fileTypeOrdinal = 3;
    } else if (S_ISLNK(fdStat.st_mode)) {
        fileTypeOrdinal = 4;
    } else if (S_ISREG(fdStat.st_mode)) {
        fileTypeOrdinal = 5;
    } else if (S_ISDIR(fdStat.st_mode)) {
        fileTypeOrdinal = 6;
    } else {
        fileTypeOrdinal = 7;
    }

    env -> CallNonvirtualVoidMethod(statStruct, statContainer, statContainerInit,
                                    fdStat.st_dev, fdStat.st_ino, fdStat.st_size, fdStat.st_blksize, fileTypeOrdinal);

    cleanup:
    freeUtf8(env, isArray, pathname, utf8Path);
}

}