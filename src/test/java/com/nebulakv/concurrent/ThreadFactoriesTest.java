package com.nebulakv.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThreadFactories — virtual thread availability and executor behaviour")
class ThreadFactoriesTest {

    @Test
    @DisplayName("virtualThreadsAvailable reflects JVM version")
    void availabilityReflectsVersion() {
        int version = Runtime.version().feature();
        assertEquals(version >= 21, ThreadFactories.virtualThreadsAvailable());
    }

    @Test
    @DisplayName("namedFactory produces daemon threads with given prefix")
    void namedFactoryProducesDaemonThreads() throws InterruptedException {
        ThreadFactory factory = ThreadFactories.namedFactory("test-worker");
        CountDownLatch latch = new CountDownLatch(1);
        String[] name = new String[1];
        boolean[] daemon = new boolean[1];

        Thread t = factory.newThread(() -> {
            name[0]   = Thread.currentThread().getName();
            daemon[0] = Thread.currentThread().isDaemon();
            latch.countDown();
        });
        t.start();
        latch.await(5, TimeUnit.SECONDS);

        assertTrue(name[0].startsWith("test-worker-"), "Thread name must start with prefix");
        assertTrue(daemon[0], "Thread must be a daemon");
    }

    @Test
    @DisplayName("newPerTaskExecutor runs submitted tasks")
    void perTaskExecutorRunsTasks() throws InterruptedException {
        ExecutorService exec = ThreadFactories.newPerTaskExecutor("eval");
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            exec.submit(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within 10s");
        assertEquals(10, counter.get());
        exec.shutdown();
    }

    @Test
    @DisplayName("newPerTaskExecutor handles many concurrent I/O-like tasks")
    void perTaskExecutorHandlesConcurrentTasks() throws InterruptedException {
        int taskCount = 1_000;
        ExecutorService exec = ThreadFactories.newPerTaskExecutor("io-sim");
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < taskCount; i++) {
            exec.submit(() -> {
                try {
                    // Simulate brief I/O wait
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS),
                "All " + taskCount + " tasks must complete");
        assertEquals(0, errors.get(), "No tasks should be interrupted");
        exec.shutdown();
    }
}
