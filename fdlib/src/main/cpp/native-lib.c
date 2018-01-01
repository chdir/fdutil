#include "common.h"

#include "linux_syscall_support.h"
#include "moar_syscalls.h"

#include <sys/inotify.h>
#include <sys/stat.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_FATAL, "fdutil", "GetEnv failed; halting");

        abort();
    }

    illegalStateException = saveClassRef("java/lang/IllegalStateException", env);
    if (illegalStateException == NULL) {
        (*env)->FatalError(env, "IllegalStateException can not be loaded; halting");
    }

    oomError = saveClassRef("java/lang/OutOfMemoryError", env);
    if (oomError == NULL) {
        return -1;
    }

    ioException = saveClassRef("java/io/IOException", env);
    if (ioException == NULL) {
        return -1;
    }

    jclass versionClass = (*env)->FindClass(env, "android/os/Build$VERSION");

    if (versionClass == NULL)
        return -1;

    jfieldID sdkIntFieldID = safeGetStaticField(versionClass, "SDK_INT", "I", env);

    if (sdkIntFieldID == NULL) {
        return -1;
    }

    API_VERSION = (*env)->GetStaticIntField(env, versionClass, sdkIntFieldID);

    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        return -1;
    }

    jclass arenaClass = saveClassRef("net/sf/xfd/Arena", env);
    if (arenaClass == NULL) {
        return -1;
    }

    arenaConstructor = (*env)->GetMethodID(env, arenaClass, "<init>", "(JLjava/nio/ByteBuffer;Lnet/sf/xfd/GuardFactory;)V");
    if (arenaConstructor == NULL) {
        return -1;
    }

    byteArrayClass = saveClassRef("[B", env);
    if (byteArrayClass == NULL) {
        return -1;
    }

    statContainer = saveClassRef("net/sf/xfd/Stat", env);
    if (statContainer == NULL) {
        return -1;
    }

    statContainerInit = (*env)->GetMethodID(env, statContainer, "init", "(JJJIIII)V");
    if (statContainerInit == NULL) {
        return -1;
    }

    limitContainer = saveClassRef("net/sf/xfd/Limit", env);
    if (limitContainer == NULL) {
        return -1;
    }

    limitContainerInit = (*env)->GetMethodID(env, limitContainer, "init", "(JJ)V");
    if (limitContainerInit == NULL) {
        return -1;
    }

    errnoException = saveClassRef("net/sf/xfd/ErrnoException", env);
    if (errnoException == NULL) {
        return -1;
    }

    errnoExceptionConstructor = (*env)->GetMethodID(env, errnoException, "<init>", "(ILjava/lang/String;)V");
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
    jfieldID nonblock = (*env)->GetStaticFieldID(env, type, "O_NONBLOCK", "I");
    if (nonblock == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
    } else {
        (*env)->SetStaticIntField(env, type, nonblock, O_NONBLOCK);
    }

    jfieldID noctty = (*env)->GetStaticFieldID(env, type, "O_NOCTTY", "I");
    if (noctty == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
    } else {
        (*env)->SetStaticIntField(env, type, noctty, O_NOCTTY);
    }

    jfieldID nofollow = (*env)->GetStaticFieldID(env, type, "O_NOFOLLOW", "I");
    if (nofollow == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
    } else {
        (*env)->SetStaticIntField(env, type, nofollow, O_NOFOLLOW);
    }

    jfieldID rlimit_nofile = (*env)->GetStaticFieldID(env, type, "RLIMIT_NOFILE", "I");
    if (rlimit_nofile == NULL || (*env)-> ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
    } else {
        (*env)->SetStaticIntField(env, type, rlimit_nofile, RLIMIT_NOFILE);
    }
}

JNIEXPORT jint JNICALL PKG_SYM(nativeOpenAt)(JNIEnv *env, jclass type, jlong token, jworkaroundstr path, jint l, jint fd, jint flags, jint mode) {
    i10n_ptr handler = (i10n_ptr) token;

    int newFd;

    const char *utf8Path = getUtf8(env, l, path);

    if (utf8Path == NULL) {
        oomThrow(env, "file name buffer");
        return -1;
    }

    if (*handler) {
        return -1;
    }

attempt_open:
    newFd = sys_openat(fd, utf8Path, flags, mode);

    int eintr;

    if (newFd < 0) {
        eintr = errno == EINTR;

        if (!eintr) {
            handleError(env);
            goto cleanup;
        }
    } else {
        eintr = 0;
    }

    if (!*handler && eintr) {
        // we got hit by some unrelated signal, keep trying
        goto attempt_open;
    }

cleanup:
    freeUtf8(env, l, path, utf8Path);

    return newFd;
}

JNIEXPORT jobject JNICALL Java_net_sf_xfd_Arena_allocate(JNIEnv *env, jclass type, jint size, jint alignment, jobject guards) {
    void* bufferAddress;

    size_t s = (size_t) size;
    size_t a = (size_t) alignment;

    switch (alignment) {
        case -1:
            a = pageSize;
        default:
            if (alignment < 0 && alignment != -1 && abs(alignment) > pageSize) {
                a = (size_t) abs(alignment);
            }

            bufferAddress = memalign(a, s);

            if (bufferAddress != NULL) {
                break;
            }
        case 0:
            bufferAddress = malloc(s);
    }

    if (bufferAddress == NULL) {
        oomThrow(env, "area");
        return NULL;
    }

    jobject buffer = (*env)->NewDirectByteBuffer(env, bufferAddress, size);

    if ((*env)->IsSameObject(env, buffer, NULL)) {
        return NULL;
    }

    return (*env)->NewObject(env, type, arenaConstructor, (jlong) bufferAddress, buffer, guards);
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

JNIEXPORT void JNICALL PKG_SYM(fchmod)(JNIEnv *env, jobject instance, jint fd, jshort mode) {
    if (TEMP_FAILURE_RETRY(fchmod(fd, (mode_t) mode))) {
        handleError(env);
    }
}

JNIEXPORT void JNICALL PKG_SYM(getrlimit)(JNIEnv *env, jobject instance, jint type, jobject limitStruct) {
    struct rlimit l;

    if (getrlimit(type, &l)) {
        handleError(env);
        return;
    }

    (*env)->CallNonvirtualVoidMethod(env, limitStruct, limitContainer, limitContainerInit, (jlong) l.rlim_cur, (jlong) l.rlim_max);
}

JNIEXPORT void JNICALL PKG_SYM(nativeSetrlimit)(JNIEnv *env, jclass clazz, jlong cur, jlong max, jint type) {
    struct kernel_rlimit l;

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

JNIEXPORT void JNICALL PKG_SYM(free)(JNIEnv *env, jclass unused, jlong pointer) {
    free((void*) pointer);
}

#define CHUNK_SIZE (64 * 1024)

_Static_assert (CHUNK_SIZE < SIZE_MAX, "size_t has unexpected size");
_Static_assert (CHUNK_SIZE < SSIZE_MAX, "ssize_t has unexpected size");

static jlong dumbCopy(i10n_ptr handler, char* buf, int64_t* sizeRef, jint fd1, jint fd2) {
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

        if (*handler) {
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

            if (*handler) {
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
            if (errno == ESPIPE) {
                errno = EINTR;
            }

            return -2;
        }
    }

    *sizeRef -= totalWritten;

    return -1;
}

JNIEXPORT jlong JNICALL PKG_SYM(doSendfile)(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong total, jint fd1, jint fd2) {
    i10n_ptr handler = (i10n_ptr) (intptr_t) ptr;

    int64_t totalBytes,remaining;

    totalBytes = remaining = total;

    if (*handler) {
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

        if (*handler) {
            goto interrupted;
        }
    }
    while (remaining > 0);

    return totalBytes - remaining;

enough:
{
    // make sure to write out any remaining data
    char *b = (char*) buffer;

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
    return totalBytes - remaining;
}

JNIEXPORT jlong JNICALL PKG_SYM(doSplice)(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong total, jint fd1, jint fd2) {
    i10n_ptr handler = (i10n_ptr) (intptr_t) ptr;

    int64_t totalBytes,remaining;

    totalBytes = remaining = total;

    if (*handler) {
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

        if (*handler) {
            goto interrupted;
        }
    }
    while (remaining > 0);

    return totalBytes - remaining;

enough:
{
    // make sure to write out any remaining data
    char *b = (char*) buffer;

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
    return totalBytes - remaining;
}

JNIEXPORT jlong JNICALL PKG_SYM(doDumbCopy)(JNIEnv *env, jclass type, jlong buffer, jlong ptr, jlong size, jint fd1, jint fd2) {
    i10n_ptr handler = (i10n_ptr) (intptr_t) ptr;

    jlong initial = size;

    if (!*handler) {
        char* b = (char*) buffer;

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

    return initial - size;
}

const size_t RLINK_INITIAL_BUFFER_SIZE = 1000;

const size_t RLINK_MAX_BUFFER_SIZE = 16 * 1024 * 1024;

static char *resolve_with_buffer_size(int base, const char *linkpath, size_t bufferSize, size_t *finalStringSize);

static char* resolve_with_buffer(int base, const char *linkpath, void* buffer, size_t bufferSize, size_t *finalStringSize) {
    LOG("calling readlink() for %s with buffer size %u", linkpath, bufferSize);

    int resolvedNameLength = TEMP_FAILURE_RETRY(sys_readlinkat(base, linkpath, (char*) buffer, bufferSize));
    int err = errno;

    if (resolvedNameLength != -1) {
        if (resolvedNameLength < bufferSize) {
            *finalStringSize = (size_t) resolvedNameLength;
            return (char*) buffer;
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
    struct kernel_stat64 dirStat;

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

        resolved = (char*) newBuf;
    }

    resolved[*stringSize] = '/';
    resolved[*stringSize + 1] = '\0';

    *stringSize = totalSize;

    strncat(resolved, linkpath, nameSize);

    return resolved;
}

static const char *resolve_link(int base, const char* linkpath, size_t *stringSize) {
    struct kernel_stat64 dirStat;

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

JNIEXPORT jworkaroundstr JNICALL PKG_SYM(nativeReadlink)(JNIEnv *env, jclass type, jworkaroundstr pathname, jint l, jint fd) {
    jworkaroundstr result = NULL;

    const char* utfName = getUtf8(env, l, pathname);

    size_t stringSize;

    const char* resolved = resolve_link(fd, utfName, &stringSize);

    if (resolved == utfName) {
        result = pathname;
        goto cleanup;
    }

    if (resolved == NULL) {
        handleError(env);
        goto cleanup;
    }

    result = toString(env, (char*) resolved, RLINK_MAX_BUFFER_SIZE, stringSize);

cleanup:
    freeUtf8(env, l, pathname, utfName);

    return result;
}

JNIEXPORT void JNICALL PKG_SYM(nativeSymlinkAt)(JNIEnv *env, jclass type, jworkaroundstr name_, jworkaroundstr newpath_, jint l1, jint l2, jint target) {
    const char *name = getUtf8(env, l1, name_);
    if (name == NULL) {
        return;
    }

    const char *newpath = getUtf8(env, l2, newpath_);
    if (newpath == NULL) {
        goto cleanup;
    }

    if (TEMP_FAILURE_RETRY(sys_symlinkat(name, target, newpath))) {
        handleError(env);
    }

    freeUtf8(env, l2, newpath_, newpath);
cleanup:
    freeUtf8(env, l1, name_, name);
}

JNIEXPORT void JNICALL PKG_SYM(nativeUnlinkAt)(JNIEnv *env, jclass type, jworkaroundstr name, jint l, jint target, jint flags) {
    const char* name_ = getUtf8(env, l, name);
    if (name_ == NULL) {
        return;
    }

    if (TEMP_FAILURE_RETRY(sys_unlinkat(target, name_, flags))) {
        handleError(env);
    }

    freeUtf8(env, l, name, name_);
}

JNIEXPORT void JNICALL PKG_SYM(nativeMknodAt)(JNIEnv *env, jclass type, jworkaroundstr name, jint l, jint target, jint mode, jint device) {
    const char* name_ = getUtf8(env, l, name);
    if (name_ == NULL) {
        return;
    }

    if (TEMP_FAILURE_RETRY(sys_mknodat(target, name_, (mode_t) mode, (dev_t) device))) {
        handleError(env);
    }

    freeUtf8(env, l, name, name_);
}

JNIEXPORT jboolean JNICALL PKG_SYM(nativeMkdirAt)(JNIEnv *env, jclass type, jworkaroundstr name, jint l, jint target, jint mode) {
    jboolean retVal = JNI_TRUE;

    const char* name_ = getUtf8(env, l, name);
    if (name_ == NULL) {
        goto exit;
    }

    if (TEMP_FAILURE_RETRY(sys_mkdirat(target, name_, (mode_t) mode))) {
        if (errno == EEXIST) {
            retVal = JNI_FALSE;
        } else {
            handleError(env);
        }
    }

    freeUtf8(env, l, name, name_);

exit:
    return retVal;
}

JNIEXPORT void JNICALL PKG_SYM(ftruncate)(JNIEnv *env, jobject obj, jlong newsize, jint fd) {
    if (TEMP_FAILURE_RETRY(sys_ftruncate(fd, (off_t) newsize))) {
        handleError(env);
    }
}

JNIEXPORT void JNICALL PKG_SYM(fstat)(JNIEnv *env, jobject obj, jint fd, jobject statStruct) {
    struct kernel_stat64 dirStat;

    if (TEMP_FAILURE_RETRY(sys_fstat64(fd, &dirStat)) != 0) {
        handleError(env);
        return;
    }

    jint fileTypeOrdinal = 0;

    switch (dirStat.st_mode & S_IFMT) {
        case S_IFREG:
            fileTypeOrdinal = 5;
            break;
        case S_IFDIR:
            fileTypeOrdinal = 6;
            break;
        case S_IFLNK:
            fileTypeOrdinal = 4;
            break;
        case S_IFBLK:
            fileTypeOrdinal = 0;
            break;
        case S_IFCHR:
            fileTypeOrdinal = 1;
            break;
        case S_IFIFO:
            fileTypeOrdinal = 2;
            break;
        case S_IFSOCK:
            fileTypeOrdinal = 3;
            break;
        default:
            fileTypeOrdinal = 7;
            break;
    }

    (*env)->CallNonvirtualVoidMethod(env, statStruct, statContainer, statContainerInit,
        (uint64_t) dirStat.st_dev,
        (uint64_t) dirStat.st_ino,
        (jlong) dirStat.st_size,
        (uint32_t) dirStat.st_rdev,
        (jint) dirStat.st_blksize,
        fileTypeOrdinal,
        (jint) (dirStat.st_mode & ~S_IFMT));
}

JNIEXPORT void JNICALL PKG_SYM(nativeRenameAt)(JNIEnv *env, jclass type, jworkaroundstr o, jworkaroundstr o1, jint l1, jint l2, jint fd, jint fd2) {
    const char* name_ = getUtf8(env, l1, o);
    if (name_ == NULL) {
        return;
    }

    const char* name1_ = getUtf8(env, l2, o1);
    if (name1_ == NULL) {
        goto cleanup;
    }

    if (sys_renameat(fd, name_, fd2, name1_) == -1) {
        handleError(env);
    }

    freeUtf8(env, l2, o1, name1_);

cleanup:
    freeUtf8(env, l1, o, name_);
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

JNIEXPORT jboolean JNICALL PKG_SYM(nativeFaccessAt)(JNIEnv *env, jclass type, jworkaroundstr pathname_, jint l, jint fd, jint mode) {
    const char *utf8Path = getUtf8(env, l, pathname_);
    if (utf8Path == NULL) {
        return 0;
    }

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

    freeUtf8(env, l, pathname_, utf8Path);

    return (jboolean) (ret ? JNI_FALSE : JNI_TRUE);
}

JNIEXPORT void JNICALL PKG_SYM(nativeFsync)(JNIEnv *env, jclass type, jlong nativePtr, jint fd) {
    i10n_ptr handler = (i10n_ptr) (intptr_t) nativePtr;

    if (*handler) {
        return;
    }

    int eintr = 0;

    do {
        if (fsync(fd)) {
            eintr = errno == EINTR;

            if (!eintr) {
                handleError(env);
                return;
            }
        }

        if (*handler) {
            return;
        }
    } while (eintr);
}

JNIEXPORT void JNICALL PKG_SYM(nativeLinkAt)(JNIEnv *env, jclass type, jworkaroundstr o, jworkaroundstr o1, jint l1, jint l2, jint oldDirFd, jint newDirFd, jint flags) {
    const char* name_ = getUtf8(env, l1, o);
    if (name_ == NULL) {
        return;
    }

    const char* name1_ = getUtf8(env, l2, o1);
    if (name1_ == NULL) {
        goto cleanup;
    }

    if (sys_linkat(oldDirFd, name_, newDirFd, name1_, flags)) {
        handleError(env);
    }

    freeUtf8(env, l2, o1, name1_);

cleanup:
    freeUtf8(env, l1, o, name_);
}

JNIEXPORT void JNICALL PKG_SYM(nativeFstatAt)(JNIEnv *env, jclass type, jworkaroundstr pathname, jobject statStruct, jint l, jint dir, jint flags) {
    const char *utf8Path = getUtf8(env, l, pathname);
    if (utf8Path == NULL) {
        return;
    }

    struct kernel_stat64 fdStat;

    jint fileTypeOrdinal = 0;

    if (TEMP_FAILURE_RETRY(sys_fstatat64_fixed(dir, utf8Path, &fdStat, flags))) {
        handleError(env);
        goto cleanup;
    }

    switch (fdStat.st_mode & S_IFMT) {
        case S_IFREG:
            fileTypeOrdinal = 5;
            break;
        case S_IFDIR:
            fileTypeOrdinal = 6;
            break;
        case S_IFLNK:
            fileTypeOrdinal = 4;
            break;
        case S_IFBLK:
            fileTypeOrdinal = 0;
            break;
        case S_IFCHR:
            fileTypeOrdinal = 1;
            break;
        case S_IFIFO:
            fileTypeOrdinal = 2;
            break;
        case S_IFSOCK:
            fileTypeOrdinal = 3;
            break;
        default:
            fileTypeOrdinal = 7;
            break;
    }

    (*env)->CallNonvirtualVoidMethod(env, statStruct, statContainer, statContainerInit,
                                     (uint64_t) fdStat.st_dev,
                                     (uint64_t) fdStat.st_ino,
                                     (jlong) fdStat.st_size,
                                     (uint32_t) fdStat.st_rdev,
                                     (jint) fdStat.st_blksize,
                                     fileTypeOrdinal,
                                     (jint) (fdStat.st_mode & ~S_IFMT));

    cleanup:
    freeUtf8(env, l, pathname, utf8Path);
}