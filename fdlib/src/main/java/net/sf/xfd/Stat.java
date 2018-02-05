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
import android.support.annotation.Keep;

public final class Stat implements Parcelable, Cloneable {
    /**
     * note: this is an unsigned 64-bit value, stored in signed Java long
     */
    public long st_dev;

    /**
     * note: this is an unsigned 64-bit value, stored in signed Java long
     */
    public long st_ino;

    /**
     * note: this is an unsigned 64-bit value, stored in signed Java long
     */
    public long st_size;

    public FsType type;

    /**
     * While it is theoretically possible to have 64-bit dev_t on 64-bit
     * architectures, there are no known device drivers with such rdev,
     * and any such drivers would be innately incompatible with 32-bit apps.
     * Let's keep this one 32-bit for simplicity.
     */
    public int st_rdev;

    /**
     * Again, while block size of underlying filesystem *may* be this big on x64,
     * such filesystems are too horrifying to imagine, so let's keep it 32-bit.
     */
    public int st_blksize;

    public short mode;

    public Stat() {
    }

    @Keep
    public void init(long st_dev, long st_ino, long st_size, int st_rdev, int st_blksize, int fsTypeId, int mode) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;
        this.st_size = st_size;
        this.st_rdev = st_rdev;
        this.st_blksize = st_blksize;

        this.mode = (short) mode;

        this.type = FsType.at(fsTypeId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(st_dev);
        dest.writeLong(st_ino);
        dest.writeLong(st_size);
        dest.writeInt(st_rdev);
        dest.writeInt(st_blksize);
        dest.writeInt(type.ordinal());
        dest.writeInt(mode);
    }

    public static final Creator<Stat> CREATOR = new Creator<Stat>() {
        @Override
        public Stat createFromParcel(Parcel in) {
            final Stat result = new Stat();

            result.init(
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    (short) in.readInt());

            return result;
        }

        @Override
        public Stat[] newArray(int size) {
            return new Stat[size];
        }
    };

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(60);

        builder.append("Stat(").append(type).append(")[");
        builder.append("size: ").append(st_size).append("; ");
        builder.append("mode: 0").append(Integer.toOctalString(mode)).append("; ");
        if (type != null && type.isSpecial()) {
            builder.append("rdev: ").append(st_rdev).append("; ");
        }
        builder.append("ino: ").append(st_ino);
        builder.append("]");

        return builder.toString();
    }

    @Override
    public Stat clone() {
        try {
            return (Stat) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
