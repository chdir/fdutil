/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/mount.h>

#include <stdlib.h> // exit
#include <stdio.h> // printf

#include <android/log.h>
#include <sepol/policydb/symtab.h>

#include "linux_syscall_support.h"
#include "moar_syscalls.h"
#include "sepol/policydb/policydb.h"
#include "sepol/policydb/services.h"
#include "sepol/policydb/constraint.h"
#include "sepol/sepol.h"
#include "common.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wmissing-noreturn"

#define LOG_TAG "fdshare"

#define REQ_TYPE_OPEN 1
#define REQ_TYPE_MKDIR 2
#define REQ_TYPE_UNLINK 3
#define REQ_TYPE_ADD_WATCH 4
#define REQ_TYPE_MKNOD 5
#define REQ_TYPE_READLINK 6
#define REQ_TYPE_RENAME 7
#define REQ_TYPE_CREAT 8
#define REQ_TYPE_LINKAT 9

#define INVALID_FD -1

#define INOTIFY_FLAGS (IN_ATTRIB | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE | IN_MOVE_SELF)

static int verbose;

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

static void DieWithError(const char *errorMessage)  /* Error handling function */
{
    const char* errDesc = strerror(errno);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failure: %s errno %s(%d)", errorMessage, errDesc, errno);
    fprintf(stderr, "Error: %s - %s%c", errorMessage, errDesc, '\0');
    exit(errno);
}

static int ancil_send_fds_with_buffer(int sock, int fd)
{
    struct msghdr msghdr;
    msghdr.msg_name = NULL;
    msghdr.msg_namelen = 0;
    msghdr.msg_flags = 0;

    const char success[] = "READY";

    struct iovec iovec;
    iovec.iov_base = (void*) success;
    iovec.iov_len = sizeof(success);

    msghdr.msg_iov = &iovec;
    msghdr.msg_iovlen = 1;

    union {
        struct cmsghdr  cmsghdr;
        char        control[CMSG_SPACE(sizeof (int))];
    } cmsgfds;
    msghdr.msg_control = cmsgfds.control;
    msghdr.msg_controllen = sizeof(cmsgfds.control);

    struct cmsghdr  *cmsg;
    cmsg = CMSG_FIRSTHDR(&msghdr);
    cmsg->cmsg_len = CMSG_LEN(sizeof (int));
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    *((int *) CMSG_DATA(cmsg)) = fd;

    return (TEMP_FAILURE_RETRY(sendmsg(sock, &msghdr, 0)) >= 0 ? 0 : -1);
}

static int ancil_recv_fds_with_buffer(int sock, int fdCount, int *fds)
{
    struct msghdr msghdr;
    msghdr.msg_name = NULL;
    msghdr.msg_namelen = 0;
    msghdr.msg_flags = 0;

    char buffer;
    struct iovec iovec;
    iovec.iov_base = &buffer;
    iovec.iov_len = 1;

    msghdr.msg_iov = &iovec;
    msghdr.msg_iovlen = 1;

    union {
        struct cmsghdr  cmsghdr;
        char        control[CMSG_SPACE(sizeof(int))];
    } cmsgfds;

    struct cmsghdr  cmsghdr;
    msghdr.msg_control = cmsgfds.control;
    msghdr.msg_controllen = sizeof(cmsgfds.control);

    struct cmsghdr *cmsg;
    cmsg = CMSG_FIRSTHDR(&msghdr);
    cmsg->cmsg_len = msghdr.msg_controllen;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;

    for(int i = 0; i < fdCount; i++) {
        ((int *) CMSG_DATA(cmsg))[0] = INVALID_FD;

        if (verbose) {
            LOG("Invoking recvmsg()");
        }

        int bytesRead = TEMP_FAILURE_RETRY(recvmsg(sock, &msghdr, 0));

        if (bytesRead <= 0) {
            DieWithError("Failed to read a message from buffer");
            return -1;
        }

        if (verbose) {
            LOG("recvmsg() received %d bytes", bytesRead);
        }

        if (cmsg->cmsg_type == SCM_RIGHTS) {
            LOG("Descriptor: %d", ((int *) CMSG_DATA(cmsg))[0]);

            fds[i] = ((int *) CMSG_DATA(cmsg))[0];
        }
    }

    return fdCount;
}

// Fork and get ourselves a tty. Acquired tty will be new stdin,
// Standard output streams will be redirected to new_stdouterr.
// Returns control side tty file descriptor.
static int GetTTY() {
    int masterFd = open("/dev/ptmx", O_RDWR);
    if (masterFd < 0)
        DieWithError("failed to open /dev/ptmx");

    char devname[64];
    pid_t pid;

    if(unlockpt(masterFd)) // grantpt is unnecessary, because we already assume devpts by using /dev/ptmx
        DieWithError("trouble with /dev/ptmx");

    memset(devname, 0, sizeof(devname));

    // Early (Android 1.6) bionic versions of ptsname_r had a bug where they returned the buffer
    // instead of 0 on success.  A compatible way of telling whether ptsname_r
    // succeeded is to zero out errno and check it after the call
    errno = 0;
    int ptsResult = ptsname_r(masterFd, devname, sizeof(devname));
    if (ptsResult && errno)
        DieWithError("ptsname_r() returned error");

    pid = fork();
    if(pid < 0)
        DieWithError("fork() failed");

    if (pid) {
        // tell creator the PID of forked process
        fprintf(stderr, "PID:%d%c", pid, '\0');
        exit(0);
    } else {
        int pts;

        setsid();

        pts = open(devname, O_RDWR);
        if(pts < 0)
            exit(-1);

        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, 0);
    }

    return masterFd;
}

// Perform initial greeting dance with server over socket with supplied name.
// The procedure ends with "READY" and tty file descriptor are sent over the socket
// and "GO" being received in response, which means, that the server process has acquired
// file descriptor on the controlling terminal
static int Bootstrap(char *socket_name) {
    int tty, sock;

    tty = GetTTY();

    if ((sock = socket(PF_LOCAL, SOCK_STREAM, 0)) < 0)
        DieWithError("socket() failed");

    struct timeval timeout;
    timeout.tv_sec = 20;
    timeout.tv_usec = 0;

    //if (setsockopt (sock, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout)) < 0)
    //    DieWithError("setting ingoing timeout failed");

    //if (setsockopt (sock, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout)) < 0)
    //    DieWithError("setting outgoing timeout failed");

    struct sockaddr_un echoServAddr;

    memset(&echoServAddr, 0, sizeof(echoServAddr));

    echoServAddr.sun_family = AF_LOCAL;

    strncpy(echoServAddr.sun_path + 1, socket_name, sizeof(echoServAddr.sun_path) - 2);

    int size = sizeof(echoServAddr) - sizeof(echoServAddr.sun_path) + strlen(echoServAddr.sun_path+1) + 1;

    if (connect(sock, (struct sockaddr *) &echoServAddr, size) < 0)
        DieWithError("connect() failed");

    dup2(sock, 1);
    dup2(sock, 2);

    if (ancil_send_fds_with_buffer(sock, tty))
        DieWithError("sending tty descriptor failed");

    if (scanf("GO") == 0) {
        if (close(tty))
            DieWithError("failed to close controlling tty");

        if (verbose) {
            LOG("The controlling tty is closed");
        }
    } else
        DieWithError("incomplete confirmation message");

    return sock;
}

static void fixPolicy(const char* context) {
    struct policy_file pf;
    struct policy_file fp;

    int outFd = load_policy_from_kernel(&pf);
    if (outFd < 0) {
        LOG("could not load SELinux policy");
        return;
    }

    size_t newSize = pf.size * 3/2;
    void* newPolicy = malloc(newSize);
    if (newPolicy == NULL) {
        LOG("Unable to reserve space for new policy");
        return;
    }

    policy_file_init(&fp);

    fp.type = PF_USE_MEMORY;
    fp.data = newPolicy;
    fp.size = newSize;
    fp.len = fp.size;

    patch_state_t ret = issue_indulgence(context, &pf, &fp);

    munmap(newPolicy, pf.size);
    close(outFd);

    switch (ret) {
        case PATCH_DONE:
            if (load_policy_into_kernel(&fp)) {
                LOG("failed to load policy into kernel");
            } else {
                LOG("Yay!");
            }
        case ALREADY_PATCHED:
            LOG("policy is already suitable, nothing to do");
        default:
            break;
    }

    free(newPolicy);
    return;
}

#include <linux/capability.h>

#define _LINUX_CAPABILITY_VERSION_3 0x20080522

static void initFileContext(int sock, const char* context) {
    umask(0);

    struct ucred creds;
    socklen_t szCreds = sizeof(creds);

    if (getsockopt(sock, SOL_SOCKET, SO_PEERCRED, &creds, &szCreds) < 0 || szCreds == 0) {
        DieWithError("failed to retrieve peer credentials");
    }

    // adjust uid/gid, used for filesystem operations a-la NFS
    // we save our capabilities and restore them after calling setfsuid/setfsgid, because those
    // calls strip some of superuser rights for legacy reasons
    cap_user_header_t hp = calloc(1, sizeof(*hp));
    cap_user_data_t d = calloc(2, sizeof(*d));

    if (hp == NULL || d == NULL) {
        DieWithError("Failed to alloc cap structures");
        return;
    }

    hp -> pid = getpid();
    hp -> version = _LINUX_CAPABILITY_VERSION_3;

    if (!sys_capget(hp, d)) {
        sys_setfsuid(creds.uid);
        sys_setfsgid(creds.gid);

        if (sys_capset(hp, d)) {
            DieWithError("Failed to restore capabilities");
        }
    }

    free(hp);
    free(d);

    fixPolicy(context);

    char fsCreatePathBuf[42];

    int threadId = gettid();

    sprintf(fsCreatePathBuf, "/proc/self/task/%d/attr/fscreate", threadId);

    FILE* fscreateFd = fopen(fsCreatePathBuf, "w");

    if (fscreateFd != NULL) {
        LOG("Switching file creation context to %s", context);

        fputs(context, fscreateFd);
        fclose(fscreateFd);
    }

    char sockCreatePathBuf[44];

    sprintf(sockCreatePathBuf, "/proc/self/task/%d/attr/sockcreate", threadId);

    FILE* sockcreateFd = fopen(fsCreatePathBuf, "w");

    if (sockcreateFd != NULL) {
        LOG("Switching socket creation context to %s", context);

        fputs(context, sockcreateFd);
        fclose(sockcreateFd);
    }
}

static char* read_filepath(size_t nameLength) {
    char* filepath;

    if ((filepath = (char*) calloc(nameLength + 1, sizeof(char))) == NULL)
        DieWithError("calloc() failed");

    if (verbose) LOG("Allocated %d - sized buffer", nameLength + 1);

    if (fgets(filepath, nameLength + 1, stdin) == NULL)
        DieWithError("reading filepath failed");

    return filepath;
}

static void invoke_mkdirat(int sock) {
    int fdCount = -1;
    int32_t mode = -1;
    size_t nameLength;

    if (scanf("%u %d %u ", &fdCount, &mode, &nameLength) != 3)
        DieWithError("reading mkdirat arguments failed");

    char* filepath = read_filepath(nameLength);

    if (verbose) LOG("Attempting to mknod %s", filepath);

    int receivedFd;

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
    } else {
        receivedFd = INVALID_FD;
    }

    if (sys_mkdirat(receivedFd, filepath, *(mode_t*)&mode) != 0) {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to create a directory - %s\n", errmsg);

        fprintf(stderr, "directory creation error - %s%c", errmsg, '\0');
    } else {
        fprintf(stderr, "READY%c", '\0');
    }

    free(filepath);

    if (receivedFd > 0) {
        close(receivedFd);
    }
}

static void invoke_mknodat(int sock) {
    int fdCount = -1;
    int32_t mode, device = -1;
    size_t nameLength;

    if (scanf("%u %d %d %u ", &fdCount, &mode, &device, &nameLength) != 4)
        DieWithError("reading mknodat arguments failed");

    char* filepath = read_filepath(nameLength);

    if (verbose) LOG("Attempting to mknod %s", filepath);

    int receivedFd;

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
    } else {
        receivedFd = INVALID_FD;
    }

    if (sys_mknodat(receivedFd, filepath, *(mode_t*)&mode, *(dev_t*)&device)) {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to open a file - %s\n", errmsg);

        fprintf(stderr, "mknod error - %s%c", errmsg, '\0');
    } else {
        fprintf(stderr, "READY%c", '\0');
    }

    free(filepath);

    if (receivedFd > 0) {
        close(receivedFd);
    }
}

static void invoke_unlinkat(int sock) {
    int fdCount = -1;
    int32_t flags = -1;
    size_t nameLength;

    if (scanf("%u %d %u ", &fdCount, &flags, &nameLength) != 3)
        DieWithError("reading unlinkat arguments failed");

    char* filepath = read_filepath(nameLength);

    if (verbose) LOG("Attempting to unlink %s", filepath);

    int receivedFd;

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
    } else {
        receivedFd = INVALID_FD;
    }

    if (sys_unlinkat(receivedFd, filepath, flags)) {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to unlink - %s\n", errmsg);

        fprintf(stderr, "unlink error  - %s%c", errmsg, '\0');
    } else {
        fprintf(stderr, "READY%c", '\0');
    }

    free(filepath);

    if (receivedFd > 0) {
        close(receivedFd);
    }
}

static void invoke_openat(int sock) {
    int fdCount = -1;
    int32_t flags = -1;
    size_t nameLength;

    if (scanf("%u %d %u ", &fdCount, &flags, &nameLength) != 3)
        DieWithError("reading openat arguments failed");

    char* filepath = read_filepath(nameLength);

    if (verbose) LOG("Attempting to open %s", filepath);

    int receivedFd;

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
    } else {
        receivedFd = INVALID_FD;
    }

    int targetFd;
    if (filepath[0] == '/') {
        targetFd = sys_open(filepath, flags, S_IRWXU | S_IRWXG);
    } else {
        targetFd = sys_openat(receivedFd, filepath, flags, S_IRWXU | S_IRWXG);
    }

    if (targetFd > 0) {
        if (ancil_send_fds_with_buffer(sock, targetFd))
            DieWithError("sending file descriptor failed");
    } else {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to open a file - %s\n", errmsg);

        fprintf(stderr, "open error - %s%c", errmsg, '\0');
    }

    free(filepath);

    if (targetFd > 0) {
        close(targetFd);
    }

    if (receivedFd > 0) {
        close(receivedFd);
    }
}

static void invoke_add_watch(int sock) {
    int fds[2];

    ancil_recv_fds_with_buffer(sock, 2, fds);

    char addWatchBuff[25];

    sprintf(addWatchBuff, "/proc/self/fd/%d", fds[1]);

    LOG("Resolving %s", addWatchBuff);

    size_t filenameSize = 1024 * 4;
    char* readLinkBuf;

    if ((readLinkBuf = (char*) calloc(filenameSize + 1, 1)) == NULL)
        DieWithError("Failed to alloc readlink buffer");

    while (1) {
        int rc = sys_readlink(addWatchBuff, readLinkBuf, filenameSize);

        if (rc == -1) {
            const char *errmsg = strerror(errno);

            LOG("failed to readlink - %s\n", errmsg);

            fprintf(stderr, "readlink error - %s%c", errmsg, '\0');

            free(readLinkBuf);
            readLinkBuf = NULL;

            break;
        } else if (rc >= filenameSize) {
            filenameSize *= 2;

            LOG("unable to fit filename, expanding to %d", filenameSize);

            char* newBuf = realloc((void*) readLinkBuf, filenameSize);

            if (newBuf == NULL) {
                free(readLinkBuf);
                newBuf = calloc(filenameSize + 1, 1);
                if (newBuf == NULL) {
                    LOG("realloc failed");

                    free(readLinkBuf);
                    readLinkBuf = NULL;

                    LOG("Failed to allocate new buffer of size %d", filenameSize);

                    break;
                }
            }

            readLinkBuf = newBuf;
            continue;
        } else {
            readLinkBuf[rc] = '\0';
            break;
        }
    }

    if (readLinkBuf != NULL) {
        int addedWatch = inotify_add_watch(fds[0], readLinkBuf, INOTIFY_FLAGS);

        free(readLinkBuf);

        if (addedWatch == -1) {
            const char *errmsg = strerror(errno);

            LOG("failed to add watch - %s\n", errmsg);

            fprintf(stderr, "inotify error - %s%c", errmsg, '\0');
        } else {
            fprintf(stderr, "%d%c", addedWatch, '\0');
        }
    }

    close(fds[0]);
    close(fds[1]);
}

const size_t RLINK_INITIAL_BUFFER_SIZE = 1000;

const size_t RLINK_MAX_BUFFER_SIZE = 16 * 1024 * 1024;

static char *resolve_with_buffer_size(int base, const char *linkpath, size_t bufferSize, size_t *finalStringSize);

static char* resolve_with_buffer(int base, const char *linkpath, void* buffer, size_t bufferSize, size_t *finalStringSize) {
    LOG("calling readlink() for %s with buffer size %u", linkpath, bufferSize);

    int resolvedNameLength = TEMP_FAILURE_RETRY(sys_readlinkat(base, linkpath, buffer, bufferSize));
    int err = errno;

    if (resolvedNameLength != -1) {
        if (resolvedNameLength < bufferSize) {
            *finalStringSize = (size_t) resolvedNameLength;
            return buffer;
        }

        if ((bufferSize = bufferSize * 2) < RLINK_MAX_BUFFER_SIZE) {
            LOG("The length is %d, trying to realloc", resolvedNameLength);

            void* newBuffer = realloc(buffer, bufferSize);
            if (newBuffer != NULL) {
                buffer = newBuffer;

                return resolve_with_buffer(base, linkpath, buffer, bufferSize, finalStringSize);
            } else {
                err = errno;
            }
        } else {
            err = ENAMETOOLONG;
        }
    }

    free(buffer);
    errno = err;
    return NULL;
}

char *resolve_with_buffer_size(int base, const char *linkpath, size_t bufferSize, size_t *finalStringSize) {
    void* buffer = realloc(NULL, bufferSize);

    if (buffer == NULL) {
        return NULL;
    }

    return resolve_with_buffer(base, linkpath, buffer, bufferSize, finalStringSize);
}

static const char* resolve_contcat(int base, const char* linkpath, size_t *stringSize, size_t nameSize) {
    struct kernel_stat64 dirStat;

    char fdBuf[25];
    sprintf(fdBuf, "/proc/self/fd/%d", base);

    if (TEMP_FAILURE_RETRY(sys_lstat64(fdBuf, &dirStat))) {
        return NULL;
    }

    size_t initialBufferSize;

    if (dirStat.st_size > 0) {
        initialBufferSize = (size_t) dirStat.st_size + 1 + nameSize;
    } else {
        initialBufferSize = RLINK_INITIAL_BUFFER_SIZE;
    }

    char *resolved = resolve_with_buffer_size(-1, fdBuf, initialBufferSize, stringSize);
    if (resolved == NULL) {
        return NULL;
    }

    size_t totalSize = *stringSize + nameSize + 1;

    if (totalSize >= initialBufferSize) {
        void* newBuf = realloc(resolved, totalSize + 1);
        int err = errno;

        if (newBuf == NULL) {
            free(resolved);
            errno = err;

            return NULL;
        }

        resolved = newBuf;
    }

    resolved[*stringSize] = '/';
    resolved[*stringSize + 1] = '\0';

    *stringSize = totalSize;

    strncat(resolved, linkpath, nameSize);

    return resolved;
}

static const char *resolve_link(int base, const char* linkpath, size_t *stringSize) {
    struct kernel_stat64 dirStat;

    if (TEMP_FAILURE_RETRY(sys_fstatat64_fixed(base, linkpath, &dirStat, AT_SYMLINK_NOFOLLOW)) < 0) {
        return NULL;
    }

    if (!S_ISLNK(dirStat.st_mode)) {
        size_t nameSize = strlen(linkpath);

        if (base < 0) {
            *stringSize = nameSize;

            return linkpath;
        }

        return resolve_contcat(base, linkpath, stringSize, nameSize);
    }

    size_t initialBufferSize;

    if (dirStat.st_size > 0) {
        initialBufferSize = (size_t) dirStat.st_size + 1;
    } else {
        initialBufferSize = RLINK_INITIAL_BUFFER_SIZE;
    }

    const char* resolved = resolve_with_buffer_size(base, linkpath, initialBufferSize, stringSize);

    if (resolved == NULL) {
        return NULL;
    }

    if (resolved[0] == '/') {
        return resolved;
    } else {
        const char* concatenated = resolve_contcat(base, resolved, stringSize, *stringSize);
        int err = errno;

        free((void *) resolved);

        if (concatenated == NULL) {
            errno = err;
            return NULL;
        }

        return concatenated;
    }
}

static void invoke_readlink(int sock) {
    int fdCount = -1;
    size_t nameLength;

    if (scanf("%u %u ", &fdCount, &nameLength) != 2)
        DieWithError("reading readlinkat arguments failed");

    char *filepath = read_filepath(nameLength);

    int receivedFd;

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
    } else {
        receivedFd = INVALID_FD;
    }

    size_t stringSize;

    char *resolved = (char *) resolve_link(receivedFd, filepath, &stringSize);

    if (resolved == NULL) {
        char *errmsg = strerror(errno);

        LOG("failed to resolve symlink, - %s\n", errmsg);

        fprintf(stderr, "symlink resolution error, - %s%c", errmsg, '\0');
    } else {
        resolved[stringSize] = '\0';

        fprintf(stderr, "%s%c", resolved, '\0');

        free(resolved);
    }

    if (filepath != resolved) {
        free(filepath);
    }

    if (receivedFd > 0) {
        close(receivedFd);
    }
}

static void invoke_rename(int sock) {
    int fds[2] = { -1, -1 };

    int fdCount = -1;
    size_t nameLength1, nameLength2;

    if (scanf("%u %u %u ", &fdCount, &nameLength1, &nameLength2) != 3)
        DieWithError("reading renameat arguments failed");

    char *filepath1 = read_filepath(nameLength1);
    char *filepath2 = read_filepath(nameLength2);

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, fdCount, fds);
    }

    if (sys_renameat(fds[0], filepath1, fds[1], filepath2)) {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to rename - %s\n", errmsg);

        fprintf(stderr, "rename error  - %s%c", errmsg, '\0');
    } else {
        fprintf(stderr, "READY%c", '\0');
    }

    free(filepath1);
    free(filepath2);

    if (fds[0] > 0) {
        close(fds[0]);
    }

    if (fds[1] > 0) {
        close(fds[1]);
    }
}

static void invoke_creat(int sock) {
    int32_t mode;
    size_t nameLength;

    if (scanf("%d %u ", &mode, &nameLength) != 2)
        DieWithError("reading creat arguments failed");

    char* filepath = read_filepath(nameLength);

    if (verbose) LOG("Attempting to creat %s", filepath);

    int targetFd = creat(filepath, *(mode_t*)&mode);

    if (targetFd > 0) {
        if (ancil_send_fds_with_buffer(sock, targetFd))
            DieWithError("sending file descriptor failed");
    } else {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to create a file - %s\n", errmsg);

        fprintf(stderr, "creat error - %s%c", errmsg, '\0');
    }

    free(filepath);

    if (targetFd > 0) {
        close(targetFd);
    }
}

static void invoke_linkat(int sock) {
    int fds[2] = { -1 };

    int flags;
    int fdCount = -1;
    size_t nameLength1, nameLength2;

    if (scanf("%u %d %u %u ", &fdCount, &flags, &nameLength1, &nameLength2) != 4)
        DieWithError("reading linkat arguments failed");

    char *filepath1 = read_filepath(nameLength1);
    char *filepath2 = read_filepath(nameLength2);

    if (fdCount > 0) {
        ancil_recv_fds_with_buffer(sock, fdCount, fds);
    }

    if (verbose) LOG("Attempting to link %s at %s", filepath1, filepath2);

    if (sys_linkat(fds[0], filepath1, fds[1], filepath2, flags)) {
        const char *errmsg = strerror(errno);

        LOG("Error: failed to link - %s\n", errmsg);

        fprintf(stderr, "link creation error  - %s%c", errmsg, '\0');
    } else {
        fprintf(stderr, "READY%c", '\0');
    }

    free(filepath1);
    free(filepath2);

    if (fds[0] > 0) {
        close(fds[0]);
    }

    if (fds[1] > 0) {
        close(fds[1]);
    }
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        uid_t myuid= getuid();
        // root test, just check out uid
        LOG("UID is %d", myuid);
        exit(myuid);
    }

    verbose = 1;

    if (sys_mount("proc", "/proc", "procfs", MS_REMOUNT, "hidepid=0")) {
        LOG("Failed to remount /proc: %s", strerror(errno));
    }

    // connect to supplied address and send the greeting message to server
    int sock = Bootstrap(argv[1]);

    // Use the context of calling app for file manipulation (this ensures. that our files
    // are created with same context as if they were created by the calling app)
    if (argc > 2) {
        initFileContext(sock, argv[2]);
    }

    // process requests infinitely (we will be killed when done)
    while(1) {
        int reqType;

        if (scanf("%d", &reqType) != 1)
            DieWithError("reading a request type failed");

        LOG("Request type is %d", reqType);

        switch (reqType) {
            case REQ_TYPE_OPEN:
                invoke_openat(sock);
                break;
            case REQ_TYPE_UNLINK:
                invoke_unlinkat(sock);
                break;
            case REQ_TYPE_ADD_WATCH:
                invoke_add_watch(sock);
                break;
            case REQ_TYPE_MKDIR:
                invoke_mkdirat(sock);
                break;
            case REQ_TYPE_MKNOD:
                invoke_mknodat(sock);
                break;
            case REQ_TYPE_READLINK:
                invoke_readlink(sock);
                break;
            case REQ_TYPE_RENAME:
                invoke_rename(sock);
                break;
            case REQ_TYPE_CREAT:
                invoke_creat(sock);
                break;
            case REQ_TYPE_LINKAT:
                invoke_linkat(sock);
                break;
            default:
                DieWithError("Unknown request type");
        }
    }
}
#pragma clang diagnostic pop