package com.nebulakv.raft;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Raft leader election (Phase 42).
 *
 * All tests use InProcessRaftTransport so there is no real network.
 * Timing tests wait up to 2 seconds for an election to complete —
 * the election timeout window is 150–300ms, so one election round
 * should finish well within that budget.
 */
@DisplayName("Raft — leader election")
class RaftElectionTest {

    private InProcessRaftTransport transport;
    private RaftNode n1, n2, n3;

    @BeforeEach
    void setup() {
        transport = new InProcessRaftTransport();

        n1 = new RaftNode("n1", List.of("n2", "n3"), transport);
        n2 = new RaftNode("n2", List.of("n1", "n3"), transport);
        n3 = new RaftNode("n3", List.of("n1", "n2"), transport);

        transport.register(n1);
        transport.register(n2);
        transport.register(n3);
    }

    @AfterEach
    void tearDown() {
        n1.shutdown(); n2.shutdown(); n3.shutdown();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /** Waits up to 2s for exactly one leader to emerge in the cluster. */
    private RaftNode waitForLeader() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            List<RaftNode> leaders = List.of(n1, n2, n3).stream()
                    .filter(RaftNode::isLeader).toList();
            if (leaders.size() == 1) return leaders.get(0);
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exactly one leader is elected in a 3-node cluster")
    void oneLeaderElected() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader, "No leader elected within 2 seconds");
        assertEquals(RaftRole.LEADER, leader.role());
    }

    @Test
    @DisplayName("leader has term >= 1 after election")
    void leaderTermAtLeastOne() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);
        assertTrue(leader.currentTerm() >= 1);
    }

    @Test
    @DisplayName("non-leaders are FOLLOWER after election")
    void nonLeadersAreFollowers() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);
        List.of(n1, n2, n3).stream()
                .filter(n -> n != leader)
                .forEach(n -> assertEquals(RaftRole.FOLLOWER, n.role(),
                        n.nodeId() + " should be FOLLOWER"));
    }

    @Test
    @DisplayName("all nodes agree on the same term after election")
    void allNodesSameTerm() {
        RaftNode leader = waitForLeader();
        assertNotNull(leader);
        long term = leader.currentTerm();
        List.of(n1, n2, n3).forEach(n ->
                assertEquals(term, n.currentTerm(), n.nodeId() + " term mismatch"));
    }

    @Test
    @DisplayName("handleRequestVote: grants vote if term and log qualify")
    void grantVoteWhenValid() {
        // Force n1 into an initial state with term 0
        RequestVoteRequest req = new RequestVoteRequest(1, "n2", 0, 0);
        RequestVoteResponse resp = n1.handleRequestVote(req);
        assertTrue(resp.voteGranted());
        assertEquals(1, resp.term());
    }

    @Test
    @DisplayName("handleRequestVote: rejects duplicate vote for different candidate same term")
    void rejectDuplicateVote() {
        n1.handleRequestVote(new RequestVoteRequest(1, "n2", 0, 0)); // n1 votes for n2
        RequestVoteResponse resp = n1.handleRequestVote(new RequestVoteRequest(1, "n3", 0, 0));
        assertFalse(resp.voteGranted(), "n1 already voted in term 1");
    }

    @Test
    @DisplayName("handleRequestVote: rejects stale term")
    void rejectStaleTerm() {
        // Bump n1 to term 5
        n1.handleRequestVote(new RequestVoteRequest(5, "n2", 0, 0));
        // Request with term 3 should be denied
        RequestVoteResponse resp = n1.handleRequestVote(new RequestVoteRequest(3, "n3", 0, 0));
        assertFalse(resp.voteGranted());
    }

    @Test
    @DisplayName("leader is re-elected after existing leader is partitioned")
    void reElectionAfterLeaderPartition() {
        RaftNode first = waitForLeader();
        assertNotNull(first, "No initial leader");

        // Partition the leader — it can no longer receive RPCs
        transport.partition(first.nodeId());

        // Wait for a new leader to emerge among the remaining two nodes
        long deadline = System.currentTimeMillis() + 3_000;
        RaftNode newLeader = null;
        while (System.currentTimeMillis() < deadline) {
            List<RaftNode> candidates = List.of(n1, n2, n3).stream()
                    .filter(n -> n != first && n.isLeader())
                    .toList();
            if (candidates.size() == 1) { newLeader = candidates.get(0); break; }
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }

        assertNotNull(newLeader, "No new leader after partitioning old leader");
        assertNotEquals(first.nodeId(), newLeader.nodeId());
        assertTrue(newLeader.currentTerm() > first.currentTerm(),
                "New leader must have higher term");
    }
}
