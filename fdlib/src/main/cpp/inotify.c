#include "common.h"

#include <stdio.h>
#include <sys/inotify.h>
#include <errno.h>

JNIEXPORT void JNICALL Java_net_sf_xfd_InotifyImpl_nativeInit(JNIEnv *env, jclass type) {
    jfieldID maskIgnoredField = (*env)->GetStaticFieldID(env, type, "MASK_IGNORED", "I");
    if (maskIgnoredField == NULL) {
        return;
    }

    (*env)->SetStaticIntField(env, type, maskIgnoredField, (jint) IN_IGNORED);
}

JNIEXPORT jint JNICALL Java_net_sf_xfd_InotifyImpl_addSubscription(JNIEnv *env, jobject self, jint fd, jint watchedFd) {
    char procFile[25];

    sprintf(procFile, "/proc/self/fd/%d", watchedFd);

    // note, that IN_ATTRIB is crucial because it informs us about changes in link count
    // if we still have an open descriptor of the monitored file, and the file gets unlinked,
    // the inode will remain alive and link count will decrease without IN_DELETE_SELF arriving!
    /// It will only arrive after no one keeps the file alive anymore
    int watchDescriptor = inotify_add_watch(fd, procFile,
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
    int bytesRead = read(fd, (void *) (intptr_t) buffer, (size_t) bufSizeRemaining);

    if (bytesRead == -1) {
        if (errno != EAGAIN || errno != EWOULDBLOCK) {
            handleError(env);
        }
    }

    return bytesRead;
}