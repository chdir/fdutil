package net.sf.fakenames.fddemo;

import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import net.sf.xfd.CloseableGuard;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

@RunWith(AndroidJUnit4.class)
public class GuardTests {
    private static final int RUNS = 1000;

    private AtomicInteger counter = new AtomicInteger();

    @Test
    public void phantomCollected() throws InterruptedException {
        counter.set(0);

        makeGarbage();

        final Runtime r = Runtime.getRuntime();
        r.gc();

        Thread.sleep(400);

        r.runFinalization();

        Thread.sleep(400);

        r.gc();

        Thread.sleep(200);

        assertThat(counter.get()).isEqualTo(RUNS);
    }

    private void makeGarbage() {
        for (int i = 0; i < RUNS; ++i) {
            new MetaGuard(new Object());
        }
    }

    private final class MetaGuard extends CloseableGuard<Object> {
        protected MetaGuard(Object r) {
            super(r);
        }

        @Override
        protected void trigger() {
            counter.incrementAndGet();
        }
    }
}
