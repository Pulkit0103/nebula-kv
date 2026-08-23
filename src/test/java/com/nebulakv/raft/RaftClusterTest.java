package com.nebulakv.raft;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end cluster tests for Phase 45 (Raft integration).
 *
 * Exercises RaftKVServer across a 3-node in-process cluster: leader election,
 * write forwarding, follower redirect, read convergence, and partition recovery.
 */
@DisplayName("Raft — cluster integration (RaftKVServer)")
class RaftClusterTest {

    private InProcessRaftTransport transport;
    private RaftKVServer s1, s2, s3;

    @BeforeEach
    void setup() {
        transport = new InProcessRaftTransport();
        RaftNode n1 = new RaftNode("n1", List.of("n2", "n3"), transport);
        RaftNode n2 = new RaftNode("n2", List.of("n1", "n3"), transport);
        RaftNode n3 = new RaftNode("n3", List.of("n1", "n2"), transport);
        transport.register(n1); transport.register(n2); transport.register(n3);

        s1 = new RaftKVServer(n1);
        s2 = new RaftKVServer(n2);
        s3 = new RaftKVServer(n3);
    }

    @AfterEach
    void tearDown() {
        s1.node().shutdown(); s2.node().shutdown(); s3.node().shutdown();
    }

    // -------------------------------------------------------------------------

    private RaftKVServer waitForLeaderServer() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (s1.isLeader()) return s1;
            if (s2.isLeader()) return s2;
            if (s3.isLeader()) return s3;
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    private RaftKVServer followerOf(RaftKVServer leader) {
        return List.of(s1, s2, s3).stream()
                .filter(s -> !s.isLeader()).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------------------
    // Leader election and introspection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exactly one RaftKVServer reports isLeader() after election")
    void exactlyOneLeader() {
        assertNotNull(waitForLeaderServer(), "No leader elected");
        long leaders = List.of(s1, s2, s3).stream().filter(RaftKVServer::isLeader).count();
        assertEquals(1, leaders);
    }

    @Test
    @DisplayName("leader nodeId and leaderId are consistent")
    void leaderIdConsistent() {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        assertEquals(leader.nodeId(), leader.leaderId());
    }

    // -------------------------------------------------------------------------
    // Write path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("put on leader is readable from leader immediately")
    void putLeaderReadable() throws InterruptedException {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        leader.put("db", "nebula");
        assertEquals("nebula", leader.get("db"));
    }

    @Test
    @DisplayName("put on non-leader throws NotLeaderException with leaderId")
    void putOnFollowerThrows() {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        RaftKVServer follower = followerOf(leader);

        RaftKVServer.NotLeaderException ex = assertThrows(
                RaftKVServer.NotLeaderException.class,
                () -> follower.put("k", "v"));
        assertEquals(leader.nodeId(), ex.leaderId());
    }

    @Test
    @DisplayName("delete on leader removes the key")
    void deleteOnLeader() throws InterruptedException {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        leader.put("tmp", "x");
        leader.delete("tmp");
        assertNull(leader.get("tmp"));
        assertFalse(leader.containsKey("tmp"));
    }

    @Test
    @DisplayName("delete on non-leader throws NotLeaderException")
    void deleteOnFollowerThrows() {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        RaftKVServer follower = followerOf(leader);
        assertThrows(RaftKVServer.NotLeaderException.class,
                () -> follower.delete("any"));
    }

    // -------------------------------------------------------------------------
    // Read convergence
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("write on leader is eventually visible on all followers")
    void writeConvergesToAllNodes() throws InterruptedException {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        leader.put("lang", "Java");

        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            if (s1.get("lang") != null && s2.get("lang") != null && s3.get("lang") != null) break;
            Thread.sleep(20);
        }
        for (RaftKVServer s : List.of(s1, s2, s3)) {
            assertEquals("Java", s.get("lang"), s.nodeId() + " did not converge");
        }
    }

    @Test
    @DisplayName("multiple writes all converge to all nodes")
    void multipleWritesConverge() throws InterruptedException {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);

        for (int i = 0; i < 5; i++) leader.put("k" + i, "v" + i);

        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allSeen = List.of(s1, s2, s3).stream()
                    .allMatch(s -> "v4".equals(s.get("k4")));
            if (allSeen) break;
            Thread.sleep(20);
        }
        for (RaftKVServer s : List.of(s1, s2, s3)) {
            for (int i = 0; i < 5; i++) {
                assertEquals("v" + i, s.get("k" + i),
                        s.nodeId() + " missing k" + i);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Partition recovery
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cluster continues operating after one follower is partitioned")
    void operatesWithFollowerPartitioned() throws InterruptedException {
        RaftKVServer leader = waitForLeaderServer();
        assertNotNull(leader);
        RaftKVServer laggard = followerOf(leader);

        transport.partition(laggard.nodeId());
        leader.put("after-partition", "yes");
        assertEquals("yes", leader.get("after-partition"));

        // Heal and trigger catch-up
        transport.heal(laggard.node());
        leader.put("trigger", "catchup");

        long deadline = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < deadline) {
            if ("yes".equals(laggard.get("after-partition"))) break;
            Thread.sleep(20);
        }
        assertEquals("yes", laggard.get("after-partition"),
                laggard.nodeId() + " did not catch up");
    }
}
