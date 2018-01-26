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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.predicates.ObjectPredicate;

import net.sf.fakenames.fddemo.R;
import net.sf.xfd.CrappyDirectory;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.Inotify;
import net.sf.xfd.InotifyWatch;
import net.sf.xfd.LogUtil;
import net.sf.xfd.NativeBits;
import net.sf.xfd.OS;
import net.sf.xfd.UnreliableIterator;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static net.sf.xfd.NativeBits.O_NOCTTY;
import static net.sf.xfd.NativeBits.O_NONBLOCK;
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

    private LongObjectHashMap<SelectionItem> selection = new LongObjectHashMap<>();

    private SelectionCallback selectionCallback;
    private Directory directory;
    private UnreliableIterator<Directory.Entry> iterator;
    private boolean ioFail;

    private int count = 0;

    private final OS os;

    public DirAdapter(OS os, Inotify selfInotify) {
        this.os = os;
        this.observer = selfInotify;

        setHasStableIds(true);
    }

    public int getSelectedCount() {
        return selection.size();
    }

    public void toggleSelection(int position) {
        long id = getOpaqueIndex(position);

        if (selection.containsKey(id)) {
            removeSelectedItem(id);
        } else {
            addSelectedItem(id);
        }

        notifyItemChanged(position);
    }

    private void removeSelectedItem(long id) {
        if (getSelectedCount() == 1) {
            dispatchSelectionCleared();
        }

        SelectionItem removed = selection.remove(id);

        releaseSelection(removed);
    }

    private void addSelectedItem(long id) {
        if (getSelectedCount() == 0) {
            dispatchSelectionStarted();
        }

        SelectionItem item = new SelectionItem();

        iterator.get(item);

        subscribeToSelection(item);

        selection.put(id, item);
    }

    private void destroySelection() {
        dispatchSelectionCleared();

        forEachSelected(clearSelection);

        selection.clear();

        notifyDataSetChanged();
    }

    private void subscribeToSelection(SelectionItem item) {
        try {
            int fd = os.openat(dirFd, item.name, O_NOCTTY | O_NONBLOCK, 0);
            try {
                item.watch = observer.subscribe(fd, selectionCleanup);
            } finally {
                os.dispose(fd);
            }
        } catch (IOException err) {
            LogUtil.logCautiously("Inotify on selected item failed", err);
        }
    }

    private void releaseSelection(SelectionItem removed) {
        if (removed == null || removed.watch == null) return;

        removed.watch.close();
    }

    private void dispatchSelectionCleared() {
        if (selectionCallback != null) {
            selectionCallback.onSelectionCleared();
        }
    }

    private void dispatchSelectionStarted() {
        if (selectionCallback != null) {
            selectionCallback.onSelectionStarted();
        }
    }

    private SelectionPredicate<SelectionItem, RuntimeException> clearSelection = value -> {
        releaseSelection(value);

        return true;
    };

    public <T extends Throwable> void forEachSelected(SelectionPredicate<? super SelectionItem, T> callback) throws T {
        if (selection.isEmpty()) return;

        final long[] keys = selection.keys;
        final int size = selection.size();

        for (int i = 0, seen = 0; seen < size; ++i) {
            if (keys[i] == 0) continue;

            ++seen;

            if (!callback.apply(selection.indexGet(i))) {
                return;
            }
        }
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

            pendingMessage = 0;

            iterator = null;

            oldFd = this.dirFd;

            selection.clear();
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
        return pendingMessage != 0;
    }

    private static final int EV_ERROR    = 1;
    private static final int EV_RESET    = 1 << 1;
    private static final int EV_DESELECT = 1 << 2;
    private static final int EV_COUNTED  = 1 << 3;

    @IntDef(value = { EV_ERROR, EV_RESET, EV_COUNTED, EV_DESELECT }, flag = true)
    @interface EventType {}

    private void noteError() {
        dispatchEventDelayed(EV_ERROR, 0);
    }

    private void advanceCount(int newCount) {
        dispatchEventDelayed(EV_COUNTED, 0, newCount);
    }

    public void clearSelection() {
        dispatchEventDelayed(EV_DESELECT, 0);
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

                    long opaqueIdx = directory.getOpaqueIndex(position);
                    SelectionItem selectionData = selection.get(opaqueIdx);

                    if (selectionData != null) {
                        selectionData.position = position;

                        holder.itemView.setSelected(true);
                    } else {
                        holder.itemView.setSelected(false);
                    }

                    return;
                }
            } catch (IOException e) {
                Log.i(IO_ERR, "IO error during iteration", e);

                noteError();
            }
        }

        holder.clearHolder();
    }

    private long getOpaqueIndex(int position) {
        try {
            iterator.moveToPosition(position);

            checkCount();

            return directory.getOpaqueIndex(position);
        } catch (IOException e) {
            Log.i(IO_ERR, "IO error during iteration", e);

            noteError();

            return -1;
        }
    }

    private final Random r = new SecureRandom();

    @Override
    public long getItemId(int position) {
        if (position == 0) {
            return 0L;
        }

        if (dirFd >= 0) {
            long result = getOpaqueIndex(position);

            if (result != 0L) {
                return position;
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

    private static final int DEBOUNCE_INTERVAL = 520;

    private long lastEvent;

    private int pendingMessage;

    void dispatchEventDelayed(Message event) {
        int arg = event.arg1, delay = event.arg2;

        dispatchEventDelayed(event.what, delay, arg);
    }

    private void dispatchEventDelayed(@EventType int type, int debounceDelay) {
        dispatchEventDelayed(type, debounceDelay, 0);
    }

    private void dispatchEventDelayed(@EventType int type, int delay, int arg) {
        int msg = pendingMessage;

        if (msg != 0) {
            notificationHandler.removeMessages(msg);
        }

        msg |= type;

        pendingMessage = msg;

        if (recyclerView != null && recyclerView.isComputingLayout()) {
            final Message m = Message.obtain(notificationHandler, msg, arg, delay);
            m.sendToTarget();
            return;
        }

        final long currentTime = SystemClock.uptimeMillis();

        if (delay != 0) {
            final long diff = Math.abs(currentTime - lastEvent);

            if (diff < DEBOUNCE_INTERVAL) {
                final long extra = DEBOUNCE_INTERVAL - diff;

                final Message m = Message.obtain(notificationHandler, msg, arg, delay, type);
                notificationHandler.sendMessageAtTime(m, currentTime + extra);
                return;
            }
        }

        lastEvent = currentTime;

        handleEvent(type, arg);
    }

    private void handleEvent(@EventType int type, int arg) {
        pendingMessage = 0;

        if ((type & EV_DESELECT) != 0) {
            destroySelection();
        }

        if ((type & EV_ERROR) != 0) {
            handleIterationErrors();
        }

        if ((type & EV_RESET) != 0) {
            refresh();

            // the last count is no longer valid
            return;
        }

        if ((type & EV_COUNTED) != 0) {
            setCount(arg);
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
            dispatchEventDelayed(EV_RESET, DEBOUNCE_INTERVAL);
        }

        @Override
        public void onReset() {
            // queue overflow: resubscribe to the updates
            packState();
            recoverState();

            onChanges();
        }
    };

    private final Inotify.InotifyListener selectionCleanup = new Inotify.InotifyListener() {
        @Override
        public void onChanges() {
        }

        @Override
        public void onReset() {
            // one of selected items was removed: remove all selection
            clearSelection();
        }
    };

    @Override
    public void close() {
        swapDirectoryDescriptor(DirFd.NIL);
    }

    private void refresh() {
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

    public void setSelectionListener(SelectionCallback selectionCallback) {
        this.selectionCallback = selectionCallback;
    }

    public final class SelectionItem extends Directory.Entry {
        int position = RecyclerView.NO_POSITION;

        InotifyWatch watch = null;
    }

    public interface SelectionCallback {
        void onSelectionStarted();

        void onSelectionCleared();
    }

    public interface SelectionPredicate<E, T extends Throwable> {
        boolean apply(E item) throws T;
    }
}
