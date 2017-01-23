package net.sf.fakenames.fddemo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.sf.fakenames.fddemo.icons.IconFontDrawable;
import net.sf.fakenames.fddemo.icons.Icons;
import net.sf.fakenames.fddemo.provider.FileProvider;
import net.sf.fakenames.fddemo.provider.PublicProvider;
import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fakenames.fddemo.view.DirFastScroller;
import net.sf.fakenames.fddemo.view.DirItemHolder;
import net.sf.fakenames.fddemo.view.DirLayoutManager;
import net.sf.fakenames.fddemo.view.FileMenuInfo;
import net.sf.fakenames.fddemo.view.NameInputFragment;
import net.sf.fakenames.fddemo.view.RenameNameInputFragment;
import net.sf.fakenames.fddemo.view.SaneDecor;
import net.sf.fdlib.DirFd;
import net.sf.fdlib.Directory;
import net.sf.fdlib.ErrnoException;
import net.sf.fdlib.FsType;
import net.sf.fdlib.LogUtil;
import net.sf.fdlib.MountInfo;
import net.sf.fdlib.OS;
import net.sf.fdlib.Stat;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import butterknife.BindView;
import butterknife.OnClick;

public class MainActivity extends BaseActivity implements
        View.OnClickListener,
        MenuItem.OnMenuItemClickListener,
        NameInputFragment.FileNameReceiver,
        RenameNameInputFragment.FileNameReceiver,
        PopupMenu.OnMenuItemClickListener {
    private final RecyclerView.ItemAnimator animator = new DefaultItemAnimator();

    private DirObserver dirObserver;
    private RecyclerView.AdapterDataObserver scrollerObserver;

    private RecyclerView.ItemDecoration decoration;

    private GuardedState state;
    private RecyclerView.LayoutManager layoutManager;

    @BindView(R.id.contentPanel)
    ViewGroup content;

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
                os = RootSingleton.get(this);
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            getWindow().setBackgroundDrawable(null);
        }
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

        if (dirInfo.type != null && dirInfo.type.isNotDir()) {
            openfile(state.adapter.getFd(), dirInfo.name);
        } else {
            opendir(state.adapter.getFd(), dirInfo.name);
        }
    }

    private void openHomeDir() {
        opendir(DirFd.NIL, state.layout.getHome().getAbsolutePath());
    }

    private void opendir(@DirFd int base, String path) {
        int newFd = DirFd.NIL, prev = DirFd.NIL;
        try {
            newFd = state.os.opendirat(base, path, OS.O_RDONLY, 0);

            final Stat stat = new Stat();

            state.os.fstat(newFd, stat);

            if (stat.type != FsType.DIRECTORY) {
                openfile(base, path);
                return;
            }

            final MountInfo.Mount m = state.layout.getFs(stat.st_dev);

            final boolean canUseTellDir = m != null && BaseDirLayout.isPosix(m.fstype);

            prev = state.adapter.swapDirectoryDescriptor(newFd, !canUseTellDir);

            layoutManager.scrollToPosition(0);

            directoryList.setItemAnimator(null);

            directoryList.swapAdapter(state.adapter, true);
        } catch (ErrnoException e) {
            LogUtil.logCautiously("Unable to open " + path + ", ignoring", e);

            if (e.code() != ErrnoException.ENOTDIR) {
                toast("Unable to open a directory. " + e.getMessage());
            } else {
                openfile(base, path);
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

    private void openfile(@DirFd int base, String path) {
        try {
            final String resolved = state.os.readlinkat(base, path);

            final Uri uri = PublicProvider.publicUri(this, resolved);

            final Intent view = new Intent(Intent.ACTION_VIEW, uri);

            startActivity(view);
        } catch (Throwable e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void editfile(int parentDir, String name) {
        try {
            final String resolved = state.os.readlinkat(parentDir, name);

            final Uri uri = PublicProvider.publicUri(this, resolved);

            final Intent view = new Intent(Intent.ACTION_EDIT, uri);

            startActivity(view);
        } catch (Throwable e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void sharefile(int parentDir, String name) {
        try {
            final String resolved = state.os.readlinkat(parentDir, name);

            final Uri uri = PublicProvider.publicUri(this, resolved);

            if (uri == null) {
                toast("Failed to generate shareable uri");
                return;
            }

            String type = getContentResolver().getType(uri);
            if (type == null) {
                type = FileProvider.DEFAULT_MIME;
            }

            final Intent view = new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setType(type);

            startActivity(view);
        } catch (Throwable e) {
            toast("Error: " + e.getMessage());
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

        final MenuItem renameItem = menu.add(Menu.NONE, R.id.menu_item_rename, Menu.CATEGORY_ALTERNATIVE, "Rename");
        renameItem.setOnMenuItemClickListener(this);

        final MenuItem delItem = menu.add(Menu.NONE, R.id.menu_item_delete, Menu.CATEGORY_ALTERNATIVE, "Delete");
        delItem.setOnMenuItemClickListener(this);

        if (info.fileInfo.type != null && info.fileInfo.type.isNotDir()) {
            final MenuItem copyItem = menu.add(Menu.NONE, R.id.menu_item_copy, Menu.CATEGORY_ALTERNATIVE, "Copy");
            copyItem.setOnMenuItemClickListener(this);

            final MenuItem editItem = menu.add(Menu.NONE, R.id.menu_item_edit, Menu.CATEGORY_ALTERNATIVE, "Edit");
            editItem.setOnMenuItemClickListener(this);

            final MenuItem shareItem = menu.add(Menu.NONE, R.id.menu_item_share, Menu.CATEGORY_ALTERNATIVE, "Share");
            shareItem.setOnMenuItemClickListener(this);
        }
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
            case R.id.menu_item_copy:
                try {
                    final String path = state.os.readlinkat(state.adapter.getFd(), info.fileInfo.name);

                    final Uri uri = PublicProvider.publicUri(this, path);

                    if (uri == null) {
                        toast("Failed to generate shareable uri");
                        return true;
                    }

                    ClipboardManager cpm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

                    ClipData data = ClipData.newRawUri(info.fileInfo.name, uri);

                    cpm.setPrimaryClip(data);
                } catch (IOException e) {
                    toast("Unable to resolve full path. "  + e.getMessage());
                }
                break;
            case R.id.menu_item_edit:
                editfile(info.parentDir, info.fileInfo.name);
                break;
            case R.id.menu_item_share:
                sharefile(info.parentDir, info.fileInfo.name);
                break;
            case R.id.menu_item_rename:
                showRenameDialog(info.fileInfo.name);
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

    private AsyncTask<ParcelFileDescriptor, ?, String> asyncTask;

    private void pasteFile(FileObject sourceFile) throws IOException {
        if (asyncTask != null) {
            toast("Unable to preform a copy: busy");
            return;
        }

        final OS os = state.os;

        final Context context = getApplicationContext();

        final ParcelFileDescriptor targetDir = ParcelFileDescriptor.fromFd(state.adapter.getFd());

        final @DirFd int dir = targetDir.getFd();

        final Stat stat = new Stat();

        os.fstat(dir, stat);

        final MountInfo.Mount m = state.layout.getFs(stat.st_dev);

        final boolean canUseExtChars = m != null && BaseDirLayout.isPosix(m.fstype);

        asyncTask = new AsyncTask<ParcelFileDescriptor, Void, String>() {
            @Override
            protected String doInBackground(ParcelFileDescriptor... params) {
                String created = null;
                boolean copied = false;
                try (Closeable fd = params[0]; Closeable c = sourceFile) {
                    final String desc = sourceFile.getDescription();

                    final String fileName = canUseExtChars
                            ? FilenameUtil.sanitize(desc)
                            : FilenameUtil.sanitizeCompat(desc);

                    os.mknodat(dir, fileName, OS.S_IFREG | OS.DEF_FILE_MODE, 0);

                    created = os.readlinkat(dir, fileName);

                    try (FileObject targetFile = FileObject.fromFile(os, context, new File(created))) {
                        copied = sourceFile.copyTo(targetFile);

                        return copied ? null : "Copy failed";
                    }
                } catch (Throwable t) {
                    t.printStackTrace();

                    final String result = t.getMessage();

                    return result == null ? t.getClass().getSimpleName() : result;
                } finally {
                    if (!copied && created != null) {
                        try {
                            os.unlinkat(DirFd.NIL, created, 0);
                        } catch (IOException ioe) {
                            LogUtil.logCautiously("Failed to remove target file", ioe);
                        }
                    }
                }
            }

            @Override
            protected void onPostExecute(String s) {
                asyncTask = null;

                toast(s == null ? "Copy complete" : s);
            }

            @Override
            protected void onCancelled() {
                asyncTask = null;

                try {
                    sourceFile.close();
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to cancel a task", e);
                }
            }
        };

        //noinspection unchecked
        asyncTask.execute(targetDir);
    }

    private void showRenameDialog(String name) {
        new RenameNameInputFragment(name).show(getFragmentManager(), null);
    }

    private void showCreateNewDialog(String name, int type) {
        new NameInputFragment(name, type).show(getFragmentManager(), null);
    }

    @Override
    public void onNewNameChosen(String oldName, String newName) {
        if (oldName.equals(newName)) return;

        final @DirFd int dirFd = state.adapter.getFd();
        try {
            state.os.renameat(dirFd, oldName, dirFd, newName);
        } catch (IOException e) {
            toast("Failed to rename " + oldName + ". " + e.getMessage());
        }
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
                    state.os.mkdirat(state.adapter.getFd(), name, OS.DEF_DIR_MODE);
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
            state.os.mknodat(state.adapter.getFd(), name, fileType | OS.DEF_FILE_MODE, 0);
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

        MenuItem paste = menu.findItem(R.id.menu_paste);

        Drawable pasteIcon = new IconFontDrawable(this, Icons.PASTE)
                .color(Color.WHITE)
                .actionBar();

        paste.setIcon(pasteIcon);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_home:
                openHomeDir();
                return true;
            case R.id.menu_paste:
                ClipboardManager cpm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

                ClipData clip = cpm.getPrimaryClip();

                if (clip == null || clip.getItemCount() == 0) {
                    toast("The clipboard is empty");
                    return true;
                }

                if (clip.getItemCount() > 1) {
                    toast("Multi-item paste is not supported yet");
                    return true;
                }

                FileObject fileObject = FileObject.fromClip(state.os, getApplicationContext(), clip);
                if (fileObject == null) {
                    toast("Unsupported data type");
                }

                try {
                    pasteFile(fileObject);
                } catch (IOException e) {
                    toast(e.getMessage());
                }

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
