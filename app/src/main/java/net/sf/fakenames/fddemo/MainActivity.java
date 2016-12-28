package net.sf.fakenames.fddemo;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.system.Os;
import android.system.StructStat;
import android.system.StructStatVfs;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fakenames.fddemo.view.DirFastScroller;
import net.sf.fakenames.fddemo.view.DirItemHolder;
import net.sf.fakenames.fddemo.view.DirLayoutManager;
import net.sf.fakenames.fddemo.view.SaneDecor;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.FsType;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.OS;

import java.io.File;
import java.io.IOException;

import butterknife.BindView;

public class MainActivity extends BaseActivity implements View.OnClickListener, View.OnLongClickListener {
    private final RecyclerView.ItemAnimator animator = new DefaultItemAnimator();

    private DirObserver dirObserver;
    private RecyclerView.AdapterDataObserver scrollerObserver;

    private RecyclerView.ItemDecoration decoration;

    private GuardedState state;
    private RecyclerView.LayoutManager layoutManager;

    @BindView(R.id.act_main_dirList)
    RecyclerView directoryList;

    @BindView(R.id.act_main_quick_scroll)
    DirFastScroller quickScroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.file_manager);

        final OS os;
        final DirAdapter adapter;

        state = getLastNonConfigurationInstance();

        if (state == null) {
            try {
                os = OS.getInstance();
            } catch (IOException e) {
                Toast.makeText(this, "failed to initialize native libraries, exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            try {
                state = GuardedState.create(os);
            } catch (IOException e) {
                Toast.makeText(this, "failed to create inotify descriptor, exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            final File file = getDir("testDir", Context.MODE_PRIVATE);
            final String path = file.getPath();

            @DirFd int directory;
            try {
                directory = os.opendir(path, OS.O_RDONLY, 0);
            } catch (IOException e) {
                Toast.makeText(this, "failed to open " + path + ", exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            adapter = state.adapter;

            adapter.setItemClickListener(this);
            adapter.setItemLongClickListener(this);
            adapter.swapDirectoryDescriptor(directory);
        } else {
            os = state.os;
            adapter = state.adapter;
        }

        dirObserver = new DirObserver();
        scrollerObserver = quickScroller.getAdapterDataObserver();

        DirLayoutManager layoutManager = new DirLayoutManager(this);
        layoutManager.setCallback(dirObserver);
        this.layoutManager = layoutManager;

        decoration = new SaneDecor(this, LinearLayout.VERTICAL);

        directoryList.setAdapter(adapter);
        directoryList.setItemAnimator(animator);
        directoryList.addItemDecoration(decoration);
        directoryList.setLayoutManager(layoutManager);
        directoryList.addOnScrollListener(quickScroller.getOnScrollListener());
        directoryList.setHasFixedSize(true);

        adapter.registerAdapterDataObserver(scrollerObserver);
        adapter.registerAdapterDataObserver(dirObserver);

        quickScroller.setRecyclerView(directoryList);
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        return state;
    }

    @Nullable
    @Override
    @SuppressWarnings("deprecation")
    public GuardedState getLastNonConfigurationInstance() {
        return (GuardedState) super.getLastNonConfigurationInstance();
    }

    @Override
    protected void onDestroy() {
        if (state != null) {
            final DirAdapter adapter = state.adapter;

            if (scrollerObserver != null) {
                adapter.unregisterAdapterDataObserver(scrollerObserver);
            }
            if (dirObserver != null) {
                adapter.unregisterAdapterDataObserver(dirObserver);
            }

            adapter.setItemLongClickListener(null);
            adapter.setItemLongClickListener(null);

            if (!isChangingConfigurations()) {
                state.close();
            }
        }

        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        final DirItemHolder dirItemHolder = (DirItemHolder) directoryList.getChildViewHolder(v);

        final Directory.Entry dirInfo = dirItemHolder.getDirInfo();

        if (dirInfo.type == null || !dirInfo.type.isNotDir()) {
            @DirFd int newFd = DirFd.NIL, prev = DirFd.NIL;
            try {
                newFd = state.os.opendirat(state.adapter.getFd(), dirInfo.name, OS.O_RDONLY, 0);

                prev = state.adapter.swapDirectoryDescriptor(newFd);

                layoutManager.scrollToPosition(0);

                directoryList.setItemAnimator(null);

                directoryList.swapAdapter(state.adapter, true);
            } catch (IOException e) {
                LogUtil.logCautiously("Unable to open directory, ignoring", e);
            } finally {
                if (newFd != DirFd.NIL && prev != DirFd.NIL) {
                    state.os.dispose(newFd);
                }
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        return false;
    }

    private void restoreDecor() {
        directoryList.setItemAnimator(animator);
        //directoryList.addItemDecoration(decoration);
    }

    private Handler handler = new Handler(Looper.getMainLooper());

    private class DirObserver extends RecyclerView.AdapterDataObserver implements DirLayoutManager.OnLayoutCallback {
        private int visibleViewsMax;
        private boolean waitForLayout;

        public void onChanged() {
            waitForLayout = true;
        }

        public void onItemRangeChanged(int positionStart, int itemCount) {
            waitForLayout = true;
        }

        public void onItemRangeInserted(int positionStart, int itemCount) {
            waitForLayout = true;
        }

        public void onItemRangeRemoved(int positionStart, int itemCount) {
            waitForLayout = true;
        }

        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            waitForLayout = true;
        }

        @Override
        public void onLaidOut(RecyclerView.LayoutManager dirLayoutManager) {
            RecyclerView.RecycledViewPool pool = directoryList.getRecycledViewPool();

            final int visibleViews = dirLayoutManager.getChildCount();

            int visibility;

            if (dirLayoutManager.getChildCount() >= state.adapter.getItemCount()) {
                visibility = View.GONE;
            } else {
                visibility = View.VISIBLE;
            }

            if (quickScroller.getVisibility() != visibility) {
                quickScroller.setVisibility(visibility);
            }

            if (!waitForLayout) {
                return;
            }

            waitForLayout = false;

            if (visibleViews > 0 && directoryList.getItemAnimator() == null) {
                handler.removeCallbacksAndMessages(null);

                if (directoryList.isComputingLayout()) {
                    handler.post(MainActivity.this::restoreDecor);
                } else {
                    restoreDecor();
                }
            }

            final int visibleViewsNew = (int) (visibleViews * 1.2);

            if (visibleViewsNew > visibleViewsMax) {
                visibleViewsMax = visibleViewsNew;

                pool.setMaxRecycledViews(0, visibleViewsNew);
            }
        }
    }
}
