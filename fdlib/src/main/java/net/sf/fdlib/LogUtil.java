package net.sf.fdlib;

import android.support.annotation.NonNull;
import android.util.Log;

import com.carrotsearch.hppc.CharArrayList;

import java.io.PrintWriter;
import java.io.Writer;

@SuppressWarnings("WeakerAccess")
public class LogUtil {
    private static final boolean VERBOSE;
    static {
        VERBOSE = Boolean.valueOf(System.getProperty("net.sf.fdlib.VERBOSE"));
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

    public static void logCautiously(String message, Throwable t) {
        final int priority = VERBOSE ? Log.ERROR : Log.VERBOSE;

        try (PrintWriter writer = new PrintWriter(new LogWriter(TAG, priority))) {
            writer.println(message);

            if (VERBOSE) {
                t.printStackTrace(writer);
            } else {
                writer.println(t.getClass().toString() + ' ' + t.getMessage());
            }

            writer.flush();

            Thread.sleep(10);
        } catch (Throwable ignore) {
        }
    }

    public static void logCautiously(String message, String heavyContents) {
        final int priority = VERBOSE ? Log.ERROR : Log.VERBOSE;

        try (PrintWriter writer = new PrintWriter(new LogWriter(TAG, priority))) {
            writer.println(message);

            if (VERBOSE) {
                writer.append(heavyContents);
            }

            writer.flush();

            Thread.sleep(10);
        } catch (Throwable ignore) {
        }
    }
}
