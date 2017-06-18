package net.sf.fakenames.fddemo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class AppPrefs {
    private static final String PREF_USER_IDENTIFIER = "XFD_UID";

    @SuppressWarnings("WrongConstant")
    public static SharedPreferences get(Context context) {
        final Context appCtx = context.getApplicationContext();

        return (SharedPreferences) appCtx.getSystemService(FdDemoApp.PREFS);
    }

    public static String getUserId(Context context) {
        final SharedPreferences prefs = get(context);

        String currentUid = prefs.getString(PREF_USER_IDENTIFIER, null);

        if (currentUid == null) {
            final UUID random = UUID.randomUUID();

            currentUid = random.toString();

            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_USER_IDENTIFIER, currentUid);
            editor.apply();
        }

        return currentUid;
    }
}
