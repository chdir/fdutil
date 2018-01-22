package net.sf.fakenames.fddemo.view;

import android.view.ContextMenu;

import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;

public final class FileMenuInfo implements ContextMenu.ContextMenuInfo {
    public final long opaqueIdx;
    public final Directory.Entry fileInfo;
    public final @DirFd int parentDir;
    public final int position;

    public FileMenuInfo(long opaqueIdx, Directory.Entry fileInfo, int parentDir, int position) {
        this.opaqueIdx = opaqueIdx;
        this.fileInfo = fileInfo;
        this.parentDir = parentDir;
        this.position = position;
    }
}
