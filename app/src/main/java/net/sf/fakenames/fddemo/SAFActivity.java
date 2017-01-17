package net.sf.fakenames.fddemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SAFActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent1 = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("*/*");

        startActivity(intent1);

        finish();
    }
}
