#include <jni.h>
#include <unistd.h>
#include <errno.h>

#include "common.h"

int API_VERSION;

jclass ioException;
jclass iIoException;
jclass oomError;
jclass illegalStateException;
jclass errnoException;
jclass statContainer;

jmethodID errnoExceptionConstructor;
jmethodID statContainerInit;

size_t pageSize;

jfieldID directoryImplPointerField;
jfieldID inotifyImplPointerField;

inline static void throwErrnoError(JNIEnv* env, jint errCode, const char* message) {
    jstring errorString = env -> NewStringUTF(message);
    if (errorString == NULL) {
        return;
    }

    jthrowable exception = reinterpret_cast<jthrowable>(env->NewObject(errnoException, errnoExceptionConstructor, errCode, errorString));

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

        LOG("Size is %u", pathLength);


        void* pathChars =  env -> GetPrimitiveArrayCritical(bytes, NULL);

        if (pathChars == NULL) {
            LOG("Failed to get array elements");

            return NULL;
        }

        char* pathBuffer = static_cast<char*>(malloc(pathLength + 1));
        if (pathBuffer == NULL) {
            LOG("Failed to malloc");

            return NULL;
        }

        memcpy(pathBuffer, pathChars, pathLength);
        env -> ReleasePrimitiveArrayCritical(bytes, pathChars, JNI_ABORT);
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

// convert linux UTF-8 string (not necessarily null-terminated) to Java String (or UTF-8 byte[], depending on Android version)
// return value of NULL guarantees, that exception was already thrown by JVM
jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount) {
    if (API_VERSION >= MARSHMALLOW) {
        bool needToFree = false;
        const char* inputString;

        if (stringByteCount == 0) {
            inputString = "";
        } else if (stringByteCount < bufferSize) {
            // slap on the terminating byte
            if (linuxString[stringByteCount] != '\0') {
                linuxString[stringByteCount] = '\0';
            }

            inputString = linuxString;
        } else {
            // oops...
            inputString = (const char*) malloc((size_t) (stringByteCount + 1));
            needToFree = true;
        }

        jstring resultString = env->NewStringUTF(inputString);

        if (needToFree) {
            free((void *) inputString);
        }

        return resultString;
    } else {
        jbyteArray arr = env->NewByteArray(stringByteCount);
        if (arr == NULL) {
            return NULL;
        }
        env->SetByteArrayRegion(arr, 0, stringByteCount, (const jbyte *) linuxString);
        return arr;
    }
}