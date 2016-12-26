#include "common.h"

#include "linux_syscall_support.h"

#include <sys/inotify.h>

inline static jclass saveClassRef(const char* name, JNIEnv *env) {
    jclass found = env -> FindClass(name);

    if (found == NULL) {
        return NULL;
    }

    return reinterpret_cast<jclass>(env->NewGlobalRef(found));
}

inline static jint coreio_open(JNIEnv *env, jclass type, jworkaroundstr path, jint flags, jint mode) {
    const char *utf8Path = getUtf8(env, path);

    if (utf8Path == NULL) {
        env->ThrowNew(oomError, "file name buffer");
        return -1;
    }

    int newFd = TEMP_FAILURE_RETRY(sys_open(utf8Path, flags, mode));

    freeUtf8(env, path, utf8Path);

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

JNIEXPORT jint JNICALL Java_net_sf_fdlib_Android_nativeOpenDir(JNIEnv *env, jclass type, jobject path, jint flags, jint mode) {
    return coreio_open(env, type, path, flags | O_DIRECTORY, mode);
}

JNIEXPORT jint JNICALL Java_net_sf_fdlib_Android_nativeOpen(JNIEnv *env, jclass type, jobject path, jint flags, jint mode) {
    return coreio_open(env, type, path, flags | O_LARGEFILE, mode);
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

}