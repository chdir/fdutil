package net.sf.xfd.provider;

import java.util.Arrays;

/**
 * This class implements an encoder for encoding byte data using
 * the Base64 encoding scheme as specified in RFC 4648 and RFC 2045.
 *
 * Instances of Base64Encoder class are safe for use by multiple concurrent threads.
 *
 * <p> Unless otherwise noted, passing a {@code null} argument to
 * a method of this class will cause a
 * {@link java.lang.NullPointerException NullPointerException} to
 * be thrown.
 */
final class Base64Encoder {
    private Base64Encoder() {}

    /**
     * It's the lookup table for "URL and Filename safe Base64" as specified
     * in Table 2 of the RFC 4648, with the '+' and '/' changed to '-' and
     * '_'. This table is used when BASE64_URL is specified.
     */
    static final char[] toBase64URL = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_'
    };

    public static int outLength(int srclen) {
        int n = srclen % 3;

        return 4 * (srclen / 3) + (n == 0 ? 0 : n + 1);
    }

    static int encode(byte[] src, int off, int end, char[] dst, int destPos) {
        char[] base64 = toBase64URL;

        int sp = off;

        int slen = (end - off) / 3 * 3;

        int sl = off + slen;

        int dp = destPos;

        while (sp < sl) {
            int sl0 = Math.min(sp + slen, sl);

            for (int sp0 = sp, dp0 = dp ; sp0 < sl0; ) {
                int bits = (src[sp0++] & 0xff) << 16 |
                        (src[sp0++] & 0xff) <<  8 |
                        (src[sp0++] & 0xff);

                dst[dp0++] = base64[(bits >>> 18) & 0x3f];

                dst[dp0++] = base64[(bits >>> 12) & 0x3f];

                dst[dp0++] = base64[(bits >>> 6)  & 0x3f];

                dst[dp0++] = base64[bits & 0x3f];
            }

            int dlen = (sl0 - sp) / 3 * 4;

            dp += dlen;

            sp = sl0;
        }

        if (sp < end) {               // 1 or 2 leftover bytes
            int b0 = src[sp++] & 0xff;

            dst[dp++] = base64[b0 >> 2];

            if (sp == end) {
                dst[dp++] = base64[(b0 << 4) & 0x3f];
            } else {
                int b1 = src[sp++] & 0xff;

                dst[dp++] = base64[(b0 << 4) & 0x3f | (b1 >> 4)];

                dst[dp++] = base64[(b1 << 2) & 0x3f];
            }
        }

        return dp;
    }

}