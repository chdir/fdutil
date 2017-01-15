package net.sf.fakenames.fddemo;

import android.content.Context;

import net.sf.fakenames.syscallserver.Rooted;
import net.sf.fdlib.OS;

import java.io.IOException;

public class RootSingleton {
    private static volatile OS instance;

    public static OS get(Context context) throws IOException {
        if (instance == null) {
            synchronized (RootSingleton.class) {
                if (instance == null) {
                    instance = Rooted.createWithChecks(context);
                }
            }
        }

        return instance;
    }
}
