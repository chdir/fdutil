package net.sf.fakenames.fddemo;

import net.sf.fdlib.DirFd;
import net.sf.fdlib.Stat;

public class FsFile {
    public final @DirFd int dirFd;
    public final String name;
    public final Stat stat;

    public FsFile(@DirFd int dirFd, String name, Stat stat) {
        this.dirFd = dirFd;
        this.name = name;
        this.stat = stat;
    }
}
