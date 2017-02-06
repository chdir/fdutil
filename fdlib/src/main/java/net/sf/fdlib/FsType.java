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
package net.sf.fdlib;

public enum FsType {
    BLOCK_DEV(6),
    CHAR_DEV(2),
    NAMED_PIPE(1),
    DOMAIN_SOCKET(12),
    LINK(10),
    FILE(8),
    DIRECTORY(4),

    // catch-all for yet unknown file types
    MYSTERY(0);

    private int nativeType;

    FsType(int nativeType) {
        this.nativeType = nativeType;
    }

    public boolean isSpecial() {
        switch (this) {
            case LINK:
            case FILE:
            case DIRECTORY:
            case MYSTERY:
                return false;
            default:
                return true;
        }
    }

    public boolean isNotDir() {
        switch (this) {
            case DIRECTORY:
            case LINK:
            case MYSTERY:
                return false;
            default:
                return true;
        }
    }

    private static final FsType[] VALUES = values();

    static FsType forDirentType(int nativeType) {
        // the type can not be retrieved at this time because the filesystem either does not
        // support getting file types, or simply not return them during directory iteration
        // for efficiency reasons
        if (nativeType == MYSTERY.nativeType) return null;

        for (FsType value : VALUES) {
            if (value.nativeType == nativeType) {
                return value;
            }
        }

        // Oops
        return MYSTERY;
    }

    static FsType at(int ordinal) {
        return VALUES[ordinal];
    }
}
