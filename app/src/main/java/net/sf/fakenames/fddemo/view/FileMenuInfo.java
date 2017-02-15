package net.sf.fakenames.fddemo.view;

import android.view.ContextMenu;

import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;

public final class FileMenuInfo implements ContextMenu.ContextMenuInfo {
    public final Directory.Entry fileInfo;
    public final @DirFd int parentDir;

    public FileMenuInfo(Directory.Entry fileInfo, int parentDir) {
        this.fileInfo = fileInfo;
        this.parentDir = parentDir;
    }
}
