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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import net.sf.xfd.provider.FileProvider;

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

        final String packageName = getPackageName();
        final String authority = packageName + FileProvider.AUTHORITY_SUFFIX;

        final Uri uri = data.getData();
        if (!authority.equals(uri.getAuthority())) {
            Toast.makeText(this, "Unsupported file picker", Toast.LENGTH_SHORT).show();
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
