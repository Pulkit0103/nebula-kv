package com.nebulakv.failure;

import com.nebulakv.cluster.*;
import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import com.nebulakv.core.NodeStatus;
import com.nebulakv.store.InMemoryKeyValueStore;
import com.nebulakv.store.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure scenario tests — validate system behaviour under:
 *   1. Node crash (immediate DOWN transition)
 *   2. Network partition (node becomes unreachable, writes go to available replicas)
 *   3. Disk failure simulation (store becomes unavailable, hinted handoff buffers writes)
 *   4. Recovery (down node comes back, hints are replayed)
 */
@DisplayName("Failure scenarios — crash, partition, disk failure, recovery")
class FailureScenarioTest {

    private MembershipManager membership;
    private HashRing ring;
    private HintedHandoff handoff;
    private Map<String, InMemoryKeyValueStore> stores;

    @BeforeEach
    void setUp() {
        membership = new MembershipManager();
        ring       = new HashRing(10);
        handoff    = new HintedHandoff(membership, 50);

        stores = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            String id = "node" + i;
            InMemoryKeyValueStore store = new InMemoryKeyValueStore();
            stores.put(id, store);
            membership.join(ClusterNode.active(id, "localhost", 7000 + i));
            ring.addNode(ClusterNode.active(id, "localhost", 7000 + i));
            handoff.registerStore(id, store);
        }
    }

    @AfterEach
    void tearDown() {
        handoff.stop();
    }

    // -------------------------------------------------------------------------
    // 1. Node crash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("node crash: failure detector marks node DOWN")
    void nodeCrashMarkedDown() throws InterruptedException {
        FailureDetector detector = new FailureDetector(membership, 100, 200, 50);
        detector.start();
        detector.registerNode("node1");
        // node1 never sends a heartbeat — wait for DOWN threshold.
        Thread.sleep(350);
        detector.stop();

        Optional<NodeStatus> status = membership.statusOf("node1");
        assertTrue(status.map(s -> s == NodeStatus.DOWN || s == NodeStatus.SUSPECT).orElse(false),
                "Crashed node must be DOWN or SUSPECT, got: " + status);
    }

    @Test
    @DisplayName("node crash: ring continues to serve reads to remaining nodes")
    void nodeCrashRingContinues() {
        ring.removeNode("node1");
        membership.markDown("node1");

        // Write 20 keys — all should route to surviving nodes.
        for (int i = 0; i < 20; i++) {
            String owner = ring.primaryNode("key-" + i)
                    .map(ClusterNode::nodeId).orElse(null);
            assertNotNull(owner, "Ring must find an owner after node1 removed");
            assertNotEquals("node1", owner, "Removed node must not be primary");
        }
    }

    // -------------------------------------------------------------------------
    // 2. Network partition
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("partition: writes buffer as hints for partitioned node")
    void partitionWritesBufferedAsHints() {
        // Simulate node2 partitioned: mark it suspect.
        membership.suspect("node2");

        // Coordinator cannot reach node2 — stores a hint.
        handoff.storeHint(HintedHandoff.Hint.put("node2", "partition-key", "value", 1L));
        handoff.storeHint(HintedHandoff.Hint.put("node2", "another-key",  "v2",    2L));

        assertEquals(2, handoff.totalPendingHints());
        assertEquals(Optional.empty(), stores.get("node2").get("partition-key"),
                "Partitioned node must not have the value yet");
    }

    @Test
    @DisplayName("partition recovery: hints replayed when node rejoins as ACTIVE")
    void partitionRecoveryReplaysHints() {
        membership.markDown("node2");
        handoff.storeHint(HintedHandoff.Hint.put("node2", "recover-key", "v-recovered", 5L));

        // Node still down — hints stay.
        handoff.replayHints();
        assertEquals(1, handoff.totalPendingHints());

        // Node recovers.
        membership.join(ClusterNode.active("node2", "localhost", 7002));
        handoff.replayHints();

        assertEquals(Optional.of("v-recovered"), stores.get("node2").get("recover-key"));
        assertEquals(0, handoff.totalPendingHints());
    }

    // -------------------------------------------------------------------------
    // 3. Disk failure simulation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("disk failure: read repair uses data from healthy replicas")
    void diskFailureReadRepairFromHealthy() {
        // node3 "disk failure" — its store is empty (as if data was lost).
        InMemoryKeyValueStore healthy1 = stores.get("node1");
        InMemoryKeyValueStore healthy2 = stores.get("node2");
        InMemoryKeyValueStore failed   = stores.get("node3");

        healthy1.put("durability-key", "durable-value");
        healthy2.put("durability-key", "durable-value");
        // node3 has nothing (disk failure).

        Map<String, Optional<VersionedValue>> responses = Map.of(
                "node1", Optional.of(VersionedValue.live("durable-value", 10L)),
                "node2", Optional.of(VersionedValue.live("durable-value", 10L)),
                "node3", Optional.empty()
        );
        Map<String, KeyValueStore> storeMap = Map.of(
                "node1", healthy1, "node2", healthy2, "node3", failed
        );

        Optional<VersionedValue> winner = ReadRepair.repair("durability-key", responses, storeMap);

        assertTrue(winner.isPresent());
        assertEquals("durable-value", winner.get().value());
        // node3 should have been repaired.
        assertEquals(Optional.of("durable-value"), failed.get("durability-key"));
    }

    // -------------------------------------------------------------------------
    // 4. Full recovery scenario
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full recovery: node crashes, hints buffer, node rejoins, cluster converges")
    void fullRecoveryScenario() {
        // Write to node1 before it crashes.
        stores.get("node1").put("important-key", "important-value");

        // node1 crashes.
        membership.markDown("node1");
        ring.removeNode("node1");

        // New writes destined for node1 are buffered as hints.
        handoff.storeHint(HintedHandoff.Hint.put("node1", "new-key-1", "v1", 10L));
        handoff.storeHint(HintedHandoff.Hint.put("node1", "new-key-2", "v2", 11L));
        assertEquals(2, handoff.totalPendingHints());

        // node1 comes back.
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        ring.addNode(ClusterNode.active("node1", "localhost", 7001));
        handoff.replayHints();

        // All hints should be delivered.
        assertEquals(0, handoff.totalPendingHints());
        assertEquals(Optional.of("v1"), stores.get("node1").get("new-key-1"));
        assertEquals(Optional.of("v2"), stores.get("node1").get("new-key-2"));
    }
}
