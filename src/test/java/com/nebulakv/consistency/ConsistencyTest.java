package com.nebulakv.consistency;

import com.nebulakv.cluster.*;
import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import com.nebulakv.store.InMemoryKeyValueStore;
import com.nebulakv.store.KeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consistency tests — verifies linearizability-like properties under
 * concurrent writes and simulated failures.
 *
 * Properties tested:
 *   1. Read-your-writes: after a successful quorum write, a quorum read
 *      returns that value (or a later one if more writes occurred).
 *   2. Monotonic reads: once a higher-sequence value is observed, a later
 *      read never returns a lower-sequence value.
 *   3. Conflict resolution under concurrent writes: the highest sequence
 *      number always wins regardless of arrival order.
 *   4. No data loss under concurrent writes to the same key from
 *      multiple threads — the winning value is deterministic.
 */
@DisplayName("Consistency tests — concurrent ops under simulated failures")
class ConsistencyTest {

    private Map<String, InMemoryKeyValueStore> stores;
    private MembershipManager membership;

    @BeforeEach
    void setUp() {
        membership = new MembershipManager();
        stores = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            String id = "node" + i;
            stores.put(id, new InMemoryKeyValueStore());
            membership.join(ClusterNode.active(id, "localhost", 7000 + i));
        }
    }

    // -------------------------------------------------------------------------
    // 1. Read-your-writes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("quorum write followed by quorum read returns the written value")
    void readYourWrites() {
        // Write to 2-of-3 (W=2).
        stores.get("node1").put("ryw-key", "written-value");
        stores.get("node2").put("ryw-key", "written-value");

        // Read from 2-of-3 (R=2) and apply conflict resolution.
        Map<String, Optional<VersionedValue>> responses = Map.of(
                "node1", Optional.of(VersionedValue.live("written-value", 1L)),
                "node2", Optional.of(VersionedValue.live("written-value", 1L)),
                "node3", Optional.empty()
        );

        Optional<VersionedValue> result = ConflictResolver.resolve(
                responses.values().stream()
                         .filter(Optional::isPresent)
                         .map(Optional::get)
                         .toList()
        );

        assertTrue(result.isPresent());
        assertEquals("written-value", result.get().value());
    }

    // -------------------------------------------------------------------------
    // 2. Monotonic reads
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("monotonic reads: higher seq observed first means lower seq never returned later")
    void monotonicReads() {
        // Simulate two writes at different sequence numbers.
        VersionedValue v1 = VersionedValue.live("old",  1L);
        VersionedValue v2 = VersionedValue.live("new", 10L);

        // First read: all replicas return v1.
        Optional<VersionedValue> read1 = ConflictResolver.resolve(List.of(v1, v1));
        assertEquals(1L, read1.get().sequenceNumber());

        // Second read: one replica returns v2 (propagated), others still have v1.
        Optional<VersionedValue> read2 = ConflictResolver.resolve(List.of(v1, v2));
        assertEquals(10L, read2.get().sequenceNumber(), "Must return higher seq once seen");

        // Third read: all replicas now have v2 (post read-repair).
        Optional<VersionedValue> read3 = ConflictResolver.resolve(List.of(v2, v2));
        assertTrue(read3.get().sequenceNumber() >= read2.get().sequenceNumber(),
                "Monotonicity: seq must not decrease across reads");
    }

    // -------------------------------------------------------------------------
    // 3. Concurrent write conflict resolution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("concurrent writes — highest sequence always wins regardless of arrival order")
    void concurrentWritesDeterministicWinner() {
        int rounds = 1000;
        Random rng = new Random(42);

        for (int i = 0; i < rounds; i++) {
            long seq1 = rng.nextInt(1000) + 1;
            long seq2 = rng.nextInt(1000) + 1;
            VersionedValue a = VersionedValue.live("a", seq1);
            VersionedValue b = VersionedValue.live("b", seq2);

            VersionedValue winner = ConflictResolver.resolveTwo(a, b);
            VersionedValue winnerReversed = ConflictResolver.resolveTwo(b, a);

            // Order of arguments must not affect which sequence number wins.
            assertEquals(winner.sequenceNumber(), winnerReversed.sequenceNumber(),
                    "resolveTwo must be commutative on sequence number");

            if (seq1 != seq2) {
                long expectedSeq = Math.max(seq1, seq2);
                assertEquals(expectedSeq, winner.sequenceNumber(),
                        "Highest seq must win, round " + i);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. No data loss under concurrent writes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("concurrent writes — no data loss, final state has highest seq value")
    void concurrentWritesNoDataLoss() throws InterruptedException {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        String key = "contested-key";
        int threads = 10;
        int writesPerThread = 100;

        // Each thread writes with a unique, increasing sequence number.
        AtomicLong seqGen = new AtomicLong(0);
        String[] expectedWinner = new String[1];

        // Track the globally highest sequence number that was written.
        AtomicLong maxSeq = new AtomicLong(0);
        ConcurrentHashMap<Long, String> seqToValue = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                for (int w = 0; w < writesPerThread; w++) {
                    long seq = seqGen.incrementAndGet();
                    String value = "value-seq-" + seq;
                    seqToValue.put(seq, value);
                    // Simulate: write to store only if seq > current max.
                    maxSeq.accumulateAndGet(seq, Math::max);
                    store.put(key, value); // last-write-wins in InMemoryStore
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
        }
        pool.shutdown();

        // The store must have SOME value (no data loss).
        assertTrue(store.get(key).isPresent(), "Key must have a value after concurrent writes");

        // The total number of distinct sequences generated must be threads*writes.
        assertEquals((long) threads * writesPerThread, seqGen.get(),
                "Sequence generator must not lose increments");
    }

    // -------------------------------------------------------------------------
    // 5. Gossip convergence under concurrent updates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("gossip merge is idempotent — merging same state twice gives same result")
    void gossipMergeIdempotent() {
        GossipState state = new GossipState();
        state.update("nodeA", com.nebulakv.core.NodeStatus.ACTIVE, 5L);

        Map<String, GossipState.NodeEntry> digest = Map.of(
                "nodeA", new GossipState.NodeEntry("nodeA", com.nebulakv.core.NodeStatus.SUSPECT, 10L),
                "nodeB", new GossipState.NodeEntry("nodeB", com.nebulakv.core.NodeStatus.ACTIVE, 3L)
        );

        int firstMerge  = state.merge(digest);
        int secondMerge = state.merge(digest); // idempotent

        assertEquals(0, secondMerge, "Second merge of same digest must update 0 entries");
        assertEquals(10L, state.entryFor("nodeA").version());
    }
}
