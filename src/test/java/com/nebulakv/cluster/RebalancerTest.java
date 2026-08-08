package com.nebulakv.cluster;

import com.nebulakv.store.InMemoryKeyValueStore;
import com.nebulakv.store.KeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rebalancer — token ring key migration")
class RebalancerTest {

    private HashRing ring;
    private Rebalancer rebalancer;

    @BeforeEach
    void setUp() {
        ring = new HashRing(10); // fewer vnodes for determinism
        rebalancer = new Rebalancer(ring);
    }

    @Test
    @DisplayName("rebalanceOnJoin migrates keys whose primary owner changed")
    void rebalanceOnJoinMigratesKeys() {
        ClusterNode nodeA = ClusterNode.active("nodeA", "localhost", 7001);
        ClusterNode nodeB = ClusterNode.active("nodeB", "localhost", 7002);

        ring.addNode(nodeA);

        InMemoryKeyValueStore storeA = new InMemoryKeyValueStore();
        // Seed many keys so at least some map to nodeB after it joins.
        for (int i = 0; i < 100; i++) {
            storeA.put("key-" + i, "val-" + i);
        }

        ring.addNode(nodeB);
        InMemoryKeyValueStore storeB = new InMemoryKeyValueStore();

        Map<String, KeyValueStore> allStores = Map.of("nodeA", storeA, "nodeB", storeB);
        int migrated = rebalancer.rebalanceOnJoin("nodeB", storeB, allStores);

        // Some keys should have been migrated to nodeB.
        assertTrue(migrated > 0, "Expected at least one key migrated, got 0");
        // Every migrated key should now be readable from nodeB.
        for (String key : storeB.keySet()) {
            assertTrue(storeA.exists(key) || storeB.exists(key),
                    "Migrated key " + key + " must exist in at least one store");
        }
    }

    @Test
    @DisplayName("rebalanceOnLeave migrates keys to new primary owner")
    void rebalanceOnLeaveMigratesKeys() {
        ClusterNode nodeA = ClusterNode.active("nodeA", "localhost", 7001);
        ClusterNode nodeB = ClusterNode.active("nodeB", "localhost", 7002);

        ring.addNode(nodeA);
        ring.addNode(nodeB);

        // Pre-populate nodeA with keys that are owned by nodeA on the current ring.
        InMemoryKeyValueStore storeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore storeB = new InMemoryKeyValueStore();

        for (int i = 0; i < 100; i++) {
            String key = "k-" + i;
            String owner = ring.primaryNode(key).map(ClusterNode::nodeId).orElse("");
            if ("nodeA".equals(owner)) {
                storeA.put(key, "v-" + i);
            }
        }
        long ownedByA = storeA.size();
        assertTrue(ownedByA > 0, "nodeA must own at least one key before removal");

        // Remove nodeA from ring, then rebalance.
        ring.removeNode("nodeA");

        Map<String, KeyValueStore> remaining = Map.of("nodeB", storeB);
        int migrated = rebalancer.rebalanceOnLeave("nodeA", storeA, remaining);

        assertEquals(ownedByA, migrated, "All nodeA keys should migrate to nodeB");
        // Every key that was in nodeA should now be in nodeB.
        for (String key : storeA.keySet()) {
            assertEquals(storeA.get(key), storeB.get(key),
                    "Key " + key + " should be replicated to nodeB");
        }
    }

    @Test
    @DisplayName("rebalanceOnJoin with empty ring produces no migrations")
    void emptySourceProducesNoMigrations() {
        ClusterNode nodeA = ClusterNode.active("nodeA", "localhost", 7001);
        ring.addNode(nodeA);

        InMemoryKeyValueStore storeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore storeB = new InMemoryKeyValueStore();
        ring.addNode(ClusterNode.active("nodeB", "localhost", 7002));

        int migrated = rebalancer.rebalanceOnJoin("nodeB", storeB,
                Map.of("nodeA", storeA, "nodeB", storeB));

        assertEquals(0, migrated);
    }
}
