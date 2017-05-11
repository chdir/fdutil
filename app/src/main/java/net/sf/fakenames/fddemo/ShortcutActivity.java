package net.sf.fakenames.fddemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import net.sf.fakenames.fddemo.util.Utils;

public final class ShortcutActivity extends Activity {
    public static final String EXTRA_FSO = "net.sf.xfd.FSO";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();

        final String path = intent.getStringExtra(EXTRA_FSO);

        if (!TextUtils.isEmpty(path) && path.charAt(0) == '/') {
            final Intent delegate = new Intent(this, MainActivity.class);
            delegate.putExtra(EXTRA_FSO, path);
            delegate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            delegate.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(delegate);
        } else {
            Utils.toast(this, "Failed to parse shortcut info");
        }

        finish();
    }
}
