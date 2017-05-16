package net.sf.fakenames.fddemo;

import android.support.test.InstrumentationRegistry;

import net.sf.xfd.CrappyDirectory;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.FsType;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.UUID;

@SuppressWarnings("ResultOfMethodCallIgnored")
public abstract class TestSetup {
    public final Stat stat;
    public final File dir;
    public final int fCount;
    private final String name;

    public TestSetup(File baseDir, Stat stat, String name) throws IOException {
        this.stat = stat;
        this.name = name;

        this.dir = new File(baseDir, UUID.randomUUID().toString());

        dir.mkdirs();

        final Random r = new Random();

        fCount = 75 * 2 + r.nextInt(10) * 75;

        for (int i = 0; i < fCount; ++i) {
            final File randomFile = new File(dir, UUID.randomUUID().toString());

            if (r.nextBoolean()) {
                randomFile.createNewFile();
            } else {
                randomFile.mkdir();
            }
        }
    }

    public abstract Directory forFd(@DirFd int fd);

    public void cleanup() {
        for (File file : dir.listFiles()) {
            file.delete();
        }

        dir.delete();
    }

    public static TestSetup internal() {
        try {
            final File dir = InstrumentationRegistry.getTargetContext().getFilesDir();

            return new NormalSetup(dir, "internal");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public static TestSetup internal2() {
        try {
            final File dir = InstrumentationRegistry.getTargetContext().getFilesDir();

            return new InternalWithTypeHint(dir, "internal_advanced");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public static TestSetup external() {
        try {
            final File dir = InstrumentationRegistry.getTargetContext().getExternalFilesDir(null);

            return new FATSetup(dir, "external");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final class NormalSetup extends TestSetup {
        OS os = OS.getInstance();

        private NormalSetup(File baseDir, String name) throws IOException {
            super(baseDir, null, name);
        }

        @Override
        public Directory forFd(@DirFd int fd) {
            return os.list(fd);
        }
    }

    private static final class FATSetup extends TestSetup {
        OS os = OS.getInstance();

        private FATSetup(File baseDir, String name) throws IOException {
            super(baseDir, null, name);
        }

        @Override
        public Directory forFd(@DirFd int fd) {
            return new CrappyDirectory(os.list(fd));
        }
    }

    private static final class InternalWithTypeHint extends TestSetup {
        OS os = OS.getInstance();

        private InternalWithTypeHint(File baseDir, String name) throws IOException {
            super(baseDir, new Stat(), name);

            stat.type = FsType.FILE;
        }

        @Override
        public Directory forFd(@DirFd int fd) {
            return os.list(fd);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
