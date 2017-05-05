/*
 * Copyright © 2016 Alexander Rvachev
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

public final class ErrnoException extends IOException {
    public static final int EOPNOTSUPP = 95;
    public static final int ENOTDIR = 20;
    public static final int ENOENT = 2;
    public static final int EAGAIN = 11;
    public static final int EINVAL = 22;
    public static final int ENOTEMPTY = 39;

    private final int errno;

    public ErrnoException(int errno, String explanation) {
        super(explanation);

        this.errno = errno;
    }

    public int code() {
        return errno;
    }

    @Override
    public String toString() {
        return super.toString() + " (errno " + errno + ')';
    }
}
