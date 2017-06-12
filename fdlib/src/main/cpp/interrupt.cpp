#include "linux_syscall_support.h"
#include <stdio.h>
#include <errno.h>
#include <atomic>
#include "pthread.h"
#include "common.h"
#include "moar_syscalls.h"

using namespace std;

const static int candidate_signals[] = { SIGWINCH, SIGTTIN, SIGTTOU };

#define IS_JNI_NULL(val) (env -> IsSameObject((val), NULL))

static_assert(sizeof(atomic_bool) == 1, "atomic booleans have unexpected size!");
static_assert(sizeof(InterruptHandler) == 1, "structures have unexpected size!");
static_assert(alignof(atomic_bool) == 1, "atomic booleans have unexpected alignment!");
static_assert(sizeof(InterruptHandler) == 1, "structures have unexpected alignment!");
static_assert(ATOMIC_BOOL_LOCK_FREE == 2, "atomic booleans are not supported!");

static int chosen_signal = 0;

static void interruption_handler(int signo, siginfo_t* info, void* unused) {
    void* ref = info -> si_value.sival_ptr;

    if (ref  != NULL) {
        ((InterruptHandler*) ref) -> set_flag();
    }
}

/*
static jmethodID iIoExceptionConstructor;
static jfieldID iIoExceptionBytesDone;

static void throwIIoException(JNIEnv *env, const char* message, int bytesTransferred) {
    jstring msg = env -> NewStringUTF(message);
    if (msg == NULL) {
        return;
    }

    jthrowable exception = (jthrowable) env -> NewObject(iIoException, iIoExceptionConstructor, msg);
    if (exception == NULL) {
        return;
    }

    env->SetIntField(exception, iIoExceptionBytesDone, bytesTransferred);
    env -> Throw(exception);
}
 */

static char* dumb_sprintf(const char* format, char* message) {
    size_t totalLen = sizeof(format) + strlen(message) - 1;

    char* buf = (char *) malloc(totalLen);

    snprintf(buf, totalLen, format, message);

    buf[totalLen - 1] = '\0';

    return buf;
}

static void throwISException(JNIEnv *env, const char* format) {
    char* message = dumb_sprintf(format, strerror(errno));

    env -> ThrowNew(illegalStateException, format);

    free(message);
}

extern "C" {

#define INVAL_SA_PTR ((kernel_sigaction*) -1)

JNIEXPORT void JNICALL PKG_SYM(i10nInit)(JNIEnv *env, jclass type) {
    kernel_sigaction prev_sigaction;

    for (int i = 0; i < ARRAY_SIZE(candidate_signals); ++i) {
        if (sys_rt_sigaction(candidate_signals[i], NULL, &prev_sigaction, sizeof(kernel_sigset_t))) {
            throwISException(env, "Failed to probe for signal handlers (%s)");
            return;
        }

        if (prev_sigaction.sa_handler_ == SIG_DFL || prev_sigaction.sa_handler_ == SIG_IGN) {
            chosen_signal = candidate_signals[i];
            break;
        }
    }

    if (chosen_signal == 0) {
        env -> ThrowNew(illegalStateException, "Can not install signal handler: all signals are already reserved");
        return;
    }

    kernel_sigaction new_handler = {};
    new_handler.sa_sigaction_ = interruption_handler;
    new_handler.sa_flags = SA_SIGINFO;

    if (sys_rt_sigaction(chosen_signal, &new_handler, NULL, sizeof(kernel_sigset_t))) {
        throwISException(env, "Failed to install signal handler (%s)");
    }
}

JNIEXPORT void JNICALL PKG_SYM(unblockSignal)(JNIEnv *env, jclass type) {
    kernel_sigset_t new_set;

    sys_sigemptyset(&new_set);
    sys_sigaddset(&new_set, chosen_signal);

    if (sys_rt_sigprocmask(SIG_UNBLOCK, &new_set, &new_set, sizeof(kernel_sigset_t))) {
        throwISException(env, "Failed to unblock signal (%s)");
    }
}

JNIEXPORT void JNICALL PKG_SYM(nativeInterrupt)(JNIEnv *env, jclass unused, jlong value, jint tid) {
    sigval val;
    val.sival_ptr = (void *) value;

    pid_t pid = getpid();

    siginfo_t info;
    memset(&info, 0, sizeof(siginfo_t));
    info.si_signo = chosen_signal;
    info.si_code = SI_QUEUE;
    info.si_pid = pid;
    info.si_uid = getuid();
    info.si_value = val;

    if (sys_rt_tgsigqueueinfo(pid, tid, chosen_signal, &info)) {
        env -> ThrowNew(illegalStateException, strerror(errno));
    }
}

JNIEXPORT jboolean JNICALL PKG_SYM(nativeInterrupted)(JNIEnv *env, jclass unused, jlong ptr) {
    InterruptHandler* handler = reinterpret_cast<InterruptHandler*>(ptr);

    bool expected = true;

    return static_cast<jboolean>(handler -> interrupted.compare_exchange_strong(expected, false));
}

JNIEXPORT jint JNICALL PKG_SYM(nativeRead)(JNIEnv *env, jclass type, jobject buffer, jint fd, jint to, jint bytes) {
    char* bufAddress = static_cast<char*>(env -> GetDirectBufferAddress(buffer));
    if (bufAddress == NULL) {
        env -> ThrowNew(illegalStateException, "Failed to get direct buffer address. Heap buffers aren't supported!");
    }

    ssize_t ret = sys_read(fd, bufAddress + to, static_cast<size_t>(bytes));

    if (ret == -1) {
        switch (errno) {
            default:
                handleError(env);
                return -1;
            case EAGAIN:
                ret = -2;
                break;
            case EINTR:
                ret = 0;
                break;
        }
    } else if (ret == 0) {
        // ReadableByteStream returns -1 on EOF
        ret = -1;
    }

    return ret;
}

JNIEXPORT jint JNICALL PKG_SYM(nativeWrite)(JNIEnv *env, jclass type, jobject buffer, jint fd, jint from, jint bytes) {
    char* bufAddress = static_cast<char*>(env -> GetDirectBufferAddress(buffer));
    if (bufAddress == NULL) {
        env -> ThrowNew(illegalStateException, "Failed to get direct buffer address. Heap buffers aren't supported!");
    }

    int written = sys_write(fd, bufAddress + from, static_cast<size_t>(bytes));

    if (written == -1) {
        switch (errno) {
            default:
                handleError(env);
                return -1;
            case EAGAIN:
                written = -2;
                break;
            case EINTR:
                written = 0;
                break;
        }
    }

    return written;
}

}