#include "common.h"

#include <stdio.h>
#include <sys/inotify.h>
#include <errno.h>

extern "C" {

JNIEXPORT void JNICALL Java_net_sf_xfd_InotifyImpl_nativeInit(JNIEnv *env, jclass type) {
    if ((inotifyImplPointerField = env -> GetFieldID(type, "nativePtr", "J")) == NULL) {
        return;
    }

    jfieldID maskIgnoredField = env -> GetStaticFieldID(type, "MASK_IGNORED", "I");
    if (maskIgnoredField == NULL) {
        return;
    }

    env -> SetStaticIntField(type, maskIgnoredField, reinterpret_cast<jint>(IN_IGNORED));
}

JNIEXPORT jobject JNICALL Java_net_sf_xfd_InotifyImpl_nativeCreate(JNIEnv *env, jobject self) {
    // If https://serverfault.com/a/9548 is to be trusted, the biggest filename length
    // in Linux as of 2026 is 510 bytes (VFAT UCS-2 filenames)
    // Let's go with Binder's favorite size and use 1Mb as upper bound of buffer size
    size_t MAX_BUF_SIZE = 1024 * 1024;

    if (pageSize > 1024 * 1024 * 2) {
        pageSize = 1024 * 4;
    }

    void *bufferAddress = memalign(pageSize, MAX_BUF_SIZE);

    if (bufferAddress == NULL) {
        bufferAddress = malloc(MAX_BUF_SIZE);
    }

    if (bufferAddress == NULL) {
        env->ThrowNew(oomError, "inotify buffer");
        return NULL;
    }

    jobject bufferObj;
    if ((bufferObj = env->NewDirectByteBuffer(bufferAddress, MAX_BUF_SIZE)) == NULL) {
        return NULL;
    }

    env ->SetLongField(self, inotifyImplPointerField, reinterpret_cast<jlong>(bufferAddress));

    return bufferObj;
}

JNIEXPORT void JNICALL Java_net_sf_xfd_InotifyImpl_nativeRelease(JNIEnv *env, jclass type, jlong pointer) {
    free(reinterpret_cast<void*>(pointer));
}

JNIEXPORT jint JNICALL Java_net_sf_xfd_InotifyImpl_addSubscription(JNIEnv *env, jobject self, jint fd, jint watchedFd) {
    char procFile[25];

    sprintf(procFile, "/proc/self/fd/%d", watchedFd);

    // note, that IN_ATTRIB is crucial because it informs us about changes in link count
    // if we still have an open descriptor of the monitored file, and the file gets unlinked,
    // the inode will remain alive and link count will decrease without IN_DELETE_SELF arriving!
    /// It will only arrive after no one keeps the file alive anymore
    int watchDescriptor = inotify_add_watch(fd, const_cast<const char*>(procFile),
                                            IN_ATTRIB | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE | IN_MOVE_SELF);

    if (watchDescriptor == -1) {
        handleError(env);
    }

    return watchDescriptor;
}

JNIEXPORT void JNICALL Java_net_sf_xfd_InotifyImpl_removeSubscription(JNIEnv *env, jclass type, jint fd, jint watchDesc) {
    if (inotify_rm_watch(fd, watchDesc) == -1) {
        handleError(env);
    }
}

JNIEXPORT jint JNICALL Java_net_sf_xfd_InotifyImpl_read(JNIEnv *env, jclass type, jint fd, jlong buffer, jint bufSizeRemaining) {
    int bytesRead = read(fd, reinterpret_cast<void*>(buffer), static_cast<size_t>(bufSizeRemaining));

    if (bytesRead == -1) {
        if (errno != EAGAIN || errno != EWOULDBLOCK) {
            handleError(env);
        }
    }

    return bytesRead;
}

}