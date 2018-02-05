/*
 * Copyright © 2017 Alexander Rvachev
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

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.GetChars;

import net.openhft.hashing.Access;
import net.openhft.hashing.LongHashFunction;

import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A string of bytes, received from Linux filesystem.
 *
 * The only assumptions, that can be made about such "string" are:
 *
 * <li>
 *     <ul> It is terminated by zero-byte and does not contain it elsewhere
 *     <ul> If it is meant to represent a file name, it can't contain a slash
 * </li>
 */
public final class NativeString implements GetChars, Parcelable, Cloneable {
    private static final LongHashFunction hasher = LongHashFunction.xx();

    final byte[] bytes;

    int length;

    String string;

    private NativeString(@Nullable String decoded, byte[] bytes, int length) {
        this.string = decoded;
        this.bytes = bytes;
        this.length = length;
    }

    NativeString(@Nullable String decoded, byte[] bytes) {
        this(decoded, bytes, bytes.length);
    }

    public NativeString(@NonNull byte[] bytes) {
        this(null, bytes, bytes.length);
    }

    @Override
    public int length() {
        if (length == 0) return 0;

        decode();

        return string.length();
    }

    @Override
    public char charAt(int index) {
        decode();

        return string.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        decode();

        return string.subSequence(start, end);
    }

    @Override
    public void getChars(int start, int end, char[] dest, int destoff) {
        decode();

        string.getChars(start, end, dest, destoff);
    }

    @NonNull
    @Override
    public String toString() {
        return toString(null);
    }

    @NonNull
    public String toString(FileNameDecoder decoder) {
        decode(decoder);

        return string;
    }

    private void decode() {
        decode(null);
    }

    private void decode(FileNameDecoder decoder) {
        if (string != null) {
            return;
        }

        if (decoder == null) {
            decoder = new FileNameDecoder();
        }

        string = decoder.toString(bytes, 0, length);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof NativeString)) {
            return false;
        }

        NativeString other = ((NativeString) obj);

        int length = other.length;

        if (length != this.length) {
            return false;
        }

        for (int i = 0; i < length; ++i) {
            if (other.bytes[i] != this.bytes[i]) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        return (int) longHash();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(bytes, 0, length);
    }

    @Override
    public NativeString clone() {
        return new NativeString(Arrays.copyOf(bytes, length));
    }

    public long longHash() {
        return hasher.hash(bytes, ArrayAccess.INSTANCE, 0, length);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int byteLength() {
        return length;
    }

    public NativeString slice(int start, int end) {
        byte[] array = Arrays.copyOfRange(bytes, start, end);

        String str = string == null ? null : string.substring(start, end);

        return new NativeString(str, array);
    }

    public static final Creator<NativeString> CREATOR = new Creator<NativeString>() {
        @Override
        public NativeString createFromParcel(Parcel source) {
            return new NativeString(source.createByteArray());
        }

        @Override
        public NativeString[] newArray(int size) {
            return new NativeString[size];
        }
    };

    private static final class ArrayAccess extends Access<byte[]> {
        static final ArrayAccess INSTANCE = new ArrayAccess();

        private ArrayAccess() {}

        @Override
        public long getLong(byte[] input, long offset) {
            return input[(int) offset];
        }

        @Override
        public long getUnsignedInt(byte[] input, long offset) {
            return getInt(input, offset) & 0xFFFFFFFFL;
        }

        @Override
        public int getInt(byte[] input, long offset) {
            return input[(int) offset];
        }

        @Override
        public int getUnsignedShort(byte[] input, long offset) {
            return getShort(input, offset) & 0xFFFF;
        }

        @Override
        public int getShort(byte[] input, long offset) {
            return input[(int) offset];
        }

        @Override
        public int getUnsignedByte(byte[] input, long offset) {
            return getByte(input, offset) & 0xFF;
        }

        @Override
        public int getByte(byte[] input, long offset) {
            return input[(int) offset];
        }

        @Override
        public ByteOrder byteOrder(byte[] input) {
            return ByteOrder.BIG_ENDIAN;
        }
    }
}
