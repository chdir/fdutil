package net.sf.fdlib;

public enum FsType {
    BLOCK_DEV(6),
    CHAR_DEV(2),
    NAMED_PIPE(1),
    DOMAIN_SOCKET(12),
    LINK(10),
    FILE(8),
    DIRECTORY(4),

    // catch-all for yet unknown file types
    MYSTERY(0);

    private int nativeType;

    FsType(int nativeType) {
        this.nativeType = nativeType;
    }

    public boolean isSpecial() {
        switch (this) {
            case LINK:
            case FILE:
            case DIRECTORY:
            case MYSTERY:
                return false;
            default:
                return true;
        }
    }

    private static final FsType[] VALUES = values();

    static FsType forDirentType(short nativeType) {
        // the type can not be retrieved at this time because the filesystem either does not
        // support getting file types, or simply not return them during directory iteration
        // for efficiency reasons
        if (nativeType == MYSTERY.nativeType) return null;

        for (FsType value : VALUES) {
            if (value.nativeType == nativeType) {
                return value;
            }
        }

        // Oops
        return MYSTERY;
    }
}
