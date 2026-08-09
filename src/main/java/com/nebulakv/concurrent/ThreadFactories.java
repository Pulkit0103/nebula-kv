package com.nebulakv.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread factory helpers that abstract over platform threads (Java 17) and
 * virtual threads (Java 21+).
 *
 * Why virtual threads?
 *   The JVM traditionally maps one Java thread to one OS thread. Under high
 *   concurrency, this limits throughput because OS threads are expensive
 *   (~1 MB stack each) and context-switching overhead grows with thread count.
 *
 *   Virtual threads (Project Loom, GA in Java 21) are scheduled by the JVM
 *   instead of the OS. They cost ~few KB each, making millions feasible.
 *   This is particularly valuable for NebulaKV's network accept loop and
 *   replica dispatch, where each connection/request blocks briefly on I/O.
 *
 * Compatibility:
 *   This class detects the JVM version at runtime. On Java 21+ it uses
 *   Executors.newVirtualThreadPerTaskExecutor() via the public API (reflected
 *   here to compile on Java 17 without preview flags). On Java 17 it falls
 *   back to a cached thread pool.
 *
 *   When the project is compiled and run on Java 21+, replace the reflection
 *   path with a direct call to Executors.newVirtualThreadPerTaskExecutor().
 */
public final class ThreadFactories {

    private static final int JAVA_VERSION = Runtime.version().feature();
    static final boolean VIRTUAL_THREADS_AVAILABLE = JAVA_VERSION >= 21;

    private ThreadFactories() {}

    /**
     * Returns an ExecutorService that uses virtual threads on Java 21+,
     * or a cached thread pool on Java 17.
     *
     * Use this for I/O-bound work where each task blocks on network or disk.
     */
    public static ExecutorService newPerTaskExecutor(String namePrefix) {
        if (VIRTUAL_THREADS_AVAILABLE) {
            return newVirtualPerTaskExecutor(namePrefix);
        }
        return Executors.newCachedThreadPool(namedFactory(namePrefix));
    }

    /**
     * Returns a ThreadFactory that produces daemon threads with a sequential
     * name ({@code namePrefix}-1, -{@code namePrefix}-2, …).
     */
    public static ThreadFactory namedFactory(String namePrefix) {
        AtomicLong counter = new AtomicLong(0);
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Returns whether virtual threads are available on the current JVM.
     * Useful for conditional logic and diagnostic logging.
     */
    public static boolean virtualThreadsAvailable() {
        return VIRTUAL_THREADS_AVAILABLE;
    }

    // Reflection-based virtual thread executor for Java 21+ at runtime.
    // On Java 17 this method is never called.
    private static ExecutorService newVirtualPerTaskExecutor(String namePrefix) {
        try {
            // Java 21: Thread.ofVirtual().name(namePrefix, 0).factory()
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Class<?> builderClass = builder.getClass().getInterfaces()[0];
            builder = builderClass.getMethod("name", String.class, long.class)
                    .invoke(builder, namePrefix + "-vt-", 0L);
            ThreadFactory factory = (ThreadFactory) builderClass.getMethod("factory").invoke(builder);
            return (ExecutorService) Executors.class
                    .getMethod("newThreadPerTaskExecutor", ThreadFactory.class)
                    .invoke(null, factory);
        } catch (Exception e) {
            // Graceful fallback — should not happen on Java 21+.
            return Executors.newCachedThreadPool(namedFactory(namePrefix));
        }
    }
}
