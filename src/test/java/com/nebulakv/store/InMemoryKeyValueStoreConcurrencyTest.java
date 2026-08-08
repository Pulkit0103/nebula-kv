package com.nebulakv.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryKeyValueStore — concurrency tests")
class InMemoryKeyValueStoreConcurrencyTest {

    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 1000;

    @Test
    @DisplayName("concurrent puts from many threads produce consistent size")
    void concurrentPutsProduceConsistentSize() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        // Each thread writes to its own key space — no overwrites.
                        store.put("t" + threadId + ":k" + i, "v" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        long expected = (long) THREADS * OPS_PER_THREAD;
        assertEquals(expected, store.size(),
                "Expected " + expected + " entries but got " + store.size());
    }

    @Test
    @DisplayName("concurrent reads and writes do not throw or corrupt data")
    void concurrentReadsAndWrites() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        // Seed some initial data.
        for (int i = 0; i < 100; i++) {
            store.put("seed:" + i, "init");
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        try {
                            if (i % 3 == 0) {
                                store.put("t" + threadId + ":k" + i, "v" + i);
                            } else if (i % 3 == 1) {
                                store.get("seed:" + (i % 100));
                            } else {
                                store.exists("seed:" + (i % 100));
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, errors.get(), "Concurrent read/write produced " + errors.get() + " errors");
    }

    @Test
    @DisplayName("concurrent deletes are idempotent and do not go negative on size")
    void concurrentDeletesAreIdempotent() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        String key = "shared-key";
        store.put(key, "value");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    store.delete(key);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertFalse(store.exists(key), "key should be deleted");
        assertTrue(store.size() >= 0, "size must never be negative");
    }

    @Test
    @DisplayName("put-get sequence maintains happens-before under contention")
    void putGetHappensBefore() throws InterruptedException, ExecutionException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        String key = "hb-key";
        String value = "hb-value";

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Writer puts the value.
        Future<?> writer = pool.submit(() -> store.put(key, value));
        writer.get(); // wait for write to complete

        // Reader must see the written value — happens-before is established.
        Future<Optional<String>> reader = pool.submit(() -> store.get(key));
        Optional<String> result = reader.get();

        pool.shutdown();

        assertEquals(Optional.of(value), result,
                "Reader must observe the value written before it started");
    }

    @Test
    @DisplayName("concurrent puts to the same key always leave exactly one value")
    void concurrentPutsToSameKey() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        String key = "contested";
        int writers = THREADS;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<String> writtenValues = new CopyOnWriteArrayList<>();

        for (int t = 0; t < writers; t++) {
            final String val = "val-" + t;
            writtenValues.add(val);
            pool.submit(() -> {
                try {
                    start.await();
                    store.put(key, val);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one value should be present; it must be one of the written values.
        assertEquals(1, store.size());
        Optional<String> result = store.get(key);
        assertTrue(result.isPresent(), "key must have a value");
        assertTrue(writtenValues.contains(result.get()),
                "value must be one of the values written by a thread");
    }
}
