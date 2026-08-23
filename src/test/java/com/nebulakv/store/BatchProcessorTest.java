package com.nebulakv.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BatchProcessor — MPUT / MGET / MDELETE / MEXISTS")
class BatchProcessorTest {

    private InMemoryKeyValueStore store;
    private BatchProcessor batch;

    @BeforeEach
    void setUp() {
        store  = new InMemoryKeyValueStore();
        batch  = new BatchProcessor(store);
    }

    @Test
    @DisplayName("mput writes all keys atomically")
    void mputWritesAll() {
        batch.mput(Map.of("a", "1", "b", "2", "c", "3"));
        assertEquals(Optional.of("1"), store.get("a"));
        assertEquals(Optional.of("2"), store.get("b"));
        assertEquals(Optional.of("3"), store.get("c"));
    }

    @Test
    @DisplayName("mget returns present and absent keys")
    void mgetMixed() {
        store.put("x", "10");
        Map<String, Optional<String>> result = batch.mget(List.of("x", "missing"));
        assertEquals(Optional.of("10"),   result.get("x"));
        assertEquals(Optional.empty(),    result.get("missing"));
    }

    @Test
    @DisplayName("mget preserves insertion order")
    void mgetOrder() {
        store.put("z", "last");
        store.put("a", "first");
        var keys   = List.of("z", "a", "gone");
        var result = batch.mget(keys);
        assertEquals(List.of("z", "a", "gone"), List.copyOf(result.keySet()));
    }

    @Test
    @DisplayName("mdelete removes all specified keys")
    void mdeleteRemovesAll() {
        store.put("d1", "v");
        store.put("d2", "v");
        batch.mdelete(List.of("d1", "d2"));
        assertFalse(store.exists("d1"));
        assertFalse(store.exists("d2"));
    }

    @Test
    @DisplayName("mdelete on non-existent key is a no-op")
    void mdeleteNonExistent() {
        assertDoesNotThrow(() -> batch.mdelete(List.of("ghost")));
    }

    @Test
    @DisplayName("mexists returns true only when all keys present")
    void mexistsAllPresent() {
        store.put("p", "1");
        store.put("q", "2");
        assertTrue(batch.mexists(List.of("p", "q")));
        assertFalse(batch.mexists(List.of("p", "missing")));
    }

    @Test
    @DisplayName("mput rolls back on failure")
    void mputRollbackOnFailure() {
        // Wrap store to throw on the 3rd put
        AtomicInteger puts = new AtomicInteger(0);
        KeyValueStore failing = new KeyValueStore() {
            @Override public void put(String k, String v) {
                if (puts.incrementAndGet() == 3) throw new RuntimeException("injected failure");
                store.put(k, v);
            }
            @Override public Optional<String> get(String k) { return store.get(k); }
            @Override public void delete(String k)          { store.delete(k); }
            @Override public boolean exists(String k)       { return store.exists(k); }
            @Override public long size()                    { return store.size(); }
        };
        BatchProcessor failingBatch = new BatchProcessor(failing);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("r1", "v1");
        entries.put("r2", "v2");
        entries.put("r3", "v3"); // triggers failure

        assertThrows(RuntimeException.class, () -> failingBatch.mput(entries));
        // r1 and r2 should be rolled back
        assertFalse(store.exists("r1"), "r1 should be rolled back");
        assertFalse(store.exists("r2"), "r2 should be rolled back");
    }

    @Test
    @DisplayName("mput empty map throws")
    void mputEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> batch.mput(Map.of()));
    }

    @Test
    @DisplayName("mdelete empty list throws")
    void mdeleteEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> batch.mdelete(List.of()));
    }

    @Test
    @DisplayName("concurrent mput calls are thread-safe")
    void concurrentMput() throws InterruptedException {
        int threads = 8;
        int keysPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                Map<String, String> entries = new LinkedHashMap<>();
                for (int i = 0; i < keysPerThread; i++) {
                    entries.put("t" + tid + "-k" + i, "v" + i);
                }
                batch.mput(entries);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals((long) threads * keysPerThread, store.size());
    }
}
