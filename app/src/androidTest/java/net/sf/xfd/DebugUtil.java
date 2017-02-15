package net.sf.xfd;

import com.carrotsearch.hppc.LongContainer;

public class DebugUtil {
    public static String getCookieList(Directory directory) {
        LongContainer lc = ((DirectoryImpl) directory).cookieCache;
        return "<"  + lc.size() + "> " + lc.toString();
    }
}
