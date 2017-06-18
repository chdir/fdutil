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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class NativeString implements GetChars, Parcelable {
    private static final LongHashFunction hasher = LongHashFunction.xx();

    private final ByteBuffer bytes;

    String string;

    NativeString(byte[] bytes) {
        this.bytes = ByteBuffer.wrap(bytes);
    }

    NativeString(ByteBuffer container) {
        this.bytes = ByteBuffer.allocate(container.limit());

        container.rewind();

        this.bytes.put(container);
    }

    NativeString(byte[] bytes, @Nullable String decoded) {
        this.bytes = ByteBuffer.wrap(bytes);
        this.string = decoded;
    }

    @Override
    public int length() {
        if (string == null) {
            createString();
        }
        return string.length();
    }

    @Override
    public char charAt(int index) {
        if (string == null) {
            createString();
        }
        return string.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (string == null) {
            createString();
        }
        return string.subSequence(start, end);
    }

    @Override
    public void getChars(int start, int end, char[] dest, int destoff) {
        if (string == null) {
            createString();
        }

        string.getChars(start, end, dest, destoff);
    }

    @NonNull
    @Override
    public String toString() {
        if (string == null) {
            createString();
        }
        return string;
    }

    private void createString() {
        string = new String(bytes.array());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(bytes.array());
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

    @Override
    public int hashCode() {
        return (int) longHash();
    }

    public long longHash() {
        return hasher.hash(bytes, BufferAccess.INSTANCE, 0, bytes.capacity());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj.getClass()  == NativeString.class) {
            return Arrays.equals(bytes.array(), ((NativeString) obj).bytes.array());
        }

        return false;
    }

    public byte[] getBytes() {
        return bytes.array();
    }

    private static final class BufferAccess extends Access<ByteBuffer> {
        public static final BufferAccess INSTANCE = new BufferAccess();

        private BufferAccess() {}

        @Override
        public long getLong(ByteBuffer input, long offset) {
            return input.getLong((int) offset);
        }

        @Override
        public long getUnsignedInt(ByteBuffer input, long offset) {
            return getInt(input, offset) & 0xFFFFFFFFL;
        }

        @Override
        public int getInt(ByteBuffer input, long offset) {
            return input.getInt((int) offset);
        }

        @Override
        public int getUnsignedShort(ByteBuffer input, long offset) {
            return getShort(input, offset) & 0xFFFF;
        }

        @Override
        public int getShort(ByteBuffer input, long offset) {
            return input.getShort((int) offset);
        }

        @Override
        public int getUnsignedByte(ByteBuffer input, long offset) {
            return getByte(input, offset) & 0xFF;
        }

        @Override
        public int getByte(ByteBuffer input, long offset) {
            return input.get((int) offset);
        }

        @Override
        public ByteOrder byteOrder(ByteBuffer input) {
            return input.order();
        }
    }
}
