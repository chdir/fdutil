package net.sf.fakenames.fddemo;

import android.support.annotation.NonNull;

import net.sf.fdlib.Directory;
import net.sf.fdlib.UnreliableIterator;
import net.sf.fdlib.WrappedIOException;

import java.io.IOException;
import java.util.ArrayList;

public class CrappyDirectory implements Directory {
    private final Directory forwardOnlyDir;

    private final It iterator;

    public CrappyDirectory(Directory forwardOnlyDir) {
        this.forwardOnlyDir = forwardOnlyDir;

        iterator = new It(forwardOnlyDir.iterator());
    }

    private static final class It implements UnreliableIterator<Entry> {
        private final UnreliableIterator<Entry> wrapped;

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
            throw new UnsupportedOperationException("next");
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
                    bogusPosition = -1;
                    entries.clear();
                    wrapped.moveToPosition(-1);
                    lastPosition = -2;
                    // do nothing, because even rewinddir may not be supported
                    return true;
                case 0:
                    if (bogusPosition == -1) {
                        if (wrapped.moveToFirst()) {
                            final Entry e = new Entry();
                            wrapped.get(e);
                            entries.add(e);
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

            while (bogusPosition < position) {
                if (!wrapped.moveToPosition(bogusPosition + 1)) {
                    lastPosition = wrapped.getPosition();

                    return bogusPosition >= position;
                }

                ++bogusPosition;

                if (bogusPosition == entries.size()) {
                    final Entry created = new Entry();
                    wrapped.get(created);
                    entries.add(created);
                }
            }

            if (!wrapped.moveToNext()) {
                lastPosition = wrapped.getPosition();
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
