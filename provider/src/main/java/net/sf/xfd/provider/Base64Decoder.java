package net.sf.xfd.provider;

import java.util.Arrays;

final class Base64Decoder {
    private Base64Decoder() {}

    /**
     * Lookup table for decoding "URL and Filename safe Base64 Alphabet"
     * as specified in Table2 of the RFC 4648.
     */
    private static final int[] fromBase64URL = new int[256];

    static {
        Arrays.fill(fromBase64URL, -1);

        char[] reverse = Base64Encoder.toBase64URL;

        for (int i = 0; i < reverse.length; i++) {
            fromBase64URL[reverse[i]] = i;
        }

        fromBase64URL['='] = -2;
    }

    public static int outLength(String src, int sp, int sl) {
        int paddings = 0;

        int len = sl - sp;

        if (len == 0) {
            return 0;
        }

        if (len < 2) {
            throw new IllegalArgumentException(
                    "Input byte[] should at least have 2 bytes for base64 bytes");
        }

        if (src.charAt(sl - 1) == '=') {
            paddings++;

            if (src.charAt(sl - 2) == '=') {
                paddings++;
            }
        }

        if (paddings == 0 && (len & 0x3) !=  0) {
            paddings = 4 - (len & 0x3);
        }

        return 3 * ((len + 3) / 4) - paddings;
    }


    public static int decode(String src, int sp, int sl, byte[] dst, int destPos) {
        int[] base64 = fromBase64URL;

        int dp = destPos;

        int bits = 0;

        int shiftto = 18;       // pos of first byte of 4-byte atom

        while (sp < sl) {
            int b = ((byte) src.charAt(sp++)) & 0xff;

            if ((b = base64[b]) < 0) {
                if (b == -2) {         // padding byte '='
                    // =     shiftto==18 unnecessary padding
                    // x=    shiftto==12 a dangling single x
                    // x     to be handled together with non-padding case
                    // xx=   shiftto==6&&sp==sl missing last =
                    // xx=y  shiftto==6 last is not =

                    if (shiftto == 6 && (sp == sl || src.charAt(sp++) != '=') ||
                            shiftto == 18) {

                        throw new IllegalArgumentException(
                                "Input byte array has wrong 4-byte ending unit");
                    }

                    break;
                } else  {
                    throw new IllegalArgumentException(
                            "Illegal base64 character " + Integer.toString(src.charAt(sp - 1), 16));
                }
            }

            bits |= (b << shiftto);

            shiftto -= 6;

            if (shiftto < 0) {
                dst[dp++] = (byte)(bits >> 16);

                dst[dp++] = (byte)(bits >>  8);

                dst[dp++] = (byte)(bits);

                shiftto = 18;

                bits = 0;
            }
        }

        // reached end of byte array or hit padding '=' characters.

        if (shiftto == 6) {
            dst[dp++] = (byte)(bits >> 16);
        } else if (shiftto == 0) {
            dst[dp++] = (byte)(bits >> 16);

            dst[dp++] = (byte)(bits >>  8);
        } else if (shiftto == 12) {
            // dangling single "x", incorrectly encoded.
            throw new IllegalArgumentException(
                    "Last unit does not have enough valid bits");
        }

        // anything left is invalid, if is not MIME.
        // if MIME, ignore all non-base64 character
        if (sp < sl) {
            throw new IllegalArgumentException(
                    "Input byte array has incorrect ending byte at " + sp);
        }

        return dp;
    }
}