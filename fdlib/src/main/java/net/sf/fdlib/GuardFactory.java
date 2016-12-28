package net.sf.fdlib;

import java.io.Closeable;
import java.io.IOException;

/**
 * A basic facility for implementing safe object disposal without {@code finalize}. Use it
 * to secure native resources, <strong>owned</strong> by objects. The use is simple: assign
 * created {@link Guard} to the private field of Java object after resource allocation
 * and close the guard in the {@link Closeable#close} of the object. Never expose the guard!
 * If the object gets collected without being properly destroyed, the guard will take care of
 * resource disposal.
 */
public abstract class GuardFactory {
    /**
     * Creates a guard for off-heap memory allocation.
     */
    public abstract Guard forMemory(Closeable scope, long memoryPointer);

    /**
     * Creates a guard for native file descriptor.
     */
    public abstract Guard forDescriptor(Closeable scope, @Fd int fileDescriptor);

    private static volatile GuardFactory defaultFactory;

    public static GuardFactory getInstance(OS os) {
        if (defaultFactory == null) {
            synchronized (GuardFactory.class) {
                if (defaultFactory == null) {
                    defaultFactory = new BlockingGuards(os);
                }
            }
        }

        return defaultFactory;
    }
}
