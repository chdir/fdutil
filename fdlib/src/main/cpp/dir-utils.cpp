#include "common.h"

#include <sys/stat.h>

#include "linux_syscall_support.h"

extern "C" {

JNIEXPORT void JNICALL Java_net_sf_fdlib_DirectoryImpl_nativeInit(JNIEnv *env, jclass type) {
    if ((directoryImplPointerField = env -> GetFieldID(type, "nativePtr", "J")) == NULL) {
        return;
    }
}

JNIEXPORT jobject JNICALL Java_net_sf_fdlib_DirectoryImpl_nativeCreate(JNIEnv *env, jobject self, jint fd) {
    kernel_stat64 dirStat;

    if (TEMP_FAILURE_RETRY(sys_fstat64(fd, &dirStat)) < 0) {
        handleError(env);
        return NULL;
    }

    if (!S_ISDIR(dirStat.st_mode)) {
        env->ThrowNew(ioException, "Not a directory!");
        return NULL;
    }

    // If https://serverfault.com/a/9548 is to be trusted, the biggest filename length
    // in Linux as of 2026 is 510 bytes (VFAT UCS-2 filenames)
    size_t MIN_BUF_SIZE = 1024 * 4;

    // Let's go with Binder's favorite size and use 1Mb as upper bound of buffer size
    size_t MAX_BUF_SIZE = 1024 * 1024;

    size_t capacity = dirStat.st_blksize <= 0 ? MIN_BUF_SIZE : (dirStat.st_blksize > MAX_BUF_SIZE ? MAX_BUF_SIZE : dirStat.st_blksize);

    if (pageSize > 1024 * 1024 * 2) {
        pageSize = 1024 * 4;
    }

    void *bufferAddress = memalign(pageSize, capacity);

    if (bufferAddress == NULL) {
        bufferAddress = malloc(capacity);
    }

    if (bufferAddress == NULL) {
        env->ThrowNew(oomError, "getdents buffer");
        return NULL;
    }

    jobject bufferObj;
    if ((bufferObj = env->NewDirectByteBuffer(bufferAddress, capacity)) == NULL) {
        return NULL;
    }

    env ->SetLongField(self, directoryImplPointerField, reinterpret_cast<jlong>(bufferAddress));

    return bufferObj;
}

JNIEXPORT void JNICALL Java_net_sf_fdlib_DirectoryImpl_rewind(JNIEnv *env, jclass type, jint dirFd) {
    off_t result;

    if ((result = lseek(dirFd, 0, SEEK_SET)) == -1) {
        handleError(env);
        return;
    }

    if (result != 0) {
        env -> ThrowNew(ioException, "assertion failed: unable to rewind directory descriptor");
    }
}

JNIEXPORT jlong JNICALL Java_net_sf_fdlib_DirectoryImpl_seekTo(JNIEnv *env, jclass type, jint dirFd, jlong cookie) {
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

JNIEXPORT jint JNICALL Java_net_sf_fdlib_DirectoryImpl_nativeReadNext(JNIEnv *env, jclass type, jint dirFd, jlong nativeBufferPtr, jint capacity) {
    int bytesRead = TEMP_FAILURE_RETRY(sys_getdents64(dirFd, reinterpret_cast<kernel_dirent64*>(nativeBufferPtr), capacity));

    if (bytesRead < 0) {
        handleError(env);
    }

    return bytesRead;
}

JNIEXPORT jint JNICALL Java_net_sf_fdlib_DirectoryImpl_nativeGetStringBytes(JNIEnv *env, jobject instance, jlong entryPtr, jbyteArray reuse_, jint arrSize) {
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

JNIEXPORT void JNICALL Java_net_sf_fdlib_DirectoryImpl_nativeRelease(JNIEnv *env, jclass type, jlong bufferPointer) {
    free(reinterpret_cast<void*>(bufferPointer));
}

}