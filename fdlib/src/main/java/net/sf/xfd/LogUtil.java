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

import android.support.annotation.NonNull;
import android.util.Log;

import com.carrotsearch.hppc.CharArrayList;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Locale;

@SuppressWarnings("WeakerAccess")
public class LogUtil {
    private static final boolean VERBOSE;
    static {
        VERBOSE = Boolean.valueOf(System.getProperty(OS.DEBUG_MODE));
    }

    public static final String TAG = "fdlib";

    private static final class LogWriter extends Writer {
        private static final int CHUNK_SIZE = 2048;

        private static final int MAX_BUFFER = 1024;

        CharArrayList charList = new CharArrayList(CHUNK_SIZE);

        private final String tag;
        private final int priority;

        LogWriter(String tag, int priority) {
            this.tag = tag;
            this.priority = priority;
        }

        @Override
        public void close() {
            flush();
        }

        @Override
        public void flush() {
            if (charList.elementsCount == 0) {
                return;
            }

            if (charList.buffer[charList.elementsCount] == '\n') {
                if (charList.elementsCount > 1) {
                    flushExceptNewline();
                } else {
                    charList.elementsCount = 0;
                }
            } else {
                flushAll();
            }
        }

        private void flushAll() {
            Log.println(priority, tag, String.valueOf(charList.buffer, 0, charList.elementsCount));
            charList.elementsCount = 0;
        }

        private void flushExceptNewline() {
            // log contents of buffer except last newline (since println will add it's own)
            Log.println(priority, tag, String.valueOf(charList.buffer, 0, charList.elementsCount - 1));
            charList.elementsCount = 0;
        }

        @Override
        public void write(@NonNull char[] buf, int offset, int count) {
            int prevAt = -1;

            for (int i = offset; i < offset + count; ++i) {
                if (buf[i] == '\n') {
                    int startIdx = prevAt + 1;

                    int substringLength = i - startIdx;

                    if (charList.elementsCount + substringLength >= MAX_BUFFER) {
                        charList.add(buf, startIdx, substringLength);

                        prevAt = i;

                        flushExceptNewline();
                    }
                }
            }

            if (prevAt != count - 1) {
                charList.add(buf, prevAt + 1, count - prevAt - 1);

                if (charList.elementsCount >= MAX_BUFFER) {
                    flush();
                }
            }
        }
    }

    public static void logCautiously(String message, Object... parts) {
        if (VERBOSE) {
            Log.println(Log.INFO, TAG, String.format(Locale.US, message, parts));
        }
    }

    /** Log a small-deal Exception, that does not endanger bright future of overall application */
    public static void logCautiously(String message, Throwable t) {
        final int priority = VERBOSE ? Log.ERROR : Log.DEBUG;

        logInner(message, t, priority, VERBOSE);
    }

    /** Log a completely unexpected stuff, that is not supposed to happen (but we still don't want to crash on it) */
    public static void swallowError(String message, Object... parts) {
        if (message == null) return;

        logInner(message, new Throwable(message), Log.ERROR, true, parts);
    }

    private static void logInner(String message, Throwable err, int priority, boolean trace, Object... args) {
        if (args.length != 0) {
            message = String.format(message, args);
        }

        try (PrintWriter writer = new PrintWriter(new LogWriter(TAG, priority))) {
            writer.println(message);

            if (trace) {
                err.printStackTrace(writer);
            } else {
                writer.println(err.getClass().toString() + ' ' + err.getMessage());
            }

            writer.flush();

            Thread.sleep(10);
        } catch (Throwable ignore) {
        }
    }
}
