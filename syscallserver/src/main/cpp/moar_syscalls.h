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

static inline int sys_lstat64(const char *name, struct kernel_stat64 *stat) {
    return syscall(__NR_lstat64, name, stat);
}

static inline int sys_readlinkat(int fd, const char *name, char* buffer, size_t bufferSize) {
    return syscall(__NR_readlinkat, fd, name, buffer, bufferSize);
}

static inline int sys_fstatat64_fixed(int dirfd, const char *filename, struct kernel_stat64 *statbuf, int flags) {
    return syscall(__NR_fstatat64, dirfd, filename, statbuf, flags);
}

#endif