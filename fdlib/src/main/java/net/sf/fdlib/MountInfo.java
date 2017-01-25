package net.sf.fdlib;

import android.os.ParcelFileDescriptor;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class MountInfo {
    private static final ThreadLocal<ByteBuffer> throwawayBuffer = new ThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return ByteBuffer.allocate(1024);
        }
    };

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
        LogUtil.logCautiously("reparse() got called");

        reparse(true);
    }

    private static final int SANE_SIZE_LIMIT = 200 * 1024 * 1024;

    /**
     * Parsing happens in two steps:
     *
     * 1) Estimate size of virtual file by reading stuff from it.
     * 2) Try to fetch entire contents in single call to read().
     *
     * This is necessary because /proc/mountinfo, like other /proc files, is only line-consistent,
     * but not atomic (the contents may be updated between individual calls to read()) — if we don't
     * exhaust it in single read() call, any changes to system mountpoints in-between may break our
     * parsing attempts.
     *
     * See also http://stackoverflow.com/a/5880485/
     */
    private void reparse(boolean force) throws IOException {
        mountMap.clear();

        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(mountinfo);
             FileChannel fc = new FileInputStream(pfd.getFileDescriptor()).getChannel()) {

            final ByteBuffer buffer = throwawayBuffer.get();

            int totalRead = 0, lastRead;

            do {
                buffer.clear();

                lastRead = fc.read(buffer);

                totalRead += lastRead;

                if (totalRead > SANE_SIZE_LIMIT) {
                    throw new IOException("/proc/mountinfo appears to be too big!!");
                }
            } while (lastRead != -1);

            fc.position(0);

            final CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();

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

                    final String fsType = scanner.next();

                    final String subject = scanner.next();

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

        reparse(false);
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
