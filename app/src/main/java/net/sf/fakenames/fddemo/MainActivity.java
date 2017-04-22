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
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
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
import net.sf.fakenames.fddemo.view.DirAdapter;
import net.sf.fakenames.fddemo.view.DirFastScroller;
import net.sf.fakenames.fddemo.view.DirItemHolder;
import net.sf.fakenames.fddemo.view.DirLayoutManager;
import net.sf.fakenames.fddemo.view.FileMenuInfo;
import net.sf.fakenames.fddemo.view.NameInputFragment;
import net.sf.fakenames.fddemo.view.RenameNameInputFragment;
import net.sf.fakenames.fddemo.view.SaneDecor;
import net.sf.xfd.DirFd;
import net.sf.xfd.Directory;
import net.sf.xfd.ErrnoException;
import net.sf.xfd.FsType;
import net.sf.xfd.LogUtil;
import net.sf.xfd.MountInfo;
import net.sf.xfd.NativeBits;
import net.sf.xfd.OS;
import net.sf.xfd.Stat;
import net.sf.xfd.provider.PublicProvider;
import net.sf.xfd.provider.RootSingleton;

import java.io.File;
import java.io.IOException;

import butterknife.BindColor;
import butterknife.BindString;
import butterknife.BindView;
import butterknife.OnClick;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static net.sf.xfd.provider.ProviderBase.DEFAULT_MIME;
import static net.sf.xfd.provider.ProviderBase.fdPath;
import static net.sf.xfd.provider.ProviderBase.isPosix;

public class MainActivity extends BaseActivity implements
        MenuItem.OnMenuItemClickListener,
        NameInputFragment.FileNameReceiver,
        RenameNameInputFragment.FileNameReceiver,
        ClipboardManager.OnPrimaryClipChangedListener,
        SharedPreferences.OnSharedPreferenceChangeListener,
        PopupMenu.OnMenuItemClickListener {
    static final String ACTION_MOVE = "net.sf.chdir.action.MOVE";
    static final String ACTION_CANCEL = "net.sf.chdir.action.CANCEL";

    private ClipboardManager cbm;

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

        dirObserver = new DirObserver();
        scrollerObserver = quickScroller.getAdapterDataObserver();

        state = getLastNonConfigurationInstance();

        if (state == null) {

            boolean useRoot = prefs.getBoolean(rootPref, true);

            if (!initState(prefs, useRoot)) {
                return;
            }

            final File home = state.layout.getHome();

            @DirFd int directory;
            try {
                final OS unpriv = OS.getInstance();

                directory = unpriv.opendir(home.getPath());
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to open home dir", e);
                Toast.makeText(this, "failed to open " + home.getPath() + ", exiting", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            state.adapter.swapDirectoryDescriptor(directory);
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
        adapter.registerAdapterDataObserver(scrollerObserver);
        adapter.registerAdapterDataObserver(dirObserver);
    }

    private void tearDownAdapter() {
        final DirAdapter adapter = state.adapter;

        if (scrollerObserver != null) {
            adapter.unregisterAdapterDataObserver(scrollerObserver);
        }
        if (dirObserver != null) {
            adapter.unregisterAdapterDataObserver(dirObserver);
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

            setUpAdapter();

            directoryList.setAdapter(state.adapter);
        } catch (IOException e) {
            LogUtil.logCautiously("Startup error", e);
            toast("Failed to create inotify descriptor, exiting");
            finish();
            return false;
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

    private void opendir(@DirFd int base, String pathname) {
        try {
            doWithAccessChecks(OS.R_OK | OS.X_OK, base, pathname, () -> doOpendir(pathname));
        } catch (IOException e) {
            toast("Failed to open directory: permission denied");
        }
    }

    private void doOpendir(String pathname) {
        final @DirFd int base = state.adapter.getFd();

        int newFd = DirFd.NIL, prev = DirFd.NIL;
        try {
            newFd = state.os.opendirat(base, pathname);

            final Stat stat = new Stat();

            state.os.fstat(newFd, stat);

            if (stat.type != FsType.DIRECTORY) {
                openfile(base, pathname);
                return;
            }

            final MountInfo.Mount m = state.layout.getFs(stat.st_dev);

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

    private void openfile(@DirFd int base, String path) {
        try {
            final String resolved;
            final int resolvedFd = state.os.openat(base, path, NativeBits.O_NONBLOCK, 0);
            try {
                resolved = state.os.readlinkat(DirFd.NIL, "/proc/" + Process.myPid() + "/fd/" + resolvedFd);
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



    private void editfile(int parentDir, String name) {
        try {
            final String resolved = state.os.readlinkat(parentDir, name);

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

        final MenuItem bufferItem = menu.add(Menu.NONE, R.id.menu_copy_path, Menu.CATEGORY_ALTERNATIVE | 6, "Copy path");
        bufferItem.setOnMenuItemClickListener(this);

        final MenuItem renameItem = menu.add(Menu.NONE, R.id.menu_item_rename, 3, "Rename");
        renameItem.setOnMenuItemClickListener(this);

        final MenuItem delItem = menu.add(Menu.NONE, R.id.menu_item_delete, 5, "Delete");
        delItem.setOnMenuItemClickListener(this);

        if (info.fileInfo.type == null || info.fileInfo.type == FsType.LINK) {
            try {
                state.os.fstatat(info.parentDir, info.fileInfo.name, tmpStat, 0);

                info.fileInfo.type = tmpStat.type;
            } catch (IOException e) {
                LogUtil.logCautiously("Failed to stat " + info.fileInfo.name, e);
            }
        }

        if (info.fileInfo.type != null && info.fileInfo.type.isNotDir()) {
            final MenuItem cutItem = menu.add(Menu.NONE, R.id.menu_item_cut, 0, "Cut");
            cutItem.setOnMenuItemClickListener(this);

            final MenuItem copyItem = menu.add(Menu.NONE, R.id.menu_item_copy, 1, "Copy");
            copyItem.setOnMenuItemClickListener(this);

            final MenuItem editItem = menu.add(Menu.NONE, R.id.menu_item_edit, 2, "Edit");
            editItem.setOnMenuItemClickListener(this);

            final MenuItem shareItem = menu.add(Menu.NONE, R.id.menu_item_share, 4, "Share");
            shareItem.setOnMenuItemClickListener(this);
        }
    }

    private static final String[] MIMETYPES_TEXT_URILIST = new String[] { ClipDescription.MIMETYPE_TEXT_URILIST };

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

                    ClipData data = ClipData.newPlainText("Absolute File Path", path);

                    cbm.setPrimaryClip(data);
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

                    final ClipDescription description = new ClipDescription(info.fileInfo.name, MIMETYPES_TEXT_URILIST);
                    final ClipData.Item clipItem = new ClipData.Item(null, null, uri);
                    cbm.setPrimaryClip(new ClipData(description, clipItem));
                } catch (IOException e) {
                    toast("Unable to resolve full path. "  + e.getMessage());
                }
                break;
            case R.id.menu_item_cut:
                try {
                    final String path = state.os.readlinkat(state.adapter.getFd(), info.fileInfo.name);

                    final Uri uri = PublicProvider.publicUri(this, path, "rw");

                    if (uri == null) {
                        toast("Failed to generate shareable uri");
                        return true;
                    }

                    final ClipDescription description = new ClipDescription(info.fileInfo.name, MIMETYPES_TEXT_URILIST);
                    final Intent intent = new Intent(ACTION_MOVE);
                    final ClipData.Item clipItem = new ClipData.Item(null, intent, uri);
                    cbm.setPrimaryClip(new ClipData(description, clipItem));
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

    private void pasteFile(FileObject sourceFile, boolean canRemoveOriginal) throws IOException {
        final OS os = state.os;

        final @DirFd int dir = os.dup(state.adapter.getFd());

        FileTasks ft = FileTasks.getInstance(this);

        ft.copy(os, state.layout, sourceFile, dir, canRemoveOriginal);
    }

    private void showRenameDialog(String name) {
        new RenameNameInputFragment(name).show(getFragmentManager(), null);
    }

    private void showCreateNewDialog(String name, int type) {
        new NameInputFragment(name, type).show(getFragmentManager(), null);
    }

    @Override
    public void onNewNameChosen(String oldName, String newName) {
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

    private void doWithAccessChecks(@OS.AccessFlags int checks, @DirFd int dirFd, String path, Action action) throws IOException {
        final boolean canAccess = state.os.isPrivileged() || state.os.faccessat(dirFd, path, checks);

        if (canAccess) {
            action.act();
            return;
        }

        final Stat stat = new Stat();
        state.os.fstat(dirFd, stat);

        final MountInfo.Mount mount = state.layout.getFs(stat.st_dev);
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

        action.act();
    }
    @Override
    public void onNameChosen(String name, int type) {
        try {
            final @DirFd int fd = state.adapter.getFd();

            doWithAccessChecks(OS.W_OK, fd, fdPath(fd), () -> doMknod(name, type));
        } catch (IOException e) {
            toast("Unable to create a file. " + e.getMessage());
        }
    }

    private void doMknod(String name, int type) {
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

        if (clip != null) {
            final ClipDescription clipInfo = clip.getDescription();
            if (clipInfo != null) {
                label = massageInSensibleForm(clipInfo.getLabel());

                ok = canHandleClip && !TextUtils.isEmpty(label);
            }
        }

        final MenuItem paste = menu.findItem(R.id.menu_paste);

        if (ok) {
            paste.setEnabled(true);

            boolean move = false;

            final int itemCount = clip.getItemCount();
            if (itemCount > 0) {
                final ClipData.Item item = clip.getItemAt(0);
                final Intent intent = item.getIntent();
                if (intent != null && ACTION_MOVE.equals(intent.getAction())) {
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
            symlink.setTitle(getString(R.string.symlink, label));
            symlink.setVisible(true);
        } else {
            symlink.setVisible(false);
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
            case R.id.menu_paste:
                final ClipData clip = clipData;

                FileObject fileObject = FileObject.fromClip(state.os, getApplicationContext(), clip);
                if (fileObject != null) {
                    try {
                        final Intent intent = clip.getItemAt(0).getIntent();

                        final boolean canRemoveOriginal = intent != null
                                && ACTION_MOVE.equals(intent.getAction());

                        pasteFile(fileObject, canRemoveOriginal);
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                } else {
                    toast("Unsupported data type");
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

        final String name = uri.getLastPathSegment();

        try {
            state.os.symlinkat(filepath, state.adapter.getFd(), name);
        } catch (IOException e) {
            LogUtil.logCautiously("Failed to create symlink", e);

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
