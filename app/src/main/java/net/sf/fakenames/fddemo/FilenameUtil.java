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
}
