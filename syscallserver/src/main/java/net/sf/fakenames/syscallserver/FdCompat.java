/*
 * Copyright © 2017 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fakenames.syscallserver;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.system.Os;
import android.util.Log;

import net.sf.xfd.Fd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

final class FdCompat {
    static void closeDescriptor(@NonNull FileDescriptor fd) throws IOException {
        if (!fd.valid()) return;

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
            ParcelFileDescriptor.adoptFd(integer).close();
            integerField.setInt(donor, -1);
        } catch (Exception e) {
            Log.e("error", "Can not obtain descriptor on this Android version: " + e.getMessage());
        }
    }

    @RequiresApi(api = LOLLIPOP)
    private static void lollipopOsClose(FileDescriptor fd) throws IOException {
        try {
            Os.close(fd);
        } catch (Exception e) {
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
        } catch (Exception e) {
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
