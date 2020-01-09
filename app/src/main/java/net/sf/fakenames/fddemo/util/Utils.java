package net.sf.fakenames.fddemo.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ShortcutManager;
import android.support.annotation.NonNull;
import android.support.v4.content.pm.ShortcutInfoCompat;
import android.support.v4.content.pm.ShortcutManagerCompat;
import android.support.v4.graphics.drawable.IconCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.carrotsearch.hppc.CharArrayList;

import net.sf.fakenames.fddemo.MainActivity;
import net.sf.fakenames.fddemo.R;
import net.sf.xfd.LogUtil;
import net.sf.xfd.NativeString;
import net.sf.xfd.provider.NameCodec;
import net.sf.xfd.provider.ProviderBase;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

public final class Utils {
    private static final String TAG = "fddemo";

    private Utils() {}

    public static String toLowerCase(String source) {
        return isAbbreviation(source) ? source : source.toLowerCase();
    }

    private static boolean isAbbreviation(String string) {
        final int l = string.length();

        if (l == 1) return false;

        for (int i = 1; i < l; ++i) {
            final boolean upperCase;

            char cur = string.charAt(i);

            if (Character.isHighSurrogate(cur) && i + 1 < l && Character.isLowSurrogate(string.charAt(i + 1))) {
                upperCase = Character.isUpperCase(Character.codePointAt(string, i++));
            } else {
                upperCase = Character.isUpperCase(cur);
            }

            if (upperCase) return true;
        }

        return false;
    }

    public static boolean createShortcut(Context ctx, CharSequence path, String shortcutName, IntentSender callback) {
        try {
            String encoded = NameCodec.toEncodedForm(path);

            // Create the intent that will handle the shortcut
            final Intent shortcutIntent = new Intent(ctx, MainActivity.class);
            shortcutIntent.setAction(Intent.ACTION_MAIN);
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            shortcutIntent.putExtra(MainActivity.EXTRA_FSO, encoded);

            ShortcutInfoCompat sic = new ShortcutInfoCompat.Builder(ctx, path.toString())
                    .setShortLabel(shortcutName)
                    .setIntent(shortcutIntent)
                    .setActivity(new ComponentName(ctx, MainActivity.class))
                    .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_fso_folder))
                    .build();

            return ShortcutManagerCompat.requestPinShortcut(ctx, sic, callback);
        } catch (Throwable e) {
            LogUtil.logCautiously(TAG, "Failed to create the shortcut", e); //$NON-NLS-1$

            return false;
        }
    }

    private static Toast toast;

    public static void toast(Context ctx, String message) {
        if (toast != null) {
            toast.cancel();
        }

        toast = Toast.makeText(ctx.getApplicationContext(), message, Toast.LENGTH_SHORT);
        toast.show();
    }

    public static void logStreamContents(InputStream in) {
        try (LogWriter writer = new LogWriter("error")) {
            char[] chars = new char[1024];

            final InputStreamReader isr = new InputStreamReader(in);

            int last;
            while ((last = isr.read()) != -1) {
                writer.write(chars, 0, last);
            }

            writer.flush();

            Thread.sleep(80);
        } catch (Throwable ignore) {
        }
    }

    @SuppressWarnings("WeakerAccess")
    public static void printTraceCautiously(Throwable t) {
        try (PrintWriter writer = new PrintWriter(new LogWriter("error"))) {
            t.printStackTrace(writer);

            writer.flush();

            Thread.sleep(80);
        } catch (Throwable ignore) {
        }
    }

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
}
