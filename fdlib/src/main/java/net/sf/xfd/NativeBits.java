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

import android.support.annotation.Keep;

import java.io.IOException;

@Keep
public final class NativeBits {
    private NativeBits() {}

    public static final int O_PATH = 0b1000000000000000000000;

    public static final int O_CREAT = 0b1000000;

    public static final int O_DIRECTORY = 0b10000000000000000;

    public static final int O_NONBLOCK = nativeInit();

    public static final int O_NOCTTY = nativeInit();

    public static final int O_NOFOLLOW = nativeInit();

    public static final int RLIMIT_NOFILE = nativeInit();

    private static int nativeInit() { return 0; }

    private static native void fixConstants();

    static {
        try {
            Android.loadLibraries();

            fixConstants();
        } catch (IOException ignored) {
        }
    }
}
