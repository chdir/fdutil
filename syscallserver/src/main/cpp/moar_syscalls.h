#ifndef MOAR_SYSCALL_SUPPORT_H
#define MOAR_SYSCALL_SUPPORT_H

#include <sys/syscall.h>
#include <sys/linux-syscalls.h>

static inline int sys_symlinkat(const char *target, int newdirfd, const char *linkpath) {
    return syscall(__NR_symlinkat, target, newdirfd, linkpath);
}

static inline int sys_mknodat(int target, const char *name, mode_t mode, dev_t dev) {
    return syscall(__NR_mknodat, target, name, mode, dev);
}

static inline int sys_mkdirat(int target, const char *name, mode_t mode) {
    return syscall(__NR_mkdirat, target, name, mode);
}

#endif