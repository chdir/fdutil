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
import net.sf.xfd.CloseableGuard;
import net.sf.xfd.DirFd;
import net.sf.xfd.Inotify;
import net.sf.xfd.InotifyFd;
import net.sf.xfd.OS;
import net.sf.xfd.SelectorThread;
import net.sf.xfd.provider.EpollThreadSingleton;

import java.io.IOException;

public final class GuardedState extends CloseableGuard {
    public final Context appContext;
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

        final BaseDirLayout layout = new BaseDirLayout(os, context.getApplicationContext());

        layout.init();

        final Context ctx = context.getApplicationContext();

        return new GuardedState(os, ctx, layout, selThread, inotify, adapter, inotifyFd);
    }

    public GuardedState swap(OS os) throws IOException {
        final BaseDirLayout layout = new BaseDirLayout(os, appContext);

        layout.init();

        final @InotifyFd int inotifyFd = os.dup(this.inotifyFd);

        final Inotify inotify = os.observe(inotifyFd);

        final DirAdapter adapter = new DirAdapter(os, inotify);

        inotify.setSelector(selThread);

        return new GuardedState(os, appContext, layout, selThread, inotify, adapter, inotifyFd);
    }

    private GuardedState(OS os, Context appContext, BaseDirLayout layout, SelectorThread selThread, Inotify inotify, DirAdapter adapter, int inotifyFd) {
        super(adapter);

        this.os = os;
        this.appContext = appContext;
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
        if (!remove(this)) return;

        super.close();

        inotify.close();
        os.dispose(inotifyFd);

        @DirFd int prev = adapter.swapDirectoryDescriptor(DirFd.NIL);
        if (prev >= 0) {
            os.dispose(prev);
        }
    }
}
