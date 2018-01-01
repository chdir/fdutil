/*
 * Copyright © 2017 Alexander Rvachev
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
package net.sf.xfd;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CopyImpl implements Copy {
    private static final int CHUNK_SIZE = 64 * 1024;

    private final Arena buffer;
    private final long nativePtr;

    CopyImpl(Arena buffer) {
        this.buffer = buffer;
        this.nativePtr = buffer.getPtr();
    }

    @Override
    public long transfer(@Fd int source, Stat sourceStat, @Fd int target, Stat targetStat, long bytes) throws IOException {
        final Interruption stage = Interruption.begin();
        try {
            long i10nPtr = stage.toNative();

            if (bytes <= 0) {
                bytes = Long.MAX_VALUE;
            }

            Android.i10nCheck(stage, "copy", 0);

            long copied;

            if (bytes > CHUNK_SIZE && sourceStat != null && targetStat != null) {
                final FsType sType = sourceStat.type;
                final FsType tType = targetStat.type;

                if (sType == FsType.FILE && (tType == FsType.FILE || tType == FsType.DOMAIN_SOCKET)) {
                    copied = Android.doSendfile(nativePtr, i10nPtr, bytes, source, target);
                } else if (sType == FsType.NAMED_PIPE || tType == FsType.NAMED_PIPE) {
                    copied = Android.doSplice(nativePtr, i10nPtr, bytes, source, target);
                } else {
                    copied = Android.doDumbCopy(nativePtr, i10nPtr, bytes, source, target);
                }
            } else {
                copied = Android.doDumbCopy(nativePtr, i10nPtr, bytes, source, target);
            }

            Android.i10nCheck(stage, "copy", copied);

            return copied;
        } finally {
            Interruption.end();
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
