package net.sf.fakenames.fddemo;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    @SuppressWarnings("WrongConstant")
    public static SharedPreferences get(Context context) {
        final Context appCtx = context.getApplicationContext();

        return (SharedPreferences) appCtx.getSystemService(FdDemoApp.PREFS);
    }
}
