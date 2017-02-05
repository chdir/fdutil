package net.sf.fdlib;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.Keep;

public final class Stat implements Parcelable {
    public static final int BYTES = (Long.SIZE * 3 + Integer.SIZE * 2) / Byte.SIZE;

    public long st_dev;

    public long st_ino;

    public long st_size;

    public FsType type;

    public int st_blksize;

    public Stat() {
    }

    @Keep
    public void init(long st_dev, long st_ino, long st_size, int st_blksize, int fsTypeId) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;
        this.st_size = st_size;
        this.st_blksize = st_blksize;

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
        dest.writeInt(st_blksize);
        dest.writeInt(type.ordinal());
    }

    public static final Creator<Stat> CREATOR = new Creator<Stat>() {
        @Override
        public Stat createFromParcel(Parcel in) {
            final Stat result = new Stat();

            result.st_dev = in.readLong();
            result.st_ino = in.readLong();
            result.st_size = in.readLong();
            result.st_blksize = in.readInt();

            return result;
        }

        @Override
        public Stat[] newArray(int size) {
            return new Stat[size];
        }
    };
}
