package com.nebulakv.raft;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Raft state machine application (Phase 44).
 *
 * Verifies that committed log entries are applied to KVStateMachine in order,
 * and that RaftKVStore provides a consistent read-your-writes view on the leader.
 */
@DisplayName("Raft — state machine application")
class RaftStateMachineTest {

    private InProcessRaftTransport transport;
    private RaftNode n1, n2, n3;
    private RaftKVStore store1, store2, store3;

    @BeforeEach
    void setup() {
        transport = new InProcessRaftTransport();
        n1 = new RaftNode("n1", List.of("n2", "n3"), transport);
        n2 = new RaftNode("n2", List.of("n1", "n3"), transport);
        n3 = new RaftNode("n3", List.of("n1", "n2"), transport);
        transport.register(n1); transport.register(n2); transport.register(n3);

        store1 = new RaftKVStore(n1);
        store2 = new RaftKVStore(n2);
        store3 = new RaftKVStore(n3);
    }

    @AfterEach
    void tearDown() { n1.shutdown(); n2.shutdown(); n3.shutdown(); }

    private RaftKVStore waitForLeaderStore() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (n1.isLeader()) return store1;
            if (n2.isLeader()) return store2;
            if (n3.isLeader()) return store3;
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("KVStateMachine: apply Put stores the value")
    void applyPutStoresValue() {
        KVStateMachine sm = new KVStateMachine();
        sm.apply(new LogEntry(1, 1, new RaftCommand.Put("city", "Tokyo")));
        assertEquals("Tokyo", sm.get("city"));
    }

    @Test
    @DisplayName("KVStateMachine: apply Delete removes the value")
    void applyDeleteRemovesValue() {
        KVStateMachine sm = new KVStateMachine();
        sm.apply(new LogEntry(1, 1, new RaftCommand.Put("city", "Tokyo")));
        sm.apply(new LogEntry(2, 1, new RaftCommand.Delete("city")));
        assertNull(sm.get("city"));
        assertFalse(sm.containsKey("city"));
    }

    @Test
    @DisplayName("KVStateMachine: NoOp is silently ignored")
    void applyNoOpIgnored() {
        KVStateMachine sm = new KVStateMachine();
        sm.apply(new LogEntry(0, 0, new RaftCommand.NoOp()));
        assertTrue(sm.snapshot().isEmpty());
    }

    @Test
    @DisplayName("KVStateMachine: later Put overwrites earlier Put")
    void applyPutOverwrite() {
        KVStateMachine sm = new KVStateMachine();
        sm.apply(new LogEntry(1, 1, new RaftCommand.Put("k", "v1")));
        sm.apply(new LogEntry(2, 1, new RaftCommand.Put("k", "v2")));
        assertEquals("v2", sm.get("k"));
    }

    @Test
    @DisplayName("RaftKVStore: put on leader is applied and readable")
    void putIsAppliedAndReadable() throws InterruptedException {
        RaftKVStore leader = waitForLeaderStore();
        assertNotNull(leader, "No leader elected");

        leader.put("name", "Raft");
        assertEquals("Raft", leader.get("name"));
    }

    @Test
    @DisplayName("RaftKVStore: delete on leader removes key")
    void deleteRemovesKey() throws InterruptedException {
        RaftKVStore leader = waitForLeaderStore();
        assertNotNull(leader);

        leader.put("temp", "value");
        leader.delete("temp");
        assertNull(leader.get("temp"));
        assertFalse(leader.containsKey("temp"));
    }

    @Test
    @DisplayName("RaftKVStore: put on non-leader throws IllegalStateException")
    void putOnNonLeaderThrows() {
        waitForLeaderStore(); // ensure election completes
        RaftKVStore follower = List.of(store1, store2, store3).stream()
                .filter(s -> !s.node().isLeader()).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> follower.put("k", "v"));
    }

    @Test
    @DisplayName("RaftKVStore: multiple puts all applied in order")
    void multiplePutsAppliedInOrder() throws InterruptedException {
        RaftKVStore leader = waitForLeaderStore();
        assertNotNull(leader);

        for (int i = 0; i < 5; i++) {
            leader.put("key" + i, "val" + i);
        }
        for (int i = 0; i < 5; i++) {
            assertEquals("val" + i, leader.get("key" + i));
        }
    }

    @Test
    @DisplayName("all nodes eventually apply the same state")
    void allNodesConvergeToSameState() throws InterruptedException {
        RaftKVStore leader = waitForLeaderStore();
        assertNotNull(leader);

        leader.put("x", "1");
        leader.put("y", "2");

        // Allow followers time to catch up
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            if (store1.get("x") != null && store2.get("x") != null && store3.get("x") != null) break;
            Thread.sleep(20);
        }

        for (RaftKVStore s : List.of(store1, store2, store3)) {
            assertEquals("1", s.get("x"), s.node().nodeId() + " missing x");
            assertEquals("2", s.get("y"), s.node().nodeId() + " missing y");
        }
    }
}
