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
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatDelegate;
import android.view.MenuInflater;

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
