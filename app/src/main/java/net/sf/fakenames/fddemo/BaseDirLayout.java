package net.sf.fakenames.fddemo;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;

import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;

import net.sf.fdlib.Directory;
import net.sf.fdlib.Fd;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BaseDirLayout extends ContextWrapper {
    private final OS os;

    private File home;

    private MountInfo mountInfo;

    public BaseDirLayout(OS os, Context base) {
        super(base);

        this.os = os;
    }

    public void init() throws IOException {
        home = getDir("Home", MODE_PRIVATE);

        @Fd int mountFd = os.open("/proc/self/mountinfo", OS.O_RDONLY, 0);
        try {
            mountInfo = new MountInfo(mountFd);
        } finally {
            os.dispose(mountFd);
        }

        final HashSet<String> usableFilesystems = new HashSet<>(10);

        usableFilesystems.addAll(Arrays.asList(
                "ext2", "ext3", "ext4", "xfs", "jfs", "yaffs", "jffs2", "f2fS", "fuse", "vfat"));

        final HashMap<File, String> pathNameMap = new HashMap<>();

        File systemRoot = Environment.getRootDirectory();
        try {
            systemRoot = systemRoot.getCanonicalFile();
        } catch (IOException ignore) {
            // ok
        }
        pathNameMap.put(systemRoot, "Android system root");

        File filesDir = getFilesDir();
        try {
            filesDir = filesDir.getCanonicalFile();
        } catch (IOException ignore) {
            // ok
        }
        pathNameMap.put(filesDir, "Internal private storage");

        File[] external = ContextCompat.getExternalFilesDirs(this, null);
        for (int i = 0; i < external.length; ++i) {
            File resolved = external[i];

            if (resolved == null) continue;

            try {
                resolved = resolved.getCanonicalFile();
            } catch (IOException ignore) {
                // ok
            }
            pathNameMap.put(resolved, "External storage " + i);
        }

        List<StorageVolume> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 24) {
            final StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
            volumes.addAll(sm.getStorageVolumes());
        }

        mounts:
        for (LongObjectCursor<MountInfo.Mount> mount : mountInfo.mountMap) {
            final Iterator<Map.Entry<File, String>> i = pathNameMap.entrySet().iterator();
            while (i.hasNext()) {
                final Map.Entry<File, String> e = i.next();
                final String p = e.getKey().toString();
                if (p.equals(mount.value.rootPath) || p.startsWith(mount.value.rootPath + '/')) {
                    i.remove();
                    pathNameMap.remove(e.getKey());
                    mount.value.rootPath = p;
                    mount.value.description = e.getValue();
                    usableFilesystems.add(mount.value.fstype);
                    break;
                }
            }

            if (Build.VERSION.SDK_INT >= 24) {
                final File mountRoot = new File(mount.value.rootPath);

                final StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                Iterator<StorageVolume> j = volumes.iterator();
                while (j.hasNext()) {
                    StorageVolume knownVolume = j.next();

                    if (knownVolume.equals(sm.getStorageVolume(mountRoot))) {
                        // seems like the mount belongs to the storage device, appropriately labeled
                        // by the manufacturer — use system-provided description
                        j.remove();
                        mount.value.description = knownVolume.getDescription(this);
                        usableFilesystems.add(mount.value.fstype);
                        continue mounts;
                    }
                }
            }
        }

        // display all mounts with sensible descriptions
        for (LongObjectCursor<MountInfo.Mount> mount : mountInfo.mountMap) {
            if (TextUtils.isEmpty(mount.value.description)) {
                continue;
            }

            File descFile = new File(home, mount.value.description);

            if (!descFile.exists()) {
                @Fd int dir = os.opendir(home.getPath(), OS.O_RDONLY, 0);

                os.symlinkat(mount.value.rootPath, dir, descFile.getName());
            }
        }
    }

    public File getHome() {
        return home;
    }
}
