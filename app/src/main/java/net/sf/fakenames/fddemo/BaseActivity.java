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

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatCallback;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.VectorEnabledTintResources;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import butterknife.ButterKnife;

public abstract class BaseActivity extends Activity implements AppCompatCallback {
    private AppCompatDelegate mDelegate;
    private Resources mResources;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        final AppCompatDelegate delegate = getDelegate();
        delegate.installViewFactory();
        delegate.onCreate(savedInstanceState);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        getDelegate().onPostCreate(savedInstanceState);
    }

    @Nullable
    public ActionBar getSupportActionBar() {
        return getDelegate().getSupportActionBar();
    }

    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        getDelegate().setSupportActionBar(toolbar);
    }

    @NonNull
    @Override
    public MenuInflater getMenuInflater() {
        return getDelegate().getMenuInflater();
    }

    @Override
    public void setContentView(View view) {
        getDelegate().setContentView(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        getDelegate().setContentView(view, params);
    }

    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getDelegate().addContentView(view, params);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getDelegate().onConfigurationChanged(newConfig);
        if (mResources != null) {
            // The real (and thus managed) resources object was already updated
            // by ResourcesManager, so pull the current metrics from there.
            final DisplayMetrics newMetrics = super.getResources().getDisplayMetrics();
            mResources.updateConfiguration(newConfig, newMetrics);
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        getDelegate().onPostResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getDelegate().onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getDelegate().onStop();
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    @Override
    public <T extends View> T findViewById(@IdRes int id) {
        return getDelegate().findViewById(id);
    }

    @Override
    public final boolean onMenuItemSelected(int featureId, android.view.MenuItem item) {
        if (super.onMenuItemSelected(featureId, item)) {
            return true;
        }

        final ActionBar ab = getSupportActionBar();
        if (item.getItemId() == android.R.id.home && ab != null &&
                (ab.getDisplayOptions() & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
            return onSupportNavigateUp();
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getDelegate().onDestroy();
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        getDelegate().setTitle(title);
    }

    public boolean supportRequestWindowFeature(int featureId) {
        return getDelegate().requestWindowFeature(featureId);
    }

    @Override
    public void invalidateOptionsMenu() {
        getDelegate().invalidateOptionsMenu();
    }

    @Override
    @CallSuper
    public void onSupportActionModeStarted(@NonNull android.support.v7.view.ActionMode mode) {
    }

    @Override
    @CallSuper
    public void onSupportActionModeFinished(@NonNull android.support.v7.view.ActionMode mode) {
    }

    @Nullable
    @Override
    public android.support.v7.view.ActionMode onWindowStartingSupportActionMode(@NonNull android.support.v7.view.ActionMode.Callback callback) {
        return null;
    }

    @Nullable
    public android.support.v7.view.ActionMode startSupportActionMode(@NonNull android.support.v7.view.ActionMode.Callback callback) {
        return getDelegate().startSupportActionMode(callback);
    }

    /**
     * Support version of {@link #onCreateNavigateUpTaskStack(android.app.TaskStackBuilder)}.
     * This method will be called on all platform versions.
     *
     * Define the synthetic task stack that will be generated during Up navigation from
     * a different task.
     *
     * <p>The default implementation of this method adds the parent chain of this activity
     * as specified in the manifest to the supplied {@link android.support.v4.app.TaskStackBuilder}. Applications
     * may choose to override this method to construct the desired task stack in a different
     * way.</p>
     *
     * <p>This method will be invoked by the default implementation of {@link #onNavigateUp()}
     * if {@link #shouldUpRecreateTask(android.content.Intent)} returns true when supplied with the intent
     * returned by {@link #getParentActivityIntent()}.</p>
     *
     * <p>Applications that wish to supply extra Intent parameters to the parent stack defined
     * by the manifest should override
     * {@link #onPrepareSupportNavigateUpTaskStack(android.support.v4.app.TaskStackBuilder)}.</p>
     *
     * @param builder An empty TaskStackBuilder - the application should add intents representing
     *                the desired task stack
     */
    public void onCreateSupportNavigateUpTaskStack(@NonNull TaskStackBuilder builder) {
        builder.addParentStack(this);
    }

    /**
     * Support version of {@link #onPrepareNavigateUpTaskStack(android.app.TaskStackBuilder)}.
     * This method will be called on all platform versions.
     *
     * Prepare the synthetic task stack that will be generated during Up navigation
     * from a different task.
     *
     * <p>This method receives the {@link android.support.v4.app.TaskStackBuilder} with the constructed series of
     * Intents as generated by {@link #onCreateSupportNavigateUpTaskStack(android.support.v4.app.TaskStackBuilder)}.
     * If any extra data should be added to these intents before launching the new task,
     * the application should override this method and add that data here.</p>
     *
     * @param builder A TaskStackBuilder that has been populated with Intents by
     *                onCreateNavigateUpTaskStack.
     */
    public void onPrepareSupportNavigateUpTaskStack(@NonNull TaskStackBuilder builder) {
    }

    /**
     * This method is called whenever the user chooses to navigate Up within your application's
     * activity hierarchy from the action bar.
     *
     * <p>If a parent was specified in the manifest for this activity or an activity-alias to it,
     * default Up navigation will be handled automatically. See
     * {@link #getSupportParentActivityIntent()} for how to specify the parent. If any activity
     * along the parent chain requires extra Intent arguments, the Activity subclass
     * should override the method {@link #onPrepareSupportNavigateUpTaskStack(android.support.v4.app.TaskStackBuilder)}
     * to supply those arguments.</p>
     *
     * <p>See <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and
     * Back Stack</a> from the developer guide and
     * <a href="{@docRoot}design/patterns/navigation.html">Navigation</a> from the design guide
     * for more information about navigating within your app.</p>
     *
     * <p>See the {@link android.support.v4.app.TaskStackBuilder} class and the Activity methods
     * {@link #getSupportParentActivityIntent()}, {@link #supportShouldUpRecreateTask(android.content.Intent)}, and
     * {@link #supportNavigateUpTo(android.content.Intent)} for help implementing custom Up navigation.</p>
     *
     * @return true if Up navigation completed successfully and this Activity was finished,
     *         false otherwise.
     */
    public boolean onSupportNavigateUp() {
        Intent upIntent = getSupportParentActivityIntent();

        if (upIntent != null) {
            if (supportShouldUpRecreateTask(upIntent)) {
                TaskStackBuilder b = TaskStackBuilder.create(this);
                onCreateSupportNavigateUpTaskStack(b);
                onPrepareSupportNavigateUpTaskStack(b);
                b.startActivities();

                try {
                    ActivityCompat.finishAffinity(this);
                } catch (IllegalStateException e) {
                    // This can only happen on 4.1+, when we don't have a parent or a result set.
                    // In that case we should just finish().
                    finish();
                }
            } else {
                // This activity is part of the application's task, so simply
                // navigate up to the hierarchical parent activity.
                supportNavigateUpTo(upIntent);
            }
            return true;
        }
        return false;
    }

    /**
     * Obtain an {@link android.content.Intent} that will launch an explicit target activity
     * specified by sourceActivity's {@link android.support.v4.app.NavUtils#PARENT_ACTIVITY} &lt;meta-data&gt;
     * element in the application's manifest. If the device is running
     * Jellybean or newer, the android:parentActivityName attribute will be preferred
     * if it is present.
     *
     * @return a new Intent targeting the defined parent activity of sourceActivity
     */
    @Nullable
    public Intent getSupportParentActivityIntent() {
        return NavUtils.getParentActivityIntent(this);
    }

    /**
     * Returns true if sourceActivity should recreate the task when navigating 'up'
     * by using targetIntent.
     *
     * <p>If this method returns false the app can trivially call
     * {@link #supportNavigateUpTo(android.content.Intent)} using the same parameters to correctly perform
     * up navigation. If this method returns false, the app should synthesize a new task stack
     * by using {@link android.support.v4.app.TaskStackBuilder} or another similar mechanism to perform up navigation.</p>
     *
     * @param targetIntent An intent representing the target destination for up navigation
     * @return true if navigating up should recreate a new task stack, false if the same task
     *         should be used for the destination
     */
    public boolean supportShouldUpRecreateTask(@NonNull Intent targetIntent) {
        return NavUtils.shouldUpRecreateTask(this, targetIntent);
    }

    /**
     * Navigate from sourceActivity to the activity specified by upIntent, finishing sourceActivity
     * in the process. upIntent will have the flag {@link android.content.Intent#FLAG_ACTIVITY_CLEAR_TOP} set
     * by this method, along with any others required for proper up navigation as outlined
     * in the Android Design Guide.
     *
     * <p>This method should be used when performing up navigation from within the same task
     * as the destination. If up navigation should cross tasks in some cases, see
     * {@link #supportShouldUpRecreateTask(android.content.Intent)}.</p>
     *
     * @param upIntent An intent representing the target destination for up navigation
     */
    public void supportNavigateUpTo(@NonNull Intent upIntent) {
        NavUtils.navigateUpTo(this, upIntent);
    }

    @Nullable
    public ActionBarDrawerToggle.Delegate getDrawerToggleDelegate() {
        return getDelegate().getDrawerToggleDelegate();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Please note: AppCompat uses its own feature id for the action bar:
     * {@link AppCompatDelegate#FEATURE_SUPPORT_ACTION_BAR FEATURE_SUPPORT_ACTION_BAR}.</p>
     */
    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return super.onMenuOpened(featureId, menu);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Please note: AppCompat uses its own feature id for the action bar:
     * {@link AppCompatDelegate#FEATURE_SUPPORT_ACTION_BAR FEATURE_SUPPORT_ACTION_BAR}.</p>
     */
    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        super.onPanelClosed(featureId, menu);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        getDelegate().onSaveInstanceState(outState);
    }

    /**
     * @return The {@link AppCompatDelegate} being used by this Activity.
     */
    @NonNull
    public AppCompatDelegate getDelegate() {
        if (mDelegate == null) {
            mDelegate = AppCompatDelegate.create(this, this);
        }
        return mDelegate;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Let support action bars open menus in response to the menu key prioritized over
        // the window handling it
        final int keyCode = event.getKeyCode();
        final ActionBar actionBar = getSupportActionBar();
        if (keyCode == KeyEvent.KEYCODE_MENU
                && actionBar != null && actionBar.onMenuKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public Resources getResources() {
        if (mResources == null && VectorEnabledTintResources.shouldBeUsed()) {
            mResources = new VectorEnabledTintResources(this, super.getResources());
        }
        return mResources == null ? super.getResources() : mResources;
    }

    /**
     * KeyEvents with non-default modifiers are not dispatched to menu's performShortcut in API 25
     * or lower. Here, we check if the keypress corresponds to a menuitem's shortcut combination
     * and perform the corresponding action.
     */
    private boolean performMenuItemShortcut(int keycode, KeyEvent event) {
        if (!(Build.VERSION.SDK_INT >= 26) && !event.isCtrlPressed()
                && !KeyEvent.metaStateHasNoModifiers(event.getMetaState())
                && event.getRepeatCount() == 0
                && !KeyEvent.isModifierKey(event.getKeyCode())) {
            final Window currentWindow = getWindow();
            if (currentWindow != null && currentWindow.getDecorView() != null) {
                final View decorView = currentWindow.getDecorView();
                if (decorView.dispatchKeyShortcutEvent(event)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (performMenuItemShortcut(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void openOptionsMenu() {
        ActionBar actionBar = getSupportActionBar();
        if (getWindow().hasFeature(Window.FEATURE_OPTIONS_PANEL)
                && (actionBar == null || !actionBar.openOptionsMenu())) {
            super.openOptionsMenu();
        }
    }

    @Override
    public void closeOptionsMenu() {
        ActionBar actionBar = getSupportActionBar();
        if (getWindow().hasFeature(Window.FEATURE_OPTIONS_PANEL)
                && (actionBar == null || !actionBar.closeOptionsMenu())) {
            super.closeOptionsMenu();
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);

        ButterKnife.bind(this);
    }
}
