#pragma once

#define typeof __typeof__

#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#ifdef __cplusplus
#include <atomic>
using namespace std;
#else
#include <stdatomic.h>
#include <malloc.h>
#include <alloca.h>

#endif

#define PKG(name) Java_net_sf_xfd_##name
#define PKG_SYM(name) Java_net_sf_xfd_Android_##name

#define ARRAY_SIZE(x) (sizeof(x)/sizeof((x)[0]))

#define MARSHMALLOW 23

typedef jobject jworkaroundstr;

extern int API_VERSION;

extern jclass ioException;
extern jclass oomError;
extern jclass illegalStateException;
extern jclass errnoException;
extern jclass statContainer;
extern jclass limitContainer;
extern jclass byteArrayClass;

extern jmethodID errnoExceptionConstructor;
extern jmethodID statContainerInit;
extern jmethodID limitContainerInit;
extern jmethodID arenaConstructor;

extern size_t pageSize;

extern void handleError(JNIEnv *env);
extern void handleError0(JNIEnv *env, int lastError);

extern void oomThrow(JNIEnv *env, const char* explanation);
extern jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount);

#if DEBUG_LOG
#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, "fdlib", __VA_ARGS__))
#else
#define LOG(...)
#endif

#define getUtf8(env, hint, str) ({                                          \
  jbyte* buffer = NULL;                                                     \
  if (hint > 0) buffer = hint <= 255 ? alloca(hint) : malloc((size_t) hint);\
  utfconv(env, str, buffer, hint);                                          \
})

#define freeUtf8(env, hint, jstr, str) ({                      \
  if (hint < 0 || hint > 255) utfrelease(env, jstr, str, hint);\
})

static const char* utfconv(JNIEnv* env, jworkaroundstr string, jbyte* buffer, jint hint) {
    if (hint > 0) {
        if (buffer == NULL) {
            oomThrow(env, "malloc failed");
            return NULL;
        }

        (*env)->GetByteArrayRegion(env, (jbyteArray) string, 0, hint, buffer);

        buffer[hint] = '\0';

        return (const char*) buffer;
    } else {
        return (*env)->GetStringUTFChars(env, (jstring) string, NULL);
    }
}

static void utfrelease(JNIEnv* env, jworkaroundstr string, const char* chars, jint hint) {
    if (hint > 0) {
        free((void *) chars);
    } else {
        (*env)->ReleaseStringUTFChars(env, string, chars);
    }
}

inline static jclass saveClassRef(const char* name, JNIEnv *env) {
    jclass found = (*env)->FindClass(env, name);

    if (found == NULL) {
        return NULL;
    }

    return (jclass) (*env)->NewGlobalRef(env, found);
}

inline static jmethodID safeGetMethod(jclass type, const char* name, const char* sig, JNIEnv *env) {
    jmethodID found = (*env)->GetMethodID(env, type, name, sig);

    if (found == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
        return NULL;
    }

    return found;
}

inline static jfieldID safeGetStaticField(jclass type, const char* name, const char* sig, JNIEnv *env) {
    jfieldID found = (*env)->GetStaticFieldID(env, type, name, sig);

    if (found == NULL || (*env)->ExceptionCheck(env) == JNI_TRUE) {
        return NULL;
    }

    return found;
}

typedef volatile uint8_t* i10n_ptr;