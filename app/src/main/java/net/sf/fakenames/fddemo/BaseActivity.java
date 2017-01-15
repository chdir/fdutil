package net.sf.fakenames.fddemo;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatDelegate;

import butterknife.ButterKnife;

public abstract class BaseActivity extends Activity {
    private AppCompatDelegate delegate;

    @NonNull
    protected AppCompatDelegate getDelegate() {
        if (delegate == null) {
            delegate = AppCompatDelegate.create(this, null);
        }
        return delegate;
    }

    @Override
    public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
        getDelegate().installViewFactory();

        super.onCreate(savedInstanceState, persistentState);

        getDelegate().onCreate(savedInstanceState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        getDelegate().onConfigurationChanged(newConfig);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);

        ButterKnife.bind(this);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        getDelegate().onPostCreate(savedInstanceState);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        getDelegate().onPostResume();
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);

        getDelegate().setTitle(title);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        getDelegate().onSaveInstanceState(outState);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        getDelegate().onStop();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        getDelegate().onDestroy();

        super.onDestroy();
    }
}
