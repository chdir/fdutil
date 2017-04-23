/*
 * Copyright © 2017 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
#include <errno.h>
#include <fcntl.h>

#include <stdio.h> // printf

#include <android/log.h>

#include "javamagic.h"

#define LOG_TAG "fdshare"

#define MARSHMALLOW 23

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, "fdlib", __VA_ARGS__))

static int API_VERSION;
static jclass errnoException;
static jclass ioException;
static jmethodID errnoExceptionConstructor;

inline static jclass saveClassRef(const char* name, JNIEnv *env) {
    jclass found = (*env) -> FindClass(env, name);

    if (found == NULL) {
        return NULL;
    }

    return (jclass) ((*env) -> NewGlobalRef(env, found));
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;

    if ((*vm) -> GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass versionClass = (*env) -> FindClass(env, "android/os/Build$VERSION");

    if (versionClass == NULL)
        return -1;

    jfieldID sdkIntFieldID = (*env) -> GetStaticFieldID(env, versionClass, "SDK_INT", "I");

    if (sdkIntFieldID == NULL) {
        return -1;
    }

    API_VERSION = (*env) -> GetStaticIntField(env, versionClass, sdkIntFieldID);

    ioException = saveClassRef("java/io/IOException", env);
    if (ioException == NULL) {
        return -1;
    }

    errnoException = saveClassRef("net/sf/xfd/ErrnoException", env);
    if (errnoException == NULL) {
        return -1;
    }

    errnoExceptionConstructor = (*env) -> GetMethodID(env, errnoException, "<init>", "(ILjava/lang/String;)V");
    if (errnoExceptionConstructor == NULL) {
        return -1;
    }

    return JNI_VERSION_1_6;
}

inline static void throwErrnoError(JNIEnv* env, jint errCode, const char* message) {
    jstring errorString = (*env) -> NewStringUTF(env, message);
    if (errorString == NULL) {
        return;
    }

    jthrowable exception = (jthrowable) ((*env) -> NewObject(env, errnoException, errnoExceptionConstructor, errCode, errorString));

    if (exception == NULL) {
        return;
    }

    (*env) -> Throw(env, exception);
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
const char* getUtf8(JNIEnv* env, jworkaroundstr string) {
    if (API_VERSION >= MARSHMALLOW) {
        return (*env) -> GetStringUTFChars(env, (jstring) string, NULL);
    } else {
        jbyteArray bytes = (jbyteArray) string;
        size_t pathLength = (size_t) (*env) -> GetArrayLength(env, bytes);

        LOG("Size is %u", pathLength);


        void* pathChars =  (*env) -> GetPrimitiveArrayCritical(env, bytes, NULL);

        if (pathChars == NULL) {
            LOG("Failed to get array elements");

            return NULL;
        }

        char* pathBuffer = malloc(pathLength + 1);
        if (pathBuffer == NULL) {
            LOG("Failed to malloc");

            return NULL;
        }

        memcpy(pathBuffer, pathChars, pathLength);
        (*env) -> ReleasePrimitiveArrayCritical(env, bytes, pathChars, JNI_ABORT);
        pathBuffer[pathLength] = '\0';
        return pathBuffer;
    }
}

void freeUtf8(JNIEnv *env, jworkaroundstr string, const char* str) {
    if (API_VERSION >= MARSHMALLOW) {
        (*env) -> ReleaseStringUTFChars(env, (jstring) string, str);
    } else {
        free((void*) str);
    }
}

// convert linux UTF-8 string (not necessarily null-terminated) to Java String (or UTF-8 byte[], depending on Android version)
// return value of NULL guarantees, that exception was already thrown by JVM
jworkaroundstr toString(JNIEnv *env, const char* linuxString, jsize stringByteCount) {
    if (API_VERSION >= MARSHMALLOW) {
        const char* inputString;

        if (stringByteCount == 0) {
            inputString = "";
        } else {
            inputString = linuxString;
        }

        jstring resultString = (*env) -> NewStringUTF(env, inputString);

        return resultString;
    } else {
        jbyteArray arr = (*env) -> NewByteArray(env, stringByteCount);
        if (arr == NULL) {
            return NULL;
        }
        (*env) -> SetByteArrayRegion(env, arr, 0, stringByteCount, (const jbyte *) linuxString);
        return arr;
    }
}

JNIEXPORT jworkaroundstr JNICALL Java_net_sf_xfd_provider_Magic_guessMimeNative(JNIEnv *env, jclass type, jobject buffer, jint fd) {
    jworkaroundstr res = NULL;

    magic_t magic = magic_open(MAGIC_MIME_TYPE | MAGIC_ERROR);
    if (magic == NULL) {
        handleError(env);
        return NULL;
    }

    size_t cap = (size_t) (*env) -> GetDirectBufferCapacity(env, buffer);
    void* bufferAddress = (*env) -> GetDirectBufferAddress(env, buffer);

    if (magic_load_buffers(magic, &bufferAddress, &cap, 1)) {
        (*env) -> ThrowNew(env, ioException, "Failed to load mime database");
        goto cleanup;
    }

    const char* result = magic_descriptor(magic, fd);

    if (result == NULL) {
        const char* error = magic_error(magic);

        if (error != NULL) {
            int errno_value = magic_errno(magic);

            if (errno_value) {
                throwErrnoError(env, errno_value, error);
            } else {
                (*env) -> ThrowNew(env, ioException, error);
            }
        }

        goto cleanup;
    }

    size_t  len = strlen(result);
    res = toString(env, result, len);

cleanup:
    magic_close(magic);
    return res;
}