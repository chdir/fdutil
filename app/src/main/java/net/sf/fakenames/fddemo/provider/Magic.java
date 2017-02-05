package net.sf.fakenames.fddemo.provider;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import net.sf.fakenames.fddemo.R;
import net.sf.fdlib.Fd;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static java.nio.channels.FileChannel.MapMode.PRIVATE;
import static java.nio.channels.FileChannel.MapMode.READ_ONLY;

public final class Magic {
    static {
        System.loadLibrary("magic");
    }

    @SuppressWarnings("FieldCanBeLocal") // finalizer closes descriptor
    private final AssetFileDescriptor magicFile;

    private final MappedByteBuffer mappedBuffer;

    private Magic(Resources res) throws IOException {
        magicFile = res.openRawResourceFd(R.raw.magic);

        try (FileChannel fc = new FileInputStream(magicFile.getFileDescriptor()).getChannel()) {
            mappedBuffer = fc.map(READ_ONLY, magicFile.getStartOffset(), magicFile.getDeclaredLength());
        }
    }

    public static Magic getInstance(Context context) throws IOException {
        return new Magic(context.getResources());
    }

    public String guessMime(@Fd int fd) throws IOException {
        return fromNative(guessMimeNative(mappedBuffer, fd));
    }

    private static native Object guessMimeNative(final MappedByteBuffer buffer, @Fd int fd) throws IOException;

    private static Object toNative(String string) {
        return Build.VERSION.SDK_INT >= 23 ? string : string.getBytes(StandardCharsets.UTF_8);
    }

    private static String fromNative(Object string) {
        return Build.VERSION.SDK_INT >= 23 ? (String) string : new String((byte[]) string, StandardCharsets.UTF_8);
    }
}
