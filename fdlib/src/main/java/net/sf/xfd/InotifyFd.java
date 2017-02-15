/*
 * Copyright © 2016 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.xfd;

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static net.sf.xfd.InotifyFd.ERROR;
import static net.sf.xfd.InotifyFd.NIL;

/**
 * A type definition for inotify file descriptors.
 */
@Retention(SOURCE)
@IntDef({NIL, ERROR})
@Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE})
public @interface InotifyFd {
    int NIL = -2;
    int ERROR = 0x80000000;
}