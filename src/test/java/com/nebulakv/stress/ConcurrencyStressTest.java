package com.nebulakv.stress;

import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent stress tests for the core store and cluster components.
 *
 * These tests run many threads simultaneously to expose race conditions,
 * lost updates, and liveCount drift that unit tests cannot catch.
 */
@DisplayName("Concurrency stress tests")
class ConcurrencyStressTest {

    private static final int THREADS    = 16;
    private static final int OPS_THREAD = 500;

    @Test
    @DisplayName("concurrent puts maintain consistent liveCount")
    void concurrentPutsLiveCount() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        int uniqueKeys = 200;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < OPS_THREAD; i++) {
                    String key = "key-" + (i % uniqueKeys);
                    store.put(key, "v-" + tid + "-" + i);
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            try { f.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { fail("Thread threw: " + e); }
        }
        pool.shutdown();

        // liveCount must equal the number of distinct keys that exist.
        long actualLive = 0;
        for (int i = 0; i < uniqueKeys; i++) {
            if (store.get("key-" + i).isPresent()) actualLive++;
        }
        assertEquals(actualLive, store.size(),
                "liveCount must match the actual number of live keys");
    }

    @Test
    @DisplayName("concurrent puts and deletes maintain correct liveCount")
    void concurrentPutsAndDeletes() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        int uniqueKeys = 100;

        // Pre-populate all keys.
        for (int i = 0; i < uniqueKeys; i++) store.put("k-" + i, "init");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < OPS_THREAD; i++) {
                    String key = "k-" + (i % uniqueKeys);
                    if (i % 3 == 0) store.delete(key);
                    else            store.put(key, "v-" + tid);
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            try { f.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { fail("Thread threw: " + e); }
        }
        pool.shutdown();

        // After all ops, liveCount must match actual live keys.
        long actualLive = 0;
        for (int i = 0; i < uniqueKeys; i++) {
            if (store.get("k-" + i).isPresent()) actualLive++;
        }
        assertEquals(actualLive, store.size(),
                "liveCount must match actual live keys after concurrent puts and deletes");
    }

    @Test
    @DisplayName("concurrent reads never see partial state")
    void concurrentReadsNeverPartialState() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        String key = "shared";
        store.put(key, "initial");

        AtomicInteger errors = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // Writers: update the value continuously.
        for (int t = 0; t < THREADS / 2; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < OPS_THREAD; i++) {
                    store.put(key, "v-" + tid + "-" + i);
                }
            }));
        }

        // Readers: read and validate that a non-null value is returned (no torn reads).
        for (int t = 0; t < THREADS / 2; t++) {
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < OPS_THREAD; i++) {
                    Optional<String> val = store.get(key);
                    // The key is always written, never deleted. Must always be present.
                    if (val.isEmpty()) errors.incrementAndGet();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            try { f.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { fail("Thread threw: " + e); }
        }
        pool.shutdown();

        assertEquals(0, errors.get(), "Readers must never see an absent value for a live key");
    }

    @Test
    @DisplayName("high-throughput put measures baseline ops/sec")
    void throughputBaseline() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        int totalOps = THREADS * OPS_THREAD * 4;
        AtomicLong completed = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < OPS_THREAD * 4; i++) {
                    store.put("key-" + (i % 1000), "v-" + tid);
                    completed.incrementAndGet();
                }
            }));
        }

        long startMs = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); }
            catch (Exception e) { fail("Thread threw: " + e); }
        }
        pool.shutdown();

        long elapsedMs = System.currentTimeMillis() - startMs;
        long opsPerSec = completed.get() * 1000L / Math.max(elapsedMs, 1);

        // Sanity: in-memory store must handle at least 100k ops/sec on CI.
        assertTrue(opsPerSec >= 100_000,
                "Expected >= 100k ops/sec, got " + opsPerSec);

        System.out.printf("[ConcurrencyStressTest] throughput: %,d ops/sec%n", opsPerSec);
    }
}
