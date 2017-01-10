#ifndef COMMON_H
#define COMMON_H

#define typeof __typeof__

#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#define MARSHMALLOW 23

typedef jobject jworkaroundstr;

extern int API_VERSION;

extern jclass ioException;
extern jclass oomError;
extern jclass errnoException;
extern jclass statContainer;

extern jmethodID errnoExceptionConstructor;
extern jmethodID statContainerConstructor;

extern size_t pageSize;

extern jfieldID directoryImplPointerField;
extern jfieldID inotifyImplPointerField;

extern void handleError(JNIEnv *env);
extern void handleError(JNIEnv *env, int lastError);

extern const char* getUtf8(JNIEnv* env, jworkaroundstr string);
extern void freeUtf8(JNIEnv *env, jworkaroundstr string, const char* str);
extern jworkaroundstr toString(JNIEnv *env, char* linuxString, int bufferSize, jsize stringByteCount);

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, "fdlib", __VA_ARGS__))

#endif