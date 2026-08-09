package com.nebulakv.benchmark;

import com.nebulakv.cluster.ConflictResolver;
import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import com.nebulakv.cluster.HashRing;
import com.nebulakv.cluster.ClusterNode;
import com.nebulakv.checksum.Checksums;
import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmarks for core NebulaKV operations.
 *
 * Methodology:
 *   - Warm-up phase: discard first WARMUP_OPS to allow JIT compilation.
 *   - Measurement phase: record MEASURE_OPS and compute throughput.
 *   - Assertions: each benchmark must meet a minimum ops/sec threshold.
 *
 * Note: for production-grade benchmarking, replace these with JMH benchmarks
 * (org.openjdk.jmh). JMH controls JIT, GC, and clock granularity more
 * precisely. These manual micro-benchmarks are sufficient for portfolio use.
 *
 * Minimum thresholds are conservative to pass reliably on CI runners.
 */
@DisplayName("Performance benchmarks — store ops, hashing, checksum")
class StoreBenchmark {

    private static final int WARMUP_OPS  = 10_000;
    private static final int MEASURE_OPS = 100_000;

    // -------------------------------------------------------------------------
    // 1. InMemoryKeyValueStore throughput
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("put() throughput >= 500k ops/sec")
    void putThroughput() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();

        // Warm up
        for (int i = 0; i < WARMUP_OPS; i++) store.put("k" + i, "v");

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            store.put("bench-key-" + (i % 1000), "bench-value");
        }
        long elapsed = System.nanoTime() - start;
        long opsPerSec = (long) (MEASURE_OPS / (elapsed / 1e9));

        System.out.printf("[StoreBenchmark] put()  : %,d ops/sec%n", opsPerSec);
        assertTrue(opsPerSec >= 500_000, "put() must be >= 500k ops/sec, got " + opsPerSec);
    }

    @Test
    @DisplayName("get() throughput >= 1M ops/sec")
    void getThroughput() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        for (int i = 0; i < 1000; i++) store.put("k" + i, "v" + i);

        // Warm up
        for (int i = 0; i < WARMUP_OPS; i++) store.get("k" + (i % 1000));

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            store.get("k" + (i % 1000));
        }
        long elapsed = System.nanoTime() - start;
        long opsPerSec = (long) (MEASURE_OPS / (elapsed / 1e9));

        System.out.printf("[StoreBenchmark] get()  : %,d ops/sec%n", opsPerSec);
        assertTrue(opsPerSec >= 1_000_000, "get() must be >= 1M ops/sec, got " + opsPerSec);
    }

    // -------------------------------------------------------------------------
    // 2. Consistent hash ring lookup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HashRing.primaryNode() >= 200k lookups/sec")
    void hashRingLookupThroughput() {
        HashRing ring = new HashRing(150);
        for (int i = 1; i <= 5; i++) {
            ring.addNode(ClusterNode.active("node" + i, "localhost", 7000 + i));
        }

        // Warm up
        for (int i = 0; i < WARMUP_OPS; i++) ring.primaryNode("key-" + i);

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            ring.primaryNode("key-" + (i % 10_000));
        }
        long elapsed = System.nanoTime() - start;
        long opsPerSec = (long) (MEASURE_OPS / (elapsed / 1e9));

        System.out.printf("[StoreBenchmark] ring   : %,d lookups/sec%n", opsPerSec);
        assertTrue(opsPerSec >= 200_000, "Ring lookup must be >= 200k/sec, got " + opsPerSec);
    }

    // -------------------------------------------------------------------------
    // 3. CRC32 checksum throughput
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CRC32 checksum >= 500k ops/sec for 64-byte payloads")
    void checksumThroughput() {
        byte[] payload = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8); // 64 bytes

        // Warm up
        for (int i = 0; i < WARMUP_OPS; i++) Checksums.compute(payload);

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            Checksums.compute(payload);
        }
        long elapsed = System.nanoTime() - start;
        long opsPerSec = (long) (MEASURE_OPS / (elapsed / 1e9));

        System.out.printf("[StoreBenchmark] crc32  : %,d ops/sec (64B)%n", opsPerSec);
        assertTrue(opsPerSec >= 500_000, "CRC32 must be >= 500k ops/sec, got " + opsPerSec);
    }

    // -------------------------------------------------------------------------
    // 4. Conflict resolution throughput
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ConflictResolver.resolve() >= 500k ops/sec for 3 versions")
    void conflictResolveThroughput() {
        List<VersionedValue> versions = List.of(
                VersionedValue.live("v1", 1L),
                VersionedValue.live("v2", 3L),
                VersionedValue.live("v3", 2L)
        );

        // Warm up
        for (int i = 0; i < WARMUP_OPS; i++) ConflictResolver.resolve(versions);

        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            ConflictResolver.resolve(versions);
        }
        long elapsed = System.nanoTime() - start;
        long opsPerSec = (long) (MEASURE_OPS / (elapsed / 1e9));

        System.out.printf("[StoreBenchmark] resolve: %,d ops/sec (3 versions)%n", opsPerSec);
        assertTrue(opsPerSec >= 500_000, "Conflict resolve must be >= 500k/sec, got " + opsPerSec);
    }

    // -------------------------------------------------------------------------
    // 5. Concurrent put saturation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("concurrent puts on 8 threads >= 1M aggregate ops/sec")
    void concurrentPutSaturation() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        int threads   = 8;
        int perThread = MEASURE_OPS / threads;
        AtomicLong completed = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < perThread; i++) {
                    store.put("k" + (i % 500), "v" + tid);
                    completed.incrementAndGet();
                }
            }));
        }

        ready.await();
        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
        }
        long elapsed = System.nanoTime() - t0;
        pool.shutdown();

        long opsPerSec = (long) (completed.get() / (elapsed / 1e9));
        System.out.printf("[StoreBenchmark] 8-thread puts: %,d aggregate ops/sec%n", opsPerSec);
        assertTrue(opsPerSec >= 1_000_000, "8-thread put must be >= 1M ops/sec, got " + opsPerSec);
    }
}
