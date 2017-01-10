package net.sf.fakenames.fddemo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.CursorLoader;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.RenderScript;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.system.Os;
import android.system.StructStat;
import android.system.StructStatVfs;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.sf.fakenames.fddemo.icons.IconFontDrawable;
import net.sf.fakenames.fddemo.icons.Icons;
import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fakenames.fddemo.view.DirFastScroller;
import net.sf.fakenames.fddemo.view.DirItemHolder;
import net.sf.fakenames.fddemo.view.DirLayoutManager;
import net.sf.fakenames.fddemo.view.FileMenuInfo;
import net.sf.fakenames.fddemo.view.NameInputFragment;
import net.sf.fakenames.fddemo.view.SaneDecor;
import net.sf.fakenames.syscallserver.Rooted;
import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.ErrnoException;
import net.sf.fdlib.FsType;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import butterknife.BindView;
import butterknife.OnClick;

public class MainActivity extends BaseActivity implements
        View.OnClickListener,
        MenuItem.OnMenuItemClickListener,
        NameInputFragment.FileNameReceiver,
        PopupMenu.OnMenuItemClickListener {
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

    @BindView(R.id.act_main_btn_append)
    View button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.file_manager);

        final OS unpriv;
        final DirAdapter adapter;

        OS os;

        state = getLastNonConfigurationInstance();

        if (state == null) {
            try {
                unpriv = OS.getInstance();
            } catch (IOException e) {
                Toast.makeText(this, "failed to initialize native libraries, exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            try {
                os = Rooted.createWithChecks(this);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to acquire root access, using unprivileged fallback", e);

                os  = unpriv;
            }

            try {
                state = GuardedState.create(os, this);
            } catch (IOException e) {
                Toast.makeText(this, "failed to create inotify descriptor, exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            final File home = state.layout.getHome();

            @DirFd int directory;
            try {
                directory = unpriv.opendir(home.getPath(), OS.O_RDONLY, 0);
            } catch (IOException e) {
                Toast.makeText(this, "failed to open " + home.getPath() + ", exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            adapter = state.adapter;

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

        quickScroller.setRecyclerView(directoryList);

        adapter.setItemClickListener(this);
        adapter.registerAdapterDataObserver(scrollerObserver);
        adapter.registerAdapterDataObserver(dirObserver);

        registerForContextMenu(directoryList);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @OnClick(R.id.act_main_btn_append)
    void appendClicked() {
        final PopupMenu menu = new PopupMenu(this, button);
        menu.inflate(R.menu.menu_main);
        menu.setOnMenuItemClickListener(this);
        menu.show();
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
        unregisterForContextMenu(directoryList);

        if (state != null) {
            final DirAdapter adapter = state.adapter;

            if (scrollerObserver != null) {
                adapter.unregisterAdapterDataObserver(scrollerObserver);
            }
            if (dirObserver != null) {
                adapter.unregisterAdapterDataObserver(dirObserver);
            }

            adapter.setItemClickListener(null);

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
            opendir(state.adapter.getFd(), dirInfo.name);
        }
    }

    private void openHomeDir() {
        opendir(DirFd.NIL, state.layout.getHome().getAbsolutePath());
    }

    private void opendir(@DirFd int base, String path) {
        @DirFd int newFd = DirFd.NIL, prev = DirFd.NIL;
        try {
            newFd = state.os.opendirat(base, path, OS.O_RDONLY, 0);

            final Stat stat = state.os.fstat(newFd);

            if (stat.type != FsType.DIRECTORY) {
                return;
            }

            final MountInfo.Mount m = state.layout.getFs(stat.st_dev);

            final boolean canUseTellDir = m != null && BaseDirLayout.isRewindSafe(m.fstype);

            prev = state.adapter.swapDirectoryDescriptor(newFd, !canUseTellDir);

            layoutManager.scrollToPosition(0);

            directoryList.setItemAnimator(null);

            directoryList.swapAdapter(state.adapter, true);
        } catch (ErrnoException e) {
            LogUtil.logCautiously("Unable to open " + path + ", ignoring", e);

            if (e.code() != ErrnoException.ENOTDIR) {
                toast("Unable to open a directory. " + e.getMessage());
            }
        } catch (IOException e) {
            LogUtil.logCautiously("Unable to open " + path + ", ignoring", e);

            toast("Unable to open a directory. " + e.getMessage());
        } finally {
            if (newFd != DirFd.NIL && prev != DirFd.NIL) {
                state.os.dispose(newFd);
            }
        }
    }

    private Toast toast;

    private void toast(String message) {
        if (toast != null) {
            toast.cancel();
        }

        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT);

        toast.show();
    }

    private void restoreDecor() {
        directoryList.setItemAnimator(animator);
    }

    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (!(menuInfo instanceof FileMenuInfo)) {
            return;
        }

        FileMenuInfo info = (FileMenuInfo) menuInfo;

        final MenuItem bufferItem = menu.add(Menu.NONE, R.id.menu_copy_path, Menu.CATEGORY_ALTERNATIVE, "Copy full path");
        bufferItem.setOnMenuItemClickListener(this);

        final MenuItem delItem = menu.add(Menu.NONE, R.id.menu_item_delete, Menu.CATEGORY_ALTERNATIVE, "Delete");
        delItem.setOnMenuItemClickListener(this);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        FileMenuInfo info = (FileMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case R.id.menu_item_delete:

                try {
                    state.os.unlinkat(info.parentDir, info.fileInfo.name,
                            info.fileInfo.type == FsType.DIRECTORY ? OS.AT_REMOVEDIR : 0);
                } catch (IOException e) {
                    toast("Unable to perform removal. " + e.getMessage());
                }
                break;
            case R.id.menu_copy_path:
                try {
                    final String path = state.os.readlinkat(state.adapter.getFd(), info.fileInfo.name);

                    ClipboardManager cpm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

                    ClipData data = ClipData.newPlainText("Selected File Path", path);

                    cpm.setPrimaryClip(data);
                } catch (IOException e) {
                    toast("Unable to resolve full path. "  + e.getMessage());
                }
                break;
            case R.id.menu_fifo:
                showCreateNewDialog(getString(R.string.fifo), item.getItemId());
                break;
            case R.id.menu_dir:
                showCreateNewDialog(getString(R.string.directory).toLowerCase(), item.getItemId());
                break;
            case R.id.menu_file:
                showCreateNewDialog(getString(R.string.file).toLowerCase(), item.getItemId());
                break;
            case R.id.menu_socket:
                showCreateNewDialog(getString(R.string.socket).toLowerCase(), item.getItemId());
                break;
        }

        return true;
    }

    private void showCreateNewDialog(String name, int type) {
        new NameInputFragment(name, type).show(getFragmentManager(), null);
    }

    @Override
    public void onNameChosen(String name, int type) {
        @OS.FileTypeFlag int fileType;

        switch (type) {
            case R.id.menu_fifo:
                fileType = OS.S_IFIFO;
                break;
            case R.id.menu_socket:
                fileType = OS.S_IFSOCK;
                break;
            case R.id.menu_dir:
                try {
                    state.os.mkdirat(state.adapter.getFd(), name, 0);
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to create a directory", e);

                    toast("Unable to create a directory. " + e.getMessage());
                }
                return;
            case R.id.menu_file:
            default:
                fileType = OS.S_IFREG;
                break;
        }

        try {
            state.os.mknodat(state.adapter.getFd(), name, fileType, 0);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create a file", e);

            toast("Unable to create a file. " + e.getMessage());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_bar, menu);

        MenuItem home = menu.findItem(R.id.menu_home);

        Drawable icon = new IconFontDrawable(this, Icons.HOME)
                .color(Color.WHITE)
                .actionBar();

        home.setIcon(icon);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_home:
                openHomeDir();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private final class DirObserver extends RecyclerView.AdapterDataObserver implements DirLayoutManager.OnLayoutCallback {
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
            final int knownCount = state.adapter.getItemCount();

            int visibility;

            if (knownCount != Integer.MAX_VALUE && visibleViews < knownCount) {
                visibility = View.VISIBLE;
            } else {
                visibility = View.GONE;
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
