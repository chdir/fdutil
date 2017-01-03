package net.sf.fdlib;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import static net.sf.fdlib.Fd.*;

/**
 * A type definition for file descriptors. This annotation is not meant to be used with
 * raw values except when those are well-known values for standard stream (0, 1, 2).
 */
@Retention(SOURCE)
@IntDef({NIL, ERROR, STDIN, STDOUT, STDERR})
@Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE})
public @interface Fd {
    /**
     * Invalid descriptor sentinel for indicating absence of value (a {@code null} replacement)
     */
    int NIL = -1;

    /**
     * Invalid descriptor sentinel for indicating an error condition
     */
    int ERROR = 0x80000000;

    int STDIN = 0;
    int STDOUT = 1;
    int STDERR = 2;
}
