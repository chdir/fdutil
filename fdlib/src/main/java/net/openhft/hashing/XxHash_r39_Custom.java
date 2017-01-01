package net.openhft.hashing;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static net.openhft.hashing.CharSequenceAccess.nativeCharSequenceAccess;

@SuppressWarnings("StaticInitializerReferencesSubClass")
public class XxHash_r39_Custom {
    private static final boolean NATIVE_LITTLE_ENDIAN = nativeOrder() == LITTLE_ENDIAN;

    private static final XxHash_r39_Custom INSTANCE = new XxHash_r39_Custom();
    private static final XxHash_r39_Custom NATIVE_XX = NATIVE_LITTLE_ENDIAN ?
            XxHash_r39_Custom.INSTANCE : BigEndian.INSTANCE;

    // Primes if treated as unsigned
    private static final long P1 = -7046029288634856825L;
    private static final long P2 = -4417276706812531889L;
    private static final long P3 = 1609587929392839161L;
    private static final long P4 = -8796714831421723037L;
    private static final long P5 = 2870177450012600261L;

    private XxHash_r39_Custom() {}

    <T> long fetch64(Access<T> access, T in, long off) {
        return access.getLong(in, off);
    }

    // long because of unsigned nature of original algorithm
    <T> long fetch32(Access<T> access, T in, long off) {
        return access.getUnsignedInt(in, off);
    }

    // int because of unsigned nature of original algorithm
    <T> int fetch8(Access<T> access, T in, long off) {
        return access.getUnsignedByte(in, off);
    }

    long toLittleEndian(long v) {
        return v;
    }

    int toLittleEndian(int v) {
        return v;
    }

    short toLittleEndian(short v) {
        return v;
    }

    public <T> long xxHash64(long seed, T input, Access<T> access, long off, long length) {
        long hash;
        long remaining = length;

        if (remaining >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;

            do {
                v1 += fetch64(access, input, off) * P2;
                v1 = Long.rotateLeft(v1, 31);
                v1 *= P1;

                v2 += fetch64(access, input, off + 8) * P2;
                v2 = Long.rotateLeft(v2, 31);
                v2 *= P1;

                v3 += fetch64(access, input, off + 16) * P2;
                v3 = Long.rotateLeft(v3, 31);
                v3 *= P1;

                v4 += fetch64(access, input, off + 24) * P2;
                v4 = Long.rotateLeft(v4, 31);
                v4 *= P1;

                off += 32;
                remaining -= 32;
            } while (remaining >= 32);

            hash = Long.rotateLeft(v1, 1)
                    + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);

            v1 *= P2;
            v1 = Long.rotateLeft(v1, 31);
            v1 *= P1;
            hash ^= v1;
            hash = hash * P1 + P4;

            v2 *= P2;
            v2 = Long.rotateLeft(v2, 31);
            v2 *= P1;
            hash ^= v2;
            hash = hash * P1 + P4;

            v3 *= P2;
            v3 = Long.rotateLeft(v3, 31);
            v3 *= P1;
            hash ^= v3;
            hash = hash * P1 + P4;

            v4 *= P2;
            v4 = Long.rotateLeft(v4, 31);
            v4 *= P1;
            hash ^= v4;
            hash = hash * P1 + P4;
        } else {
            hash = seed + P5;
        }

        hash += length;

        while (remaining >= 8) {
            long k1 = fetch64(access, input, off);
            k1 *= P2;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= P1;
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            off += 8;
            remaining -= 8;
        }

        if (remaining >= 4) {
            hash ^= fetch32(access, input, off) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            off += 4;
            remaining -= 4;
        }

        while (remaining != 0) {
            hash ^= fetch8(access, input, off) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            --remaining;
            ++off;
        }

        return finalize(hash);
    }

    private static long finalize(long hash) {
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static class BigEndian extends XxHash_r39_Custom {
        private static final BigEndian INSTANCE = new BigEndian();

        private BigEndian() {}

        @Override
        <T> long fetch64(Access<T> access, T in, long off) {
            return Long.reverseBytes(super.fetch64(access, in, off));
        }

        @Override
        <T> long fetch32(Access<T> access, T in, long off) {
            return Integer.reverseBytes(access.getInt(in, off)) & 0xFFFFFFFFL;
        }

        // fetch8 is not overloaded, because endianness doesn't matter for single byte

        @Override
        long toLittleEndian(long v) {
            return Long.reverseBytes(v);
        }

        @Override
        int toLittleEndian(int v) {
            return Integer.reverseBytes(v);
        }

        @Override
        short toLittleEndian(short v) {
            return Short.reverseBytes(v);
        }
    }

    public static AsLongHashFunction asLongHashFunctionWithoutSeed() {
        return AsLongHashFunction.SEEDLESS_INSTANCE;
    }

    private static class AsLongHashFunction {
        public static final AsLongHashFunction SEEDLESS_INSTANCE = new AsLongHashFunction();
        private static final long serialVersionUID = 0L;

        private Object readResolve() {
            return SEEDLESS_INSTANCE;
        }

        public long seed() {
            return 0L;
        }

        public long hashNativeChars(CharSequence input) {
            return hashNativeChars(input, 0, input.length());
        }

        public long hashNativeChars(CharSequence input, int off, int len) {
            return hash(input, nativeCharSequenceAccess(), off * 2L, len * 2L);
        }

        public long hashLong(long input) {
            input = NATIVE_XX.toLittleEndian(input);
            long hash = seed() + P5 + 8;
            input *= P2;
            input = Long.rotateLeft(input, 31);
            input *= P1;
            hash ^= input;
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            return XxHash_r39_Custom.finalize(hash);
        }

        public long hashInt(int input) {
            input = NATIVE_XX.toLittleEndian(input);
            long hash = seed() + P5 + 4;
            hash ^= input * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            return XxHash_r39_Custom.finalize(hash);
        }

        public long hashShort(short input) {
            input = NATIVE_XX.toLittleEndian(input);
            long hash = seed() + P5 + 2;
            hash ^= (input & 0xFF) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            hash ^= (input >> 8 & 0xFF) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            return XxHash_r39_Custom.finalize(hash);
        }

        public long hashChar(char input) {
            return hashShort((short) input);
        }

        public long hashByte(byte input) {
            long hash = seed() + P5 + 1;
            hash ^= input * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            return XxHash_r39_Custom.finalize(hash);
        }

        public long hashVoid() {
            return XxHash_r39_Custom.finalize(P5);
        }

        public <T> long hash(T input, Access<T> access, long off, long len) {
            long seed = seed();
            if (access.byteOrder(input) == LITTLE_ENDIAN) {
                return XxHash_r39_Custom.INSTANCE.xxHash64(seed, input, access, off, len);
            } else {
                return BigEndian.INSTANCE.xxHash64(seed, input, access, off, len);
            }
        }
    }

    public static AsLongHashFunctionSeeded asLongHashFunctionWithSeed(long seed) {
        return new AsLongHashFunctionSeeded(seed);
    }

    public static class AsLongHashFunctionSeeded extends AsLongHashFunction {
        private final long seed;
        private final long voidHash;

        private AsLongHashFunctionSeeded(long seed) {
            this.seed = seed;
            voidHash = XxHash_r39_Custom.finalize(seed + P5);
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public long hashVoid() {
            return voidHash;
        }
    }
}