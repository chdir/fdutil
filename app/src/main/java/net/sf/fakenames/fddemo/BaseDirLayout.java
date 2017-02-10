/**
 * Copyright © 2017 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.fakenames.fddemo;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.cursors.LongObjectCursor;

import net.sf.fdlib.Fd;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;
import net.sf.xfd.provider.MountsSingleton;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

public class BaseDirLayout extends ContextWrapper {
    private final OS os;

    private File home;

    private MountInfo mountInfo;

    private final ObjectHashSet<String> usableFilesystems = new ObjectHashSet<>(10); {
        usableFilesystems.addAll("ext2", "ext3", "ext4", "xfs", "jfs", "yaffs", "jffs2", "f2fS", "fuse", "vfat");
    }

    public BaseDirLayout(OS os, Context base) {
        super(base);

        this.os = os;
    }

    public void init() throws IOException {
        home = getDir("Home", MODE_PRIVATE);

        mountInfo = MountsSingleton.get(os);

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

        final Lock lock = mountInfo.getLock();
        lock.lock();
        try {
            parseMounts(pathNameMap, volumes);
        } finally {
            lock.unlock();
        }

    }

    private void parseMounts(HashMap<File, String> pathNameMap, List<StorageVolume> volumes) throws IOException {
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

        final Set<File> current = new HashSet<>();
        Collections.addAll(current, home.listFiles());

        // display all mounts with sensible descriptions
        for (LongObjectCursor<MountInfo.Mount> mount : mountInfo.mountMap) {
            if (TextUtils.isEmpty(mount.value.description)) {
                continue;
            }

            File descFile = new File(home, mount.value.description);

            if (!descFile.exists()) {
                if (current.contains(descFile)) {
                    // a broken link...

                    //noinspection ResultOfMethodCallIgnored
                    descFile.delete();
                }

                @Fd int dir = os.opendir(home.getPath(), OS.O_RDONLY, 0);

                os.symlinkat(mount.value.rootPath, dir, descFile.getName());
            }
        }
    }

    public MountInfo.Mount getFs(long dev_t) {
        final Lock lock = mountInfo.getLock();
        lock.lock();
        try {
            return mountInfo.mountMap.get(dev_t);
        } finally {
            lock.unlock();
        }
    }

    public File getHome() {
        return home;
    }
}
