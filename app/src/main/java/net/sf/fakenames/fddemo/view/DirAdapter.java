/*
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
package net.sf.fakenames.fddemo.view;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import net.sf.fakenames.fddemo.R;
import net.sf.xfd.CrappyDirectory;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.Inotify;
import net.sf.xfd.InotifyWatch;
import net.sf.xfd.LogUtil;
import net.sf.xfd.OS;
import net.sf.xfd.UnreliableIterator;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static net.sf.xfd.provider.ProviderBase.fdPath;

public final class DirAdapter extends RecyclerView.Adapter<DirItemHolder> implements Closeable {
    private static final String IO_ERR = "core.io.ui";

    private static final String TEST_DIR_NAME = "testDir";

    private final Inotify observer;

    private Context localContext;
    private LayoutInflater inflater;
    private RecyclerView recyclerView;

    private @DirFd int dirFd = DirFd.NIL;

    private InotifyWatch subscription;

    private Directory directory;
    private UnreliableIterator<Directory.Entry> iterator;
    private boolean ioFail;

    private int count = 0;

    private final OS os;

    public DirAdapter(OS os, Inotify observer) {
        this.os = os;
        this.observer = observer;

        setHasStableIds(true);
    }

    public @DirFd int getFd() {
        return dirFd;
    }

    private CharSequence pathname;

    public void packState() {
        if (dirFd < 0) return;

        try {
            pathname = os.readlinkat(DirFd.NIL, fdPath(dirFd));
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to fetch name of directory", e);
        }
    }

    public void recoverState() {
        if (pathname == null) return;

        try {
            swapDirectoryDescriptor(os.opendir(pathname));
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to fetch name of directory", e);
        } finally {
            pathname = null;
        }
    }

    public @DirFd int swapDirectoryDescriptor(@DirFd int dirFd) {
        return swapDirectoryDescriptor(dirFd, true);
    }

    public @DirFd int swapDirectoryDescriptor(@DirFd int dirFd, boolean useCachingWrapper) {
        if (this.dirFd == dirFd) {
            return DirFd.NIL;
        }

        pathname = null;

        LogUtil.logCautiously("Switching directories; fd: %d, useCachingWrapper: %s", dirFd, useCachingWrapper);

        int oldFd = DirFd.NIL;

        if (this.dirFd >= 0) {
            notificationHandler.removeCallbacksAndMessages(null);

            if (subscription != null) {
                subscription.close();
                subscription = null;
            }

            isStalled = false;

            iterator = null;

            oldFd = this.dirFd;
        }

        if (dirFd >= 0) {
            if (directory != null && (directory instanceof CrappyDirectory) != useCachingWrapper) {
                directory.close();
                directory = null;
            }

            if (oldFd >= 0) {
                try {
                    os.dup2(dirFd, oldFd);
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to switch descriptors", e);

                    return swapDirectoryDescriptor(DirFd.ERROR);
                }
            } else {
                this.dirFd = dirFd;
            }

            if (directory == null) {
                final Directory newDir = os.list(this.dirFd);

                directory = useCachingWrapper ? new CrappyDirectory(newDir) : newDir;
            }

            iterator = directory.iterator();

            ioFail = false;

            try {
                subscription = observer.subscribe(dirFd, notificationListener);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to add inotify watch", e);
            }

            try {
                // reset the directory descriptor offset in case it is not zero
                iterator.moveToPosition(-1);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to reset iterator position", e);
            }
        } else {
            if (directory != null) {
                directory.close();
                directory = null;
            }

            this.dirFd = dirFd;

            iterator = null;
        }

        advanceCount(dirFd >= 0 ? Integer.MAX_VALUE : 0);

        return oldFd;
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);

        this.recyclerView = recyclerView;

        localContext = recyclerView.getContext();
        inflater = LayoutInflater.from(localContext);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        inflater = null;
        localContext = null;

        this.recyclerView = null;

        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override
    public void onBindViewHolder(DirItemHolder holder, int position, List<Object> payloads) {
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public DirItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new DirItemHolder(inflater.inflate(R.layout.list_item_file, parent, false));
    }

    @Override
    public int getItemCount() {
        return count;
    }

    // must be called immediately after moveToPosition!
    private void checkCount() {
        int lastPosition = iterator.getPosition();

        if (lastPosition != -1 && !iterator.hasNext()) {
            // seems like we reached current directory end, update the count
            if (++lastPosition != count) {
                advanceCount(lastPosition);
            }
        }
    }

    public boolean isStalled() {
        return isStalled;
    }

    private boolean isStalled;

    private static final int MSG_COUNTED = 0;

    private enum EventType {
        COUNTED,
        RESET,
        ERROR,
    }

    private void noteError() {
        isStalled = true;

        dispatchEventDelayed(EventType.ERROR, 0);
    }

    private void advanceCount(int newCount) {
        isStalled = true;

        dispatchEventDelayed(EventType.COUNTED, 0, newCount);
    }

    private void setCount(int count) {
        this.count = count;

        if (recyclerView != null) {
            recyclerView.setVerticalScrollBarEnabled(count < Integer.MAX_VALUE);
        }

        notifyDataSetChanged();
    }

    @Override
    public void onBindViewHolder(DirItemHolder holder, int position) {
        if (dirFd >= 0) {
            try {
                final boolean hasAdvanced = iterator.moveToPosition(position);

                checkCount();

                if (hasAdvanced) {
                    ioFail = false;

                    holder.setFile(iterator);

                    holder.itemView.setOnLongClickListener(itemLongClickListener);
                    holder.itemView.setOnClickListener(itemClickListener);
                    holder.itemView.setOnTouchListener(itemTouchListener);

                    return;
                }
            } catch (IOException e) {
                Log.i(IO_ERR, "IO error during iteration", e);

                noteError();
            }
        }

        holder.clearHolder();
    }

    private final Random r = new SecureRandom();

    @Override
    public long getItemId(int position) {
        if (position == 0) {
            return 0L;
        }

        if (dirFd >= 0) {
            try {
                iterator.moveToPosition(position);

                checkCount();

                return directory.getOpaqueIndex(position);
            } catch (IOException e) {
                Log.i(IO_ERR, "IO error during iteration", e);

                noteError();
            }
        }

        return r.nextLong();
    }

    private final Handler notificationHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            dispatchEventDelayed(msg);
        }
    };

    private static final int DEBOUNCE_INTERVAL = (int) TimeUnit.MILLISECONDS.toNanos(520);

    private long lastEvent;

    void dispatchEventDelayed(Message event) {
        int arg = event.arg1, delay = event.arg2;

        dispatchEventDelayed((EventType) event.obj, delay, arg);
    }

    private void dispatchEventDelayed(EventType type, int debounceDelay) {
        dispatchEventDelayed(type, debounceDelay, 0);
    }

    private void dispatchEventDelayed(EventType type, int delay, int arg) {
        notificationHandler.removeCallbacksAndMessages(null);

        if (recyclerView != null && recyclerView.isComputingLayout()) {
            final Message m = Message.obtain(notificationHandler, MSG_COUNTED, arg, delay, type);
            m.sendToTarget();
            return;
        }

        final long currentTime = System.nanoTime();

        if (delay != 0) {
            final long diff = Math.abs(currentTime - lastEvent);

            if (diff < DEBOUNCE_INTERVAL) {
                final long extra = DEBOUNCE_INTERVAL - diff;

                final Message m = Message.obtain(notificationHandler, MSG_COUNTED, arg, delay, type);
                notificationHandler.sendMessageDelayed(m, TimeUnit.NANOSECONDS.toMillis(extra));
                return;
            }
        }

        lastEvent = currentTime;

        handleEvent(type, arg);
    }

    private void handleEvent(EventType type, int arg) {
        isStalled = false;

        switch (type) {
            case COUNTED:
                setCount(arg);

                break;
            case ERROR:
                handleIterationErrors();

                break;
            case RESET:
                refresh();
        }
    }

    private void handleIterationErrors() {
        try {
            if (!iterator.moveToPosition(-1)) {
                ioFail = true;
            }
        } catch (IOException e1) {
            ioFail = true;
        }

        if (ioFail) {
            @DirFd int oldFd = swapDirectoryDescriptor(DirFd.ERROR);
            if (oldFd >= 0) {
                os.dispose(oldFd);
            }
        } else {
            ioFail = true;

            notifyDataSetChanged();
        }
    }

    private final Inotify.InotifyListener notificationListener = new Inotify.InotifyListener() {
        @Override
        public void onChanges() {
            dispatchEventDelayed(EventType.RESET, DEBOUNCE_INTERVAL);
        }

        @Override
        public void onReset() {
            onChanges();
        }
    };

    @Override
    public void close() {
        swapDirectoryDescriptor(DirFd.NIL);
    }

    public void refresh() {
        if (iterator != null) {
            try {
                iterator.moveToPosition(-1);
            } catch (IOException e) {
                ioFail = true;
            }
        }

        setCount(Integer.MAX_VALUE);
    }

    private View.OnClickListener itemClickListener;
    private View.OnLongClickListener itemLongClickListener;
    private View.OnTouchListener itemTouchListener;

    public void setItemLongClickListener(View.OnClickListener itemClickListener) {
        this.itemClickListener = itemClickListener;
    }

    public void setItemClickListener(View.OnClickListener itemClickListener) {
        this.itemClickListener = itemClickListener;
    }

    public void setItemTouchListener(View.OnTouchListener itemTouchListener) {
        this.itemTouchListener = itemTouchListener;
    }
}
