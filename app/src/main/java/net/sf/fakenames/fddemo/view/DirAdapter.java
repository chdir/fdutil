package net.sf.fakenames.fddemo.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import net.sf.fakenames.fddemo.CrappyDirectory;
import net.sf.fakenames.fddemo.R;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.Inotify;
import net.sf.fdlib.InotifyFd;
import net.sf.fdlib.InotifyWatch;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.OS;
import net.sf.fdlib.UnreliableIterator;
import net.sf.fdlib.WrappedIOException;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

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

    private int count = Integer.MAX_VALUE;

    private final OS os;

    public DirAdapter(OS os, Inotify observer) {
        this.os = os;
        this.observer = observer;

        setHasStableIds(true);
    }

    public @DirFd int getFd() {
        return dirFd;
    }

    public @DirFd int swapDirectoryDescriptor(@DirFd int dirFd) {
        if (this.dirFd == dirFd) {
            return DirFd.NIL;
        }

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
            if (directory != null) {
                try {
                    os.dup2(dirFd, oldFd);
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to switch descriptors", e);

                    return swapDirectoryDescriptor(DirFd.ERROR);
                }
            } else {
                directory = new CrappyDirectory(os.list(dirFd));

                this.dirFd = dirFd;
            }

            iterator = directory.iterator();

            ioFail = false;

            try {
                subscription = observer.subscribe(dirFd, notificationListener);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to add inotify watch", e);
            }
        } else {
            if (directory != null) {
                directory.close();
                directory = null;
            }

            iterator = null;
        }

        setCount(Integer.MAX_VALUE);

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
        return dirFd > 0 ? count : 0;
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

    private void advanceCount(int newCount) {
        if (recyclerView.isComputingLayout()) {
            isStalled = true;

            notificationHandler.removeMessages(MSG_COUNTED);

            final Message m = Message.obtain(notificationHandler, MSG_COUNTED, newCount, 0);

            m.sendToTarget();
        } else {
            setCount(newCount);
        }
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

                    return;
                }
            } catch (IOException e) {
                Log.i(IO_ERR, "IO error during iteration", e);

                handleIterationErrors();
            }
        }

        holder.clearHolder();
    }

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

                handleIterationErrors();
            }
        }

        return -1L;
    }

    private final Handler notificationHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            isStalled = false;

            setCount(msg.arg1);
        }
    };

    private void handleIterationErrors() {
        try {
            if (!iterator.moveToPosition(-1)) {
                ioFail = true;
            }
        } catch (IOException e1) {
            ioFail = true;
        }

        if (ioFail) {
            swapDirectoryDescriptor(DirFd.ERROR);
        } else {
            ioFail = true;
        }

        notificationHandler.post(this::notifyDataSetChanged);
    }

    private final Inotify.InotifyListener notificationListener = new Inotify.InotifyListener() {
        @Override
        public void onChanges() {
            refresh();
        }

        @Override
        public void onReset() {
            swapDirectoryDescriptor(DirFd.NIL);
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

    public void setItemClickListener(View.OnClickListener itemClickListener) {
        this.itemClickListener = itemClickListener;
    }

    public void setItemLongClickListener(View.OnLongClickListener itemLongClickListener) {
        this.itemLongClickListener = itemLongClickListener;
    }
}
