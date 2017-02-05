package net.sf.fakenames.fddemo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import net.sf.fakenames.fddemo.provider.FileProvider;

public final class SAFActivity extends Activity {
    private IntentHandler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new IntentHandler(this, Intent.ACTION_VIEW);

        final Intent intent1 = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*");

        try {
            startActivityForResult(intent1, R.id.req_open_file);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to launch file picker", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != R.id.req_open_file) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }

        final Uri uri = data.getData();
        if (!FileProvider.AUTHORITY.equals(uri.getAuthority())) {
            Toast.makeText(this, "Unsupported provder", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final String path = DocumentsContract.getDocumentId(uri);

        final Intent intent = handler.createIntentForFile(path, false);
        if (intent == null) {
            Toast.makeText(this, "Failed to construct uri", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to launch activity", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
