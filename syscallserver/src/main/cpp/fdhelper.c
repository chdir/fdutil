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

#include <stdlib.h> // exit
#include <stdio.h> // printf

#include <android/log.h>

#include "linux_syscall_support.h"
#include "moar_syscalls.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wmissing-noreturn"

#define LOG_TAG "fdshare"

#define REQ_TYPE_OPEN 1
#define REQ_TYPE_MKDIR 2
#define REQ_TYPE_UNLINK 3
#define REQ_TYPE_ADD_WATCH 4

#define INOTIFY_FLAGS (IN_ATTRIB | IN_CREATE | IN_DELETE | IN_DELETE_SELF | IN_MOVE | IN_MOVE_SELF)

static int verbose;

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

static void DieWithError(const char *errorMessage)  /* Error handling function */
{
    const char* errDesc = strerror(errno);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failure: %s errno %s(%d)", errorMessage, errDesc, errno);
    fprintf(stderr, "Error: %s - %s\n", errorMessage, errDesc);
    exit(errno);
}

static int ancil_send_fds_with_buffer(int sock, int fd)
{
    struct msghdr msghdr;
    msghdr.msg_name = NULL;
    msghdr.msg_namelen = 0;
    msghdr.msg_flags = 0;

    const char* success = "READY";

    struct iovec iovec;
    iovec.iov_base = (void*) success;
    iovec.iov_len = sizeof(success) + 1;

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
        ((int *) CMSG_DATA(cmsg))[0] = AT_FDCWD;

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
        printf("PID:%d", pid);
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

static void initFileContext(const char* context) {
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

int main(int argc, char *argv[]) {
    if (argc < 2) {
        // root test, just check out uid
        return getuid();
    }

    verbose = 1;

    // connect to supplied address and send the greeting message to server
    int sock = Bootstrap(argv[1]);

    // Use the context of calling app for file manipulation (this ensures. that our files
    // are created with same context as if they were created by the calling app)
    if (argc > 2) {
        initFileContext(argv[2]);
    }

    // process requests infinitely (we will be killed when done)
    char status[3];

    while(1) {
        int reqType, nameLength, mode, fdCount, receivedFd = -1;

        if (scanf("%d %d %d %d ", &reqType, &nameLength, &mode, &fdCount) != 4)
            DieWithError("reading a filename length, mode and fd count failed");

        if (verbose) {
            LOG("Length: %d mode: %d fds: %d", nameLength, mode, fdCount);
        }

        char* filename;
        if ((filename = (char*) calloc(nameLength + 1, 1)) == NULL)
            DieWithError("calloc() failed");

        if (verbose) {
            LOG("Allocated %d - sized buffer", nameLength + 1);
        }

        if (fgets(filename, nameLength + 1, stdin) == NULL)
            DieWithError("reading filename failed");

        if (verbose) {
            LOG("Attempting to open %s", filename);
        }

        switch (reqType) {
            case REQ_TYPE_OPEN: {
                if (fdCount > 0) {
                    ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
                } else {
                    receivedFd = AT_FDCWD;
                }

                int targetFd = sys_openat(receivedFd, filename, mode, S_IRWXU | S_IRWXG);

                if (targetFd > 0) {
                    if (ancil_send_fds_with_buffer(sock, targetFd))
                        DieWithError("sending file descriptor failed");
                } else {
                    const char *errmsg = strerror(errno);

                    LOG("Error: failed to open a file - %s\n", errmsg);

                    fprintf(stderr, "open error - %s\n", errmsg);
                }

                close(targetFd);

                break;
            }

            case REQ_TYPE_MKDIR: {
                if (fdCount > 0) {
                    ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
                } else {
                    receivedFd = AT_FDCWD;
                }

                if (sys_mkdirat(receivedFd, filename, (mode_t) mode) != 0) {
                    const char *errmsg = strerror(errno);

                    LOG("Error: failed to open a file - %s\n", errmsg);

                    fprintf(stderr, "directory creation error - %s\n", errmsg);
                } else {
                    fprintf(stderr, "READY");
                }

                break;
            }

            case REQ_TYPE_UNLINK: {
                if (fdCount > 0) {
                    ancil_recv_fds_with_buffer(sock, 1, &receivedFd);
                } else {
                    receivedFd = AT_FDCWD;
                }

                if (sys_unlinkat(receivedFd, filename, mode) != 0) {
                    const char *errmsg = strerror(errno);

                    LOG("Error: failed to unlink - %s\n", errmsg);

                    fprintf(stderr, "unlink error  - %s\n", errmsg);
                } else {
                    fprintf(stderr, "READY");
                }

                break;
            }

            case REQ_TYPE_ADD_WATCH: {
                if (fdCount != 2) {
                    DieWithError("Unexpected number of descriptors");
                }

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

                        fprintf(stderr, "readlink error - %s\n", errmsg);

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

                        fprintf(stderr, "inotify error - %s\n", errmsg);
                    } else {
                        fprintf(stderr, "%d", addedWatch);
                    }
                }

                close(fds[0]);
                close(fds[1]);

                break;
            }

            default:
                DieWithError("unknown request type");
        }

        free(filename);

        if (receivedFd > 0) {
            close(receivedFd);
        }
    }
}
#pragma clang diagnostic pop