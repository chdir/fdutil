package net.sf.fakenames.fddemo;

import android.support.annotation.NonNull;
import android.util.Log;

import com.carrotsearch.hppc.CharArrayList;

import java.io.PrintWriter;
import java.io.Writer;

public class LogUtil {
    private static final class LogWriter extends Writer {
        private static final int CHUNK_SIZE = 2048;

        private static final int MAX_BUFFER = 1024;

        CharArrayList charList = new CharArrayList(CHUNK_SIZE);

        private final String tag;

        LogWriter(String tag) {
            this.tag = tag;
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
            Log.println(Log.ERROR, tag, String.valueOf(charList.buffer, 0, charList.elementsCount));
            charList.elementsCount = 0;
        }

        private void flushExceptNewline() {
            // log contents of buffer except last newline (since println will add it's own)
            Log.println(Log.ERROR, tag, String.valueOf(charList.buffer, 0, charList.elementsCount - 1));
            charList.elementsCount = 0;
        }

        @Override
        public void write(@NonNull char[] buf, int offset, int count) {
            int prevAt = -1;

            for (int i = 0; i < buf.length; ++i) {
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

            if (prevAt != buf.length - 1) {
                charList.add(buf, prevAt + 1, buf.length - prevAt - 1);

                if (charList.elementsCount >= MAX_BUFFER) {
                    flush();
                }
            }
        }
    }

    // TODO do something about StackOverflowError
    @SuppressWarnings("WeakerAccess")
    public static void printTraceCautiously(Throwable t) {
        try (PrintWriter writer = new PrintWriter(new LogWriter("error"))) {
            t.printStackTrace(writer);

            writer.flush();

            Thread.sleep(80);
        } catch (Throwable ignore) {
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static void printStringCautiously(String t) {
        try (PrintWriter writer = new PrintWriter(new LogWriter("error"))) {
            writer.append(t);

            writer.flush();

            Thread.sleep(80);
        } catch (Throwable ignore) {
        }
    }
}
