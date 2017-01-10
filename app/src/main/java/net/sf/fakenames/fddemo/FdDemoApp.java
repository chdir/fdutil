package net.sf.fakenames.fddemo;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;

import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.fdlib.OS;

public final class FdDemoApp extends Application {
    static {
        System.setProperty(SyscallFactory.DEBUG_MODE, "true");
        System.setProperty(OS.DEBUG_MODE, "true");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        new Handler().post(() -> StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build()));
    }
}
