package com.nebulakv.cluster;

import com.nebulakv.cluster.HintedHandoff.Hint;
import com.nebulakv.cluster.HintedHandoff.Op;
import com.nebulakv.store.InMemoryKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HintedHandoff — hint storage and replay")
class HintedHandoffTest {

    private MembershipManager membership;
    private HintedHandoff handoff;

    @BeforeEach
    void setUp() {
        membership = new MembershipManager();
        handoff = new HintedHandoff(membership, 50);
    }

    @AfterEach
    void tearDown() {
        handoff.stop();
    }

    @Test
    @DisplayName("storeHint adds hint to pending list")
    void storeHintAddsHint() {
        handoff.storeHint(Hint.put("node1", "k", "v", 1L));
        assertEquals(1, handoff.pendingHints("node1").size());
    }

    @Test
    @DisplayName("pendingHints returns empty for unknown node")
    void pendingHintsUnknownNode() {
        assertEquals(List.of(), handoff.pendingHints("ghost"));
    }

    @Test
    @DisplayName("totalPendingHints sums across nodes")
    void totalPendingHintsSums() {
        handoff.storeHint(Hint.put("node1", "k1", "v1", 1L));
        handoff.storeHint(Hint.put("node1", "k2", "v2", 2L));
        handoff.storeHint(Hint.delete("node2", "k3", 3L));
        assertEquals(3, handoff.totalPendingHints());
    }

    @Test
    @DisplayName("replay delivers PUT hints to active node store")
    void replayDeliversPut() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        handoff.registerStore("node1", store);
        handoff.storeHint(Hint.put("node1", "color", "blue", 1L));

        handoff.replayHints();

        assertEquals(Optional.of("blue"), store.get("color"));
        assertEquals(0, handoff.pendingHints("node1").size());
    }

    @Test
    @DisplayName("replay delivers DELETE hints to active node store")
    void replayDeliversDelete() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        store.put("key", "val");
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        handoff.registerStore("node1", store);
        handoff.storeHint(Hint.delete("node1", "key", 2L));

        handoff.replayHints();

        assertEquals(Optional.empty(), store.get("key"));
        assertEquals(0, handoff.pendingHints("node1").size());
    }

    @Test
    @DisplayName("replay skips hints for non-ACTIVE node")
    void replaySkipsDownNode() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        membership.markDown("node1");
        handoff.registerStore("node1", store);
        handoff.storeHint(Hint.put("node1", "k", "v", 1L));

        handoff.replayHints();

        // Hint must remain undelivered.
        assertEquals(1, handoff.pendingHints("node1").size());
        assertEquals(Optional.empty(), store.get("k"));
    }

    @Test
    @DisplayName("replay delivers when node recovers to ACTIVE")
    void replayDeliversOnRecovery() {
        InMemoryKeyValueStore store = new InMemoryKeyValueStore();
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        membership.markDown("node1");
        handoff.registerStore("node1", store);
        handoff.storeHint(Hint.put("node1", "msg", "hello", 5L));

        // Still down — hint stays.
        handoff.replayHints();
        assertEquals(1, handoff.pendingHints("node1").size());

        // Node recovers.
        membership.join(ClusterNode.active("node1", "localhost", 7001));
        handoff.replayHints();
        assertEquals(Optional.of("hello"), store.get("msg"));
        assertEquals(0, handoff.pendingHints("node1").size());
    }
}
