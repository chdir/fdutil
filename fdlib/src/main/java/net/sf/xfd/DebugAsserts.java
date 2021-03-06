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

import android.os.Looper;

final class DebugAsserts {
    static void failIf(boolean fail, String s) {
        if (fail) {
            throw new AssertionError(s);
        }
    }

    static void thread(Looper expected, String s) {
        if (Looper.myLooper() != expected) {
            throw new AssertionError(s + " must be executed on " + expected.getThread() +
                    ", but was called on " + Thread.currentThread());
        }
    }
}
