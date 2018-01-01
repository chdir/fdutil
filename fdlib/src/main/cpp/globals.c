#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <malloc.h>
#include <alloca.h>

#include "common.h"

int API_VERSION;

jclass ioException;
jclass oomError;
jclass illegalStateException;
jclass errnoException;
jclass statContainer;
jclass limitContainer;
jclass byteArrayClass;

jmethodID errnoExceptionConstructor;
jmethodID statContainerInit;
jmethodID limitContainerInit;
jmethodID arenaConstructor;

size_t pageSize;

void oomThrow(JNIEnv *env, const char* explanation) {
    __android_log_print(ANDROID_LOG_ERROR, "fdlib", "%s", explanation);

    (*env)->ThrowNew(env, oomError, explanation);
}

void throwErrnoError(JNIEnv* env, jint errCode, const char* message) {
    jstring errorString = (*env)->NewStringUTF(env, message);
    if (errorString == NULL) {
        return;
    }

    jthrowable exception = (jthrowable) ((*env)->NewObject(env, errnoException, errnoExceptionConstructor, errCode, errorString));
    if (exception == NULL) {
        return;
    }

    (*env)->Throw(env, exception);
}

void handleError0(JNIEnv *env, int lastError) {
    const char *errDesc;
    if (lastError == 0) {
        errDesc = "Unknown error: errno is not set";
    } else {
        errDesc = strerror(lastError);
    }

    throwErrnoError(env, lastError, errDesc);
}

void handleError(JNIEnv *env) {
    handleError0(env, errno);
}

// convert Java String (or byte array with UTF-8, depending on Android version) to Linux UTF-8 string
// return value of NULL guarantees, that exception was already thrown by JVM

// convert linux UTF-8 string (not necessarily null-terminated) to Java String (or UTF-8 byte[], depending on Android version)
// return value of NULL guarantees, that exception was already thrown by JVM
jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount) {
    jbyteArray arr = (*env)->NewByteArray(env, stringByteCount);
    if (arr == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, arr, 0, stringByteCount, (const jbyte*) linuxString);
    return arr;
}