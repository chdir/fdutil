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

import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fdlib.CloseableGuard;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.OS;
import net.sf.fdlib.SelectorThread;
import net.sf.xfd.provider.EpollThreadSingleton;

import java.io.IOException;

public final class GuardedState extends CloseableGuard {
    public final OS os;
    public final SelectorThread selThread;
    public final Inotify inotify;
    public final BaseDirLayout layout;

    public final DirAdapter adapter;

    public final int inotifyFd;

    public static GuardedState create(OS os, Context context) throws IOException {
        final SelectorThread selThread = EpollThreadSingleton.get();

        @InotifyFd int inotifyFd = os.inotify_init();

        final Inotify inotify = os.observe(inotifyFd);

        final DirAdapter adapter = new DirAdapter(os, inotify);

        inotify.setSelector(selThread);

        // no need to use root access here
        final BaseDirLayout layout = new BaseDirLayout(os, context.getApplicationContext());

        layout.init();

        return new GuardedState(os, layout, selThread, inotify, adapter, inotifyFd);
    }

    private GuardedState(OS os, BaseDirLayout layout, SelectorThread selThread, Inotify inotify, DirAdapter adapter, int inotifyFd) {
        super(adapter);

        this.os = os;
        this.layout = layout;
        this.selThread = selThread;
        this.adapter = adapter;
        this.inotify = inotify;
        this.inotifyFd = inotifyFd;
    }

    @Override
    protected void trigger() {
        close();
    }

    @Override
    public void close() {
        super.close();

        inotify.close();
        os.dispose(inotifyFd);

        @DirFd int prev = adapter.swapDirectoryDescriptor(DirFd.NIL);
        if (prev >= 0) {
            os.dispose(prev);
        }
    }
}
