package net.sf.fdlib;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static net.sf.fdlib.DirFd.ERROR;
import static net.sf.fdlib.DirFd.NIL;

/**
 * A type definition for inotify file descriptors.
 */
@Retention(SOURCE)
@IntDef({NIL, ERROR})
@Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE})
public @interface InotifyFd {
    int NIL = -1;
    int ERROR = 0x80000000;
}