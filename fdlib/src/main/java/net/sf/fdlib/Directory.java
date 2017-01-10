package net.sf.fdlib;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.Closeable;
import java.io.File;

/**
 * A semi-living window into filesystem directory. This class offers a way to perform bidirectional
 * iteration over directory contents without fully storing them in memory.
 *
 * <p/>
 *
 * Linux/Posix directories do have concept of "position", made available by {@code telldir} and
 * {@code seekdir} C library functions. Unfortunately, that "position" is not required to be
 * a sequential index, and some filesystems (such as ext4) use filename hashes or other opaque
 * values to uniquely identify a file within directory. This class exposes a mapping between those
 * values (to avoid further confusion let's call them "opaque indexes") and logical positions,
 * perceived by user. This mapping is not guaranteed to be stable, so caller should take their own
 * measures to handle any inconsistencies. Same goes for number of directory children.
 *
 * <p/>
 *
 * Total count of files within directory is not known at the time of calling {@code open} on
 * directory descriptor and generally is not stored in filesystem at all. The only way to learn
 * it is manually counting files by iterating over directory contents:
 *
 * <pre>
 *     Directory dir = ...
 *     UnreliableIterator iterator = dir.iterator();
 *     iterator.moveToPosition(Integer.MAX_VALUE);
 *     int filesTotal = iterator.getPosition() + 1;
 * </pre>
 *
 * Doing so may take a substantial amount of time for very large directories. If you use this class
 * to present a list of files in UI, consider not counting files in advance, but instead returning
 * {@link Integer#MAX_VALUE} as "count" and refreshing the list contents as soon as the count is
 * known.
 *
 * <p/>
 *
 * Not all filesystems properly support readdir/getdents API. Many poorly-written FUSE/unionfs
 * plugins, including infamous Android FUSE Daemon (aka "sdcard"), return garbage {@code telldir}
 * values or randomly fail during directory seeking, making backward iteration impossible. FAT
 * and some exotic kernel filesystems may not have appropriate on-disk data structures and often end
 * up incurring substantial overhead or implementing {@code telldir} incorrectly.
 *
 * See {@link CrappyDirectory} for reference implementation, that lazily caches directory contents
 * and works around some limitations of FAT-like filesystems.
 *
 * Even filesystems with token support for {@code telldir} rarely implement it flawlessly.
 * For example, ext3/4 use hashing to generate opaque indexes (which creates possibility of collisions)
 * and still do not guarantee flawless backward iteration after directory changes (e.g. a file or two
 * may be missing and duplicates may appear).
 *
 * <p/>
 *
 * This class is not thread-safe.
 *
 * <p/>
 *
 * See also:<br/>
 *
 * https://android.googlesource.com/platform/system/core/+/75e17a8908d52e32f5de85b90b74e156265c60c6^!/
 * <br/>
 * http://lkml.iu.edu/hypermail/linux/kernel/0704.1/1243.html
 */
public interface Directory extends Iterable<Directory.Entry>, Closeable {
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
     * <b/>
     *
     * <strong>Calling this method resets current directory offset.</strong>
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
     * This class caches opaque indexes after every advancement towards new position. This is done
     * in order to implement movement backwards in the directory stream.
     *
     * <p/>
     *
     * {@code -1L} and {@code 0L} are reserved values (the later one corresponds to the first entry
     * in the directory).
     *
     * <p/>
     *
     * Opaque indexes may be persistent until/after reboot, but aren't guaranteed to. They are
     * guaranteed to remain stable until closing or rewinding the directory.
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
         *
         * <p/>
         *
         * Some filesystems of old are known to return {@code 0} inode number to userspace
         * in order to indicate a deleted file (e.g. an empty space in directory structure, that
         * hasn't been filled yet). It seems, that such behavior is not seen in modern
         * Linux filesystems anymore — https://bugzilla.redhat.com/show_bug.cgi?id=1066751, but
         * callers are advised to avoid filtering out such entries early in low-level code, because
         * they may still appear because of bugs, hash collisions or changes to current behavior.
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

    /**
     * Release resources, associated with this wrapper. The underlying directory descriptor
     * is unaffected by this call and must be closed separately.
     *
     * <p/>
     *
     * This method is idempotent, second and following calls have no effect.
     */
    @Override
    void close();
}
