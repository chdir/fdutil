#include "common.h"

#include <sys/stat.h>

#include "linux_syscall_support.h"

extern "C" {

JNIEXPORT void JNICALL PKG_SYM(rewind)(JNIEnv *env, jclass type, jint dirFd) {
    off_t result;

    if ((result = lseek(dirFd, 0, SEEK_SET)) == -1) {
        handleError(env);
        return;
    }

    if (result != 0) {
        env -> ThrowNew(ioException, "assertion failed: unable to rewind directory descriptor");
    }
}

JNIEXPORT jlong JNICALL PKG_SYM(seekTo)(JNIEnv *env, jclass type, jint dirFd, jlong cookie) {
    loff_t result = 0;

    if (result == cookie) {
        ++result;
    }

    unsigned long off_hi = static_cast<unsigned long>(cookie >> 32);
    unsigned long off_lo = static_cast<unsigned long>(cookie);
    if (sys__llseek((uint) dirFd, off_hi, off_lo, &result, SEEK_SET) < 0) {
        handleError(env);
        return -1;
    }

    return result;
}

JNIEXPORT jint JNICALL PKG_SYM(nativeReadNext)(JNIEnv *env, jclass type, jint dirFd, jlong nativeBufferPtr, jint capacity) {
    int bytesRead = TEMP_FAILURE_RETRY(sys_getdents64(dirFd, reinterpret_cast<kernel_dirent64*>(nativeBufferPtr), capacity));

    if (bytesRead < 0) {
        handleError(env);
    }

    return bytesRead;
}

JNIEXPORT jint JNICALL PKG_SYM(nativeGetStringBytes)(JNIEnv *env, jclass type, jlong entryPtr, jbyteArray reuse_, jint arrSize) {
    kernel_dirent64* entry = reinterpret_cast<kernel_dirent64*>(entryPtr);

    char* stringChars = entry->d_name;

    unsigned char maxLength = entry->d_reclen - offsetof(kernel_dirent64, d_name);

    size_t nameLength = strnlen(stringChars, maxLength);

    if (nameLength <= static_cast<size_t>(arrSize)) {
        // shamelessly skipping error checks…
        env -> SetByteArrayRegion(reuse_, 0, nameLength, reinterpret_cast<const jbyte*>(stringChars));
    } else {
        env->ThrowNew(ioException, "Name length too long");
        return -1;
    }

    return nameLength;
}

}