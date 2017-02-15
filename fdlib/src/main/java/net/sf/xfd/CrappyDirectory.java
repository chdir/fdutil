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

import android.support.annotation.NonNull;

import net.openhft.hashing.XxHash_r39_Custom;

import java.io.IOException;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * A wrapper for {@link Directory}, that keeps all already read contents in memory.
 *
 * <p/>
 *
 * As the name implies, this API is meant to be used when you need to deal with really, really
 * terrible, non-Posix-compliant filesystems. This includes FAT32, exFAT and any FUSE/unionfs
 * filesystems, that does not support {@code telldir} and/or does not report proper inode numbers.
 *
 * <p/>
 *
 * This class does not {@code seek} the underlying directory except rewinding to 0 position when you
 * call {@code moveToPosition(-1)}. Instead of returning inode numbers, reported by underlying
 * filesystem, it reports filename hashes. The same hashes are returned from {@link #getOpaqueIndex}.
 */
public class CrappyDirectory implements Directory {
    private final Directory forwardOnlyDir;

    private final It iterator;

    public CrappyDirectory(Directory forwardOnlyDir) {
        this.forwardOnlyDir = forwardOnlyDir;

        iterator = new It(forwardOnlyDir.iterator());
    }

    private static final class It implements UnreliableIterator<Entry> {
        private final UnreliableIterator<Entry> wrapped;

        private final XxHash_r39_Custom.AsLongHashFunctionSeeded hash =
                XxHash_r39_Custom.asLongHashFunctionWithSeed(System.currentTimeMillis());

        It(UnreliableIterator<Entry> wrapped) {
            this.wrapped = wrapped;
        }

        private ArrayList<Entry> entries = new ArrayList<>();

        private int bogusPosition = -1;

        @Override
        public boolean hasNext() {
            return lastPosition != bogusPosition;
        }

        private int lastPosition = -2;

        @NonNull
        @Override
        public Entry next() {
            try {
                final int currentPosition = bogusPosition;

                if (currentPosition == -1) {
                    if (!moveToFirst()) {
                        throw new WrappedIOException(new IOException("The directory is empty"));
                    }
                } else if (lastPosition == currentPosition) {
                    throw new NoSuchElementException("position = " + bogusPosition);
                }

                final Entry newEntry = new Entry();

                get(newEntry);

                moveToNext();

                return newEntry;
            } catch (IOException e) {
                throw new WrappedIOException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }

        @Override
        public void get(@NonNull Entry reuse) {
            if (bogusPosition == -1) {
                throw new IllegalStateException("Attempting to get element at position -1");
            }

            final Entry existing = entries.get(bogusPosition);

            reuse.ino = existing.ino;
            reuse.type = existing.type;
            reuse.name = existing.name;
        }

        @Override
        public boolean moveToNext() throws IOException {
            return moveToPosition(bogusPosition + 1);
        }

        private void appendEntry() {
            final Entry entry = new Entry();
            wrapped.get(entry);
            entry.ino = hash.hashNativeChars(entry.name);
            entries.add(entry);
        }

        @Override
        public int getPosition() {
            return bogusPosition;
        }

        @Override
        public boolean moveToFirst() throws IOException {
            return moveToPosition(0);
        }

        @Override
        public boolean moveToPrevious() throws IOException {
            return moveToPosition(bogusPosition -1);
        }

        @Override
        public boolean moveToPosition(int position) throws IOException {
            if (position == bogusPosition) {
                return true;
            }

            if (lastPosition > 0 && position > lastPosition) {
                return false;
            }

            switch (position) {
                case -1:
                    entries.clear();
                    bogusPosition = -1;
                    wrapped.moveToPosition(-1);
                    lastPosition = -2;
                    return true;
                case 0:
                    if (bogusPosition == -1) {
                        if (wrapped.moveToFirst()) {
                            appendEntry();
                            bogusPosition = 0;
                            return true;
                        } else {
                            return false;
                        }
                    }
                default:
                    if (position < entries.size() - 1) {
                        bogusPosition = position;
                        return true;
                    }
            }

            if (bogusPosition == -1 && !moveToFirst()) {
                return false;
            }

            bogusPosition = wrapped.getPosition();

            while (bogusPosition < position) {
                if (!wrapped.moveToPosition(bogusPosition + 1)) {
                    lastPosition = wrapped.getPosition();

                    return bogusPosition >= position;
                }

                ++bogusPosition;

                if (bogusPosition == entries.size()) {
                    appendEntry();
                }
            }

            return true;
        }
    }

    @Override
    public UnreliableIterator<Entry> iterator() {
        try {
            iterator.moveToPosition(-1);

            return iterator;
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }
    }

    @Override
    public long getOpaqueIndex(int position) {
        if (position >= iterator.entries.size()) {
            return -1L;
        }

        return iterator.entries.get(position).ino;
    }

    @Override
    public void close() {
        forwardOnlyDir.close();
    }
}
