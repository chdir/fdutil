package net.sf.xfd.provider;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.os.Build;

import net.sf.fdlib.Fd;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;

final class Magic {
    static {
        System.loadLibrary("magic-" + BuildConfig.MAGIC_VER);
    }

    @SuppressWarnings("FieldCanBeLocal") // finalizer closes descriptor
    private final AssetFileDescriptor magicFile;

    private final MappedByteBuffer mappedBuffer;

    private Magic(Resources res) throws IOException {
        magicFile = res.openRawResourceFd(R.raw.mgc);

        try (FileChannel fc = new FileInputStream(magicFile.getFileDescriptor()).getChannel()) {
            mappedBuffer = fc.map(READ_ONLY, magicFile.getStartOffset(), magicFile.getDeclaredLength());
        }
    }

    public static Magic getInstance(Context context) throws IOException {
        return new Magic(context.getResources());
    }

    String guessMime(@Fd int fd) throws IOException {
        return fromNative(guessMimeNative(mappedBuffer, fd));
    }

    private static native Object guessMimeNative(final MappedByteBuffer buffer, @Fd int fd) throws IOException;

    private static String fromNative(Object string) {
        return Build.VERSION.SDK_INT >= 23 ? (String) string : new String((byte[]) string, StandardCharsets.UTF_8);
    }
}
