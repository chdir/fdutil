package net.sf.fdlib;

import android.support.annotation.Keep;

public final class Stat {
    public long st_dev;

    public long st_ino;

    public long st_size;

    public FsType type;

    public int st_blksize;

    public Stat() {
    }

    @Keep
    private void init(long st_dev, long st_ino, long st_size, int st_blksize, int fsTypeId) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;
        this.st_size = st_size;
        this.st_blksize = st_blksize;

        this.type = FsType.at(fsTypeId);
    }
}
