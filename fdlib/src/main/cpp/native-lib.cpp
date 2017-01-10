#include "common.h"

#include "linux_syscall_support.h"
#include "moar_syscalls.h"

#include <sys/inotify.h>
#include <sys/stat.h>
#include <stdio.h>


inline static jclass saveClassRef(const char* name, JNIEnv *env) {
    jclass found = env -> FindClass(name);

    if (found == NULL) {
        return NULL;
    }

    return reinterpret_cast<jclass>(env->NewGlobalRef(found));
}

inline static jint coreio_openat(JNIEnv *env, jint fd, jworkaroundstr name, jint flags, jint mode) {
    const char *utf8Path = getUtf8(env, name);

    if (utf8Path == NULL) {
        env->ThrowNew(oomError, "file name buffer");
        return -1;
    }

    int newFd = TEMP_FAILURE_RETRY(sys_openat(fd, utf8Path, flags, mode));

    freeUtf8(env, name, utf8Path);

    if (newFd < 0) {
        handleError(env);
    }

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

    ioException = saveClassRef("java/io/IOException", env);
    if (ioException == NULL) {
        return -1;
    }

    oomError = saveClassRef("java/lang/OutOfMemoryError", env);
    if (oomError == NULL) {
        return -1;
    }

    statContainer = saveClassRef("net/sf/fdlib/Stat", env);
    if (statContainer == NULL) {
        return -1;
    }

    statContainerConstructor = env->GetMethodID(statContainer, "<init>", "(JJJI)V");
    if (statContainerConstructor == NULL) {
        return -1;
    }

    errnoException = saveClassRef("net/sf/fdlib/ErrnoException", env);
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

JNIEXPORT jint JNICALL Java_net_sf_fdlib_Android_nativeOpenDirAt(JNIEnv *env, jclass type, jint fd, jobject name, jint flags, jint mode) {
    return coreio_openat(env, fd, name, flags | O_DIRECTORY, mode);
}

JNIEXPORT jint JNICALL Java_net_sf_fdlib_Android_nativeOpenAt(JNIEnv *env, jclass type, jint fd, jobject path, jint flags, jint mode) {
    return coreio_openat(env, fd, path, flags | O_LARGEFILE, mode);
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_nativeClose(JNIEnv *env, jclass type, jint fd) {
    if (close(fd) == -1) {
        handleError(env);
    }
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_dup2(JNIEnv *env, jobject instance, jint source, jint dest) {
    if (sys_dup2(source, dest) == dest) {
        return;
    }

    handleError(env);
}

JNIEXPORT jint JNICALL Java_net_sf_fdlib_Android_inotify_1init(JNIEnv *env, jobject instance) {
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

JNIEXPORT void JNICALL Java_net_sf_fdlib_BlockingGuards_free(JNIEnv *env, jclass cl, jlong pointer) {
    free(reinterpret_cast<void*>(pointer));
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

JNIEXPORT jworkaroundstr JNICALL Java_net_sf_fdlib_Android_nativeReadlink(JNIEnv *env, jclass type, jint fd, jworkaroundstr pathname) {
    const char* utfName = getUtf8(env, pathname);

    size_t stringSize;

    const char* resolved = resolve_link(fd, utfName, &stringSize);

    if (resolved == utfName) {
        freeUtf8(env, pathname, utfName);
        return pathname;
    }

    if (stringSize < 0 || resolved == NULL) {
        handleError(env);
        return NULL;
    }

    jworkaroundstr result = toString(env, const_cast<char*>(resolved), RLINK_MAX_BUFFER_SIZE, stringSize);

    free((void *) resolved);

    return result;
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_nativeSymlinkAt(JNIEnv *env, jobject instance, jworkaroundstr name_, jint target, jworkaroundstr newpath_) {
    const char *name = getUtf8(env, name_);
    if (name == NULL) {
        return;
    }
    const char *newpath = getUtf8(env, newpath_);
    if (newpath == NULL) {
        return;
    }

    int rc = sys_symlinkat(name, target, newpath);
    int err = errno;

    freeUtf8(env, name_, name);
    freeUtf8(env, newpath_, newpath);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_nativeUnlinkAt(JNIEnv *env, jobject instance, jint target, jworkaroundstr name, jint flags) {
    const char* name_ = getUtf8(env, name);
    if (name_ == NULL) {
        return;
    }

    int rc = sys_unlinkat(target, name_, flags);
    int err = errno;

    freeUtf8(env, name, name_);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_nativeMknodAt(JNIEnv *env, jclass type, jint target, jworkaroundstr name, jint mode, jint device) {
    const char* name_ = getUtf8(env, name);

    if (name_ == NULL) {
        return;
    }

    int rc = sys_mknodat(target, name_, static_cast<mode_t>(mode), static_cast<dev_t>(device));
    int err = errno;

    freeUtf8(env, name, name_);

    if (rc) {
        handleError(env, err);
    }
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_Android_nativeMkdirAt(JNIEnv *env, jclass type, jint target, jworkaroundstr name, jint mode) {
    const char* name_ = getUtf8(env, name);

    int rc = sys_mkdirat(target, name_, static_cast<mode_t>(mode));
    int err = errno;

    freeUtf8(env, name, name_);

    if (rc) {
        handleError(env, err);
    }
}


JNIEXPORT jobject JNICALL Java_net_sf_fdlib_Android_fstat(JNIEnv *env, jobject self, jint fd) {
    kernel_stat64 dirStat;

    if (sys_fstat64(fd, &dirStat) != 0) {
        handleError(env);
        return NULL;
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

    return env -> NewObject(statContainer, statContainerConstructor,
                            dirStat.st_dev, dirStat.st_ino, dirStat.st_size, fileTypeOrdinal);
}

}