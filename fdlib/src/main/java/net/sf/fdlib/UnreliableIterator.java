package net.sf.fdlib;

import android.database.Cursor;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Bidirectional iterator for live sequences of unpredictable size.
 *
 * <br/>
 *
 * This class was created for sole purpose of iterating over filesystem directories
 * using Linux getdents API. As such, it's capacity to represent other types
 * of sequences should be taken with a grain of salt.
 *
 * The design is largely inspired by {@link Cursor}, but this class does not pretend to know
 * sequence size in advance. Failed forward advancement keeps the last reached position,
 * failed backward advancement may keep the position or throw an {@link IOException}.
 *
 * <br/>
 *
 * The {@link Iterator} part of API makes a hidden assumption, that all filesystem directories
 * are non-empty, e.g. have at least '.' and/or '.' items; users of methods other than
 * {@link #hasNext} and {@link #next} are unaffected.
 *
 * <br/>
 *
 * This class keeps track of it's logical position in sequence, but does not take any measures
 * to prevent changes in underlying directory from introducing inconsistencies with it's contents.
 * A caller must subscribe to changes in the directory and use {@link #moveToPosition} to reset
 * iterator every time a change happens.
 *
 * <br/>
 *
 * Exceptions thrown by methods of this class can be caused by variety of reasons:
 * failed attempt to list directory contents backwards, OS bugs, hardware issues, severe
 * filesystem corruption and force-detaching some types of file-systems. Calling code should
 * attempt to recover from them by {@linkplain #moveToPosition resetting the iterator}, and provide
 * appropriate fallback if that fails.
 *
 * @param <E> type of element
 */
public interface UnreliableIterator<E> extends Iterator<E> {
    /**
     * Returns true, if the iterator can be further advanced. This method is inherently racy,
     * so calling {@link #next} can still cause the exception to be thrown.
     */
    @Override
    boolean hasNext();

    /**
     * Advances the iterator and returns the item at new position.
     *
     * @throws NoSuchElementException if advancement fails due to absence of next element
     * @throws RuntimeException if the advancement attempt fails for any other reason.
     */
    @Override
    @NonNull E next();

    /**
     * All modern filesystems decouple directories from files within. In Linux having access to
     * directory descriptor does not automatically imply being able to delete contained files:
     * you still have to pass a security check and/or obtain the descriptor of each file in
     * question. This is inherently racy and may to result in {@link IOException},
     * which violates both implicit and explicit contracts of {@link Iterator}.
     *
     * @deprecated always throws UnsupportedOperationException
     */
    @Override
    @Deprecated
    void remove();

    /**
     * Returns the element at current position. Can be called only at 0 and positive positions.
     *
     * @param reuse the object to use for filling out element fields (to avoid allocating new
     *              object for sequence elements.
     */
    void get(@NonNull E reuse);

    /**
     * Same as calling {@link #moveToPosition} with 0 argument
     */
    boolean moveToFirst() throws IOException;

    /**
     * Attempt to advance at the next position.
     *
     * @return true on success, otherwise false
     *
     * @throws IOException if an IO error prevents advancement
     */
    boolean moveToNext() throws IOException;

    /**
     * Attempt to return to the position before current.
     *
     * @return true on success, otherwise false
     *
     * @throws IOException if an IO error prevents movement
     */
    boolean moveToPrevious() throws IOException;

    /**
     * Attempt to advance at specified position.
     *
     * @param position position starting from -1
     *
     * @return true on success, otherwise false
     *
     * @throws IOException if an IO error prevents advancement
     * @throws IllegalArgumentException if passed position is < -1
     */
    boolean moveToPosition(int position) throws IOException;

    /**
     * @return the logical position current element, may be -1 or above highest present position
     */
    int getPosition();
}
