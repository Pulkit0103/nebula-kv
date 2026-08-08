package com.nebulakv.cluster;

import com.nebulakv.cluster.ConflictResolver.VersionedValue;
import com.nebulakv.store.InMemoryKeyValueStore;
import com.nebulakv.store.KeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReadRepair — stale replica reconciliation")
class ReadRepairTest {

    @Test
    @DisplayName("stale replica receives winning value after repair")
    void staleReplicaUpdated() {
        InMemoryKeyValueStore nodeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore nodeB = new InMemoryKeyValueStore();
        // Pre-populate to reflect what the stores actually hold.
        nodeA.put("color", "new-val");
        nodeB.put("color", "old-val");

        // nodeA has seq=10, nodeB has stale seq=3
        Map<String, Optional<VersionedValue>> responses = Map.of(
                "nodeA", Optional.of(VersionedValue.live("new-val", 10L)),
                "nodeB", Optional.of(VersionedValue.live("old-val", 3L))
        );
        Map<String, KeyValueStore> stores = Map.of("nodeA", nodeA, "nodeB", nodeB);

        Optional<VersionedValue> winner = ReadRepair.repair("color", responses, stores);

        assertTrue(winner.isPresent());
        assertEquals("new-val", winner.get().value());
        // nodeB should now have the winning value
        assertEquals(Optional.of("new-val"), nodeB.get("color"));
        // nodeA is already current — unchanged
        assertEquals(Optional.of("new-val"), nodeA.get("color"));
    }

    @Test
    @DisplayName("replica missing key receives winning value")
    void missingReplicaReceivesValue() {
        InMemoryKeyValueStore nodeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore nodeB = new InMemoryKeyValueStore();

        Map<String, Optional<VersionedValue>> responses = Map.of(
                "nodeA", Optional.of(VersionedValue.live("hello", 5L)),
                "nodeB", Optional.empty()
        );
        Map<String, KeyValueStore> stores = Map.of("nodeA", nodeA, "nodeB", nodeB);

        ReadRepair.repair("greeting", responses, stores);

        assertEquals(Optional.of("hello"), nodeB.get("greeting"));
    }

    @Test
    @DisplayName("tombstone winner causes stale replica to delete key")
    void tombstoneWinnerDeletesFromStale() {
        InMemoryKeyValueStore nodeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore nodeB = new InMemoryKeyValueStore();
        nodeB.put("item", "old");

        Map<String, Optional<VersionedValue>> responses = Map.of(
                "nodeA", Optional.of(VersionedValue.tombstone(20L)),
                "nodeB", Optional.of(VersionedValue.live("old", 2L))
        );
        Map<String, KeyValueStore> stores = Map.of("nodeA", nodeA, "nodeB", nodeB);

        Optional<VersionedValue> winner = ReadRepair.repair("item", responses, stores);

        assertTrue(winner.isPresent());
        assertTrue(winner.get().tombstone());
        assertEquals(Optional.empty(), nodeB.get("item"));
    }

    @Test
    @DisplayName("all replicas consistent — no spurious writes")
    void noRepairWhenConsistent() {
        InMemoryKeyValueStore nodeA = new InMemoryKeyValueStore();
        InMemoryKeyValueStore nodeB = new InMemoryKeyValueStore();
        nodeA.put("k", "v");
        nodeB.put("k", "v");

        Map<String, Optional<VersionedValue>> responses = Map.of(
                "nodeA", Optional.of(VersionedValue.live("v", 7L)),
                "nodeB", Optional.of(VersionedValue.live("v", 7L))
        );
        Map<String, KeyValueStore> stores = Map.of("nodeA", nodeA, "nodeB", nodeB);

        Optional<VersionedValue> winner = ReadRepair.repair("k", responses, stores);

        assertEquals("v", winner.get().value());
        // Stores unchanged (both were already consistent)
        assertEquals(Optional.of("v"), nodeA.get("k"));
        assertEquals(Optional.of("v"), nodeB.get("k"));
    }

    @Test
    @DisplayName("all replicas missing — returns empty")
    void allMissingReturnsEmpty() {
        Map<String, Optional<VersionedValue>> responses = Map.of(
                "nodeA", Optional.empty(),
                "nodeB", Optional.empty()
        );
        Map<String, KeyValueStore> stores = Map.of(
                "nodeA", new InMemoryKeyValueStore(),
                "nodeB", new InMemoryKeyValueStore()
        );

        assertEquals(Optional.empty(), ReadRepair.repair("ghost", responses, stores));
    }
}
