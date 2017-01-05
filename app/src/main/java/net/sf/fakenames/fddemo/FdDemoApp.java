package net.sf.fakenames.fddemo;

import android.app.Application;

import net.sf.fakenames.syscallserver.SyscallFactory;
import net.sf.fdlib.OS;

public final class FdDemoApp extends Application {
    static {
        System.setProperty(SyscallFactory.DEBUG_MODE, "true");
        System.setProperty(OS.DEBUG_MODE, "true");
    }
}
