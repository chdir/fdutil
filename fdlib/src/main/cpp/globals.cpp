#include <jni.h>
#include <unistd.h>
#include <errno.h>

#include "common.h"

int API_VERSION;

jclass ioException;
jclass oomError;
jclass errnoException;

jmethodID errnoExceptionConstructor;

size_t pageSize;

jfieldID nativePointerField;

inline static void throwErrnoError(JNIEnv* env, int errCode, const char* message) {
    jthrowable exception = reinterpret_cast<jthrowable>(env->NewObject(errnoException, errnoExceptionConstructor, errCode, message));

    if (exception == NULL) {
        return;
    }

    env->Throw(exception);
}

void handleError(JNIEnv *env, int lastError) {
    const char *errDesc;
    if (lastError == 0) {
        errDesc = "Unknown error: errno is not set";
    } else {
        errDesc = strerror(lastError);
    }

    throwErrnoError(env, lastError, errDesc);
}

void handleError(JNIEnv *env) {
    handleError(env, errno);
}

// convert Java String (or byte array with UTF-8, depending on Android version) to Linux UTF-8 string
// return value of NULL guarantees, that exception was already thrown by JVM
const char* getUtf8(JNIEnv* env, jworkaroundstr string) {
    if (API_VERSION >= MARSHMALLOW) {
        return env->GetStringUTFChars((jstring) string, NULL);
    } else {
        jbyteArray bytes = (jbyteArray) string;
        size_t pathLength = static_cast<size_t>(env -> GetArrayLength(bytes));
        void* pathChars =  env -> GetPrimitiveArrayCritical(bytes, NULL);

        if (pathChars == NULL) {
            return NULL;
        }

        char* pathBuffer = static_cast<char*>(malloc(pathLength + 1));
        memcpy(pathBuffer, pathChars, pathLength);
        env -> ReleasePrimitiveArrayCritical(bytes, pathBuffer, JNI_ABORT);
        pathBuffer[pathLength] = '\0';
        return const_cast<const char*>(pathBuffer);
    }
}

void freeUtf8(JNIEnv *env, jworkaroundstr string, const char* str) {
    if (API_VERSION >= MARSHMALLOW) {
        env -> ReleaseStringUTFChars((jstring) string, str);
    } else {
        free((void*) str);
    }
}