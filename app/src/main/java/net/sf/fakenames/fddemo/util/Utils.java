package net.sf.fakenames.fddemo.util;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import net.sf.fakenames.fddemo.R;
import net.sf.fakenames.fddemo.ShortcutActivity;
import net.sf.xfd.LogUtil;
import net.sf.xfd.provider.ProviderBase;

public class Utils {
    private static final String TAG = "fddemo";

    public static String toLowerCase(String source) {
        return isAbbreviation(source) ? source : source.toLowerCase();
    }

    private static boolean isAbbreviation(String string) {
        final int l = string.length();

        if (l == 1) return false;

        for (int i = 1; i < l; ++i) {
            final boolean upperCase;

            char cur = string.charAt(i);

            if (Character.isHighSurrogate(cur) && i + 1 < l && Character.isLowSurrogate(string.charAt(i + 1))) {
                upperCase = Character.isUpperCase(Character.codePointAt(string, i++));
            } else {
                upperCase = Character.isUpperCase(cur);
            }

            if (upperCase) return true;
        }

        return false;
    }

    public static void createShortcut(Context ctx, CharSequence path, String shortcutName) {
        try {
            // Create the intent that will handle the shortcut
            final Intent shortcutIntent = new Intent(ctx, ShortcutActivity.class);
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            shortcutIntent.putExtra(ShortcutActivity.EXTRA_FSO, path);

            // The intent to send to broadcast for register the shortcut intent
            final Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName);
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(ctx, R.drawable.ic_fso_folder));
            intent.setAction("com.android.launcher.action.INSTALL_SHORTCUT"); //$NON-NLS-1$
            ctx.sendBroadcast(intent);

            // Show the confirmation
            toast(ctx, "Shortcut creation succeeded");

        } catch (Throwable e) {
            LogUtil.logCautiously(TAG, "Failed to create the shortcut", e); //$NON-NLS-1$

            toast(ctx, "Failed to create the shortcut");
        }
    }

    private static Toast toast;

    public static void toast(Context ctx, String message) {
        if (toast != null) {
            toast.cancel();
        }

        toast = Toast.makeText(ctx.getApplicationContext(), message, Toast.LENGTH_SHORT);
        toast.show();
    }
}
