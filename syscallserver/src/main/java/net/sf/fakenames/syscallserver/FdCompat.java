package net.sf.fakenames.syscallserver;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import net.sf.fdlib.Fd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

final class FdCompat {
    static void closeDescriptor(@NonNull FileDescriptor fd) throws IOException {
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            lollipopOsClose(fd);
        } else {
            closeOldWay(fd);
        }
    }

    @SuppressWarnings("WrongConstant")
    private static void closeOldWay(FileDescriptor donor) throws IOException {
        final @Fd int integer;
        try {
            readCachedField();
            integer = integerField.getInt(donor);
            Rooted.getInstance().close(integer);
        } catch (Exception e) {
            Log.e("error", "Can not obtain descriptor on this Android version: " + e.getMessage());
        }
    }

    @RequiresApi(api = LOLLIPOP)
    private static void lollipopOsClose(FileDescriptor fd) throws IOException {
        try {
            Os.close(fd);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    static @NonNull ParcelFileDescriptor adopt(@NonNull FileDescriptor fd) throws IOException {
        if (Build.VERSION.SDK_INT >= LOLLIPOP) {
            return adoptAnCloseOld(fd);
        } else {
            return transplantOldWay(fd);
        }
    }

    @RequiresApi(api = LOLLIPOP)
    private static ParcelFileDescriptor adoptAnCloseOld(FileDescriptor donor) throws IOException {
        final ParcelFileDescriptor result = ParcelFileDescriptor.dup(donor);
        try {
            Os.close(donor);
            return result;
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    private static ParcelFileDescriptor transplantOldWay(FileDescriptor donor) throws IOException {
        final int integer;
        try {
            readCachedField();
            integer = integerField.getInt(donor);
            return ParcelFileDescriptor.adoptFd(integer);
        } catch (Exception e) {
            throw new IOException("Can not obtain descriptor on this Android version: " + e.getMessage());
        }
    }

    private static volatile Field integerField;

    private static void readCachedField() throws NoSuchFieldException {
        if (integerField == null) {
            integerField = FileDescriptor.class.getDeclaredField("descriptor");
            integerField.setAccessible(true);
        }
    }
}
