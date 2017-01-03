package net.sf.fdlib;

import android.os.ParcelFileDescriptor;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;

import java.io.IOException;
import java.util.Scanner;

public class MountInfo {
    private final int mountinfo;

    public final LongObjectMap<Mount> mountMap = new LongObjectHashMap<>();

    public MountInfo(@Fd int mountinfo) throws IOException {
        this.mountinfo = mountinfo;

        reparse();
    }

    private static long makedev(long major, long minor) {
        return ((major & 0xfff) << 8) | (minor & 0xff) | ((minor & 0xfff00) << 12);
    }

    public void reparse() throws IOException {
        mountMap.clear();

        try (Scanner scanner = new Scanner(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.fromFd(mountinfo)))) {
            while (scanner.hasNextLine()) {
                scanner.nextInt();
                scanner.nextInt();

                final String[] mm = scanner.next().split(":");

                final int major = Integer.parseInt(mm[0]);
                final int minor = Integer.parseInt(mm[1]);

                final long dev_t = makedev(major, minor);

                scanner.next();

                final String location = scanner.next();

                // skip optional parts
                scanner.skip("(.+ -)");

                final String fsType = scanner.next();

                final String subject = scanner.next();

                mountMap.put(dev_t, new Mount(fsType, location, subject));

                scanner.nextLine();
            }
        }
    }

    public static final class Mount {
        public final String fstype;
        public final String subject;

        public volatile String rootPath;
        public volatile String description;

        public Mount(String fstype, String rootPath, String subject) {
            this.fstype = fstype;
            this.rootPath = rootPath;
            this.subject = subject;
        }

        @Override
        public String toString() {
            return fstype + ' ' + rootPath + ' ' + subject;
        }
    }
}
