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
package net.sf.fakenames.fddemo;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;

import net.sf.fakenames.fddemo.icons.IconFontDrawable;
import net.sf.fakenames.fddemo.icons.Icons;
import net.sf.fakenames.fddemo.util.Utils;
import net.sf.fakenames.fddemo.view.ConfirmationDialog;
import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fakenames.fddemo.view.DirFastScroller;
import net.sf.fakenames.fddemo.view.DirItemHolder;
import net.sf.fakenames.fddemo.view.DirLayoutManager;
import net.sf.fakenames.fddemo.view.FileMenuInfo;
import net.sf.fakenames.fddemo.view.NameInputFragment;
import net.sf.fakenames.fddemo.view.NewLinkInputFragment;
import net.sf.fakenames.fddemo.view.RenameNameInputFragment;
import net.sf.fakenames.fddemo.view.SaneDecor;
import net.sf.fakenames.fddemo.view.ShortcutNameInputFragment;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.FsType;
import net.sf.xfd.Limit;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.NativeBits;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.provider.ProviderBase;
import net.sf.xfd.provider.PublicProvider;
import net.sf.xfd.provider.RootSingleton;

import java.io.File;
import java.io.IOException;
import java.util.List;

import butterknife.Bind;
import butterknife.BindColor;
import butterknife.BindString;
import butterknife.OnClick;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static net.sf.xfd.provider.ProviderBase.DEFAULT_MIME;
import static net.sf.xfd.provider.ProviderBase.fdPath;
import static net.sf.xfd.provider.ProviderBase.isPosix;

public class MainActivity extends BaseActivity implements
        MenuItem.OnMenuItemClickListener,
        NameInputFragment.FileNameReceiver,
        NewLinkInputFragment.LinkNameReceiver,
        RenameNameInputFragment.FileNameReceiver,
        ConfirmationDialog.ConfirmationReceiver,
        ShortcutNameInputFragment.ShortcutNameReceiver,
        ClipboardManager.OnPrimaryClipChangedListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        PopupMenu.OnMenuItemClickListener {
    static final String ACTION_MOVE = "net.sf.chdir.action.MOVE";
    static final String ACTION_COPY = "net.sf.chdir.action.COPY";
    static final String ACTION_CANCEL = "net.sf.chdir.action.CANCEL";
    static final String EXTRA_NOT_DIR = "net.sf.chdir.extra.IS_FILE";
    static final String EXTRA_DIR_FS = "net.sf.chdir.extra.FS_ID";

    private ClipboardManager cbm;

    private final RecyclerView.ItemAnimator animator = new DefaultItemAnimator();

    private DirObserver dirObserver;
    private RecyclerView.AdapterDataObserver scrollerObserver;

    private RecyclerView.ItemDecoration decoration;

    private GuardedState state;
    private RecyclerView.LayoutManager layoutManager;

    @Bind(R.id.contentPanel)
    ViewGroup content;

    @Bind(R.id.act_main_dirList)
    RecyclerView directoryList;

    @Bind(R.id.act_main_quick_scroll)
    DirFastScroller quickScroller;

    @Bind(R.id.act_main_btn_append)
    View button;

    @BindColor(R.color.colorPrimary)
    ColorStateList primaryBlue;

    @BindColor(R.color.colorAccent)
    ColorStateList ascentPurple;

    @BindString(R.string.pref_use_root)
    String rootPref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        cbm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cbm.addPrimaryClipChangedListener(this);

        super.onCreate(savedInstanceState);

        final SharedPreferences prefs = AppPrefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        setContentView(R.layout.file_manager);

        evaluateCurrentClip();

        state = getLastNonConfigurationInstance();

        if (state == null) {
            boolean useRoot = prefs.getBoolean(rootPref, true);

            if (!initState(prefs, useRoot)) {
                return;
            }

            final Intent intent = getIntent();

            final CharSequence dest = intent.getCharSequenceExtra(ShortcutActivity.EXTRA_FSO);

            final CharSequence dirToOpen;

            if (dest != null) {
                dirToOpen = dest;
            } else {
                final File home = state.layout.getHome();

                dirToOpen = home.getAbsolutePath();
            }

            @DirFd int directory;
            try {
                final OS unpriv = OS.getInstance();

                directory = unpriv.opendir(dirToOpen);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to open home dir", e);
                Utils.toast(this, "failed to open " + dirToOpen + ", exiting");
                finish();
                return;
            }

            state.adapter.swapDirectoryDescriptor(directory);
        } else {
            setUpAdapter();

            directoryList.setAdapter(state.adapter);
        }

        updateButtonState(state.os.isPrivileged());

        DirLayoutManager layoutManager = new DirLayoutManager(this);
        layoutManager.setCallback(dirObserver);
        this.layoutManager = layoutManager;

        decoration = new SaneDecor(this, LinearLayout.VERTICAL);

        directoryList.setItemAnimator(animator);
        directoryList.addItemDecoration(decoration);
        directoryList.setLayoutManager(layoutManager);
        directoryList.addOnScrollListener(quickScroller.getOnScrollListener());
        directoryList.setHasFixedSize(true);

        quickScroller.setRecyclerView(directoryList);

        registerForContextMenu(directoryList);
    }

    private void setUpAdapter() {
        final DirAdapter adapter = state.adapter;

        adapter.setItemClickListener(clickHandler);
        adapter.registerAdapterDataObserver(scrollerObserver = quickScroller.getAdapterDataObserver());
        adapter.registerAdapterDataObserver(dirObserver = new DirObserver());
    }

    private void tearDownAdapter() {
        final DirAdapter adapter = state.adapter;

        if (scrollerObserver != null) {
            adapter.unregisterAdapterDataObserver(scrollerObserver);
            scrollerObserver = null;
        }
        if (dirObserver != null) {
            adapter.unregisterAdapterDataObserver(dirObserver);
            dirObserver = null;
        }

        adapter.setItemClickListener(null);
    }

    private boolean initState(SharedPreferences prefs, boolean useRoot) {
        if (state != null) {
            if (state.os.isPrivileged() == useRoot) {
                return true;
            }
        }

        OS os, unpriv;
        try {
            unpriv = OS.getInstance();
        } catch (IOException e) {
            toast("Failed to initialize native libraries, exiting");
            finish();
            return false;
        }

        if (useRoot) {
            try {
                os = RootSingleton.get(this);
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to acquire root access, using unprivileged fallback", e);

                os = unpriv;
            }
        } else {
            os = unpriv;
        }

        try {
            if (state == null) {
                state = GuardedState.create(os, this);
            } else {
                tearDownAdapter();

                try (GuardedState oldState = state) {
                    state = oldState.swap(os);
                }
            }

            if (state.os.isPrivileged() != useRoot) {
                prefs.edit().putBoolean(rootPref, !useRoot).apply();
            }
        } catch (IOException e) {
            LogUtil.logCautiously("Startup error", e);
            toast("Failed to create inotify descriptor, exiting");
            finish();
            return false;
        }

        setUpAdapter();

        directoryList.setAdapter(state.adapter);

        final Limit limit = new Limit();
        try {
            os.getrlimit(NativeBits.RLIMIT_NOFILE, limit);
            limit.current = limit.max;
            os.setrlimit(NativeBits.RLIMIT_NOFILE, limit);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to adjust rlimits", e);
        }

        return true;
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
        cbm.removePrimaryClipChangedListener(this);

        unregisterForContextMenu(directoryList);

        SharedPreferences prefs = AppPrefs.get(this);

        prefs.unregisterOnSharedPreferenceChangeListener(this);

        if (state != null) {
            tearDownAdapter();

            if (!isChangingConfigurations()) {
                state.close();
            }
        }

        super.onDestroy();
    }

    private final DebouncingOnClickListener clickHandler = new DebouncingOnClickListener() {
        @Override
        public void doClick(View v) {
            if (state.adapter.isStalled()) {
                return;
            }

            final DirItemHolder dirItemHolder = (DirItemHolder) directoryList.getChildViewHolder(v);

            if (dirItemHolder.isPlaceholder()) {
                return;
            }

            final Directory.Entry dirInfo = dirItemHolder.getDirInfo();

            if (dirInfo.type != null && dirInfo.type.isNotDir()) {
                openfile(state.adapter.getFd(), dirInfo.name);
            } else {
                opendir(state.adapter.getFd(), dirInfo.name);
            }
        }
    };

    private void openHomeDir() {
        opendir(DirFd.NIL, state.layout.getHome().getAbsolutePath());
    }

    private void opendir(@DirFd int base, CharSequence pathname) {
        try {
            doWithAccessChecks(OS.R_OK | OS.X_OK, base, pathname, () -> doOpendir(pathname));
        } catch (IOException e) {
            toast("Failed to open directory. " + e.getMessage());
        }
    }

    private void doOpendir(CharSequence pathname) {
        final @DirFd int base = state.adapter.getFd();

        int newFd = DirFd.NIL, prev = DirFd.NIL;
        try {
            newFd = state.os.opendirat(base, pathname);

            state.os.fstat(newFd, tmpStat);

            if (tmpStat.type != FsType.DIRECTORY) {
                openfile(base, pathname);
                return;
            }

            final MountInfo.Mount m = state.layout.getFs(tmpStat.st_dev);

            final boolean canUseTellDir = m != null && isPosix(m.fstype);

            prev = state.adapter.swapDirectoryDescriptor(newFd, !canUseTellDir);

            layoutManager.scrollToPosition(0);

            directoryList.setItemAnimator(null);

            directoryList.swapAdapter(state.adapter, true);
        } catch (ErrnoException e) {
            LogUtil.logCautiously("Unable to open " + pathname + ", ignoring", e);

            if (e.code() != ErrnoException.ENOTDIR) {
                toast("Unable to open a directory. " + e.getMessage());
            } else {
                openfile(base, pathname);
            }
        } catch (IOException e) {
            LogUtil.logCautiously("Unable to open " + pathname + ", ignoring", e);

            toast("Unable to open a directory. " + e.getMessage());
        } finally {
            if (newFd != DirFd.NIL && prev != DirFd.NIL) {
                state.os.dispose(newFd);
            }
        }
    }

    private void openfile(@DirFd int base, CharSequence path) {
        try {
            final CharSequence resolved;
            final int resolvedFd = state.os.openat(base, path, NativeBits.O_NONBLOCK, 0);
            try {
                resolved = state.os.readlinkat(DirFd.NIL, ProviderBase.fdPath(resolvedFd));
            } finally {
                state.os.dispose(resolvedFd);
            }

            final Uri uri = PublicProvider.publicUri(this, resolved, "r");

            final Intent view = new Intent(Intent.ACTION_VIEW, uri);

            startActivity(view);
        } catch (ActivityNotFoundException noHandler) {
            toast(getString(R.string.no_handler));
        } catch (Throwable e) {
            LogUtil.logCautiously("Failed to open file", e);

            toast("Error: " + e.getMessage());
        }
    }



    private void editfile(int parentDir, CharSequence name) {
        try {
            final CharSequence resolved = state.os.readlinkat(parentDir, name);

            final Uri uri = PublicProvider.publicUri(this, resolved, "rw");

            final Intent view = new Intent(Intent.ACTION_EDIT, uri);

            startActivity(view);
        } catch (ActivityNotFoundException noHandler) {
            toast(getString(R.string.no_handler));
        } catch (Throwable e) {
            LogUtil.logCautiously("Failed to open file for editing", e);

            toast("Error: " + e.getMessage());
        }
    }

    private void sharefile(int parentDir, CharSequence name) {
        try {
            final CharSequence resolved = state.os.readlinkat(parentDir, name);

            final Uri uri = PublicProvider.publicUri(this, resolved);

            if (uri == null) {
                toast("Failed to generate shareable uri");
                return;
            }

            String type = getContentResolver().getType(uri);
            if (type == null) {
                type = DEFAULT_MIME;
            }

            final Intent view = new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setType(type);

            startActivity(view);
        } catch (ActivityNotFoundException noHandler) {
            toast(getString(R.string.no_handler));
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

    private final Stat tmpStat = new Stat();

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (!(menuInfo instanceof FileMenuInfo)) {
            return;
        }

        final FileMenuInfo info = (FileMenuInfo) menuInfo;

        final MenuItem cutItem = menu.add(Menu.NONE, R.id.menu_item_cut, 0, "Cut");
        cutItem.setOnMenuItemClickListener(this);

        final MenuItem copyItem = menu.add(Menu.NONE, R.id.menu_item_copy, 1, "Copy");
        copyItem.setOnMenuItemClickListener(this);

        final MenuItem renameItem = menu.add(Menu.NONE, R.id.menu_item_rename, 3, "Rename");
        renameItem.setOnMenuItemClickListener(this);

        final MenuItem delItem = menu.add(Menu.NONE, R.id.menu_item_delete, 5, "Delete");
        delItem.setOnMenuItemClickListener(this);

        final MenuItem selItem = menu.add(Menu.NONE, R.id.menu_item_select, 6, "Select");
        selItem.setOnMenuItemClickListener(this);

        final MenuItem bufferItem = menu.add(Menu.NONE, R.id.menu_copy_path, Menu.CATEGORY_ALTERNATIVE | 6, "Copy path");
        bufferItem.setOnMenuItemClickListener(this);

        FsType type = null;

        if (info.fileInfo.type == null || info.fileInfo.type == FsType.LINK) {
            try {
                state.os.fstatat(info.parentDir, info.fileInfo.name, tmpStat, 0);

                type = tmpStat.type;
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to stat " + info.fileInfo.name, e);
            }
        } else {
            type = info.fileInfo.type;
        }

        if (type != null) {
            if (type.isNotDir()) {
                final MenuItem editItem = menu.add(Menu.NONE, R.id.menu_item_edit, 2, "Edit");
                editItem.setOnMenuItemClickListener(this);

                final MenuItem shareItem = menu.add(Menu.NONE, R.id.menu_item_share, 4, "Share");
                shareItem.setOnMenuItemClickListener(this);
            } else {
                final MenuItem shortcutItem = menu.add(Menu.NONE, R.id.menu_make_shortcut, Menu.CATEGORY_ALTERNATIVE | 7, "Make shortcut");
                shortcutItem.setOnMenuItemClickListener(this);
            }
        }
    }

    private static final String[] MIMETYPES_TEXT_URILIST = new String[] { ClipDescription.MIMETYPE_TEXT_URILIST };

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        FileMenuInfo info = (FileMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case R.id.menu_item_delete:
                try {
                    final CharSequence targetName = info.fileInfo.name;
                    try {
                        FsType fsType = info.fileInfo.type;
                        if (fsType == null) {
                            state.os.fstatat(info.parentDir, info.fileInfo.name, tmpStat, 0);
                            fsType = tmpStat.type;
                        }

                        state.os.unlinkat(info.parentDir, targetName, fsType == FsType.DIRECTORY ? OS.AT_REMOVEDIR : 0);
                    } catch (ErrnoException errno) {
                        if (errno.code() == ErrnoException.ENOTEMPTY) {
                            new ConfirmationDialog(targetName, R.string.rmdir_title, R.string.q_rmdir_msg, R.string.remove)
                                    .show(getFragmentManager(), null);
                        } else {
                            throw errno;
                        }
                    }
                } catch (IOException e) {
                    toast("Unable to perform removal. " + e.getMessage());
                }
                break;
            case R.id.menu_copy_path:
                try {
                    final CharSequence path = state.os.readlinkat(state.adapter.getFd(), info.fileInfo.name);

                    ClipData data = ClipData.newPlainText("Absolute File Path", path);

                    cbm.setPrimaryClip(data);
                } catch (IOException e) {
                    toast("Unable to resolve full path. "  + e.getMessage());
                }
                break;
            case R.id.menu_item_copy:
            case R.id.menu_item_cut:
                try {
                    boolean cut = item.getItemId() == R.id.menu_item_cut;

                    final CharSequence path = state.os.readlinkat(state.adapter.getFd(), info.fileInfo.name);

                    final Uri uri = PublicProvider.publicUri(this, path, cut ? "rw" : "r");

                    if (uri == null) {
                        toast("Failed to generate shareable uri");
                        return true;
                    }

                    final FsType type = info.fileInfo.type;
                    final ClipDescription description = new ClipDescription(info.fileInfo.name, MIMETYPES_TEXT_URILIST);
                    final Intent intent = new Intent(cut ? ACTION_MOVE : ACTION_COPY);
                    if (type != null && type.isNotDir()) {
                        try {
                            state.os.fstat(state.adapter.getFd(), tmpStat);
                            intent.putExtra(EXTRA_NOT_DIR, true);
                            intent.putExtra(EXTRA_DIR_FS, tmpStat.st_dev);
                        } catch (IOException ioe) {
                            LogUtil.logCautiously("Failed to stat target file", ioe);
                        }
                    }
                    final ClipData.Item clipItem = new ClipData.Item(null, intent, uri);
                    cbm.setPrimaryClip(new ClipData(description, clipItem));
                } catch (IOException e) {
                    toast("Unable to resolve full path. "  + e.getMessage());
                }
                break;
            case R.id.menu_item_select:
                state.adapter.toggleSelection(info.position);
                startActionMode(new ActionModeHandler());
                break;
            case R.id.menu_item_edit:
                editfile(info.parentDir, info.fileInfo.name);
                break;
            case R.id.menu_item_share:
                sharefile(info.parentDir, info.fileInfo.name);
                break;
            case R.id.menu_make_shortcut:
                showCreateShortcutDialog(info.fileInfo.name);
                break;
            case R.id.menu_item_rename:
                showRenameDialog(info.fileInfo.name);
                break;
            case R.id.menu_fifo:
                showCreateNewDialog(R.string.fifo, item.getItemId());
                break;
            case R.id.menu_dir:
                showCreateNewDialog(R.string.directory, item.getItemId());
                break;
            case R.id.menu_file:
                showCreateNewDialog(R.string.file, item.getItemId());
                break;
            case R.id.menu_socket:
                showCreateNewDialog(R.string.socket, item.getItemId());
                break;
            case R.id.menu_symlink:
                showCreateLinkDialog(R.string.symlink, item.getItemId());
                break;
            case R.id.menu_link:
                showCreateLinkDialog(R.string.hardlink, item.getItemId());
                break;
        }

        return true;
    }

    private void showCreateShortcutDialog(CharSequence name) {
        new ShortcutNameInputFragment(name).show(getFragmentManager(), null);
    }

    private void pasteFileObject(List<FileObject> sourceFiles, boolean canRemoveOriginal) throws IOException {
        final OS os = state.os;

        FileTasks ft = FileTasks.getInstance(this);

        ft.copy(os, state.layout, sourceFiles, state.adapter.getFd(), canRemoveOriginal);
    }

    private void showRenameDialog(CharSequence name) {
        new RenameNameInputFragment(name).show(getFragmentManager(), null);
    }

    private void showCreateNewDialog(@StringRes int name, int type) {
        new NameInputFragment(name, type).show(getFragmentManager(), null);
    }

    private void showCreateLinkDialog(@StringRes int name, int type) {
        new NewLinkInputFragment(name, type).show(getFragmentManager(), null);
    }

    @Override
    public void onNewNameChosen(CharSequence oldName, String newName) {
        if (oldName.equals(newName) || TextUtils.isEmpty(newName)) return;

        final @DirFd int dirFd = state.adapter.getFd();
        try {
            state.os.renameat(dirFd, oldName, dirFd, newName);
        } catch (IOException e) {
            toast("Failed to rename " + oldName + ". " + e.getMessage());
        }
    }

    private IntObjectMap<Action> pendingActions = new IntObjectHashMap<>();

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (rootPref.equals(key)) {
            final boolean useRoot = prefs.getBoolean(key, true);

            updateButtonState(useRoot);

            if (state != null && useRoot != state.os.isPrivileged()) {
                @DirFd int currentDir = state.adapter.getFd();

                if (currentDir > 0) {
                    currentDir = state.adapter.swapDirectoryDescriptor(DirFd.NIL);

                    initState(prefs, useRoot);

                    state.adapter.swapDirectoryDescriptor(currentDir);
                }
            }
        }
    }

    private void updateButtonState(boolean useRoot) {
        if (Build.VERSION.SDK_INT >= 21) {
            button.setBackgroundTintList(useRoot ? ascentPurple : primaryBlue);
        }
    }

    private interface Action {
        void act();
    }

    private void doWithAccessChecks(@OS.AccessFlags int checks, @DirFd int dirFd, CharSequence path, Action action) throws IOException {
        final boolean canAccess = state.os.isPrivileged() || state.os.faccessat(dirFd, path, checks);

        if (canAccess) {
            action.act();
            return;
        }

        try {
            state.os.fstat(dirFd, tmpStat);

            final MountInfo.Mount mount = state.layout.getFs(tmpStat.st_dev);
            if (mount != null) {
                if (mount.askForPermission(this, R.id.req_permission)) {
                    state.adapter.packState();

                    pendingActions.put(R.id.req_permission, () -> {
                        state.adapter.recoverState();

                        action.act();
                    });
                    return;
                }
            }
        } catch (ErrnoException errno) {
            // fstat can fail for unlinked directories. Ignore that
            LogUtil.logCautiously("Failed to stat", errno);
        }

        action.act();
    }

    @Override
    public void onShortcutNameChosen(CharSequence shortcutTarget, String shortcutName) {
        try {
            final CharSequence path = state.os.readlinkat(state.adapter.getFd(), shortcutTarget);

            Utils.createShortcut(this, path, shortcutName);
        } catch (IOException e) {
            toast("Unable to resolve full path. "  + e.getMessage());
        }
    }

    @Override
    public void onNameChosen(String name, int type) {
        try {
            final @DirFd int fd = state.adapter.getFd();

            final String dirName = name.charAt(0) == '/' ? ProviderBase.extractParent(name) : fdPath(fd);

            doWithAccessChecks(OS.W_OK, fd, dirName, () -> doMknod(name, type));
        } catch (IOException e) {
            toast("Unable to create a file. " + e.getMessage());
        }
    }

    @Override
    public void onAffirmed(CharSequence fileName) {
        FileTasks ft = FileTasks.getInstance(this);

        try {
            ft.rmdir(state.os, fileName, state.adapter.getFd());
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    @Override
    public void onLinkParamsChosen(String name, String target, int type) {
        try {
            final @DirFd int cur = state.adapter.getFd();

            final String dirName = name.charAt(0) == '/' ? ProviderBase.extractParent(name) : fdPath(cur);

            switch (type) {
                case R.id.menu_symlink:
                    doWithAccessChecks(OS.W_OK, cur, dirName, () -> doSymlink(name, target));
                    break;
                case R.id.menu_link:
                    doWithAccessChecks(OS.W_OK, cur, dirName, () -> {
                        try {
                            doWithAccessChecks(OS.F_OK, cur, target, () -> doHardlink(name, target));
                        } catch (IOException e) {
                            toast("Unable to create a link. " + e.getMessage());
                        }
                    });
                    break;
            }
        } catch (IOException e) {
            toast("Unable to create a link. " + e.getMessage());
        }
    }

    private void doHardlink(String name, String target) {
        try {
            final int dir = state.adapter.getFd();

            state.os.linkat(dir, target, dir, name, 0);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create a link", e);

            toast("Unable to create a link. " + e.getMessage());
        }
    }

    private void doSymlink(String name, String target) {
        try {
            state.os.symlinkat(target, state.adapter.getFd(), name);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create a symlink", e);

            toast("Failed to create a symlink. " + e.getMessage());
        }
    }

    private void doMkdir(CharSequence name) throws IOException {
        final int fd = state.adapter.getFd();

        if (!state.os.mkdirat(fd, name, OS.DEF_DIR_MODE)) {
            state.os.fstatat(fd, name, tmpStat, OS.AT_SYMLINK_NOFOLLOW);

            if (tmpStat.type != FsType.DIRECTORY) {
                throw new IOException("non directory with this name already exists");
            }
        }
    }

    private void doMknod(String name, int type) {
        @OS.FileTypeFlag int fileType;

        switch (type) {
            case R.id.menu_dir:
                try {
                    doMkdir(name);
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to create a directory", e);

                    toast("Unable to create a directory. " + e.getMessage());
                }
                return;
            case R.id.menu_fifo:
                fileType = OS.S_IFIFO;
                break;
            case R.id.menu_socket:
                fileType = OS.S_IFSOCK;
                break;
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case R.id.req_permission:
                if (permissions.length == 1 && grantResults.length == 1) {
                    if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[0])
                            && grantResults[0] == PERMISSION_GRANTED) {
                        final Action runnable = pendingActions.remove(requestCode);

                        if (runnable != null) {
                            handler.post(runnable::act);
                        }
                    }
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case R.id.req_permission:
                if (resultCode == RESULT_OK) {
                    final Action runnable = pendingActions.remove(requestCode);

                    handler.post(runnable::act);
                }
                break;
            case R.id.req_task:
                FileTasks ft = FileTasks.getInstance(this);

                ft.handleCancellationIntent(data);
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            FileTasks ft = FileTasks.getInstance(this);

            ft.handleCancellationIntent(intent);
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

    private static final int NAME_MAX = 16;

    public static String massageInSensibleForm(CharSequence message) {
        final StringBuilder builder;

        final boolean quoted = FilenameUtil.isQuote(message.charAt(0))
                && FilenameUtil.isQuote(message.charAt(message.length() - 1));

        if (message.length() > NAME_MAX) {
            builder = new StringBuilder(17);
            if (!quoted) builder.append('"');
            builder.append(message.subSequence(0, 8));
            builder.append('…');
            builder.append(message.subSequence(message.length() - 5, message.length()));
            if (!quoted) builder.append('"');
            return builder.toString();
        } else {
            builder = new StringBuilder(message.length() + 2);
            if (!quoted) builder.append('"');
            builder.append(message);
            builder.setCharAt(0, Character.toLowerCase(builder.charAt(0)));
            if (!quoted) builder.append('"');
        }

        return builder.toString();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean ok = false;
        CharSequence label = null;

        final ClipData clip = clipData;

        if (canHandleClip) {
            final ClipDescription clipInfo = clip.getDescription();
            if (clipInfo != null) {
                label = massageInSensibleForm(clipInfo.getLabel());

                ok = !TextUtils.isEmpty(label);
            }
        }

        Intent intent = null;

        final MenuItem paste = menu.findItem(R.id.menu_paste);

        if (ok) {
            paste.setEnabled(true);

            boolean move = false;

            final ClipData.Item item = clip.getItemAt(0);

            intent = item.getIntent();

            if (intent != null) {
                if (ACTION_MOVE.equals(intent.getAction())) {
                    move = true;
                }
            }

            final CharSequence labelText = getString(move ? R.string.move : R.string.paste_name, label);

            paste.setTitle(labelText);
        } else {
            paste.setEnabled(false);
            paste.setTitle(getString(R.string.paste));
        }

        final MenuItem symlink = menu.findItem(R.id.menu_symlink);

        if (ok && canSymlinkClip) {
            symlink.setTitle(getString(R.string.symlink_name, label));
            symlink.setVisible(true);
        } else {
            symlink.setVisible(false);
        }

        final MenuItem hardlink = menu.findItem(R.id.menu_hardlink);

        boolean allowHardLink = false;

        if (canSymlinkClip && intent != null) {
            if (intent.getBooleanExtra(EXTRA_NOT_DIR, false) && intent.hasExtra(EXTRA_DIR_FS)) {
                try {
                    state.os.fstat(state.adapter.getFd(), tmpStat);

                    allowHardLink = tmpStat.st_dev == intent.getLongExtra(EXTRA_DIR_FS, -1);
                } catch (IOException e) {
                    LogUtil.logCautiously("Failed to stat current dir", e);
                }
            }
        }

        hardlink.setVisible(allowHardLink);

        if (allowHardLink) {
            hardlink.setTitle(getString(R.string.hardlink_name, label));
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_home:
                openHomeDir();
                return true;
            case R.id.menu_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.menu_symlink:
                trySymlink();
                return true;
            case R.id.menu_hardlink:
                tryHardlink();
                break;
            case R.id.menu_paste:
                final ClipData clip = clipData;

                List<FileObject> fileObjects = FileObject.fromClip(state.os, getApplicationContext(), clip);
                if (fileObjects == null) {
                    toast("Unsupported data type");

                    return true;
                }

                final boolean canRemoveOriginal;

                final Intent intent = clip.getItemAt(0).getIntent();
                if (intent == null) {
                    canRemoveOriginal = false;

                    LogUtil.swallowError("Clip does not contain Intent");
                } else {
                    canRemoveOriginal = ACTION_MOVE.equals(intent.getAction());
                }

                try {
                    pasteFileObject(fileObjects, canRemoveOriginal);
                } catch (IOException e) {
                    toast(e.getMessage());
                }

                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void trySymlink() {
        final ClipData clip = clipData;

        final ClipData.Item item = clip.getItemAt(0);

        final Uri uri = item.getUri();

        final String filepath = uri.getPath();

        String name = uri.getLastPathSegment();
        if (name == null) {
            name = "/";
        }

        try {
            state.os.symlinkat(filepath, state.adapter.getFd(), name);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create symlink", e);

            toast(e.getMessage());
        }
    }

    private void tryHardlink() {
        final ClipData clip = clipData;

        final ClipData.Item item = clip.getItemAt(0);

        final Uri uri = item.getUri();

        final String filepath = uri.getPath();

        String name = uri.getLastPathSegment();
        if (name == null) {
            name = "/";
        }

        try {
            state.os.linkat(DirFd.NIL, filepath, state.adapter.getFd(), name, 0);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create hard link", e);

            toast(e.getMessage());
        }
    }

    private ClipData clipData;
    private boolean canHandleClip;
    private boolean canSymlinkClip;

    private void evaluateCurrentClip() {
        clipData = cbm.getPrimaryClip();

        closeOptionsMenu();

        boolean canHandleNew = canHandleClip();
        boolean canSymlinkNew = canHandleNew && canSymlinkClip();

        if (canHandleNew != canHandleClip || canSymlinkClip != canSymlinkNew) {
            canHandleClip = canHandleNew;
            canSymlinkClip = canSymlinkNew;

            invalidateOptionsMenu();
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        evaluateCurrentClip();
    }

    private boolean canSymlinkClip() {
        final ClipData clip = clipData;

        final ClipData.Item item = clip.getItemAt(0);

        final Uri uri = item.getUri();

        if (uri.getPath() == null) {
            return false;
        }

        final String scheme = uri.getScheme();
        switch (scheme == null ? "" : scheme) {
            case ContentResolver.SCHEME_FILE:
                return true;
            case ContentResolver.SCHEME_CONTENT:
                final String authority = uri.getAuthority();
                final String packageName = getPackageName();
                final String myAuthority = packageName + PublicProvider.AUTHORITY_SUFFIX;
                return myAuthority.equals(authority);
        }

        return false;
    }

    private boolean canHandleClip() {
        final ClipData clip = clipData;

        if (clip == null || clip.getItemCount() != 1) {
            return false;
        }

        final ClipData.Item item = clip.getItemAt(0);

        final Uri uri = item.getUri();
        if (uri == null) {
            return false;
        }

        final String scheme = uri.getScheme();
        switch (scheme == null ? "" : scheme) {
            case ContentResolver.SCHEME_FILE:
            case ContentResolver.SCHEME_ANDROID_RESOURCE:
            case ContentResolver.SCHEME_CONTENT:
                return true;
        }

        return false;
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
            int visibility = quickScroller.canScroll() ? View.VISIBLE : View.GONE;

            if (quickScroller.getVisibility() != visibility) {
                quickScroller.setVisibility(visibility);
            }

            if (!waitForLayout) {
                return;
            }

            waitForLayout = false;

            RecyclerView.RecycledViewPool pool = directoryList.getRecycledViewPool();

            final int visibleViews = dirLayoutManager.getChildCount();

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

    public final class ActionModeHandler implements ActionMode.Callback {
        private ActionMode mode;

        private boolean safe = true;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (Build.VERSION.SDK_INT >= 23) {
                mode.setType(ActionMode.TYPE_PRIMARY);
            }

            this.mode = mode;

            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (mode != null && mode.equals(this.mode)) {
                this.mode = null;
            }

            if (!safe) return;

            safe = false;
            try {
                state.adapter.clearSelection();
            } finally {
                safe = true;
            }
        }

        public void onSelectionCleared() {
            if (!safe) return;

            safe = false;
            try {
                if (mode != null) {
                    mode.finish();
                }
            } finally {
                safe = true;
            }
        }

        public void onSelectionStarted() {
            startActionMode(this);
        }
    }

    public static abstract class DebouncingOnClickListener implements View.OnClickListener {
        static boolean enabled = true;

        private static final Runnable ENABLE_AGAIN = () -> enabled = true;

        @Override public void onClick(View v) {
            if (enabled) {
                enabled = false;
                v.post(ENABLE_AGAIN);
                doClick(v);
            }
        }

        public abstract void doClick(View v);
    }
}
