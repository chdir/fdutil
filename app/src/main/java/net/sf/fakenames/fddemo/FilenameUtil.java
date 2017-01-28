package net.sf.fakenames.fddemo;

import java.util.Arrays;

public final class FilenameUtil {
    private static final int[] COMPAT_ILLEGAL = { '\0', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, '"' /* 34 */, 42, '/' /* 47 */, 58, 60, 62, 63, 92, 124, };

    private static final int[] LINUX_ILLEGAL = { '\0', '/' /* 47 */ };

    public static String sanitize(String name) {
        return sanitizeInner(name, LINUX_ILLEGAL);
    }

    public static String sanitizeCompat(String name) {
        return sanitizeInner(name, COMPAT_ILLEGAL);
    }

    private static String sanitizeInner(String name, int[] forbiddenChars) {
        final StringBuilder cleanName = new StringBuilder();
        final int len = name.codePointCount(0, name.length());
        for (int i=0; i<len; i++) {
            int c = name.codePointAt(i);
            if (Arrays.binarySearch(forbiddenChars, c) < 0) {
                cleanName.appendCodePoint(c);
            } else {
                cleanName.appendCodePoint('_');
            }
        }
        return cleanName.toString();
    }

    public static boolean isQuote(char c) {
        switch (c) {
            case '"':  // quotation mark (")
            case '\'':  // apostrophe (')
            case '«':  // left-pointing double-angle quotation mark
            case '»':  // right-pointing double-angle quotation mark
            case '‘':  // left single quotation mark
            case '’':  // right single quotation mark
            case '‚':  // single low-9 quotation mark
            case '‛':  // single high-reversed-9 quotation mark
            case '“':  // left double quotation mark
            case '”':  // right double quotation mark
            case '„':  // double low-9 quotation mark
            case '‟':  // double high-reversed-9 quotation mark
            case '‹':  // single left-pointing angle quotation mark
            case '›':  // single right-pointing angle quotation mark
            case '「':  // left corner bracket
            case '」':  // right corner bracket
            case '『':  // left white corner bracket
            case '』':  // right white corner bracket
            case '﹁':  // presentation form for vertical left corner bracket
            case '﹂':  // presentation form for vertical right corner bracket
            case '﹃':  // presentation form for vertical left corner white bracket
            case '﹄':  // presentation form for vertical right corner white bracket
            case '｢':  // halfwidth left corner bracket
            case '｣':  // halfwidth right corner bracket
            case '\u301d':  // reversed double prime quotation mark
            case '\u301e':  // double prime quotation mark
            case '\u301f':  // low double prime quotation mark
            case '\uff02':  // fullwidth quotation mark
            case '\uff07':  // fullwidth apostrophe
                return true;
        }

        return false;
    }
}
