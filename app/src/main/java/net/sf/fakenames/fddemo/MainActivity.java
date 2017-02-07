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
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
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
import android.os.Process;
import android.os.RemoteException;
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
import net.sf.fakenames.fddemo.provider.FileProvider;
import net.sf.fakenames.fddemo.provider.ProviderBase;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import butterknife.BindView;
import butterknife.OnClick;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static net.sf.fakenames.fddemo.provider.ProviderBase.fdPath;

public class MainActivity extends BaseActivity implements
        MenuItem.OnMenuItemClickListener,
        NameInputFragment.FileNameReceiver,
        RenameNameInputFragment.FileNameReceiver,
        ClipboardManager.OnPrimaryClipChangedListener,
        PopupMenu.OnMenuItemClickListener {
    private static final String EXTRA_ACTION_CUT = "cut";

    private ClipboardManager cbm;

    private Executor ioExec;

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
        cbm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cbm.addPrimaryClipChangedListener(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.file_manager);

        evaluateCurrentClip();

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

        final ThreadFactory priorityFactory = r -> new Thread(r, "Odd jobs thread");

        ioExec = Executors.newCachedThreadPool(priorityFactory);

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

        adapter.setItemClickListener(clickHandler);
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
        cbm.removePrimaryClipChangedListener(this);

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
            newFd = state.os.opendirat(base, pathname, OS.O_RDONLY, 0);

            final Stat stat = new Stat();

            state.os.fstat(newFd, stat);

            if (stat.type != FsType.DIRECTORY) {
                openfile(base, pathname);
                return;
            }

            final MountInfo.Mount m = state.layout.getFs(stat.st_dev);

            final boolean canUseTellDir = m != null && BaseDirLayout.isPosix(m.fstype);

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
            final int resolvedFd = state.os.openat(base, path, OS.O_RDONLY, 0);
            try {
                resolved = state.os.readlinkat(DirFd.NIL, "/proc/" + Process.myPid() + "/fd/" + resolvedFd);
            } finally {
                state.os.dispose(resolvedFd);
            }

            final Uri uri = PublicProvider.publicUri(this, resolved, "r");

            final Intent view = new Intent(Intent.ACTION_VIEW, uri);

            startActivity(view);
        } catch (Throwable e) {
            toast("Error: " + e.getMessage());
        }
    }

    private void editfile(int parentDir, String name) {
        try {
            final String resolved = state.os.readlinkat(parentDir, name);

            final Uri uri = PublicProvider.publicUri(this, resolved, "rw");

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

        final FileMenuInfo info = (FileMenuInfo) menuInfo;

        final MenuItem bufferItem = menu.add(Menu.NONE, R.id.menu_copy_path, Menu.CATEGORY_ALTERNATIVE | 6, "Copy path");
        bufferItem.setOnMenuItemClickListener(this);

        final MenuItem renameItem = menu.add(Menu.NONE, R.id.menu_item_rename, 3, "Rename");
        renameItem.setOnMenuItemClickListener(this);

        final MenuItem delItem = menu.add(Menu.NONE, R.id.menu_item_delete, 5, "Delete");
        delItem.setOnMenuItemClickListener(this);

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

                    final ClipData data = ClipData.newRawUri(info.fileInfo.name, uri);

                    cbm.setPrimaryClip(data);
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

                    final ClipData data = ClipData.newRawUri(info.fileInfo.name,
                            uri.buildUpon().fragment(EXTRA_ACTION_CUT).build());

                    cbm.setPrimaryClip(data);
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

    private void pasteFile(FileObject sourceFile, boolean canRemoveOriginal) throws IOException {
        if (asyncTask != null) {
            toast("Unable to preform a copy: busy");
            return;
        }

        final OS os = state.os;

        final Context context = getApplicationContext();

        final @DirFd int dir = os.dup(state.adapter.getFd());

        final Stat targetDirStat = new Stat();

        os.fstat(dir, targetDirStat);

        final MountInfo.Mount m = state.layout.getFs(targetDirStat.st_dev);

        final boolean canUseExtChars = m != null && BaseDirLayout.isPosix(m.fstype);

        asyncTask = new AsyncTask<ParcelFileDescriptor, Void, String>() {
            @Override
            protected String doInBackground(ParcelFileDescriptor... params) {
                boolean copied = false;
                FileObject targetFile = null;
                try (Closeable c = sourceFile) {
                    final String desc = sourceFile.getDescription();

                    final String fileName = canUseExtChars
                            ? FilenameUtil.sanitize(desc)
                            : FilenameUtil.sanitizeCompat(desc);

                    if (os.faccessat(dir, fileName, OS.F_OK)) {
                        throw new IOException("File exists!");
                    }

                    final FsFile tmpFileInfo = new FsFile(dir, fileName, targetDirStat);

                    targetFile = FileObject.fromTempFile(os, context, tmpFileInfo);

                    copied = canRemoveOriginal
                            ? sourceFile.moveTo(targetFile)
                            : sourceFile.copyTo(targetFile);

                    return copied ? null : "Copy failed";
                } catch (Throwable t) {
                    t.printStackTrace();

                    final String result = t.getMessage();

                    return result == null ? t.getClass().getSimpleName() : result;
                } finally {
                    if (targetFile != null) {
                        if (!copied) {
                            try {
                                targetFile.delete();
                            } catch (RemoteException | IOException ioe) {
                                LogUtil.logCautiously("Failed to remove target file", ioe);
                            }
                        }

                        targetFile.close();
                    }
                }
            }

            @Override
            protected void onPostExecute(String s) {
                asyncTask = null;

                if (s == null) {
                    toast("Copy complete");
                } else {
                    toast(s);
                }
            }

            @Override
            protected void onCancelled() {
                asyncTask = null;

                sourceFile.close();
            }
        };

        //noinspection unchecked
        asyncTask.executeOnExecutor(ioExec);
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
            default:
                super.onActivityResult(requestCode, resultCode, data);
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


        final CharSequence label;
        final ClipDescription clipInfo = cbm.getPrimaryClipDescription();
        if (clipInfo != null) {
            label = clipInfo.getLabel();
            ok = canHandleClip && !TextUtils.isEmpty(label);
        } else {
            label = null;
        }

        final MenuItem paste = menu.findItem(R.id.menu_paste);

        if (ok) {
            paste.setEnabled(true);
            paste.setTitle(getString(R.string.paste_name, massageInSensibleForm(label)));
        } else {
            paste.setEnabled(false);
            paste.setTitle(getString(R.string.paste));
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_home:
                openHomeDir();
                return true;
            case R.id.menu_paste:
                final ClipData clip = cbm.getPrimaryClip();

                if (clip == null || clip.getItemCount() == 0) {
                    toast("The clipboard is empty");
                    return true;
                }

                if (clip.getItemCount() > 1) {
                    toast("Multi-item paste is not supported yet");
                    return true;
                }

                FileObject fileObject = FileObject.fromClip(state.os, getApplicationContext(), clip);
                if (fileObject != null) {
                    try {
                        final Uri uri = clip.getItemAt(0).getUri();

                        final boolean canRemoveOriginal =
                                EXTRA_ACTION_CUT.equals(uri.getEncodedFragment());

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

    private boolean canHandleClip;

    private void evaluateCurrentClip() {
        boolean canHandleNew = canHandleClip();

        if (canHandleNew != canHandleClip) {
            canHandleClip = canHandleNew;

            invalidateOptionsMenu();
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        evaluateCurrentClip();
    }

    private boolean canHandleClip() {
        final ClipData clip = cbm.getPrimaryClip();

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
