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
#endif

#define PKG(name) Java_net_sf_xfd_##name
#define PKG_SYM(name) Java_net_sf_xfd_Android_##name

#define ARRAY_SIZE(x) (sizeof(x)/sizeof((x)[0]))

#define MARSHMALLOW 23

typedef jobject jworkaroundstr;

extern int API_VERSION;

extern jclass ioException;
extern jclass iIoException;
extern jclass oomError;
extern jclass illegalStateException;
extern jclass errnoException;
extern jclass statContainer;

extern jmethodID errnoExceptionConstructor;
extern jmethodID statContainerInit;

extern size_t pageSize;

extern jfieldID directoryImplPointerField;
extern jfieldID inotifyImplPointerField;

extern void handleError(JNIEnv *env);
extern void handleError(JNIEnv *env, int lastError);

extern const char* getUtf8(JNIEnv* env, jworkaroundstr string);
extern void freeUtf8(JNIEnv *env, jworkaroundstr string, const char* str);
extern jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount);

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, "fdlib", __VA_ARGS__))

inline static jclass saveClassRef(const char* name, JNIEnv *env) {
    jclass found = env -> FindClass(name);

    if (found == NULL) {
        return NULL;
    }

    return reinterpret_cast<jclass>(env->NewGlobalRef(found));
}

struct InterruptHandler {
public:
    atomic_bool interrupted;

    void set_flag() {
        interrupted.store(true, memory_order_relaxed);
    }

    void clear_flag() {
        interrupted.store(false, memory_order_relaxed);
    }
};