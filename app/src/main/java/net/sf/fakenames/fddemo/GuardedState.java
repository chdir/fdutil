package net.sf.fakenames.fddemo;

import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.RecyclerView;

import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fdlib.CloseableGuard;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.OS;
import net.sf.fdlib.SelectorThread;
import net.sf.fdlib.WrappedIOException;

import java.io.IOException;

public final class GuardedState extends CloseableGuard {
    public final OS os;
    public final SelectorThread selThread;
    public final Inotify inotify;

    public final DirAdapter adapter;

    public final int inotifyFd;

    public static GuardedState create(OS os) throws IOException {
        final SelectorThread selThread = new SelectorThread();
        selThread.start();

        @InotifyFd int inotifyFd = os.inotify_init();

        final Inotify inotify = os.observe(inotifyFd);

        final DirAdapter adapter = new DirAdapter(os, inotify);

        inotify.setSelector(selThread);

        return new GuardedState(os, selThread, inotify, adapter, inotifyFd);
    }

    private GuardedState(OS os, SelectorThread selThread, Inotify inotify, DirAdapter adapter, int inotifyFd) {
        super(adapter);

        this.os = os;
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

        try {
            selThread.close();
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }
    }
}
