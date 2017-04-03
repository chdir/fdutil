package net.sf.xfd;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CopyImpl implements Copy {
    private static final int CHUNK_SIZE = 64 * 1024;

    private final AtomicBoolean dead = new AtomicBoolean();

    private final Guard guard;
    private final long bufferPtr;

    private InterruptibleStageImpl stage;

    CopyImpl(OS os) {
        this.bufferPtr = nativeInit();

        final GuardFactory factory = GuardFactory.getInstance(os);

        this.guard = factory.forMemory(this, bufferPtr);
    }

    @Override
    public long transfer(@Fd int source, Stat sourceStat, @Fd int target, Stat targetStat, long bytes) throws IOException {
        if (stage == null) {
            stage = InterruptibleStageImpl.get();
        }

        stage.begin();
        try {
            Interruption i = stage.i;

            if (bytes <= 0) {
                bytes = Long.MAX_VALUE;
            }

            if (bytes > CHUNK_SIZE && sourceStat != null && targetStat != null) {
                final FsType sType = sourceStat.type;
                final FsType tType = targetStat.type;

                if (sType == FsType.FILE && (tType == FsType.FILE || tType == FsType.DOMAIN_SOCKET)) {
                    return doSendfile(bufferPtr, i.nativePtr, bytes, source, target);
                } else if (sType == FsType.NAMED_PIPE || tType == FsType.NAMED_PIPE) {
                    return doSplice(bufferPtr, i.nativePtr, bytes, source, target);
                }
            }

            return doDumbCopy(bufferPtr, i.nativePtr, bytes, source, target);
        } finally {
            stage.end();
        }
    }

    @Override
    public void close() {
        if (dead.compareAndSet(false, true)) {
            guard.close();
        }
    }

    private static native long nativeInit();

    private static native long doSendfile(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;

    private static native long doSplice(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;

    private static native long doDumbCopy(long buffer, long interruptPtr, long size, int fd1, int fd2) throws IOException;
}
