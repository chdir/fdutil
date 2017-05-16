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
jclass limitContainer;
jclass byteArrayClass;

jmethodID iieConstructor;
jmethodID errnoExceptionConstructor;
jmethodID statContainerInit;
jmethodID limitContainerInit;

size_t pageSize;

jfieldID directoryImplPointerField;
jfieldID inotifyImplPointerField;
jfieldID limitContainerCur;
jfieldID limitContainerMax;

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
const char* getUtf8(JNIEnv* env, jboolean isArrray, jworkaroundstr string) {
    if (isArrray != JNI_TRUE) {
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

void freeUtf8(JNIEnv *env, jboolean isArray, jworkaroundstr string, const char* str) {
    if (isArray != JNI_TRUE) {
        env -> ReleaseStringUTFChars((jstring) string, str);
    } else {
        free((void*) str);
    }
}

static int is_utf8(const char* string)
{
    if(!string)
        return 0;

    const unsigned char * bytes = (const unsigned char*) string;
    while(*bytes)
    {
        if( (// ASCII
                // use bytes[0] <= 0x7F to allow ASCII control characters
                bytes[0] == 0x09 ||
                bytes[0] == 0x0A ||
                bytes[0] == 0x0D ||
                (0x20 <= bytes[0] && bytes[0] <= 0x7E)
        )
                ) {
            bytes += 1;
            continue;
        }

        if( (// non-overlong 2-byte
                (0xC2 <= bytes[0] && bytes[0] <= 0xDF) &&
                (0x80 <= bytes[1] && bytes[1] <= 0xBF)
        )
                ) {
            bytes += 2;
            continue;
        }

        if( (// excluding overlongs
                    bytes[0] == 0xE0 &&
                    (0xA0 <= bytes[1] && bytes[1] <= 0xBF) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF)
            ) ||
            (// straight 3-byte
                    ((0xE1 <= bytes[0] && bytes[0] <= 0xEC) ||
                     bytes[0] == 0xEE ||
                     bytes[0] == 0xEF) &&
                    (0x80 <= bytes[1] && bytes[1] <= 0xBF) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF)
            ) ||
            (// excluding surrogates
                    bytes[0] == 0xED &&
                    (0x80 <= bytes[1] && bytes[1] <= 0x9F) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF)
            )
                ) {
            bytes += 3;
            continue;
        }

        if( (// planes 1-3
                    bytes[0] == 0xF0 &&
                    (0x90 <= bytes[1] && bytes[1] <= 0xBF) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF) &&
                    (0x80 <= bytes[3] && bytes[3] <= 0xBF)
            ) ||
            (// planes 4-15
                    (0xF1 <= bytes[0] && bytes[0] <= 0xF3) &&
                    (0x80 <= bytes[1] && bytes[1] <= 0xBF) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF) &&
                    (0x80 <= bytes[3] && bytes[3] <= 0xBF)
            ) ||
            (// plane 16
                    bytes[0] == 0xF4 &&
                    (0x80 <= bytes[1] && bytes[1] <= 0x8F) &&
                    (0x80 <= bytes[2] && bytes[2] <= 0xBF) &&
                    (0x80 <= bytes[3] && bytes[3] <= 0xBF)
            )
                ) {
            bytes += 4;
            continue;
        }

        return 0;
    }

    return 1;
}

// convert linux UTF-8 string (not necessarily null-terminated) to Java String (or UTF-8 byte[], depending on Android version)
// return value of NULL guarantees, that exception was already thrown by JVM
jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount) {
    if (API_VERSION >= MARSHMALLOW && is_utf8(linuxString)) {
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