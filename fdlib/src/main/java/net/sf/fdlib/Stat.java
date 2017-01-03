package net.sf.fdlib;

public final class Stat {
    public final long st_dev;

    public final long st_ino;

    public final long st_size;

    public final FsType type;

    public Stat(long st_dev, long st_ino, long st_size, int fsTypeId) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;
        this.st_size = st_size;

        this.type = FsType.at(fsTypeId);
    }
}
