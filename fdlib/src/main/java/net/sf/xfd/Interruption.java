package net.sf.xfd;

import android.app.Application;
import android.os.Process;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;

/**
 * A low-level primitive for task cancellation. This class wakes up a thread blocked in native code
 * by sending a Linux signal.
 *
 * This class is not thread-safe: using it in multiple threads at once may result in waking up
 * unexpected threads or failed interruptions. If any instance of this class gets garbage-collected
 * before the signalled thread, a memory corruption may occur. The nature of this class makes it
 * prone to the common kinds of ABA problems. You are strongly advised to make each instance
 * thread-local, limit it's exposure and use appropriate synchronization.
 *
 * Using this class incurs no extra overhead on fast path: it does not internally use volatile reads
 * or synchronization. This is similar to one of approaches used by librcu, except this class actually
 * uses both properties of signals: semi-reliable interruption and implicit memory barrier.
 *
 * Signals can interrupt most kinds of Linux system calls, but actual act of sending a signal is costly
 * and results in 2 to 3 extra context switches compared to simply closing the descriptor or shutting down
 * a network connection. If you need to frequently interrupt read/write operations, you are better off
 * using the {@link InterruptibleChannel} implementations (which internally close the descriptor when
 * Thread gets interrupted). On other hand, some system calls, such as {@code sendfile} or blocking {@code open}
 * can not be interrupted without use of signals.
 *
 * When Linux system calls get interrupted by signal, some may return with {@code EINTR} errno;
 * {@code read}/{@code write} may report short reads/writes, and threads blocked in time-based waiting
 * ({@code eopll}, {@code sleep} etc.) may experience spurious wake ups. Some library methods
 * automatically retry in such cases, making them incompatible with this class. For an example of
 * class with built-in support, see {@link FdStream}.
 */
public final class Interruption implements Closeable {
    private static final OS os = init();
    private static final GuardFactory factory = GuardFactory.getInstance(os);
    private static final ThreadLocal<Integer> tidCache = new TidCache();

    private static OS init() {
        try {
            OS result = Android.getInstance();

            nativeInit();

            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    public static @NonNull Interruption newInstance() {
        return nativeCreate();
    }

    final Guard guard;

    final long nativePtr;

    final ByteBuffer buf;

    @Keep
    private Interruption(ByteBuffer buf, long nativePtr) {
        this.buf = buf;
        this.nativePtr = nativePtr;
        this.guard = factory.forMemory(this, nativePtr);
    }

    /**
     * Remove the signal used by this class from list of signals, blocked within the current thread.
     *
     * Blocking (also sometimes called "masking") is a mechanism for controlling signal delivery.
     * It is occasionally used to defend against reentrancy issues or to achieve predictable
     * performance in face of high signal delivery rate. Signal handler installed by this class does
     * nothing except safely setting a single variable in memory, so it is always safe to unmask.
     *
     * Linux processes have empty signal masks after creation, but Android runtime or third-party
     * libraries may change that, possibly blocking some or all signals. Signal masks are inherited
     * across thread creation, so you should call this method either as early as possible during
     * {@link Application} startup or at least once in the {@link Thread#run} method of each thread
     * you expect to signal.
     */
    public static native void unblockSignal();

    /**
     * Obtains cached Linux thread ID, that can be used with {@link #interrupt}
     *
     * @return thread ID of the calling thread
     */
    public static int myTid() {
        return tidCache.get();
    }

    /**
     * Interrupts the thread with specified thread ID by sending it a signal.
     *
     * This method is similar in purpose to {@link Thread#interrupt}, but works independently from it
     * and affects only this instance.
     *
     * The  target thread will receive a Linux signal, automatically picked from list of unused
     * signal numbers. If the thread is performing a blocking OS call, and the signal is not masked,
     * that call might be interrupted in implementation-dependant manner. The thread should then check
     * the conditional and actually interrupt whatever it was doing.
     *
     * @param tid native thread ID (as returned by {@link Process#myTid()}
     */
    public void interrupt(int tid) {
        nativeInterrupt(nativePtr, tid);
    }

    /**
     * Read and clear the interruption state of calling thread.
     *
     * This method is similar in purpose to {@link Thread#interrupted}, but works independently from it
     * and affects only this instance.
     *
     * @return whether the thread was previously
     */
    public boolean interrupted() {
        return nativeInterrupted(nativePtr);
    }

    /**
     * Check for interruption status.
     *
     * This method is similar in purpose to {@link Thread#isInterrupted}, but works independently from it
     * and affects only this instance.
     *
     * This method is hella fast.
     */
    public boolean isInterrupted() {
        return buf.get(0) != 0;
    }

    private static native void nativeInit();

    private static native boolean nativeInterrupt(long nativePtr, int tid);

    private static native boolean nativeInterrupted(long nativePtr);

    private static native Interruption nativeCreate();

    @Override
    public void close() {
        guard.close();
    }

    private static final class TidCache extends ThreadLocal<Integer> {
        @Override
        protected Integer initialValue() {
            return Process.myTid();
        }
    }
}
