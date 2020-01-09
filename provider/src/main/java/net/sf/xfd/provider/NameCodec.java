package net.sf.xfd.provider;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import net.sf.xfd.NativeString;

public final class NameCodec {
    private NameCodec() {}

    public static @NonNull String toEncodedForm(@NonNull CharSequence string) {
        if (string instanceof NativeString) {
            return toEncodedForm((NativeString) string);
        }

        return "s" + string;
    }

    public static @NonNull String toEncodedForm(@NonNull NativeString string) {
        byte[] bytes = string.getBytes();

        for (byte b : bytes) {
            switch (b) {
                case '-':
                case '.':
                case '_':
                case '~':
                case '/':
                    continue;
                default:
                    if (b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9') {
                        continue;
                    }

                    return encodeBase64(string);
            }
        }

        return "s" + string.toString();
    }

    private static String encodeBase64(NativeString string) {
        int arraySize = Base64Encoder.outLength(string.byteLength());

        char[] chars = new char[arraySize + 1];

        chars[0] = 'b';

        int encodedLen = Base64Encoder.encode(string.getBytes(), 0, string.byteLength(), chars, 1);

        return new String(chars, 0, encodedLen);
    }

    public static @Nullable NativeString fromEncodedForm(@NonNull String encoded) {
        if (encoded.startsWith("s")) {
            String raw = encoded.substring(1);

            return new NativeString(raw.getBytes());
        } if (encoded.startsWith("b")) {
            int arraySize = Base64Decoder.outLength(encoded, 1, encoded.length() - 1);

            byte[] bytes = new byte[arraySize];

            int decodedLen = Base64Decoder.decode(encoded, 1, encoded.length() - 1, bytes, 0);

            return new NativeString(bytes, decodedLen);
        } else {
            return null;
        }
    }
}
