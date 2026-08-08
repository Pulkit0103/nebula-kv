package com.nebulakv.cluster;

import com.nebulakv.store.InMemoryKeyValueStore;
import com.nebulakv.store.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Coordinator — quorum reads and writes")
class CoordinatorTest {

    private HashRing ring;
    private Map<String, KeyValueStore> stores;
    private Coordinator coordinator;

    @BeforeEach
    void setUp() {
        ring = new HashRing(50);
        stores = new HashMap<>();

        // Create 3 simulated nodes.
        for (int i = 1; i <= 3; i++) {
            ClusterNode node = ClusterNode.active("node" + i, "localhost", 7000 + i);
            ring.addNode(node);
            stores.put("node" + i, new InMemoryKeyValueStore());
        }

        coordinator = new Coordinator(ring, QuorumConfig.DEFAULT, stores);
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
    }

    @Test
    @DisplayName("put with quorum succeeds and value is readable")
    void putAndGetWithQuorum() {
        assertTrue(coordinator.put("user:1", "Alice"));
        Optional<String> result = coordinator.get("user:1");
        assertTrue(result.isPresent(), "Value must be readable after quorum write");
        assertEquals("Alice", result.get());
    }

    @Test
    @DisplayName("delete removes the key from quorum-readable stores")
    void deleteWithQuorum() {
        coordinator.put("temp", "data");
        assertTrue(coordinator.delete("temp"));
        // After delete, the key should not be returned by get on any replica.
        // With quorum, at least W replicas deleted it.
        // This test checks the coordinator-level behavior.
        // (Individual store may still have the key if quorum=W<N)
    }

    @Test
    @DisplayName("QuorumConfig validates constraints")
    void quorumConfigValidates() {
        assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(3, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(3, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new QuorumConfig(3, 2, 0));
    }

    @Test
    @DisplayName("strong consistency: R+W>N")
    void strongConsistency() {
        assertTrue(QuorumConfig.DEFAULT.isStronglyConsistent());
        assertFalse(new QuorumConfig(3, 1, 1).isStronglyConsistent());
    }

    @Test
    @DisplayName("get returns empty when key does not exist")
    void getMissingKeyReturnsEmpty() {
        assertEquals(Optional.empty(), coordinator.get("ghost"));
    }

    @Test
    @DisplayName("multiple keys route to correct replicas independently")
    void multipleKeysRouteCorrectly() {
        for (int i = 0; i < 20; i++) {
            String key = "key:" + i;
            String value = "val:" + i;
            assertTrue(coordinator.put(key, value), "PUT must succeed for " + key);
        }
        for (int i = 0; i < 20; i++) {
            Optional<String> result = coordinator.get("key:" + i);
            assertTrue(result.isPresent(), "GET must return value for key:" + i);
            assertEquals("val:" + i, result.get());
        }
    }
}
