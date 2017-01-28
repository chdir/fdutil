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

static inline int sys_lstat64(const char *name, kernel_stat64* stat) {
    return syscall(__NR_lstat64, name, stat);
}

static inline int sys_readlinkat(int fd, const char *name, char* buffer, size_t bufferSize) {
    return syscall(__NR_readlinkat, fd, name, buffer, bufferSize);
}

static inline int sys_faccessat(int fd, const char *name, int mode) {
    return syscall(__NR_faccessat, fd, name, mode);
}

static inline int sys_fstatat64_fixed(int dirfd, const char *filename, kernel_stat64* statbuf, int flags) {
    return syscall(__NR_fstatat64, dirfd, filename, statbuf, flags);
}

static inline int sys_renameat(int dirfd, const char *filename, int dirfd2, const char *filename2) {
    return syscall(__NR_renameat, dirfd, filename, dirfd2, filename2);
}

static inline int sys_linkat(int dirfd, const char *filename, int dirfd2, const char *filename2, int flags) {
    return syscall(__NR_linkat, dirfd, filename, dirfd2, filename2, flags);
}

static inline int  sys_fadvise(int fd, off64_t offset, off64_t len, int advice) {
#if defined(__arm__)
    return syscall(__NR_arm_fadvise64_64, fd, advice,
#if defined(__BYTE_ORDER) && __BYTE_ORDER == __BIG_ENDIAN
            // big-endian: high word in r2, low word in r3
            (unsigned int)(offset >> 32),
            (unsigned int)(offset & 0xFFFFFFFF),
            // big-endian: high word in r4, low word in r5
            (unsigned int)(len >> 32),
            (unsigned int)(len & 0xFFFFFFFF));
#else
            // little-endian: low word in r2, high word in r3
            (unsigned int)(offset & 0xFFFFFFFF),
            (unsigned int)(offset >> 32),
            // little-endian: low word in r4, high word in r5
            (unsigned int)(len & 0xFFFFFFFF),
            (unsigned int)(len >> 32));
#endif

#else
    return syscall(__NR_fadvise64, fd,
            (uint32_t) (offset >> 32), (uint32_t) (offset & 0xffffffff),
            (uint32_t) (len >> 32), (uint32_t) (len & 0xffffffff),
            advice);
#endif
}

#endif