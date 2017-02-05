package net.sf.fdlib;

import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Scanner;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

public class MountInfo {
    private static final ThreadLocal<ByteBuffer> throwawayBuffer = new ThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return ByteBuffer.allocate(1024);
        }
    };

    private final int mountinfo;

    public final LongObjectMap<Mount> mountMap = new LongObjectHashMap<>();

    public final ObjectSet<String> nodev = new ObjectHashSet<>();

    public MountInfo(@Fd int mountinfo) throws IOException {
        this.mountinfo = mountinfo;

        reparse();
    }

    private static long makedev(long major, long minor) {
        return ((major & 0xfff) << 8) | (minor & 0xff) | ((minor & 0xfff00) << 12);
    }

    public void reparse() throws IOException {
        LogUtil.logCautiously("reparse() got called");

        mountMap.clear();
        nodev.clear();

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

        parseFilesystems(decoder, true);
        parseMounts(decoder, true);
    }

    private static final int SANE_SIZE_LIMIT = 200 * 1024 * 1024;

    private int measure(FileChannel fc) throws IOException {
        final ByteBuffer buffer = throwawayBuffer.get();

        int totalRead = 0, lastRead;

        do {
            buffer.clear();

            lastRead = fc.read(buffer);

            totalRead += lastRead;

            if (totalRead > SANE_SIZE_LIMIT) {
                throw new IOException("/proc/ file appears to be too big!!");
            }
        } while (lastRead != -1);

        fc.position(0);

        return totalRead;
    }

    private void parseMounts(CharsetDecoder d, boolean force) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(mountinfo);
             FileChannel fc = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {

            final int totalRead = measure(fc);

            try (Scanner scanner = new Scanner(Channels.newReader(new FileInputStream(pfd.getFileDescriptor()).getChannel(), d, totalRead))) {
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

                    final String fsType = scanner.next().intern();

                    final String subject = scanner.next().intern();

                    mountMap.put(dev_t, new Mount(fsType, location, subject));

                    scanner.nextLine();
                }

                return;
            } catch (NumberFormatException | NoSuchElementException nse) {
                // oops..
                if (!force) {
                    throw new IOException("Failed to parse mounts list", nse);
                }
            }
        }

        parseMounts(d, false);
    }

    private void parseFilesystems(CharsetDecoder d, boolean force) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File("/proc/filesystems"), MODE_READ_ONLY);
             FileChannel fc = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {

            int totalRead = measure(fc);

            try (Scanner scanner = new Scanner(Channels.newReader(new FileInputStream(pfd.getFileDescriptor()).getChannel(), d, totalRead))) {
                while (scanner.hasNextLine()) {
                    final String firstCol = scanner.next();

                    if ("nodev".equals(firstCol)) {
                        final String secondCol = scanner.next();

                        nodev.add(secondCol.intern());
                    }

                    scanner.nextLine();
                }

                return;
            } catch (NoSuchElementException nse) {
                // oops..
                if (!force) {
                    throw new IOException("Failed to parse fs list", nse);
                }
            }
        }

        parseFilesystems(d, false);
    }

    public boolean isVolatile(Mount fs) {
        switch (fs.fstype) {
            case "fuse":
                return false;
            default:
                return nodev.contains(fs.fstype);
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
