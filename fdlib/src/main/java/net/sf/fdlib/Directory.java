package net.sf.fdlib;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.Closeable;
import java.io.File;

public interface Directory extends Iterable<Directory.Entry>, Closeable, Cloneable {
    /**
     * Iterators over Linux directories are weakly consistent. That is — they can usually
     * preserve their current position during failed advancement attempts, but aren't guaranteed to
     * do so. In particular, seeking backwards always has a chance to fail with exception,
     * leaving the iterator in inconsistent state. You will generally want to reset the iterator
     * by calling {@link UnreliableIterator#moveToPosition} with {@code -1} argument whenever any
     * exception occurs. Furthermore, Posix allows directory reading APIs to return just-renamed
     * files twice (and generally does not specify behavior in such situations), so even results of
     * simple forward iteration are not guaranteed to be consistent (the same applies to "normal"
     * directory APIs, such as {@code readdir} and {@link File#list}). If you want a consistent
     * view of the directory, subscribe via inotify <strong>before</strong> listing it's
     * contents.
     *
     * <b/>
     *
     * Note, that the inconsistencies in iterators contents do not apply to results of
     * {@link #getOpaqueIndex} — those are expected to be consistent at all times.
     *
     * @return the iterator over directory entries
     *
     * @see UnreliableIterator
     * @see <a href="http://pubs.opengroup.org/onlinepubs/9699919799/">Posix readdir() page</a>
     */
    @Override
    UnreliableIterator<Entry> iterator();

    /**
     * Opaque indexes are 64-bit unsigned values, assigned to each entry in a directory.
     * They are unique within the directory.
     *
     * Opaque indexes are cached after advancing towards new position in order to implement
     * movement backwards in the directory stream.
     *
     * <b/>
     *
     * {@code -1L} and {@code 0L} are reserved values (the later one corresponds to the first entry
     * in the directory).
     *
     * @return opaque filesystem indexes, corresponding to given logical position or {@code -1L}, if the position has not been visited yet
     */
    long getOpaqueIndex(int position);

    @SuppressWarnings("NullableProblems")
    class Entry {
        /**
         * Linux inode number. Uniquely identifies each "file" within a parent filesystem.
         *
         * <p/>
         *
         * Note, that this is not the same thing as logical indexes from {@link #getOpaqueIndex},
         * and these can not be used interchangeably: logical indexes are both semi-persistent and
         * <i>"truly" unique</i> within directory, while same inode number can be shared by
         * multiple files (also known as "hard links") or even have specific meaning for filesystem
         * (usually the case for 0 and other small numbers).
         */
        public long ino;

        /**
         * File type. May be null even for files of known types — some filesystems do not store that
         * metadata within same structures as directory contents, so you may have to make a
         * separate {@code stat} call to determine it.
         */
        @Nullable
        public FsType type;

        /**
         * Name of file. May be empty.
         */
        @NonNull
        public String name;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;

            if (o == null || getClass() != o.getClass()) return false;

            Entry entry = (Entry) o;

            return ino == entry.ino;
        }

        @Override
        public int hashCode() {
            return (int) (ino ^ (ino >>> 32));
        }

        @Override
        public String toString() {
            return "" + name + ":" + type + " " + ino;
        }
    }

    @Override
    void close();

    Directory clone();
}
