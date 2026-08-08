package com.nebulakv.cluster;

import com.nebulakv.core.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HashRing — consistent hashing with virtual nodes")
class HashRingTest {

    private HashRing ring;

    @BeforeEach
    void setUp() {
        ring = new HashRing(10); // small vnode count for tests
    }

    private ClusterNode node(String id) {
        return ClusterNode.active(id, "localhost", 7000 + Integer.parseInt(id.replace("node", "")));
    }

    // -------------------------------------------------------------------------
    // Basic operations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty ring returns empty for primaryNode")
    void emptyRingReturnsEmpty() {
        assertEquals(Optional.empty(), ring.primaryNode("any-key"));
    }

    @Test
    @DisplayName("single node owns all keys")
    void singleNodeOwnsAllKeys() {
        ring.addNode(node("node1"));
        for (int i = 0; i < 100; i++) {
            Optional<ClusterNode> primary = ring.primaryNode("key:" + i);
            assertTrue(primary.isPresent());
            assertEquals("node1", primary.get().nodeId());
        }
    }

    @Test
    @DisplayName("addNode is idempotent")
    void addNodeIsIdempotent() {
        ring.addNode(node("node1"));
        ring.addNode(node("node1"));
        assertEquals(1, ring.physicalNodeCount());
        assertEquals(10, ring.totalTokenCount()); // vnodes × 1
    }

    @Test
    @DisplayName("removeNode removes all tokens for that node")
    void removeNodeRemovesAllTokens() {
        ring.addNode(node("node1"));
        ring.addNode(node("node2"));
        ring.removeNode("node1");

        assertEquals(1, ring.physicalNodeCount());
        assertEquals(10, ring.totalTokenCount()); // only node2 remains

        // All keys should now map to node2.
        for (int i = 0; i < 50; i++) {
            Optional<ClusterNode> n = ring.primaryNode("k" + i);
            assertTrue(n.isPresent());
            assertEquals("node2", n.get().nodeId());
        }
    }

    @Test
    @DisplayName("removing non-existent node is a no-op")
    void removeNonExistentNodeIsNoOp() {
        ring.addNode(node("node1"));
        assertDoesNotThrow(() -> ring.removeNode("phantom"));
        assertEquals(1, ring.physicalNodeCount());
    }

    // -------------------------------------------------------------------------
    // Key distribution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("keys distribute across all nodes")
    void keysDistributeAcrossNodes() {
        ring.addNode(node("node1"));
        ring.addNode(node("node2"));
        ring.addNode(node("node3"));

        Map<String, Integer> counts = new HashMap<>();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String nodeId = ring.primaryNode("key:" + i).orElseThrow().nodeId();
            counts.merge(nodeId, 1, Integer::sum);
        }

        // Each node should get some keys — not all concentrated on one.
        assertEquals(3, counts.size(), "All 3 nodes should own at least one key");
        counts.values().forEach(count ->
                assertTrue(count > 0, "Every node must own at least one key")
        );
    }

    @Test
    @DisplayName("adding a node remaps minimal keys (consistent hashing property)")
    void addingNodeRemapsMinimalKeys() {
        HashRing ring2 = new HashRing(150); // use realistic vnode count
        ring2.addNode(ClusterNode.active("n1", "localhost", 7001));
        ring2.addNode(ClusterNode.active("n2", "localhost", 7002));

        int total = 1000;
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < total; i++) {
            before.put("k" + i, ring2.primaryNode("k" + i).orElseThrow().nodeId());
        }

        ring2.addNode(ClusterNode.active("n3", "localhost", 7003));

        int remapped = 0;
        for (int i = 0; i < total; i++) {
            String after = ring2.primaryNode("k" + i).orElseThrow().nodeId();
            if (!after.equals(before.get("k" + i))) remapped++;
        }

        // With 3 nodes and consistent hashing, ~1/3 of keys should be remapped.
        // Allow generous bounds (10% – 60%) to account for hash variance.
        double fraction = (double) remapped / total;
        assertTrue(fraction >= 0.10 && fraction <= 0.60,
                "Expected ~33% remapping on 3-node ring, got " + (fraction * 100) + "%");
    }

    // -------------------------------------------------------------------------
    // Replication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("replicaNodes returns N distinct physical nodes")
    void replicaNodesReturnsNDistinctNodes() {
        ring.addNode(node("node1"));
        ring.addNode(node("node2"));
        ring.addNode(node("node3"));

        List<ClusterNode> replicas = ring.replicaNodes("my-key", 3);

        assertEquals(3, replicas.size());
        Set<String> ids = new HashSet<>();
        replicas.forEach(n -> ids.add(n.nodeId()));
        assertEquals(3, ids.size(), "All 3 replicas must be distinct physical nodes");
    }

    @Test
    @DisplayName("replicaNodes returns fewer nodes if ring has fewer than N nodes")
    void replicaNodesReturnsFewerWhenRingSmall() {
        ring.addNode(node("node1"));
        ring.addNode(node("node2"));

        List<ClusterNode> replicas = ring.replicaNodes("key", 3);
        assertEquals(2, replicas.size(), "Only 2 nodes available — can't return 3 distinct replicas");
    }

    @Test
    @DisplayName("same key always maps to same primary")
    void sameKeyAlwaysMapsToSamePrimary() {
        ring.addNode(node("node1"));
        ring.addNode(node("node2"));
        ring.addNode(node("node3"));

        String first = ring.primaryNode("stable-key").orElseThrow().nodeId();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, ring.primaryNode("stable-key").orElseThrow().nodeId());
        }
    }
}
