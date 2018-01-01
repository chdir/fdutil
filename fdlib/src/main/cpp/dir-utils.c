#include "common.h"

#include <sys/stat.h>

#include "linux_syscall_support.h"

JNIEXPORT void JNICALL PKG_SYM(rewind)(JNIEnv *env, jclass type, jint dirFd) {
    off_t result;

    if ((result = lseek(dirFd, 0, SEEK_SET)) == -1) {
        handleError(env);
        return;
    }

    if (result != 0) {
        (*env) -> ThrowNew(env, ioException, "assertion failed: unable to rewind directory descriptor");
    }
}

JNIEXPORT jlong JNICALL PKG_SYM(seekTo)(JNIEnv *env, jclass type, jlong cookie, jint dirFd) {
    loff_t result = 0;

    if (result == cookie) {
        ++result;
    }

    unsigned long off_hi = (unsigned long) (cookie >> 32);
    unsigned long off_lo = (unsigned long) cookie;
    if (sys__llseek((uint) dirFd, off_hi, off_lo, &result, SEEK_SET) < 0) {
        handleError(env);
        return -1;
    }

    return result;
}

JNIEXPORT jint JNICALL PKG_SYM(nativeReadNext)(JNIEnv *env, jclass type, jlong nativeBufferPtr, jint dirFd, jint capacity) {
    struct kernel_dirent64* entry = (struct kernel_dirent64*) (intptr_t) nativeBufferPtr;

    int bytesRead = TEMP_FAILURE_RETRY(sys_getdents64(dirFd, entry, capacity));

    if (bytesRead < 0) {
        handleError(env);
    }

    return bytesRead;
}

JNIEXPORT jint JNICALL PKG_SYM(nativeGetStringBytes)(JNIEnv *env, jclass type, jlong entryPtr, jbyteArray reuse_, jint arrSize) {
    struct kernel_dirent64* entry = (struct kernel_dirent64*) (intptr_t) entryPtr;

    char* stringChars = entry->d_name;

    unsigned char maxLength = entry->d_reclen - offsetof(struct kernel_dirent64, d_name);

    size_t nameLength = strnlen(stringChars, maxLength);

    if (nameLength <= (size_t) arrSize) {
        // shamelessly skipping error checks…
        (*env)->SetByteArrayRegion(env, reuse_, 0, nameLength, (const jbyte*) stringChars);
    } else {
        (*env)->ThrowNew(env, ioException, "Name length too long");
        return -1;
    }

    return nameLength;
}