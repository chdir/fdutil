package net.sf.fdlib;

public final class Stat {
    public final FsType type;

    public final long st_dev;

    public final long st_ino;

    public Stat(long st_dev, long st_ino, int fsTypeId) {
        this.st_dev = st_dev;
        this.st_ino = st_ino;

        this.type = FsType.forDirentType(fsTypeId);
    }
}
