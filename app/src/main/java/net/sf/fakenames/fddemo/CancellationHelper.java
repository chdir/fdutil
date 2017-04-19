package net.sf.fakenames.fddemo;

import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;

import net.sf.xfd.*;

import java.io.Closeable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

public class CancellationHelper implements Closeable {
    private static final Runnable ENOUGH = () -> {};

    private CancellationSignal signal;

    private final AtomicReference<Runnable> callback = new AtomicReference<>();

    private final Runnable cancelSignal = () -> {
        final CancellationSignal s = this.signal;
        if (s != null) {
            s.cancel();
        }
    };

    private final AsyncTask<?, ?, ?> task;

    @UiThread
    public CancellationHelper(AsyncTask<?, ?, ?> task) {
        this.task = task;
    }

    @WorkerThread
    public CancellationSignal getSignal() throws CancellationException {
        CancellationSignal signal = this.signal;

        if (signal == null) {
            signal = new CancellationSignal();
            this.signal = signal;
        }

        if (this.callback.getAndSet(cancelSignal) == ENOUGH) {
            throw new CancellationException();
        }

        return signal;
    }

    public void cancel() {
        task.cancel(true);

        final Runnable runnable = this.callback.getAndSet(ENOUGH);

        if (runnable != null) {
            runnable.run();
        }
    }

    @Override
    @UiThread
    public void close() {
    }
}
