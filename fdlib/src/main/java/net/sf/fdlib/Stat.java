package net.sf.fdlib;

public final class Stat {
    public long st_dev;

    public long st_ino;

    public long st_size;

    public FsType type;

    public Stat() {
    }

    private void init(long st_dev, long st_ino, long st_size, int fsTypeId) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;
        this.st_size = st_size;

        this.type = FsType.at(fsTypeId);
    }
}
